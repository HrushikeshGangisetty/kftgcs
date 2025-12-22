package com.example.aerogcsclone.Telemetry

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
    val levelSensorEmptyMv: Int = 10000,     // Voltage when tank is EMPTY (calibrated)
    val levelSensorFullMv: Int = 45000,      // Voltage when tank is FULL (calibrated)

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
    // Mission timer (seconds elapsed since mission start, null if not running)
    val missionElapsedSec: Long? = null,
    val lastMissionElapsedSec: Long? = null,
    val missionCompleted: Boolean = false,
    val totalDistanceMeters: Float? = null,
    // Formatted speed values for UI
    val formattedAirspeed: String? = null,
    val formattedGroundspeed: String? = null,
    val heading: Float? = null,
    // Waypoint tracking for pause/resume
    val currentWaypoint: Int? = null,
    val missionPaused: Boolean = false,
    val pausedAtWaypoint: Int? = null,
    // Last waypoint when in AUTO mode (Mission Planner's lastautowp equivalent)
    // This tracks the waypoint number during mission execution to pre-fill resume dialog
    val lastAutoWaypoint: Int = -1,

    // Spray telemetry for agricultural drones
    val sprayTelemetry: SprayTelemetry = SprayTelemetry()
)

data class CalibrationPoint(
    val voltageMv: Int,          // Voltage at this calibration point
    val levelPercent: Int        // Corresponding tank level % at this voltage
)
