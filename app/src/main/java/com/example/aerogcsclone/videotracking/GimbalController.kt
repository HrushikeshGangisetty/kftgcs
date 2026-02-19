package com.example.aerogcsclone.videotracking

import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.definitions.common.*
import com.example.aerogcsclone.telemetry.MavlinkTelemetryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/**
 * Gimbal Controller — manages gimbal orientation via MAVLink.
 *
 * Mirrors MissionPlanner's GimbalManagerProtocol:
 * - Sends gimbal pitch/yaw commands (DO_GIMBAL_MANAGER_PITCHYAW)
 * - Sets ROI location (DO_SET_ROI_LOCATION)
 * - Receives gimbal attitude status (GIMBAL_DEVICE_ATTITUDE_STATUS)
 */
class GimbalController(
    private val repository: MavlinkTelemetryRepository
) {
    companion object {
        private const val TAG = "GimbalController"
        const val MSG_ID_GIMBAL_DEVICE_ATTITUDE_STATUS = 285u
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _gimbalState = MutableStateFlow(GimbalState())
    val gimbalState: StateFlow<GimbalState> = _gimbalState.asStateFlow()

    private var gimbalSystemId: UByte = 0u
    private var gimbalComponentId: UByte = 0u

    /**
     * Start listening for gimbal MAVLink messages.
     */
    fun startListening() {
        Timber.d("$TAG: Starting gimbal listener")

        scope.launch {
            try {
                repository.mavFrame
                    .collect { frame ->
                        when (val msg = frame.message) {
                            is GimbalDeviceAttitudeStatus -> handleGimbalAttitude(frame.systemId, frame.componentId, msg)
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error in gimbal message listener")
            }
        }

        // Request gimbal attitude status at 5Hz
        scope.launch {
            delay(3000)
            requestGimbalAttitudeInterval(5)
        }
    }

    /**
     * Handle GIMBAL_DEVICE_ATTITUDE_STATUS message.
     * Extracts pitch, roll, yaw from quaternion.
     */
    private fun handleGimbalAttitude(sysId: UByte, compId: UByte, msg: GimbalDeviceAttitudeStatus) {
        gimbalSystemId = sysId
        gimbalComponentId = compId

        // Convert quaternion to Euler angles
        val q = msg.q.toFloatArray()
        if (q.size >= 4) {
            val (roll, pitch, yaw) = quaternionToEuler(q[0], q[1], q[2], q[3])
            _gimbalState.value = GimbalState(
                pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat(),
                rollDeg = Math.toDegrees(roll.toDouble()).toFloat(),
                yawDeg = Math.toDegrees(yaw.toDouble()).toFloat(),
                isAvailable = true
            )
        }
    }

    /**
     * Set gimbal pitch and yaw.
     *
     * @param pitchDeg Pitch angle in degrees (negative = down)
     * @param yawDeg Yaw angle in degrees
     * @param pitchRate Pitch rate in deg/s (0 = use default)
     * @param yawRate Yaw rate in deg/s (0 = use default)
     */
    suspend fun setPitchYaw(pitchDeg: Float, yawDeg: Float, pitchRate: Float = 0f, yawRate: Float = 0f) {
        Timber.d("$TAG: Setting gimbal pitch=%.1f° yaw=%.1f°".format(pitchDeg, yawDeg))

        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = repository.fcuComponentId,
            command = MavCmd.DO_GIMBAL_MANAGER_PITCHYAW.wrap(),
            confirmation = 0u,
            param1 = pitchDeg,    // pitch angle (deg)
            param2 = yawDeg,      // yaw angle (deg)
            param3 = pitchRate,   // pitch rate (deg/s)
            param4 = yawRate,     // yaw rate (deg/s)
            param5 = 0f,          // flags (0 = angle mode)
            param6 = 0f,          // reserved
            param7 = 0f           // gimbal device ID (0 = primary)
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Set Region-of-Interest (ROI) to a GPS location.
     * The gimbal will continuously point at this location.
     *
     * @param lat Latitude in degrees
     * @param lng Longitude in degrees
     * @param alt Altitude in meters (AMSL)
     */
    suspend fun setROILocation(lat: Double, lng: Double, alt: Float) {
        Timber.d("$TAG: Setting ROI location lat=%.6f lng=%.6f alt=%.1f".format(lat, lng, alt))

        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = repository.fcuComponentId,
            command = MavCmd.DO_SET_ROI_LOCATION.wrap(),
            confirmation = 0u,
            param1 = 0f, param2 = 0f, param3 = 0f, param4 = 0f,
            param5 = lat.toFloat(),   // latitude
            param6 = lng.toFloat(),   // longitude
            param7 = alt              // altitude
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Clear ROI / return gimbal to neutral position.
     */
    suspend fun clearROI() {
        Timber.d("$TAG: Clearing ROI")
        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = repository.fcuComponentId,
            command = MavCmd.DO_SET_ROI_NONE.wrap(),
            confirmation = 0u,
            param1 = 0f, param2 = 0f, param3 = 0f, param4 = 0f,
            param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Request gimbal attitude updates at a given rate.
     */
    private suspend fun requestGimbalAttitudeInterval(rateHz: Int) {
        val intervalUs = if (rateHz > 0) (1_000_000f / rateHz) else 0f
        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = repository.fcuComponentId,
            command = MavCmd.SET_MESSAGE_INTERVAL.wrap(),
            confirmation = 0u,
            param1 = MSG_ID_GIMBAL_DEVICE_ATTITUDE_STATUS.toFloat(),
            param2 = intervalUs,
            param3 = 0f, param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Convert quaternion (w, x, y, z) to Euler angles (roll, pitch, yaw) in radians.
     */
    private fun quaternionToEuler(w: Float, x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        // Roll (x-axis rotation)
        val sinRCosP = 2f * (w * x + y * z)
        val cosRCosP = 1f - 2f * (x * x + y * y)
        val roll = Math.atan2(sinRCosP.toDouble(), cosRCosP.toDouble()).toFloat()

        // Pitch (y-axis rotation)
        val sinP = 2f * (w * y - z * x)
        val pitch = if (Math.abs(sinP) >= 1f) {
            Math.copySign(Math.PI / 2, sinP.toDouble()).toFloat()
        } else {
            Math.asin(sinP.toDouble()).toFloat()
        }

        // Yaw (z-axis rotation)
        val sinYCosP = 2f * (w * z + x * y)
        val cosYCosP = 1f - 2f * (y * y + z * z)
        val yaw = Math.atan2(sinYCosP.toDouble(), cosYCosP.toDouble()).toFloat()

        return Triple(roll, pitch, yaw)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        scope.cancel()
    }
}

