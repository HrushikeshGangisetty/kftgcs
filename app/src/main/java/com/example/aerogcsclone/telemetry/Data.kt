package com.example.aerogcsclone.Telemetry

import com.divpundir.mavlink.definitions.common.OpenDroneIdBasicId
import com.divpundir.mavlink.definitions.common.MavOdidIdType

/**
 * Drone identifier extracted from OpenDroneID messages
 * Used to uniquely identify drones for backend storage
 */
data class DroneIdentifier(
    val serialNumber: String,      // PRIMARY unique identifier
    val idOrMac: String,            // Secondary identifier (MAC address)
    val idType: String,             // Type of ID used
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Extract the unique drone identifier for backend storage
 * Returns null if no valid serial number is found
 */
fun extractDroneUniqueId(message: OpenDroneIdBasicId): DroneIdentifier? {
    // Extract idOrMac (MAC address / device ID)
    val idOrMacHex = message.idOrMac
        .takeWhile { it != 0.toUByte() }
        .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }

    // Check if this is a Serial Number (recommended for backend)
    val idType = message.idType.entry ?: MavOdidIdType.NONE

    return when (idType) {
        MavOdidIdType.SERIAL_NUMBER -> {
            // Extract serial number (ASCII string)
            val serialNumber = message.uasId
                .takeWhile { it != 0.toUByte() }
                .map { it.toByte().toInt().toChar() }
                .joinToString("")

            if (serialNumber.isNotEmpty()) {
                DroneIdentifier(
                    serialNumber = serialNumber,
                    idOrMac = idOrMacHex,
                    idType = "SERIAL_NUMBER"
                )
            } else null
        }

        MavOdidIdType.CAA_REGISTRATION_ID -> {
            // Also acceptable as unique ID (regulatory registration)
            val registration = message.uasId
                .takeWhile { it != 0.toUByte() }
                .map { it.toByte().toInt().toChar() }
                .joinToString("")

            if (registration.isNotEmpty()) {
                DroneIdentifier(
                    serialNumber = registration,
                    idOrMac = idOrMacHex,
                    idType = "CAA_REGISTRATION"
                )
            } else null
        }

        MavOdidIdType.UTM_ASSIGNED_UUID -> {
            // UUID format - also unique
            val uuid = extractUuidString(message.uasId)
            DroneIdentifier(
                serialNumber = uuid,
                idOrMac = idOrMacHex,
                idType = "UTM_UUID"
            )
        }

        else -> {
            // Fallback: use idOrMac as identifier (not recommended)
            if (idOrMacHex.isNotEmpty()) {
                DroneIdentifier(
                    serialNumber = idOrMacHex,
                    idOrMac = idOrMacHex,
                    idType = "FALLBACK_MAC"
                )
            } else null
        }
    }
}

