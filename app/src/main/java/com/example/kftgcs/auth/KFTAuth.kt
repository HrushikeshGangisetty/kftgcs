package com.example.kftgcs.auth

import android.util.Log
import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.trySendUnsignedV2
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.Statustext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.example.kftgcs.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AuthResult {
    AUTHENTICATED,
    LEGACY_FIRMWARE,
    DENIED,
    FAILED
}

object KFTAuth {
    private const val TAG = "KFTAuth"
    private const val APP_ID: Int = 1

    private const val MAV_CMD_USER_1: UInt = 31010u
    private const val MAV_CMD_USER_2: UInt = 31011u

    private const val MAV_RESULT_ACCEPTED: UInt = 0u
    private const val MAV_RESULT_DENIED: UInt = 2u
    private const val MAV_RESULT_UNSUPPORTED: UInt = 3u
    private const val MAV_RESULT_TEMPORARILY_REJECTED: UInt = 4u

    // ========================================================================
    // APP_SECRET is loaded from BuildConfig, which is injected from local.properties
    // at build time. The key is NEVER stored in source code or version control.
    // ========================================================================
    private val APP_SECRET: ByteArray
        get() = parseHexString(BuildConfig.KFT_APP_SECRET)

    private fun parseHexString(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun computeHmac(challenge: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(APP_SECRET, "HmacSHA256"))
        val fullHmac = mac.doFinal(challenge)
        return fullHmac.copyOf(24)
    }

