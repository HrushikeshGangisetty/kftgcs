package com.example.kftgcs.parammanagement

import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Data layer for the Servo Output configurator.
 *
 * ── Architecture notes ──────────────────────────────────────────────────────
 * This project does NOT use the MAVSDK-Kotlin SDK; it uses the [divpundir
 * mavlink] library routed through [SharedViewModel]. All MAVLink I/O is
 * therefore delegated to [SharedViewModel]'s suspend API:
 *
 *   • [SharedViewModel.readParameter]          → PARAM_REQUEST_READ
 *   • [SharedViewModel.setParameter]           → PARAM_SET (returns PARAM_VALUE ACK)
 *   • [SharedViewModel.sendCalibrationCommandRaw] → raw COMMAND_LONG
 *   • [SharedViewModel.requestAllParameters]   → PARAM_REQUEST_LIST
 *   • [SharedViewModel.paramValue]             → hot SharedFlow of all incoming PARAM_VALUE messages
 *
 * ── Isolation guarantee ─────────────────────────────────────────────────────
 * All network operations run on [Dispatchers.IO].  The [externalScope] (the
 * ViewModel's viewModelScope) is used ONLY for the long-lived paramValue
 * collector that must survive across individual suspend-call boundaries.
 * All other coroutines are structured inside the suspend functions themselves.
 *
 * Errors are returned as [Result.failure] so the ViewModel/UI can handle them
 * with a Toast/Snackbar without ever crashing.
 *
 * ── Servo PWM test ──────────────────────────────────────────────────────────
 * Uses MAVLink MAV_CMD_DO_SET_SERVO (ID 183):
 *   param1 = servo channel number (1-based)
 *   param2 = PWM value in µs (800–2200)
 * The vehicle MUST be disarmed before sending this command.
 *
 * @param sharedViewModel  Shared data-access hub; must already be connected.
 * @param externalScope    The ViewModel's viewModelScope — used for the
 *                         long-lived paramValue subscription during bulk fetch.
 */
class ServoRepository(
    private val sharedViewModel: SharedViewModel,
    private val externalScope: CoroutineScope
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "ServoRepo"

        /** Number of SERVO channels to fetch (SERVO1 … SERVO16). */
        const val NUM_CHANNELS = 16

        /** ArduPilot defaults when a parameter is missing / timed out. */
        const val DEFAULT_MIN_PWM  = 1100
        const val DEFAULT_MAX_PWM  = 1900
        const val DEFAULT_TRIM_PWM = 1500

        /** Allowed PWM range (ArduPilot clamps to 800–2200 µs). */
        const val PWM_MIN = 800
        const val PWM_MAX = 2200

        /** MAVLink MAV_CMD_DO_SET_SERVO = 183 */
        private const val MAV_CMD_DO_SET_SERVO: UInt = 183u

        /** How long to wait for a single PARAM_SET ACK. */
        private const val PARAM_WRITE_TIMEOUT_MS = 5_000L

        /** Poll interval while waiting for collected params. */
        private const val POLL_INTERVAL_MS = 100L

        /** Spacing between consecutive PARAM_REQUEST_READ sends (avoids flooding the link). */
        private const val READ_REQUEST_SPACING_MS = 12L

        /** How long to wait for replies after a round of PARAM_REQUEST_READ. */
        private const val READ_ROUND_TIMEOUT_MS = 5_000L

        /** How many request/retry rounds to attempt before giving up on missing params. */
        private const val MAX_READ_ROUNDS = 4
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private val _servoChannels = MutableStateFlow(defaultChannelList())
    /**
     * Emits the live list of [ServoChannel] objects.
     * Updated incrementally as parameters arrive from the FC.
     */
    val servoChannels: StateFlow<List<ServoChannel>> = _servoChannels.asStateFlow()

    private val _vehicleState = MutableStateFlow(VehicleState())
    /**
     * Reflects the current armed/connected state of the vehicle.
     * Used to gate the servo PWM test (must not be armed).
     */
    val vehicleState: StateFlow<VehicleState> = _vehicleState.asStateFlow()

    private val _isLoadingAll = MutableStateFlow(false)
    /** True while [requestServoParameters] is running. */
    val isLoadingAll: StateFlow<Boolean> = _isLoadingAll.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Carries the most recent operation error message; null when no error. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Init — mirror vehicle state
    // ─────────────────────────────────────────────────────────────────────────

    init {
        // Keep VehicleState in sync with the shared telemetry state.
        // This collector is intentionally long-lived (lives with the ViewModel).
        externalScope.launch {
            sharedViewModel.telemetryState.collect { tel ->
                _vehicleState.update {
                    it.copy(isArmed = tel.armed, isConnected = tel.connected)
                }
            }
        }

        // Drive the live "Position" bars from the high-frequency SERVO_OUTPUT_RAW
        // telemetry stream (NOT the parameter protocol). ArduPilot reports all 16
        // outputs in servo1Raw…servo16Raw, so we map them straight to channels 1–16
        // and update the whole list in a single emission per message.
        externalScope.launch {
            sharedViewModel.servoOutputRaw.collect { raw ->
                val pwms = intArrayOf(
                    raw.servo1Raw.toInt(),  raw.servo2Raw.toInt(),  raw.servo3Raw.toInt(),  raw.servo4Raw.toInt(),
                    raw.servo5Raw.toInt(),  raw.servo6Raw.toInt(),  raw.servo7Raw.toInt(),  raw.servo8Raw.toInt(),
                    raw.servo9Raw.toInt(),  raw.servo10Raw.toInt(), raw.servo11Raw.toInt(), raw.servo12Raw.toInt(),
                    raw.servo13Raw.toInt(), raw.servo14Raw.toInt(), raw.servo15Raw.toInt(), raw.servo16Raw.toInt()
                )
                _servoChannels.update { list ->
                    list.map { ch ->
                        val pwm = pwms.getOrNull(ch.channelIndex - 1) ?: return@map ch
                        if (ch.livePwm == pwm) ch else ch.copy(livePwm = pwm)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk parameter fetch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches all SERVOx_FUNCTION / _MIN / _MAX / _TRIM / _REVERSED parameters
     * from the flight controller for channels 1–[NUM_CHANNELS].
     *
     * Strategy — targeted reads (NOT a full PARAM_REQUEST_LIST dump):
     *  1. Subscribe to [SharedViewModel.paramValue] — the hot SharedFlow of all
     *     incoming PARAM_VALUE MAVLink messages — filtered to the servo params.
     *  2. Send one PARAM_REQUEST_READ per needed param, lightly spaced.
     *  3. Poll until every param arrives (or the round times out).
     *  4. Re-request only the still-missing params, up to [MAX_READ_ROUNDS].
     *  5. Build and emit the [ServoChannel] list (defaults fill any gaps).
     *
     * Why not PARAM_REQUEST_LIST? That asks the FC to re-stream ALL ~1400 params
     * just to extract ~80 of them. On a second (refresh) call the FC throttles or
     * skips the re-dump, so only a handful arrive (observed: 2/80). Targeted reads
     * request exactly what we need and can retry individual misses — faster and
     * far more reliable. Safe to run alongside [FullParamListViewModel]; both
     * receive the same param stream.
     */
    suspend fun requestServoParameters() = withContext(Dispatchers.IO) {
        if (!_vehicleState.value.isConnected) {
            _lastError.value = "Not connected to flight controller"
            return@withContext
        }
        if (_isLoadingAll.value) return@withContext  // already in-flight

        _isLoadingAll.value = true
        _lastError.value = null

        // Mark all channels as "loading" for progressive UI feedback
        _servoChannels.update { list -> list.map { it.copy(isLoading = true) } }

        // Build the complete list of param names we need to collect
        val neededParams: List<String> = buildList {
            for (ch in 1..NUM_CHANNELS) {
                add("SERVO${ch}_FUNCTION")
                add("SERVO${ch}_MIN")
                add("SERVO${ch}_MAX")
                add("SERVO${ch}_TRIM")
                add("SERVO${ch}_REVERSED")
            }
        }
        val neededSet = neededParams.toHashSet()

        // Thread-safe map to accumulate incoming param values from the FC stream
        val received = ConcurrentHashMap<String, Float>(neededParams.size * 2)

        // ── Step 1: Subscribe BEFORE issuing any read requests ────────────────
        // externalScope runs on Main; ConcurrentHashMap handles cross-thread safety.
        val collectJob = externalScope.launch {
            sharedViewModel.paramValue.collect { pv ->
                val name = pv.paramId.trim().replace(" ", "")
                if (name in neededSet) {
                    received[name] = pv.paramValue
                    LogUtils.v(TAG, "Received $name = ${pv.paramValue} (${received.size}/${neededSet.size})")
                }
            }
        }

        // Brief wait to guarantee the collector is active before the request fires
        delay(150)

        try {
            // ── Steps 2-4: request targeted reads, wait, retry the missing ────
            for (round in 1..MAX_READ_ROUNDS) {
                val missing = neededParams.filter { it !in received.keys }
                if (missing.isEmpty()) break

                LogUtils.d(TAG, "Servo param read round $round/$MAX_READ_ROUNDS — requesting ${missing.size} param(s)")
                for (paramName in missing) {
                    sharedViewModel.requestParameter(paramName)
                    delay(READ_REQUEST_SPACING_MS)
                }

                val deadline = System.currentTimeMillis() + READ_ROUND_TIMEOUT_MS
                while (received.size < neededSet.size && System.currentTimeMillis() < deadline) {
                    delay(POLL_INTERVAL_MS)
                }
            }

            val fetched   = received.size
            val expected  = neededSet.size
            val timedOut  = fetched < expected
            LogUtils.d(TAG, "Param fetch done: $fetched/$expected params received (timedOut=$timedOut)")

            if (timedOut) {
                _lastError.value = "Received $fetched/$expected servo params — " +
                    "missing: ${neededSet.minus(received.keys).take(5).joinToString()}"
            }

        } catch (e: CancellationException) {
            collectJob.cancel()
            _isLoadingAll.value = false
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "requestServoParameters failed", e)
            _lastError.value = "Failed to request parameters: ${e.message}"
        } finally {
            collectJob.cancel()
        }

        // ── Step 4: Build channel list (defaults fill any missing params) ─────
        _servoChannels.value = (1..NUM_CHANNELS).map { ch ->
            ServoChannel(
                channelIndex = ch,
                function     = ServoFunction.fromValue(
                    received["SERVO${ch}_FUNCTION"]?.toInt() ?: ServoFunction.DISABLED.value
                ),
                minPwm       = received["SERVO${ch}_MIN"]?.toInt()  ?: DEFAULT_MIN_PWM,
                maxPwm       = received["SERVO${ch}_MAX"]?.toInt()  ?: DEFAULT_MAX_PWM,
                trimPwm      = received["SERVO${ch}_TRIM"]?.toInt() ?: DEFAULT_TRIM_PWM,
                reversed     = (received["SERVO${ch}_REVERSED"]?.toInt() ?: 0) != 0,
                isLoading    = false
            )
        }

        _isLoadingAll.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-parameter write helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes SERVOx_FUNCTION and optimistically updates the local state on success.
     *
     * @return [Result.success] if the FC acknowledged the write within timeout;
     *         [Result.failure] with a descriptive exception otherwise.
     */
    suspend fun setServoFunction(
        channelIndex: Int,
        function: ServoFunction
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val paramName = "SERVO${channelIndex}_FUNCTION"
        writeParam(paramName, function.value.toFloat()) {
            updateChannel(channelIndex) { ch -> ch.copy(function = function) }
        }
    }

    /**
     * Writes SERVOx_MIN.
     * @param minPwm Valid range [PWM_MIN]..[PWM_MAX]; clamped automatically.
     */
    suspend fun setServoMin(
        channelIndex: Int,
        minPwm: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val clamped   = minPwm.coerceIn(PWM_MIN, PWM_MAX)
        val paramName = "SERVO${channelIndex}_MIN"
        writeParam(paramName, clamped.toFloat()) {
            updateChannel(channelIndex) { ch -> ch.copy(minPwm = clamped) }
        }
    }

    /**
     * Writes SERVOx_MAX.
     * @param maxPwm Valid range [PWM_MIN]..[PWM_MAX]; clamped automatically.
     */
    suspend fun setServoMax(
        channelIndex: Int,
        maxPwm: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val clamped   = maxPwm.coerceIn(PWM_MIN, PWM_MAX)
        val paramName = "SERVO${channelIndex}_MAX"
        writeParam(paramName, clamped.toFloat()) {
            updateChannel(channelIndex) { ch -> ch.copy(maxPwm = clamped) }
        }
    }

    /**
     * Writes SERVOx_TRIM.
     * @param trimPwm Valid range [PWM_MIN]..[PWM_MAX]; clamped automatically.
     */
    suspend fun setServoTrim(
        channelIndex: Int,
        trimPwm: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val clamped   = trimPwm.coerceIn(PWM_MIN, PWM_MAX)
        val paramName = "SERVO${channelIndex}_TRIM"
        writeParam(paramName, clamped.toFloat()) {
            updateChannel(channelIndex) { ch -> ch.copy(trimPwm = clamped) }
        }
    }

    /**
     * Writes SERVOx_REVERSED (0 = normal, 1 = reversed).
     */
    suspend fun setServoReverse(
        channelIndex: Int,
        reversed: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val paramName = "SERVO${channelIndex}_REVERSED"
        writeParam(paramName, if (reversed) 1f else 0f) {
            updateChannel(channelIndex) { ch -> ch.copy(reversed = reversed) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live PWM test
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends MAV_CMD_DO_SET_SERVO (183) to move a specific servo to [pwmValue] µs
     * in real-time without permanently changing any stored parameters.
     *
     * Safety rules enforced here (mirrors Mission Planner's servo test guard):
     *  • The vehicle must be **disarmed**. Sending servo commands while armed
     *    could interfere with the autopilot's output and is dangerous.
     *  • [pwmValue] is clamped to the safe ArduPilot range [PWM_MIN]..[PWM_MAX].
     *
     * MAVLink payload:
     *   param1 = servo channel number (1-based)
     *   param2 = PWM value in µs
     *   All other params = 0
     *
     * @param channelIndex  1-based servo output channel (1–14).
     * @param pwmValue      Desired PWM in µs; clamped to [800, 2200].
     * @return [Result.success] if the command was sent;
     *         [Result.failure] if safety check failed or the send threw.
     */
    suspend fun setServoPwm(
        channelIndex: Int,
        pwmValue: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // ── Safety guard ──────────────────────────────────────────────────────
        if (_vehicleState.value.isArmed) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Servo test blocked: vehicle is ARMED. " +
                    "Disarm the vehicle before testing servo outputs."
                )
            )
        }

        val clamped = pwmValue.coerceIn(PWM_MIN, PWM_MAX)

        return@withContext try {
            // DO_SET_SERVO (183): param1 = channel#, param2 = PWM µs
            sharedViewModel.sendCalibrationCommandRaw(
                commandId = MAV_CMD_DO_SET_SERVO,
                param1    = channelIndex.toFloat(),
                param2    = clamped.toFloat(),
                param3    = 0f,
                param4    = 0f,
                param5    = 0f,
                param6    = 0f,
                param7    = 0f
            )
            LogUtils.d(TAG, "DO_SET_SERVO: ch=$channelIndex pwm=$clamped µs sent")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "setServoPwm failed: ch=$channelIndex pwm=$clamped", e)
            Result.failure(IOException("Servo PWM command failed: ${e.message}", e))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live telemetry — SERVO_OUTPUT_RAW
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates the live position bar for [channel] (1-based) with a PWM value
     * sourced from the SERVO_OUTPUT_RAW MAVLink message.
     *
     * This is NOT driven by the parameter protocol. Wire it from the MAVLink
     * message handler whenever a SERVO_OUTPUT_RAW frame arrives, e.g.:
     *   servoRepository.onServoOutputRawReceived(1, msg.servo1Raw)
     *   servoRepository.onServoOutputRawReceived(2, msg.servo2Raw)
     *   ...
     */
    fun onServoOutputRawReceived(channel: Int, pwmValue: Int) {
        if (channel in 1..NUM_CHANNELS) {
            updateChannel(channel) { it.copy(livePwm = pwmValue) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Housekeeping
    // ─────────────────────────────────────────────────────────────────────────

    /** Call from the ViewModel's [clearMessages] to reset error state. */
    fun clearLastError() {
        _lastError.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a PARAM_SET via [SharedViewModel.setParameter] and translates the
     * nullable-ACK result into [Result<Unit>].
     *
     * [onSuccess] runs immediately when the FC ACK confirms the write; used for
     * optimistic local state updates so the UI reflects changes without a
     * full re-fetch.
     */
    private suspend fun writeParam(
        paramName: String,
        value: Float,
        onSuccess: () -> Unit
    ): Result<Unit> = try {
        LogUtils.d(TAG, "📤 PARAM_SET: $paramName = $value")
        val ack = sharedViewModel.setParameter(
            paramId   = paramName,
            value     = value,
            timeoutMs = PARAM_WRITE_TIMEOUT_MS
        )
        if (ack != null) {
            LogUtils.d(TAG, "✅ Param confirmed: $paramName = ${ack.paramValue}")
            onSuccess()
            Result.success(Unit)
        } else {
            val msg = "PARAM_SET timed out for $paramName (no ACK within ${PARAM_WRITE_TIMEOUT_MS}ms)"
            LogUtils.e(TAG, msg)
            Result.failure(IOException(msg))
        }
    } catch (e: CancellationException) {
        throw e  // always re-propagate coroutine cancellation
    } catch (e: Exception) {
        LogUtils.e(TAG, "writeParam failed: $paramName", e)
        Result.failure(IOException("Failed to write $paramName: ${e.message}", e))
    }

    /**
     * Thread-safely updates a single channel in [_servoChannels] by its
     * 1-based [channelIndex].
     */
    private fun updateChannel(channelIndex: Int, transform: (ServoChannel) -> ServoChannel) {
        _servoChannels.update { list ->
            list.mapIndexed { idx, ch ->
                if (idx == channelIndex - 1) transform(ch) else ch
            }
        }
    }

    /**
     * Creates the initial placeholder list (all channels disabled, default PWM).
     */
    private fun defaultChannelList(): List<ServoChannel> =
        (1..NUM_CHANNELS).map { ch ->
            ServoChannel(
                channelIndex = ch,
                function     = ServoFunction.DISABLED,
                minPwm       = DEFAULT_MIN_PWM,
                maxPwm       = DEFAULT_MAX_PWM,
                trimPwm      = DEFAULT_TRIM_PWM,
                reversed     = false,
                isLoading    = false
            )
        }
}