fun extractUuidString(bytes: List<UByte>): String {
    val hex = bytes.take(16).joinToString("") { it.toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

/**
 * Spray telemetry data for agricultural drones
 * Maps to BATTERY_STATUS messages from flow sensor (BATT2) and level sensor (BATT3)
 */
data class SprayTelemetry(
    // Spray system status (RC7 channel)
    val sprayEnabled: Boolean = false,       // Whether spray system is ON (RC7 > 1500)
    val rc7Value: Int? = null,               // Raw RC7 PWM value (1000-2000)

    // Flow sensor data (BATT2 - Instance 1)
    val flowRateLiterPerMin: Float? = null,  // Current flow rate in L/min
    val consumedLiters: Float? = null,       // Total liquid sprayed in liters
    val flowCapacityLiters: Float? = null,   // Total tank capacity for flow sensor
    val flowRemainingPercent: Int? = null,   // Remaining % based on flow sensor

    // Level sensor data (BATT3 - Instance 2)
    val tankVoltageMv: Int? = null,          // Raw voltage from level sensor
    val tankLevelPercent: Int? = null,       // Tank level % based on voltage (calculated in app)
    val tankCapacityLiters: Float? = null,   // Total tank capacity for level sensor

    // Level sensor calibration (voltage ranges)
    val levelSensorEmptyMv: Int = 29044,     // Voltage when tank is EMPTY (calibrated)
    val levelSensorFullMv: Int = 29232 ,      // Voltage when tank is FULL (calibrated)

    // Piecewise calibration points for non-linear tanks (optional, overrides simple calibration)
    val levelCalibrationPoints: List<CalibrationPoint>? = null,

    // Formatted values for UI
    val formattedFlowRate: String? = null,   // e.g., "0.4 L/min"
    val formattedConsumed: String? = null,   // e.g., "2.5 L"

    // Configuration read from ArduPilot parameters
    val batt2CapacityMah: Int = 0,           // BATT2_CAPACITY parameter (0 = not configured)
    val batt3CapacityMah: Int = 0,           // BATT3_CAPACITY parameter (0 = not configured)
    val batt2MonitorType: Int? = null,       // BATT2_MONITOR (should be 11 for flow sensor)
    val batt2AmpPerVolt: Float? = null,      // BATT2_AMP_PERVLT (calibration factor)
    val batt2CurrPin: Int? = null,           // BATT2_CURR_PIN (sensor pin)
    val batt3VoltMult: Float? = null,        // BATT3_VOLT_MULT (voltage multiplier from FCU)

    // Configuration status flags
    val parametersReceived: Boolean = false, // True when all params are received
    val configurationValid: Boolean = false, // True when configuration is correct
    val configurationError: String? = null   // Error message if configuration is wrong
)

data class TelemetryState(

    val connected : Boolean = false,
    val fcuDetected : Boolean = false,
    //Altitude
    val altitudeMsl: Float? = null,
    val altitudeRelative: Float? = null,
    //Speeds
    val airspeed: Float? = null,
    val groundspeed: Float? = null,
    //Battery
    val voltage: Float? = null,
    val batteryPercent: Int? = null,
    val currentA : Float? = null,
    //RC Battery
    val rcBatteryPercent: Int? = null,
    //Sat count and HDOP
    val sats : Int? = null,
    val hdop : Float? = null,
    //Latitude and Longitude
    val latitude : Double?= null,
    val longitude : Double? = null,

    val mode: String? = null,
    val armed: Boolean = false,
    val armable: Boolean = false,

    // Simple boolean flag for mission active state - easy to use throughout the app
    // True when flight tracking has started (drone armed + airborne/moving)
    val isMissionActive: Boolean = false,

    // Mission timer (seconds elapsed since mission start, null if not running)
    val missionElapsedSec: Long? = null,
    val lastMissionElapsedSec: Long? = null,
    val missionCompleted: Boolean = false,
    val missionCompletedHandled: Boolean = false, // Tracks if the completion popup was already shown
    val totalDistanceMeters: Float? = null,

    // Sprayed distance tracking (distance traveled while pump ON and flow > 0)
    val totalSprayedDistanceMeters: Float? = null,
    // Sprayed acres calculated from sprayed distance: (distance * spray_width) / 4046.86
    val totalSprayedAcres: Float? = null,

    // Crop type for agricultural missions
    val cropType: String? = null,
    // Formatted speed values for UI
    val formattedAirspeed: String? = null,
    val formattedGroundspeed: String? = null,
    val heading: Float? = null,
    // Attitude data (roll, pitch in degrees)
    val roll: Float? = null,
    val pitch: Float? = null,
    // Waypoint tracking for pause/resume
    val currentWaypoint: Int? = null,
    val missionPaused: Boolean = false,
    val pausedAtWaypoint: Int? = null,
    // Last waypoint when in AUTO mode (Mission Planner's lastautowp equivalent)
    // This tracks the waypoint number during mission execution to pre-fill resume dialog
    val lastAutoWaypoint: Int = -1,

    // Drone identification from OpenDroneID BASIC_ID message (uasId field - SERIAL_NUMBER)
    val droneUid: String? = null,  // Primary UID from OpenDroneID serial number
    val droneUid2: String? = null, // Secondary UID from uid2 field (if different from uid)
    val vendorId: Int? = null,     // Board vendor ID
    val productId: Int? = null,    // Board product ID
    val firmwareVersion: String? = null, // Formatted firmware version
    val boardVersion: Int? = null, // Hardware/board version

    // Spray telemetry for agricultural drones
    val sprayTelemetry: SprayTelemetry = SprayTelemetry()
)

data class CalibrationPoint(
    val voltageMv: Int,          // Voltage at this calibration point
    val levelPercent: Int        // Corresponding tank level % at this voltage
)