    private fun packHmacIntoFloats(hmac: ByteArray): FloatArray {
        val floats = FloatArray(6)
        val buffer = ByteBuffer.wrap(hmac).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 6) {
            floats[i] = buffer.float
        }
        return floats
    }

    /**
     * Perform the complete 4-step authentication handshake with the drone.
     *
     * Call this:
     * - After FCU is first detected (initial auth)
     * - After link recovery from extended heartbeat gap (re-auth)
     *
     * @return AuthResult indicating outcome
     */
    suspend fun authenticate(
        connection: CoroutinesMavConnection,
        mavFrame: SharedFlow<MavFrame<out MavMessage<*>>>,
        gcsSystemId: UByte,
        gcsComponentId: UByte,
        fcuSystemId: UByte,
        fcuComponentId: UByte
    ): AuthResult = coroutineScope {
        try {
            // Shared state for challenge collection
            val challengeState = object {
                @Volatile var ch1: String? = null
                @Volatile var ch2: String? = null
            }

            // ─── Start collecting STATUSTEXTs FIRST (before sending challenge) ───
            val collectorJob = launch {
                mavFrame
                    .mapNotNull { it.message as? Statustext }
                    .collect { statusText ->
                        val text = statusText.text.trimEnd('\u0000')
                        Log.d(TAG, "STATUSTEXT during auth: $text")
                        when {
                            text.startsWith("KFTCH1:") -> {
                                challengeState.ch1 = text.removePrefix("KFTCH1:")
                                Log.i(TAG, "✓ Received KFTCH1")
                            }
                            text.startsWith("KFTCH2:") -> {
                                challengeState.ch2 = text.removePrefix("KFTCH2:")
                                Log.i(TAG, "✓ Received KFTCH2")
                            }
                        }
                    }
            }

            try {
                // Start collecting ACK for challenge request
                val ack1Deferred = async {
                    withTimeoutOrNull(3000L) {
                        mavFrame
                            .mapNotNull { it.message as? CommandAck }
                            .first { it.command.value == MAV_CMD_USER_1 }
                    }
                }

                // Small delay to ensure collectors are fully subscribed
                delay(100L)

                // ─── Step 1: Send challenge request ───
                Log.d(TAG, "Step 1: Sending challenge request (MAV_CMD_USER_1)")
                val challengeRequest = CommandLong(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    command = MavEnumValue.fromValue(MAV_CMD_USER_1),
                    confirmation = 0u,
                    param1 = APP_ID.toFloat(),
                    param2 = 0f, param3 = 0f, param4 = 0f,
                    param5 = 0f, param6 = 0f, param7 = 0f
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, challengeRequest)

                val ack1 = ack1Deferred.await()

                if (ack1 == null) {
                    Log.i(TAG, "No ACK for challenge request — legacy firmware")
                    return@coroutineScope AuthResult.LEGACY_FIRMWARE
                }

                when (ack1.result.value) {
                    MAV_RESULT_UNSUPPORTED -> {
                        Log.i(TAG, "Firmware doesn't support auth — legacy firmware")
                        return@coroutineScope AuthResult.LEGACY_FIRMWARE
                    }
                    MAV_RESULT_TEMPORARILY_REJECTED -> {
                        Log.i(TAG, "Firmware temporarily rejected — treating as legacy")
                        return@coroutineScope AuthResult.LEGACY_FIRMWARE
                    }
                    MAV_RESULT_DENIED -> {
                        Log.e(TAG, "Firmware rejected app_id")
                        return@coroutineScope AuthResult.DENIED
                    }
                    MAV_RESULT_ACCEPTED -> {
                        Log.d(TAG, "Challenge request ACCEPTED, waiting for challenge bytes")
                    }
                    else -> {
                        return@coroutineScope AuthResult.LEGACY_FIRMWARE
                    }
                }

                // ─── Step 2: Wait for both challenge parts ───
                Log.d(TAG, "Step 2: Polling for KFTCH1/KFTCH2 (up to 8s)")
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 8000L) {
                    if (challengeState.ch1 != null && challengeState.ch2 != null) {
                        break
                    }
                    delay(100L)
                }

                val ch1Hex = challengeState.ch1
                val ch2Hex = challengeState.ch2

                if (ch1Hex == null || ch2Hex == null) {
                    Log.e(TAG, "Incomplete challenge: ch1=${ch1Hex != null} ch2=${ch2Hex != null}")
                    return@coroutineScope AuthResult.FAILED
                }

                val challenge = try {
                    parseHexString(ch1Hex) + parseHexString(ch2Hex)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse challenge hex: ${e.message}")
                    return@coroutineScope AuthResult.FAILED
                }

                if (challenge.size != 32) {
                    Log.e(TAG, "Challenge size wrong: ${challenge.size}")
                    return@coroutineScope AuthResult.FAILED
                }

                // ─── Step 3: Compute HMAC ───
                Log.d(TAG, "Step 3: Computing HMAC-SHA256")
                val hmac = computeHmac(challenge)
                val hmacFloats = packHmacIntoFloats(hmac)

                // ─── Step 4: Send auth response ───
                val ack2Deferred = async {
                    withTimeoutOrNull(5000L) {
                        mavFrame
                            .mapNotNull { it.message as? CommandAck }
                            .first { it.command.value == MAV_CMD_USER_2 }
                    }
                }

                delay(50L)

                Log.d(TAG, "Step 4: Sending auth response (MAV_CMD_USER_2)")
                val authResponse = CommandLong(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    command = MavEnumValue.fromValue(MAV_CMD_USER_2),
                    confirmation = 0u,
                    param1 = APP_ID.toFloat(),
                    param2 = hmacFloats[0], param3 = hmacFloats[1], param4 = hmacFloats[2],
                    param5 = hmacFloats[3], param6 = hmacFloats[4], param7 = hmacFloats[5]
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, authResponse)

                val ack2 = ack2Deferred.await()

                if (ack2 == null) {
                    Log.e(TAG, "No ACK for auth response")
                    return@coroutineScope AuthResult.FAILED
                }

                return@coroutineScope when (ack2.result.value) {
                    MAV_RESULT_ACCEPTED -> {
                        Log.i(TAG, "✓ Authentication SUCCESS")
                        AuthResult.AUTHENTICATED
                    }
                    MAV_RESULT_DENIED -> {
                        Log.e(TAG, "✗ HMAC verification FAILED — keys don't match")
                        AuthResult.DENIED
                    }
                    else -> {
                        Log.e(TAG, "Auth failed with result: ${ack2.result.value}")
                        AuthResult.FAILED
                    }
                }

            } finally {
                collectorJob.cancel()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Auth exception: ${e.message}", e)
            return@coroutineScope AuthResult.FAILED
        }
    }

    private class ChallengeCompleteException : Exception()
}
