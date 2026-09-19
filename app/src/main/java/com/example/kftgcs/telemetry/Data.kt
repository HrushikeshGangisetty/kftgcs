package com.example.kftgcs.telemetry

import com.google.android.gms.maps.model.LatLng
import com.divpundir.mavlink.definitions.common.OpenDroneIdBasicId
import com.divpundir.mavlink.definitions.common.MavOdidIdType

/**
 * UI-friendly description of a single DataFlash log stored on the flight controller,
 * derived from a MAVLink LOG_ENTRY message.
 *
 * @param id log number on the FC (used in LOG_REQUEST_DATA).
 * @param sizeBytes log size in bytes.
 * @param timeUtcSec UTC unix-seconds the log was written, or 0 if the FC had no RTC.
 */
data class LogEntryInfo(
    val id: Int,
    val sizeBytes: Long,
    val timeUtcSec: Long
)

/**
 * A DataFlash log discovered by browsing the FC's SD card over MAVLink FTP (`/APM/LOGS`), used as a
 * fallback when the MAVLink LOG_REQUEST log index is empty (e.g. our custom FC).
 *
 * Unlike [LogEntryInfo] this is path-based, because FTP addresses files by absolute path rather than
 * the numeric log id the LOG protocol uses.
 *
 * @param name the file name, e.g. `1.BIN`.
 * @param path the absolute path on the FC's SD card, e.g. `/APM/LOGS/1.BIN`.
 * @param sizeBytes file size in bytes (0 if the directory listing didn't report it).
 */
data class SdLogEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long
)

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
 * Converts the AUTOPILOT_VERSION `uid2` field (18-byte / 96-bit Silicon Serial Number reported by
 * ArduPilot) into an uppercase hex string, e.g. "3200330...". Identical hardware models share the
 * same vendor/product/board-version IDs, but this chip UID is unique per physical unit.
 *
 * Returns null when the field wasn't populated (all zero bytes) — older firmware, MAVLink 1 links
 * that drop MAVLink 2 extension fields, or boards without a readable UID.
 */
fun List<UByte>.toChipUidHex(): String? {
    if (isEmpty() || all { it == 0.toUByte() }) return null
    return joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
}

/** RCx_OPTION value that maps an RC channel to the sprayer enable switch (ArduPilot "Sprayer"). */
const val RC_OPTION_SPRAYER = 15

/** RC channel assumed to carry the sprayer switch until RCx_OPTION params resolve the real one. */
const val DEFAULT_SPRAY_RC_CHANNEL = 7

/** Highest RC channel scanned for RCx_OPTION = 15 (RC_CHANNELS carries 1..18). */
const val MAX_RC_OPTION_CHANNEL = 16

/**
 * Spray telemetry data for agricultural drones
 * Maps to BATTERY_STATUS messages from flow sensor (BATT2) and level sensor (BATT3)
 */
