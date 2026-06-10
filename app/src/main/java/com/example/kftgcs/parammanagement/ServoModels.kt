package com.example.kftgcs.parammanagement

// ─────────────────────────────────────────────────────────────────────────────
// ServoFunction
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents one ArduPilot SERVOx_FUNCTION value.
 *
 * Uses a data-class (not an enum) so the UI can show any integer value the FC
 * reports — even firmware-specific ones not in this list — via [fromValue].
 *
 * Source: ArduPilot SRV_Channel.h / SERVO1_FUNCTION parameter documentation
 * (validated against ArduCopter 4.x parameter list).
 */
data class ServoFunction(val value: Int, val displayName: String) {

    companion object {

        // ─────────────────────────────────────────────────────────────────────
        // Authoritative ArduPilot SRV_Channel::Aux_servo_function_t values.
        // These MUST match the integers the FC stores in SERVOx_FUNCTION and the
        // labels Mission Planner shows — e.g. 33 = Motor1, 34 = Motor2, …
        // Source: ArduPilot SRV_Channel.h enum / SERVOx_FUNCTION param metadata.
        // ─────────────────────────────────────────────────────────────────────

        // ── Utility ──────────────────────────────────────────────────────────
        val DISABLED         = ServoFunction(0,  "Disabled")
        val RC_PASS_THRU     = ServoFunction(1,  "RCPassThru")

        // ── Aerodynamic surfaces ──────────────────────────────────────────────
        val FLAP             = ServoFunction(2,  "Flap")
        val FLAP_AUTO        = ServoFunction(3,  "Flap Auto")
        val AILERON          = ServoFunction(4,  "Aileron")
        val DSPOILER_L1      = ServoFunction(16, "DifferentialSpoilerLeft1")
        val DSPOILER_R1      = ServoFunction(17, "DifferentialSpoilerRight1")
        val AILERON_W_INPUT  = ServoFunction(18, "AileronWithInput")
        val ELEVATOR         = ServoFunction(19, "Elevator")
        val ELEVATOR_W_INPUT = ServoFunction(20, "ElevatorWithInput")
        val RUDDER           = ServoFunction(21, "Rudder")
        val FLAPERON_L       = ServoFunction(24, "FlaperonLeft")
        val FLAPERON_R       = ServoFunction(25, "FlaperonRight")
        val STEERING         = ServoFunction(26, "GroundSteering")
        val ELEVON_L         = ServoFunction(77, "ElevonLeft")
        val ELEVON_R         = ServoFunction(78, "ElevonRight")
        val VTAIL_L          = ServoFunction(79, "VTailLeft")
        val VTAIL_R          = ServoFunction(80, "VTailRight")
        val DSPOILER_L2      = ServoFunction(86, "DifferentialSpoilerLeft2")
        val DSPOILER_R2      = ServoFunction(87, "DifferentialSpoilerRight2")

        // ── Camera / Gimbal ───────────────────────────────────────────────────
        val MOUNT_PAN        = ServoFunction(6,  "Mount1 Yaw")
        val MOUNT_TILT       = ServoFunction(7,  "Mount1 Pitch")
        val MOUNT_ROLL       = ServoFunction(8,  "Mount1 Roll")
        val MOUNT_OPEN       = ServoFunction(9,  "Mount1 Retract")
        val CAMERA_TRIGGER   = ServoFunction(10, "CameraTrigger")
        val MOUNT2_PAN       = ServoFunction(12, "Mount2 Yaw")
        val MOUNT2_TILT      = ServoFunction(13, "Mount2 Pitch")
        val MOUNT2_ROLL      = ServoFunction(14, "Mount2 Roll")
        val MOUNT2_OPEN      = ServoFunction(15, "Mount2 Retract")
        val CAMERA_ISO       = ServoFunction(90, "CameraISO")
        val CAMERA_APERTURE  = ServoFunction(91, "CameraAperture")
        val CAMERA_FOCUS     = ServoFunction(92, "CameraFocus")
        val CAMERA_SHUTTER   = ServoFunction(93, "CameraShutterSpeed")

        // ── Motors (copter / multirotor) ──────────────────────────────────────
        val MOTOR1           = ServoFunction(33, "Motor 1")
        val MOTOR2           = ServoFunction(34, "Motor 2")
        val MOTOR3           = ServoFunction(35, "Motor 3")
        val MOTOR4           = ServoFunction(36, "Motor 4")
        val MOTOR5           = ServoFunction(37, "Motor 5")
        val MOTOR6           = ServoFunction(38, "Motor 6")
        val MOTOR7           = ServoFunction(39, "Motor 7")
        val MOTOR8           = ServoFunction(40, "Motor 8")
        val MOTOR9           = ServoFunction(82, "Motor 9")
        val MOTOR10          = ServoFunction(83, "Motor 10")
        val MOTOR11          = ServoFunction(84, "Motor 11")
        val MOTOR12          = ServoFunction(85, "Motor 12")
        val MOTOR_TILT       = ServoFunction(41, "Motor Tilt")

        // ── Helicopter ────────────────────────────────────────────────────────
        val HELI_RSC         = ServoFunction(31, "HeliRSC")
        val HELI_TAIL_RSC    = ServoFunction(32, "HeliTailRSC")

        // ── Agricultural (spray) ──────────────────────────────────────────────
        val SPRAYER_PUMP     = ServoFunction(22, "SprayerPump")
        val SPRAYER_SPINNER  = ServoFunction(23, "SprayerSpinner")

        // ── Power / throttle ──────────────────────────────────────────────────
        val IGNITION         = ServoFunction(67, "Ignition")
        val STARTER          = ServoFunction(69, "Starter")
        val THROTTLE         = ServoFunction(70, "Throttle")
        val THROTTLE_LEFT    = ServoFunction(73, "ThrottleLeft")
        val THROTTLE_RIGHT   = ServoFunction(74, "ThrottleRight")
        val BOOST_THROTTLE   = ServoFunction(81, "BoostThrottle")

        // ── Tilt motors ───────────────────────────────────────────────────────
        val TILT_MOTOR_LEFT  = ServoFunction(75, "TiltMotorLeft")
        val TILT_MOTOR_RIGHT = ServoFunction(76, "TiltMotorRight")

        // ── Misc actuators ────────────────────────────────────────────────────
        val PARACHUTE        = ServoFunction(27, "Parachute")
        val GRIPPER          = ServoFunction(28, "Gripper")
        val LANDING_GEAR     = ServoFunction(29, "LandingGear")
        val ENGINE_RUN_EN    = ServoFunction(30, "EngineRunEnable")
        val WINCH            = ServoFunction(88, "Winch")
        val MAIN_SAIL        = ServoFunction(89, "MainSail")

        // ── RC passthrough (RCIN1…RCIN16 = 51…66) ─────────────────────────────
        val RCIN: List<ServoFunction> =
            (1..16).map { ServoFunction(50 + it, "RCIN$it") }

        // ── Master dropdown list (shown in UI selectors) ──────────────────────
        val ALL: List<ServoFunction> = (listOf(
            DISABLED, RC_PASS_THRU,
            FLAP, FLAP_AUTO, AILERON,
            DSPOILER_L1, DSPOILER_R1, AILERON_W_INPUT, ELEVATOR, ELEVATOR_W_INPUT,
            RUDDER, FLAPERON_L, FLAPERON_R, STEERING,
            ELEVON_L, ELEVON_R, VTAIL_L, VTAIL_R, DSPOILER_L2, DSPOILER_R2,
            MOUNT_PAN, MOUNT_TILT, MOUNT_ROLL, MOUNT_OPEN, CAMERA_TRIGGER,
            MOUNT2_PAN, MOUNT2_TILT, MOUNT2_ROLL, MOUNT2_OPEN,
            CAMERA_ISO, CAMERA_APERTURE, CAMERA_FOCUS, CAMERA_SHUTTER,
            MOTOR1, MOTOR2, MOTOR3, MOTOR4, MOTOR5, MOTOR6,
            MOTOR7, MOTOR8, MOTOR9, MOTOR10, MOTOR11, MOTOR12, MOTOR_TILT,
            HELI_RSC, HELI_TAIL_RSC,
            SPRAYER_PUMP, SPRAYER_SPINNER,
            IGNITION, STARTER, THROTTLE, THROTTLE_LEFT, THROTTLE_RIGHT, BOOST_THROTTLE,
            TILT_MOTOR_LEFT, TILT_MOTOR_RIGHT,
            PARACHUTE, GRIPPER, LANDING_GEAR, ENGINE_RUN_EN, WINCH, MAIN_SAIL
        ) + RCIN)
            .distinctBy { it.value }
            .sortedBy { it.value }

        /**
         * Returns the [ServoFunction] whose [value] matches [v], or a synthetic
         * entry showing the raw integer if not in the known list.
         */
        fun fromValue(v: Int): ServoFunction =
            ALL.firstOrNull { it.value == v } ?: ServoFunction(v, "Function $v")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ServoChannel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Complete configuration snapshot for one ArduPilot SERVO output channel.
 *
 * Maps to the five SERVOx_* parameters on the flight controller:
 *   SERVOx_FUNCTION  – what role the output plays
 *   SERVOx_MIN       – minimum PWM (800–2200 µs)
 *   SERVOx_MAX       – maximum PWM (800–2200 µs)
 *   SERVOx_TRIM      – neutral/trim PWM (800–2200 µs)
 *   SERVOx_REVERSED  – invert output (0 = normal, 1 = reversed)
 */
data class ServoChannel(
    /** 1-based channel index (matches SERVOx parameter prefix). */
    val channelIndex: Int,

    val function: ServoFunction,

    /** Minimum PWM output in microseconds (ArduPilot default: 1100). */
    val minPwm: Int,

    /** Maximum PWM output in microseconds (ArduPilot default: 1900). */
    val maxPwm: Int,

    /** Trim/neutral PWM output in microseconds (ArduPilot default: 1500). */
    val trimPwm: Int,

    /** True when the servo output is inverted (SERVOx_REVERSED = 1). */
    val reversed: Boolean,

    /** True while this channel's parameters are being fetched from the FC. */
    val isLoading: Boolean = false,

    /**
     * Live PWM value in µs driven by SERVO_OUTPUT_RAW MAVLink telemetry.
     * Null until the first SERVO_OUTPUT_RAW message arrives for this channel.
     * Do NOT populate this from the parameter protocol — it is a high-frequency
     * telemetry stream updated via ServoOutputViewModel.onServoOutputRawReceived.
     */
    val livePwm: Int? = null
) {
    /** Convenience label for display: "CH 1", "CH 2", etc. */
    val channelLabel: String get() = "CH $channelIndex"
}

// ─────────────────────────────────────────────────────────────────────────────
// VehicleState
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Minimal vehicle state needed by the servo configurator.
 *
 * Both values mirror [com.example.kftgcs.telemetry.TelemetryState]; they are
 * kept in a separate class so the servo feature has zero coupling to the
 * broader telemetry hierarchy.
 */
data class VehicleState(
    /** True when the flight controller reports the vehicle as armed. */
    val isArmed: Boolean = false,

    /** True when a MAVLink connection to the FC is active. */
    val isConnected: Boolean = false
)