data class SprayTelemetry(
    // Spray system status
    // The sprayer switch is whichever RC channel has RCx_OPTION = 15 (Sprayer). That channel is
    // resolved at runtime from the RCx_OPTION parameters, so setups that put spray enable on RC6
    // (or any other channel) are monitored correctly. Falls back to 7 until the params arrive.
    val sprayEnabled: Boolean = false,       // Whether spray system is ON (spray channel PWM > 1500)
    val rc7Value: Int? = null,               // Raw PWM of the resolved spray channel (1000-2000)
    val sprayRcChannel: Int = DEFAULT_SPRAY_RC_CHANNEL, // RC channel carrying RCx_OPTION = 15
    val sprayRcChannelResolved: Boolean = false,        // True once an RCx_OPTION = 15 was found

    // AUTO mission spray detection - spray is active when flow is detected
    // This catches spray enabled via DO_SET_SERVO, DO_SPRAYER, or ArduPilot Sprayer library
    val sprayActive: Boolean = false,        // TRUE if sprayEnabled OR flow > 0 (actual spraying)

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
    // Vertical speed in m/s, derived by differentiating relative altitude (NOT VFR_HUD.climb
    // — see TelemetryRepository). Positive = climbing. This is the RAW, unfiltered value:
    // responsive, and correspondingly noisy. Use it to answer "is the vehicle moving?".
    val climbRate: Float? = null,
    // The same signal low-pass filtered (see TelemetryRepository.climbEmaMps). The altitude
    // ceiling projects the vehicle's stopping altitude from THIS one: predicting off the raw
    // value let barometric noise read as several m/s of climb and pushed the intervention
    // metres further down the envelope than the vehicle's real motion justified.
    val climbRateSmoothed: Float? = null,
    //Battery
    val voltage: Float? = null,
    // Wall-clock time of the last frame that produced [voltage]. `voltage` itself keeps its last
    // value when frames stop, so without this a stale reading is indistinguishable from a live
    // one — and the critical-battery debounce cannot count distinct readings.
    val voltageReceivedAtMs: Long? = null,
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

    // System.currentTimeMillis() when the last GLOBAL_POSITION_INT was parsed. The
    // altitude-ceiling and max-range failsafes need to know how OLD the fix they are
    // acting on is: when the telemetry link saturates, position can degrade from 10Hz to
    // 1-2Hz, and a margin sized for a fresh fix then lets the drone overshoot. Null until
    // the first position message arrives.
    val positionReceivedAtMs: Long? = null,

    // Home (launch) position reported by the FC via HOME_POSITION (242). This is the same point
    // RTL flies back to, so the map overlay's "distance to home" matches the vehicle's own idea
    // of home. Null until the FC has set/reported a home position.
    val homeLatitude : Double? = null,
    val homeLongitude : Double? = null,

    val mode: String? = null,
    val armed: Boolean = false,
    val armable: Boolean = false,
    // True while a GCS-side failsafe (battery voltage or RC-battery) is active
    val failsafeActive: Boolean = false,

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
    // Sprayed acres = sprayed distance × effective swath / 4046.856 (see GridUtils.sweptAcres).
    // Swath = active mission line spacing (auto) or configured default (manual).
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
    // Last waypoint when in AUTO mode (Mission Planner's lastautowp equivalent).
    // This is the mission item the FC is flying TOWARDS - i.e. the same thing currentWaypoint
    // means, remembered across a mode change so pause/resume knows where the mission was
    // interrupted. It must never be set from MISSION_ITEM_REACHED, which reports the item
    // just COMPLETED (one behind); resuming from that seq makes the drone fly back to the
    // START of the line it was half way along. See lastReachedWaypoint below.
    val lastAutoWaypoint: Int = -1,
    // Highest mission item the FC has reported as REACHED (MISSION_ITEM_REACHED). Always one
    // behind lastAutoWaypoint while a mission runs normally.
    val lastReachedWaypoint: Int = -1,

    // Drone identification from OpenDroneID BASIC_ID message (uasId field - SERIAL_NUMBER)
    val droneUid: String? = null,  // Primary UID: OpenDroneID serial number, else AUTOPILOT_VERSION chip UID (uid2) hex
    val droneUid2: String? = null, // Secondary UID from OpenDroneID idOrMac (MAC address)
    val vendorId: Int? = null,     // Board vendor ID
    val productId: Int? = null,    // Board product ID
    val firmwareVersion: String? = null, // Formatted firmware version
    val boardVersion: Int? = null, // Hardware/board version

    // Obstacle-avoidance / terrain telemetry relayed from the CAN hub as standard MAVLink messages.
    // Both come from DISTANCE_SENSOR (132), split by orientation:
    //   terrainData   <- orientation 25 (PITCH_270, downward rangefinder -> distance to ground)
    //   proximityData <- orientation  0 (NONE, forward rangefinder -> obstacle distance)
    val terrainData: TerrainData? = null,
    val proximityData: ProximityData? = null,

    // Spray telemetry for agricultural drones
    val sprayTelemetry: SprayTelemetry = SprayTelemetry()
)

// CalibrationPoint is defined in SprayTelemetryUtils.kt (same package). A second,
// identical copy used to live here under the old capital-"Telemetry" package; it was
// removed when the package was unified to lowercase "telemetry" to avoid a duplicate
// class declaration.

/**
 * One sample of the drone's flown path, tagged with whether the sprayer was running.
 *
 * Consecutive points sharing an [isSpraying] value are drawn as one polyline segment, so
 * the flag is what splits the trail into green (sprayed) and red (not sprayed) runs.
 *
 * Lives here, in the telemetry package, because the trail is owned by SharedViewModel rather
 * than by the map composable: GcsMap is created separately by MainPage and PlanScreen, and
 * composable-local state was being destroyed whenever navigation swapped between them,
 * wiping the sprayed-line history mid-mission.
 */
data class DronePathPoint(
    val position: LatLng,
    val isSpraying: Boolean
)
