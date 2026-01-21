package com.example.aerogcsclone.telemetry

import android.util.Log
import com.divpundir.mavlink.adapters.coroutines.asCoroutine
import com.divpundir.mavlink.adapters.coroutines.tryConnect
import com.divpundir.mavlink.adapters.coroutines.trySendUnsignedV2
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.connection.StreamState
import com.divpundir.mavlink.connection.tcp.TcpClientMavConnection
import com.divpundir.mavlink.definitions.common.*
import com.divpundir.mavlink.definitions.minimal.*
import com.divpundir.mavlink.definitions.ardupilotmega.MagCalProgress
import com.divpundir.mavlink.definitions.common.MagCalReport
import com.example.aerogcsclone.Telemetry.AppScope
import com.example.aerogcsclone.Telemetry.TelemetryState

import com.example.aerogcsclone.utils.AppStrings
import com.example.aerogcsclone.telemetry.connections.MavConnectionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong


// MAVLink flight modes (ArduPilot values)
object MavMode {
    const val STABILIZE: UInt = 0u
    const val LOITER: UInt = 5u
    const val AUTO: UInt = 3u
    const val GUIDED: UInt = 4u // GUIDED mode for copter takeoff
    const val RTL: UInt = 6u // RTL (Return to Launch) mode
    const val LAND: UInt = 9u // Add LAND mode for explicit landing
    const val BRAKE: UInt = 17u // BRAKE mode - immediately stops all horizontal movement
    // Add other modes as needed
}

// MAVLink command IDs for mission items
object MavCmdId {
    const val NAV_WAYPOINT: UInt = 16u
    const val NAV_LOITER_UNLIM: UInt = 17u
    const val NAV_RETURN_TO_LAUNCH: UInt = 20u
    const val NAV_LAND: UInt = 21u
    const val NAV_TAKEOFF: UInt = 22u
}

// ARM/DISARM magic values (Mission Planner protocol)
object ArmMagicValues {
    // Force arm value - bypasses pre-arm checks (use with caution)
    // This is the Mission Planner magic value for forcing arm
    const val FORCE_ARM: Float = 2989.0f
    
    // Force disarm value - immediately disarms even in flight (emergency use only)
    const val FORCE_DISARM: Float = 21196.0f
}

// Altitude limits for mission validation
object AltitudeLimits {
    // Minimum altitude in meters (relative to home)
    const val MIN_ALTITUDE: Float = 0f
    
    // Maximum altitude in meters (10km - reasonable limit for most drones)
    // ArduPilot typically limits to 120m AGL for regulatory compliance,
    // but we allow higher values for special use cases
    const val MAX_ALTITUDE: Float = 10000f
}

class MavlinkTelemetryRepository(
    private val provider: MavConnectionProvider,
    private val sharedViewModel: SharedViewModel
) {
    val gcsSystemId: UByte = 255u
    val gcsComponentId: UByte = 1u
    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    var fcuSystemId: UByte = 0u
    var fcuComponentId: UByte = 0u

    // Track if disconnection was intentional (user-initiated)
    private var intentionalDisconnect = false

    // Diagnostic info
    private val _lastFailure = MutableStateFlow<Throwable?>(null)
    val lastFailure: StateFlow<Throwable?> = _lastFailure.asStateFlow()

    // Connection
    val connection = provider.createConnection()
    lateinit var mavFrame: Flow<MavFrame<out MavMessage<*>>>
        private set

    // Track last heartbeat time from FCU (thread-safe using AtomicLong)
    private val lastFcuHeartbeatTime = AtomicLong(0L)
    private val HEARTBEAT_TIMEOUT_MS = 8000L // Increased to 8 seconds for Bluetooth reliability

    // Rate limiting for state updates to reduce Bluetooth buffer pressure
    private var lastStateUpdateTime = 0L
    private val MIN_UPDATE_INTERVAL_MS = 50L // 20Hz max update rate (increased from 10Hz for smoother UI)

    // For total distance tracking
    private val positionHistory = mutableListOf<Pair<Double, Double>>()
    private var totalDistanceMeters: Float = 0f
    private var lastMissionRunning = false
    private var flightStartTime: Long = 0L  // Track when flight actually started
    private var isFlightActive = false  // Track if flight is in progress

    // Flow rate filter for sensor fault detection and smoothing
    private val flowRateFilter = FlowRateFilter(windowSize = 5)

    // Voltage filter for BATT3 tank level sensor smoothing
    private val tankVoltageFilter = VoltageFilter(windowSize = 10)

    // Manual mission tracking removed - now handled by UnifiedFlightTracker
    private var previousArmedState = false  // Track previous armed state for TTS announcements
    private var isMissionUploadInProgress = false  // Track if mission upload is actively in progress (not just clearing)

    // COMMAND_ACK flow for calibration and other commands
    private val _commandAck = MutableSharedFlow<CommandAck>(replay = 0, extraBufferCapacity = 10)
    val commandAck: SharedFlow<CommandAck> = _commandAck.asSharedFlow()

    // COMMAND_LONG flow for incoming commands from FC (e.g., ACCELCAL_VEHICLE_POS)
    private val _commandLong = MutableSharedFlow<CommandLong>(replay = 0, extraBufferCapacity = 10)
    val commandLong: SharedFlow<CommandLong> = _commandLong.asSharedFlow()

    // RC Battery failsafe tracking
    private var rcBatteryFailsafeTriggered = false

    // Tank empty notification tracking (based on flow rate when sprayer is ON)
    private var tankEmptyNotificationShown = false
    private var lastTankLevelPercent: Int? = null

    // Zero flow detection for tank empty (when sprayer is ON but flow is 0)
    private var zeroFlowStartTime: Long? = null
    private val ZERO_FLOW_THRESHOLD_MS = 1500L // 1.5 seconds of zero flow = tank empty

    // MAG_CAL_PROGRESS flow for compass calibration progress
    private val _magCalProgress = MutableSharedFlow<MagCalProgress>(replay = 0, extraBufferCapacity = 10)
    val magCalProgress: SharedFlow<MagCalProgress> = _magCalProgress.asSharedFlow()

    // MAG_CAL_REPORT flow for compass calibration final report
    private val _magCalReport = MutableSharedFlow<MagCalReport>(replay = 0, extraBufferCapacity = 10)
    val magCalReport: SharedFlow<MagCalReport> = _magCalReport.asSharedFlow()

    // RC_CHANNELS flow for radio control calibration
    private val _rcChannels = MutableSharedFlow<RcChannels>(replay = 0, extraBufferCapacity = 10)
    val rcChannels: SharedFlow<RcChannels> = _rcChannels.asSharedFlow()

    // PARAM_VALUE flow for parameter reading
    private val _paramValue = MutableSharedFlow<ParamValue>(replay = 0, extraBufferCapacity = 10)
    val paramValue: SharedFlow<ParamValue> = _paramValue.asSharedFlow()

    /**
     * Throttled state update for high-frequency messages (GPS, VFR_HUD, etc.)
     * Limits update rate to prevent Bluetooth buffer overflow
     */
    private fun throttledStateUpdate(update: TelemetryState.() -> TelemetryState) {
        val now = System.currentTimeMillis()
        if (now - lastStateUpdateTime >= MIN_UPDATE_INTERVAL_MS) {
            _state.update(update)
            lastStateUpdateTime = now
        }
    }

    fun start() {
        val scope = AppScope

        suspend fun reconnect(scope: kotlinx.coroutines.CoroutineScope) {
            while (scope.isActive) {
                try {
                    if (connection.tryConnect(scope)) {
                        return // Exit on successful connection
                    }
                } catch (e: Exception) {
                    Log.e("MavlinkRepo", "Connection attempt failed", e)
                    _lastFailure.value = e
                }
                delay(1000)
            }
        }

        // Manage connection state + reconnects
        scope.launch {
            reconnect(this) // Initial connection attempt
            connection.streamState.collect { st ->
                when (st) {
                    is StreamState.Active -> {
                        // Don't set connected=true here anymore
                        // Connection will be marked as true only when FCU heartbeat is received
                        Log.d("MavlinkRepo", "Stream Active - waiting for FCU heartbeat")
                    }
                    is StreamState.Inactive -> {
                        Log.d("MavlinkRepo", "Stream Inactive")
                        _state.update { it.copy(connected = false, fcuDetected = false) }
                        lastFcuHeartbeatTime.set(0L)
                        // Auto-reconnect disabled - user must manually reconnect via connection tab
                        Log.d("MavlinkRepo", "Connection lost - user must manually reconnect via connection tab")
                    }
                }
            }
        }

        // Monitor FCU heartbeat timeout
        scope.launch {
            while (isActive) {
                delay(1000) // Check every second
                if (state.value.fcuDetected && lastFcuHeartbeatTime.get() > 0L) {
                    val timeSinceLastHeartbeat = System.currentTimeMillis() - lastFcuHeartbeatTime.get()
                    if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                        if (state.value.connected) {
                            Log.w("MavlinkRepo", "FCU heartbeat timeout - marking as disconnected")
                            _state.update { it.copy(connected = false, fcuDetected = false) }
                            lastFcuHeartbeatTime.set(0L)
                        }
                    }
                }
            }
        }

        // Send GCS heartbeat
        scope.launch {
            val heartbeat = Heartbeat(
                type = MavType.GCS.wrap(),
                autopilot = MavAutopilot.INVALID.wrap(),
                baseMode = emptyList<MavModeFlag>().wrap(),
                customMode = 0u,
                mavlinkVersion = 3u
            )
            while (isActive) {
                // Send heartbeat even if not fully connected (to allow FCU detection)
                try {
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, heartbeat)
                } catch (e: Exception) {
                    Log.e("MavlinkRepo", "Failed to send heartbeat", e)
                    _lastFailure.value = e
                }
                delay(1000)
            }
        }

        // Shared message stream
        mavFrame = connection.mavFrame
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

        // Log raw messages
        scope.launch {
            mavFrame.collect {
                Log.d("MavlinkRepo", "Frame: ${it.message.javaClass.simpleName} (sysId=${it.systemId}, compId=${it.componentId})")
            }
        }

        // Detect FCU and set connected state based on FCU heartbeat
        scope.launch {
            mavFrame
                .filter { frame ->
                    val msg = frame.message
                    if (msg is Heartbeat) {
                        // CRITICAL: Only detect actual flight controllers, not ADSB/cameras/gimbals
                        val isNotGCS = msg.type != MavType.GCS.wrap()
                        val isAutopilot = msg.autopilot != MavAutopilot.INVALID.wrap()

                        if (!isNotGCS || !isAutopilot) {
                            Log.d("MavlinkRepo", "Ignoring heartbeat: type=${msg.type.entry?.name ?: msg.type.value}, autopilot=${msg.autopilot.entry?.name ?: msg.autopilot.value}")
                            return@filter false
                        }
                        return@filter true
                    }
                    false
                }
                .collect {
                    // Update heartbeat timestamp
                    lastFcuHeartbeatTime.set(System.currentTimeMillis())

                    if (!state.value.fcuDetected) {
                        fcuSystemId = it.systemId
                        fcuComponentId = it.componentId

                        // Extract mode from the FIRST heartbeat during connection
                        val hb = it.message as Heartbeat
                        val armed = (hb.baseMode.value and MavModeFlag.SAFETY_ARMED.value) != 0u

                        // ArduPilot Copter mode mapping
                        val initialMode = when (hb.customMode) {
                            0u -> "Stabilize"
                            1u -> "Acro"
                            2u -> "AltHold"
                            3u -> "Auto"
                            4u -> "Guided"
                            5u -> "Loiter"
                            6u -> "RTL"
                            7u -> "Circle"
                            8u -> "Position"
                            9u -> "Land"
                            10u -> "OF_Loiter"
                            11u -> "Drift"
                            13u -> "Sport"
                            14u -> "Flip"
                            15u -> "AutoTune"
                            16u -> "PosHold"
                            17u -> "Brake"
                            18u -> "Throw"
                            19u -> "Avoid_ADSB"
                            20u -> "Guided_NoGPS"
                            21u -> "Smart_RTL"
                            22u -> "FlowHold"
                            23u -> "Follow"
                            24u -> "ZigZag"
                            25u -> "SystemID"
                            26u -> "AutoRotate"
                            27u -> "Auto_RTL"
                            else -> "Mode ${hb.customMode}"
                        }

                        Log.i("MavlinkRepo", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.i("MavlinkRepo", "✅ FCU DETECTED:")
                        Log.i("MavlinkRepo", "  System ID: ${it.systemId}, Component ID: ${it.componentId}")
                        Log.i("MavlinkRepo", "  Type: ${hb.type.entry?.name ?: hb.type.value}")
                        Log.i("MavlinkRepo", "  Autopilot: ${hb.autopilot.entry?.name ?: hb.autopilot.value}")
                        Log.i("MavlinkRepo", "  customMode: ${hb.customMode} → $initialMode")
                        Log.i("MavlinkRepo", "  armed: $armed")
                        Log.i("MavlinkRepo", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                        // Set fcuDetected, connected, AND initial mode/armed state
                        _state.update { state ->
                            state.copy(
                                fcuDetected = true,
                                connected = true,
                                mode = initialMode,
                                armed = armed
                            )
                        }

                        // Set message intervals
                        launch {
                            suspend fun setMessageRate(messageId: UInt, hz: Float) {
                                val intervalUsec = if (hz <= 0f) 0f else (1_000_000f / hz)
                                val cmd = CommandLong(
                                    targetSystem = fcuSystemId,
                                    targetComponent = fcuComponentId,
                                    command = MavCmd.SET_MESSAGE_INTERVAL.wrap(),
                                    confirmation = 0u,
                                    param1 = messageId.toFloat(),
                                    param2 = intervalUsec,
                                    param3 = 0f,
                                    param4 = 0f,
                                    param5 = 0f,
                                    param6 = 0f,
                                    param7 = 0f
                                )
                                try {
                                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, cmd)
                                } catch (e: Exception) {
                                    Log.e("MavlinkRepo", "Failed to send SET_MESSAGE_INTERVAL", e)
                                    _lastFailure.value = e
                                }
                            }

                            setMessageRate(1u, 0.5f)   // SYS_STATUS - reduced from 1Hz for Bluetooth
                            setMessageRate(24u, 0.5f)  // GPS_RAW_INT - reduced from 1Hz for Bluetooth
                            setMessageRate(33u, 5f)    // GLOBAL_POSITION_INT - increased to 5Hz for smoother position updates
                            setMessageRate(74u, 20f)   // VFR_HUD - 20Hz (50ms) for INSTANT speed updates (pilot critical)
                            setMessageRate(30u, 20f)   // ATTITUDE - 20Hz (50ms) for smooth yaw updates (critical for nose position)
                            setMessageRate(147u, 4f)   // BATTERY_STATUS - 4Hz for fast flow rate updates (spray telemetry)
                            setMessageRate(65u, 1f)    // RC_CHANNELS - reduced from 2Hz for Bluetooth

                            // Request RADIO_STATUS for RC battery monitoring
                            Log.i("RCBattery", "📡 Requesting RADIO_STATUS messages (ID: 109) at 1Hz for RC battery telemetry")
                            setMessageRate(109u, 1f) // RADIO_STATUS (1Hz for RC battery monitoring)
                            Log.i("RCBattery", "✓ RADIO_STATUS message request sent to FCU")

                            // Request AUTOPILOT_VERSION for drone identification
                            Log.i("DroneID", "📡 Requesting AUTOPILOT_VERSION for drone identification")
                            val autopilotVersionCmd = CommandLong(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                command = MavCmd.REQUEST_MESSAGE.wrap(),
                                confirmation = 0u,
                                param1 = 148f, // AUTOPILOT_VERSION message ID
                                param2 = 0f,
                                param3 = 0f,
                                param4 = 0f,
                                param5 = 0f,
                                param6 = 0f,
                                param7 = 0f
                            )
                            try {
                                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, autopilotVersionCmd)
                                Log.i("DroneID", "✓ AUTOPILOT_VERSION request sent to FCU")
                            } catch (e: Exception) {
                                Log.e("DroneID", "Failed to request AUTOPILOT_VERSION", e)
                            }

                            // Request spray telemetry capacity parameters
                            delay(500) // Small delay to let message rates stabilize
                            requestSprayCapacityParameters()
                        }
                    } else if (!state.value.connected) {
                        // FCU was detected before but connection was lost, now it's back
                        Log.i("MavlinkRepo", "FCU heartbeat resumed - marking as connected")
                        _state.update { state -> state.copy(connected = true) }
                    }
                }
        }

        // Collector to log COMMAND_ACK messages for diagnostics
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<CommandAck>()
                .collect { ack ->
                    try {
                        Log.d(
                            "MavlinkRepo",
                            "COMMAND_ACK received: command=${ack.command} result=${ack.result} progress=${ack.progress}"
                        )
                        // Emit to the shared flow for ViewModels to consume
                        _commandAck.emit(ack)
                    } catch (t: Throwable) {
                        Log.d("MavlinkRepo", "COMMAND_ACK received (unable to stringify fields)")
                    }
                }
        }

        // Collector for incoming COMMAND_LONG messages from FC (e.g., for IMU calibration)
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<CommandLong>()
                .collect { cmd ->
                    try {
                        Log.d(
                            "MavlinkRepo",
                            "COMMAND_LONG received: command=${cmd.command.value} param1=${cmd.param1}"
                        )
                        // Emit to the shared flow for ViewModels to consume
                        _commandLong.emit(cmd)
                    } catch (t: Throwable) {
                        Log.d("MavlinkRepo", "COMMAND_LONG received (unable to stringify fields)")
                    }
                }
        }

        // VFR_HUD - CRITICAL: Speed updates must be instant for pilot safety
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<VfrHud>()
                .collect { hud ->
                    // Normalize heading to 0-360 range
                    // VFR_HUD heading is in degrees, but can be out of range or negative
                    val normalizedHeading = when {
                        hud.heading < 0 -> {
                            // Wrap negative values to positive (e.g., -10 becomes 350)
                            ((hud.heading % 360) + 360) % 360
                        }
                        hud.heading >= 360 -> {
                            // Wrap values >= 360 (e.g., 370 becomes 10)
                            hud.heading % 360
                        }
                        else -> hud.heading
                    }.toFloat()

                    // INSTANT update - NO throttling for speed data (pilot critical)
                    // Speed must update immediately for pilot safety
                    _state.update { state ->
                        state.copy(
                            altitudeMsl = hud.alt,
                            airspeed = hud.airspeed.takeIf { v -> v > 0f },
                            groundspeed = hud.groundspeed.takeIf { v -> v > 0f },
                            formattedAirspeed = formatSpeed(hud.airspeed.takeIf { v -> v > 0f }),
                            formattedGroundspeed = formatSpeed(hud.groundspeed.takeIf { v -> v > 0f }),
                            heading = normalizedHeading
                        )
                    }
                }
        }

        // ATTITUDE - for high-frequency yaw updates (nose position)
        // ATTITUDE provides roll, pitch, yaw in radians at higher rate than VFR_HUD
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<Attitude>()
                .collect { att ->
                    // Convert yaw from radians to degrees (0-360)
                    // Attitude yaw is in radians, range -PI to PI
                    val yawDegrees = Math.toDegrees(att.yaw.toDouble()).toFloat()
                    val normalizedYaw = when {
                        yawDegrees < 0 -> yawDegrees + 360f
                        yawDegrees >= 360 -> yawDegrees - 360f
                        else -> yawDegrees
                    }

                    // Direct state update without throttling for smooth yaw display
                    // ATTITUDE is critical for nose position display
                    _state.update { state ->
                        state.copy(
                            heading = normalizedYaw,
                            // Also store raw attitude values if needed
                            roll = Math.toDegrees(att.roll.toDouble()).toFloat(),
                            pitch = Math.toDegrees(att.pitch.toDouble()).toFloat()
                        )
                    }
                }
        }

        // GLOBAL_POSITION_INT
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<GlobalPositionInt>()
                .collect { gp ->
                    val altAMSLm = gp.alt / 1000f
                    val relAltM = gp.relativeAlt / 1000f
                    val lat = gp.lat.takeIf { it != Int.MIN_VALUE }?.let { it / 10_000_000.0 }
                    val lon = gp.lon.takeIf { it != Int.MIN_VALUE }?.let { it / 10_000_000.0 }

                    val currentArmed = state.value.armed

                    // Announce armed/disarmed state transitions via TTS
                    if (currentArmed && !previousArmedState) {
                        // Drone just armed - announce it
                        Log.i("MavlinkRepo", "[TTS] Drone armed - announcing via TTS")
                        sharedViewModel.announceDroneArmed()
                    } else if (!currentArmed && previousArmedState) {
                        // Drone just disarmed - announce it
                        Log.i("MavlinkRepo", "[TTS] Drone disarmed - announcing via TTS")
                        sharedViewModel.announceDroneDisarmed()
                    }

                    // Update state with position data only
                    // NOTE: Flight tracking removed - now handled by UnifiedFlightTracker
                    // Use throttled update for high-frequency GLOBAL_POSITION_INT messages
                    throttledStateUpdate {
                        copy(
                            altitudeMsl = altAMSLm,
                            altitudeRelative = relAltM,
                            latitude = lat,
                            longitude = lon
                        )
                    }

                    // Update previous armed state for next iteration
                    previousArmedState = currentArmed
                }
        }

        // BATTERY_STATUS
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<BatteryStatus>()
                .collect { b ->
                    // Log ALL battery status messages first for debugging
                    Log.d("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d("Spray Telemetry", "📦 BATTERY_STATUS message received:")
                    Log.d("Spray Telemetry", "   Battery ID: ${b.id.toInt()}")
                    Log.d("Spray Telemetry", "   current_battery: ${b.currentBattery} cA (${b.currentBattery / 100f} A)")
                    Log.d("Spray Telemetry", "   current_consumed: ${b.currentConsumed} mAh")
                    Log.d("Spray Telemetry", "   battery_remaining: ${b.batteryRemaining}%")
                    Log.d("Spray Telemetry", "   voltages[0]: ${b.voltages.firstOrNull()} mV")
                    Log.d("Spray Telemetry", "   temperature: ${b.temperature} °C")
                    Log.d("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                    // Main battery (id=0)
                    if (b.id.toInt() == 0) {
                        Log.d("Spray Telemetry", "✅ Processing main battery (id=0)")
                        val currentA = if (b.currentBattery.toInt() == -1) null else b.currentBattery / 100f
                        _state.update { it.copy(currentA = currentA) }
                    }
                    // Flow sensor (BATT2 - id=1)
                    else if (b.id.toInt() == 1) {
                        Log.d("Spray Telemetry", "🚿 Processing FLOW SENSOR (BATT2 - id=1)")
                        Log.d("Spray Telemetry", "Flow sensor (BATT2) - Raw data: " +
                                "current_battery=${b.currentBattery} cA, " +
                                "current_consumed=${b.currentConsumed} mAh, " +
                                "battery_remaining=${b.batteryRemaining}%")

                        // Check for spray enabled but no flow detected
                        val currentSprayEnabled = state.value.sprayTelemetry.sprayEnabled
                        val currentRc7 = state.value.sprayTelemetry.rc7Value

                        if (currentSprayEnabled && b.currentBattery == 0.toShort()) {
                            Log.e("Spray Telemetry", "⚠️⚠️⚠️ CONFIGURATION ERROR DETECTED ⚠️⚠️⚠️")
                            Log.e("Spray Telemetry", "RC7 shows spray ENABLED ($currentRc7 PWM > 1500)")
                            Log.e("Spray Telemetry", "BUT flow sensor reports 0 cA (no flow data)")
                            Log.e("Spray Telemetry", "")
                            Log.e("Spray Telemetry", "Possible causes:")
                            Log.e("Spray Telemetry", "1. Flow sensor not physically connected")
                            Log.e("Spray Telemetry", "2. BATT2_MONITOR parameter not set correctly")
                            Log.e("Spray Telemetry", "   (should be 11 for Fuel Flow sensor)")
                            Log.e("Spray Telemetry", "3. Flow sensor pin not configured in FCU")
                            Log.e("Spray Telemetry", "4. Flow sensor not calibrated (BATT2_AMP_PERVLT)")
                            Log.e("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        }

                        // ═══ IMPROVED: Input validation and conversion ═══
                        val flowRateLiterPerHour = FlowRateValidator.validateAndConvert(b.currentBattery)

                        // Apply filtering and spike detection for non-zero values
                        val filteredFlowRate = if (flowRateLiterPerHour != null && flowRateLiterPerHour > 0f) {
                            // Check for sensor spikes before adding to filter
                            if (flowRateFilter.detectSpike(flowRateLiterPerHour, threshold = 2.0f)) {
                                Log.w("Spray Telemetry", "⚠️ SENSOR SPIKE DETECTED: ${flowRateLiterPerHour} L/h (${flowRateLiterPerHour/60f} L/min)")
                                Log.w("Spray Telemetry", "   Average was: ${flowRateFilter.getAverage()} L/h")
                                Log.w("Spray Telemetry", "   Possible sensor fault or erratic reading - using filtered average")

                                // Use current average instead of spike value
                                flowRateFilter.getAverage()
                            } else {
                                // Normal value - add to filter and get smoothed result
                                val smoothed = flowRateFilter.addValue(flowRateLiterPerHour)
                                Log.d("Spray Telemetry", "✓ Flow rate: raw=${flowRateLiterPerHour} L/h, filtered=${smoothed} L/h")
                                smoothed
                            }
                        } else {
                            // Reset filter when flow stops
                            if (flowRateLiterPerHour == 0f) {
                                flowRateFilter.reset()
                            }
                            flowRateLiterPerHour
                        }

                        val flowRateLiterPerMin = filteredFlowRate?.let {
                            val ratePerMin = it / 60f
                            Log.d("Spray Telemetry", "✓ Flow rate per minute: $ratePerMin L/min")
                            ratePerMin
                        }

                        // Parse consumed volume (current_consumed in mAh = mL)
                        val consumedLiters = if (b.currentConsumed == -1) {
                            Log.w("Spray Telemetry", "⚠️ Consumed volume is -1 (invalid)")
                            null
                        } else if (b.currentConsumed == 0) {
                            // 0 is valid for start of spraying
                            Log.d("Spray Telemetry", "✓ Consumed volume: 0.0 L (0 mL) - No consumption yet")
                            0f
                        } else {
                            val consumed = b.currentConsumed / 1000f  // Convert mAh (mL) to Liters
                            Log.d("Spray Telemetry", "✓ Consumed volume: $consumed L (${b.currentConsumed} mL)")
                            consumed
                        }

                        // Use capacity from parameters (read dynamically from FCU)
                        val flowCapacityLiters = state.value.sprayTelemetry.batt2CapacityMah / 1000f
                        Log.d("Spray Telemetry", "✓ Tank capacity (from params): $flowCapacityLiters L")

                        val flowRemainingPercent = if (b.batteryRemaining.toInt() == -1) {
                            Log.w("Spray Telemetry", "⚠️ Remaining percentage is -1 (invalid)")
                            null
                        } else {
                            val remaining = b.batteryRemaining.toInt()
                            Log.d("Spray Telemetry", "✓ Remaining: $remaining%")
                            remaining
                        }

                        // Format values for UI
                        val formattedFlowRate = flowRateLiterPerMin?.let {
                            "%.2f L/min".format(it)
                        }

                        // Format consumed volume - show in mL for small amounts, L for larger amounts
                        val formattedConsumed = when {
                            consumedLiters == null -> null
                            consumedLiters == 0f -> "0 mL"
                            consumedLiters < 1f -> {
                                val mL = (consumedLiters * 1000f).toInt()
                                "$mL mL"
                            }
                            else -> "%.2f L".format(consumedLiters)
                        }

                        Log.d("Spray Telemetry", "📊 BATT2 Summary:")
                        Log.d("Spray Telemetry", "   Flow Rate: ${formattedFlowRate ?: "N/A"}")
                        Log.d("Spray Telemetry", "   Consumed: ${formattedConsumed ?: "N/A"} (raw: ${consumedLiters}L)")
                        Log.d("Spray Telemetry", "   Capacity: $flowCapacityLiters L")
                        Log.d("Spray Telemetry", "   Remaining: ${flowRemainingPercent?.toString() ?: "N/A"}%")

                        _state.update { state ->
                            state.copy(
                                sprayTelemetry = state.sprayTelemetry.copy(
                                    flowRateLiterPerMin = flowRateLiterPerMin,
                                    consumedLiters = consumedLiters,
                                    flowCapacityLiters = flowCapacityLiters,
                                    flowRemainingPercent = flowRemainingPercent,
                                    formattedFlowRate = formattedFlowRate,
                                    formattedConsumed = formattedConsumed
                                )
                            )
                        }
                        Log.d("Spray Telemetry", "✅ State updated with BATT2 data")

                        // ═══ FLOW-BASED TANK EMPTY DETECTION ═══
                        // Tank is considered empty when:
                        // 1. Sprayer is ON (rc7 enabled)
                        // 2. Flow rate is 0 for 1.5+ seconds
                        // 3. BATT2 is properly configured
                        val currentSprayEnabledForEmpty = state.value.sprayTelemetry.sprayEnabled
                        val configValid = state.value.sprayTelemetry.configurationValid
                        val flowIsZero = flowRateLiterPerMin == null || flowRateLiterPerMin == 0f

                        if (currentSprayEnabledForEmpty && configValid && flowIsZero) {
                            // Sprayer is ON but no flow - start/continue timing
                            if (zeroFlowStartTime == null) {
                                zeroFlowStartTime = System.currentTimeMillis()
                                Log.d("Spray Telemetry", "⏱️ Zero flow detected with sprayer ON - starting timer")
                            } else {
                                val zeroFlowDuration = System.currentTimeMillis() - zeroFlowStartTime!!
                                Log.d("Spray Telemetry", "⏱️ Zero flow duration: ${zeroFlowDuration}ms")

                                if (zeroFlowDuration >= ZERO_FLOW_THRESHOLD_MS && !tankEmptyNotificationShown) {
                                    Log.w("Spray Telemetry", "⚠️ TANK EMPTY - Sprayer ON but no flow for ${zeroFlowDuration}ms")
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "Tank Empty! Sprayer is ON but no flow detected.",
                                            type = NotificationType.WARNING
                                        )
                                    )
                                    sharedViewModel.announceTankEmpty()
                                    tankEmptyNotificationShown = true
                                }
                            }
                        } else {
                            // Reset zero flow timer if sprayer is OFF or flow is detected
                            if (zeroFlowStartTime != null) {
                                Log.d("Spray Telemetry", "✓ Zero flow timer reset (sprayer=${currentSprayEnabledForEmpty}, flow=${flowRateLiterPerMin}, config=${configValid})")
                                zeroFlowStartTime = null
                            }

                            // Reset tank empty notification if flow is detected again (tank refilled)
                            if (!flowIsZero && tankEmptyNotificationShown) {
                                Log.i("Spray Telemetry", "✓ Tank refilled - flow detected, resetting notification flag")
                                tankEmptyNotificationShown = false
                            }
                        }
                    }
                    // Level sensor (BATT3 - id=2)
                    else if (b.id.toInt() == 2) {
                        Log.d("Spray Telemetry", "💧 Processing LEVEL SENSOR (BATT3 - id=2)")

                        // ═══ DIAGNOSTIC: Log ALL voltage cells for debugging ═══
                        Log.d("Spray Telemetry", "═══ BATT3 VOLTAGE DIAGNOSTICS ═══")
                        b.voltages.forEachIndexed { index, voltage ->
                            Log.d("Spray Telemetry", "   voltages[$index]: $voltage mV (raw UShort)")
                        }
                        Log.d("Spray Telemetry", "   battery_remaining: ${b.batteryRemaining}%")
                        Log.d("Spray Telemetry", "   current_battery: ${b.currentBattery} cA")
                        Log.d("Spray Telemetry", "   current_consumed: ${b.currentConsumed} mAh")

                        // Get VOLT_MULT from parameters (if available)
                        val voltMult = state.value.sprayTelemetry.batt3VoltMult ?: 1.0f
                        Log.d("Spray Telemetry", "   BATT3_VOLT_MULT: $voltMult")
                        Log.d("Spray Telemetry", "═══════════════════════════════════")

                        // Parse raw voltage from level sensor
                        // Note: voltages[] in MAVLink is UShort (0-65535), representing millivolts
                        val rawVoltageUShort = b.voltages.firstOrNull()
                        val rawVoltageMv = rawVoltageUShort?.toInt()

                        // Check for UINT16_MAX (65535) which means "not available"
                        val validRawVoltageMv = if (rawVoltageMv == 65535 || rawVoltageMv == null) {
                            Log.w("Spray Telemetry", "⚠️ Voltage is UINT16_MAX (65535) or null - sensor not configured properly")
                            null
                        } else {
                            rawVoltageMv
                        }

                        Log.d("Spray Telemetry", "Level sensor (BATT3) - Raw voltage: $validRawVoltageMv mV, " +
                                "battery_remaining=${b.batteryRemaining}%")

                        // Calculate true sensor voltage (before FCU multiplied it)
                        val trueSensorVoltageMv = if (validRawVoltageMv != null && voltMult > 0) {
                            (validRawVoltageMv / voltMult).toInt()
                        } else {
                            validRawVoltageMv
                        }
                        Log.d("Spray Telemetry", "   True sensor voltage (÷$voltMult): $trueSensorVoltageMv mV")

                        // Apply voltage filter to smooth out fluctuations
                        val tankVoltageMv = if (validRawVoltageMv != null && validRawVoltageMv > 0) {
                            // Check for spike before adding to filter
                            if (tankVoltageFilter.size() >= 3 && tankVoltageFilter.detectSpike(validRawVoltageMv, maxDeviation = 100)) {
                                Log.w("Spray Telemetry", "⚠️ VOLTAGE SPIKE DETECTED: $validRawVoltageMv mV")
                                Log.w("Spray Telemetry", "   Current median: ${tankVoltageFilter.getMedian()} mV")
                                Log.w("Spray Telemetry", "   Using last stable value instead")
                                // Use last stable value instead of spike
                                tankVoltageFilter.getLastStable() ?: validRawVoltageMv
                            } else {
                                // Normal value - add to filter and get smoothed result
                                val filtered = tankVoltageFilter.addValue(validRawVoltageMv)
                                Log.d("Spray Telemetry", "✓ Voltage: raw=$validRawVoltageMv mV, filtered=$filtered mV (samples: ${tankVoltageFilter.size()})")
                                filtered
                            }
                        } else {
                            Log.w("Spray Telemetry", "⚠️ Tank voltage is null or invalid: $validRawVoltageMv")
                            null
                        }

                        // Get calibration values from state (configurable in settings)
                        val emptyVoltageMv = state.value.sprayTelemetry.levelSensorEmptyMv
                        val fullVoltageMv = state.value.sprayTelemetry.levelSensorFullMv

                        // Determine if sensor is inverted (higher voltage = empty)
                        val isInverted = emptyVoltageMv > fullVoltageMv

                        // Calculate tank level percentage from filtered voltage
                        // Supports both normal (voltage increases with level) and inverted sensors
                        val tankLevelPercent = if (tankVoltageMv != null) {
                            if (isInverted) {
                                // Inverted sensor: higher voltage = lower tank level
                                // Empty = high voltage, Full = low voltage
                                when {
                                    tankVoltageMv >= emptyVoltageMv -> {
                                        Log.d("Spray Telemetry", "⚠️ Voltage at/above empty threshold (inverted): $tankVoltageMv >= $emptyVoltageMv mV")
                                        0  // At or above empty voltage = empty
                                    }
                                    tankVoltageMv <= fullVoltageMv -> {
                                        Log.d("Spray Telemetry", "⚠️ Voltage at/below full threshold (inverted): $tankVoltageMv <= $fullVoltageMv mV")
                                        100  // At or below full voltage = full
                                    }
                                    else -> {
                                        // Linear interpolation for inverted sensor
                                        // level% = (emptyV - currentV) / (emptyV - fullV) * 100
                                        val level = ((emptyVoltageMv - tankVoltageMv).toFloat() /
                                                (emptyVoltageMv - fullVoltageMv) * 100).toInt()
                                            .coerceIn(0, 100)
                                        Log.d("Spray Telemetry", "✓ Calculated tank level (inverted): $level% (from ${tankVoltageMv}mV)")
                                        level
                                    }
                                }
                            } else {
                                // Normal sensor: higher voltage = higher tank level
                                when {
                                    tankVoltageMv <= emptyVoltageMv -> {
                                        Log.d("Spray Telemetry", "⚠️ Voltage at/below empty threshold: $tankVoltageMv <= $emptyVoltageMv mV")
                                        0  // At or below empty threshold
                                    }
                                    tankVoltageMv >= fullVoltageMv -> {
                                        Log.d("Spray Telemetry", "⚠️ Voltage at/above full threshold: $tankVoltageMv >= $fullVoltageMv mV")
                                        100  // At or above full threshold
                                    }
                                    else -> {
                                        // Linear interpolation for normal sensor
                                        val level = ((tankVoltageMv - emptyVoltageMv).toFloat() /
                                                (fullVoltageMv - emptyVoltageMv) * 100).toInt()
                                            .coerceIn(0, 100)
                                        Log.d("Spray Telemetry", "✓ Calculated tank level: $level% (from ${tankVoltageMv}mV)")
                                        level
                                    }
                                }
                            }
                        } else {
                            Log.w("Spray Telemetry", "⚠️ Cannot calculate level - voltage is null")
                            null
                        }

                        // Use capacity from parameters (read dynamically from FCU)
                        val tankCapacityLiters = state.value.sprayTelemetry.batt3CapacityMah / 1000f
                        Log.d("Spray Telemetry", "✓ Tank capacity (from params): $tankCapacityLiters L")
                        Log.d("Spray Telemetry", "   Calibration: empty=$emptyVoltageMv mV, full=$fullVoltageMv mV")

                        Log.i("Spray Telemetry", "📊 BATT3 Summary:")
                        Log.i("Spray Telemetry", "   FCU Reported Voltage: ${validRawVoltageMv?.toString() ?: "N/A"} mV")
                        Log.i("Spray Telemetry", "   True Sensor Voltage: ${trueSensorVoltageMv?.toString() ?: "N/A"} mV (÷$voltMult)")
                        Log.i("Spray Telemetry", "   Filtered Voltage: ${tankVoltageMv?.toString() ?: "N/A"} mV")
                        Log.i("Spray Telemetry", "   Level: ${tankLevelPercent?.toString() ?: "N/A"}%")
                        Log.i("Spray Telemetry", "   Sensor Mode: ${if (isInverted) "INVERTED (higher V = empty)" else "NORMAL (higher V = full)"}")
                        Log.i("Spray Telemetry", "   Calibration: Empty=$emptyVoltageMv mV, Full=$fullVoltageMv mV")
                        Log.i("Spray Telemetry", "   Voltage Range: ${kotlin.math.abs(fullVoltageMv - emptyVoltageMv)} mV")
                        Log.i("Spray Telemetry", "   BATT3_VOLT_MULT: $voltMult")
                        Log.i("Spray Telemetry", "   Capacity: $tankCapacityLiters L")
                        Log.i("Spray Telemetry", "   ArduPilot battery_remaining: ${b.batteryRemaining}% (not used)")

                        _state.update { state ->
                            state.copy(
                                sprayTelemetry = state.sprayTelemetry.copy(
                                    tankVoltageMv = tankVoltageMv,
                                    tankLevelPercent = tankLevelPercent,
                                    tankCapacityLiters = tankCapacityLiters
                                )
                            )
                        }
                        Log.i("Spray Telemetry", "✅ State updated with BATT3 data")

                        // NOTE: Tank empty detection is now handled by flow-based detection in BATT2 section
                        // BATT3 level is still tracked for display purposes only
                        // Low tank warning at 15% (still useful as an early warning)
                        if (tankLevelPercent != null) {
                            if (tankLevelPercent <= 15 && tankLevelPercent > 0 && lastTankLevelPercent != null && lastTankLevelPercent!! > 15) {
                                Log.w("Spray Telemetry", "⚠️ TANK LOW (${tankLevelPercent}%) - Showing warning")
                                sharedViewModel.addNotification(
                                    Notification(
                                        message = "Tank Low! ${tankLevelPercent}% remaining.",
                                        type = NotificationType.WARNING
                                    )
                                )
                            }
                            lastTankLevelPercent = tankLevelPercent
                        }
                    }
                    else {
                        Log.w("Spray Telemetry", "⚠️ Unknown battery ID: ${b.id.toInt()} - ignoring")
                    }
                }
        }
        // HEARTBEAT for mode, armed, armable
        var missionTimerJob: kotlinx.coroutines.Job? = null
        var lastMode: String? = null
        var lastArmed: Boolean? = null
        scope.launch {
            mavFrame
                .filter { frame ->
                    state.value.fcuDetected &&
                            frame.systemId == fcuSystemId &&
                            frame.componentId == fcuComponentId  // Only process heartbeats from the main FCU component
                }
                .map { frame -> frame.message }
                .filterIsInstance<Heartbeat>()
                .collect { hb ->
                    // CRITICAL: Log the RAW customMode value from the FCU heartbeat
                    Log.d("MavlinkRepo", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d("MavlinkRepo", "HEARTBEAT from FCU - RAW DATA:")
                    Log.d("MavlinkRepo", "  customMode = ${hb.customMode} (raw UInt value)")
                    Log.d("MavlinkRepo", "  baseMode = ${hb.baseMode.value}")
                    Log.d("MavlinkRepo", "  type = ${hb.type.entry?.name ?: hb.type.value}")
                    Log.d("MavlinkRepo", "  autopilot = ${hb.autopilot.entry?.name ?: hb.autopilot.value}")
                    Log.d("MavlinkRepo", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                    val armed = (hb.baseMode.value and MavModeFlag.SAFETY_ARMED.value) != 0u

                    // ArduPilot Copter mode mapping (consistent with initial detection)
                    // Reference: https://github.com/ArduPilot/ardupilot/blob/master/ArduCopter/mode.h
                    val mode = when (hb.customMode) {
                        0u -> "Stabilize"
                        1u -> "Acro"
                        2u -> "AltHold"
                        3u -> "Auto"
                        4u -> "Guided"
                        5u -> "Loiter"
                        6u -> "RTL"
                        7u -> "Circle"
                        8u -> "Position"      // Position mode
                        9u -> "Land"
                        10u -> "OF_Loiter"    // Optical Flow Loiter
                        11u -> "Drift"
                        13u -> "Sport"
                        14u -> "Flip"
                        15u -> "AutoTune"
                        16u -> "PosHold"
                        17u -> "Brake"
                        18u -> "Throw"
                        19u -> "Avoid_ADSB"
                        20u -> "Guided_NoGPS"
                        21u -> "Smart_RTL"
                        22u -> "FlowHold"
                        23u -> "Follow"
                        24u -> "ZigZag"
                        25u -> "SystemID"
                        26u -> "AutoRotate"
                        27u -> "Auto_RTL"
                        else -> {
                            Log.w("MavlinkRepo", "⚠️ Unknown customMode in heartbeat: ${hb.customMode}")
                            Log.w("MavlinkRepo", "   This mode is not in the ArduPilot Copter mode table")
                            "Mode ${hb.customMode}"
                        }
                    }

                    // Log the parsed mode for verification
                    Log.d("MavlinkRepo", "✅ Parsed mode: $mode (from customMode: ${hb.customMode})")

                    // Only update state if mode or armed status actually changed
                    if (mode != state.value.mode || armed != state.value.armed) {
                        _state.update { it.copy(armed = armed, mode = mode) }
                        Log.d("MavlinkRepo", "🔄 State updated - Mode: $mode, Armed: $armed")
                    } else {
                        Log.d("MavlinkRepo", "No change - Mode: $mode, Armed: $armed")
                    }

                    // Arm/Disarm Notifications
                    if (lastArmed != null && armed != lastArmed) {
                        if (armed) {
                            sharedViewModel.addNotification(Notification(AppStrings.droneArmed, NotificationType.SUCCESS))
                        } else {
                            sharedViewModel.addNotification(Notification(AppStrings.droneDisarmed, NotificationType.INFO))
                        }
                    }

                    // Mission timer logic
                    if (lastMode != mode || lastArmed != armed) {
                        if (mode.equals("Auto", ignoreCase = true) && armed && (lastMode != mode || lastArmed != armed)) {
                            // === NEW: Check if this is a transition TO AUTO for resume mission ===
                            if (lastMode != null && !lastMode.equals("Auto", ignoreCase = true)) {
                                Log.i("TelemetryRepo", "🔄 Mode changed TO AUTO from $lastMode - checking for resume mission")
                                sharedViewModel.onModeChangedToAuto()
                            }

                            missionTimerJob?.cancel()
                            missionTimerJob = scope.launch {
                                var elapsed = 0L
                                _state.update { it.copy(missionElapsedSec = 0L, missionCompleted = false, lastMissionElapsedSec = null, missionCompletedHandled = false) }
                                while (isActive && state.value.mode?.equals("Auto", ignoreCase = true) == true && state.value.armed) {
                                    delay(1000)
                                    elapsed += 1
                                    _state.update { it.copy(missionElapsedSec = elapsed) }
                                }
                                // NOTE: Do NOT set missionCompleted here - let the mode change handler do it
                                // This coroutine exits when mode changes or drone disarms, and the handler below
                                // will properly set missionCompleted based on context (paused vs completed)
                            }
                        } else if ((lastMode?.equals("Auto", ignoreCase = true) == true && !mode.equals("Auto", ignoreCase = true))) {
                            // Mode changed from Auto to something else (Loiter, RTL, etc.)
                            // Check if mission is paused - if so, DON'T mark as completed
                            val isPaused = state.value.missionPaused

                            // Cancel the timer job
                            missionTimerJob?.cancel()
                            missionTimerJob = null

                            // === NEW: Detect AUTO → LOITER transition for "Add Resume Here" popup ===
                            // Only show popup if:
                            // 1. This is a user-initiated LOITER, not geofence-triggered
                            // 2. User selected Automatic mode (not Manual mode)
                            if (mode.equals("Loiter", ignoreCase = true) && !sharedViewModel.isGeofenceTriggeringModeChange && sharedViewModel.isPauseResumeEnabled()) {
                                // Get the current waypoint as the resume point
                                val resumeWaypoint = state.value.lastAutoWaypoint.takeIf { it > 0 }
                                    ?: state.value.currentWaypoint
                                    ?: 1

                                Log.i("TelemetryRepo", "🔄 AUTO → LOITER detected at waypoint $resumeWaypoint (user-initiated, pause/resume enabled)")

                                // Trigger the "Add Resume Here" popup in SharedViewModel
                                sharedViewModel.onModeChangedToLoiterFromAuto(resumeWaypoint)

                                // Keep the timer state frozen for resume
                                val lastElapsed = state.value.missionElapsedSec
                                if (lastElapsed != null && lastElapsed > 0L) {
                                    _state.update { it.copy(lastMissionElapsedSec = lastElapsed) }
                                }
                            } else if (mode.equals("Loiter", ignoreCase = true) && !sharedViewModel.isPauseResumeEnabled()) {
                                // User is in Manual mode - don't show resume popup
                                Log.i("TelemetryRepo", "🔄 Mode change detected but SKIPPING resume popup (user in MANUAL mode)")
                            } else if (mode.equals("Loiter", ignoreCase = true)) {
                                // Geofence triggered this LOITER - don't show resume popup
                                Log.i("TelemetryRepo", "🔄 AUTO → LOITER detected but SKIPPING resume popup (geofence-triggered)")
                            } else {
                                // Only mark as completed if NOT paused AND not already marked
                                if (!isPaused && !state.value.missionCompleted) {
                                    val lastElapsed = state.value.missionElapsedSec
                                    // Only set missionCompleted if we had a meaningful mission (elapsed time > 0)
                                    if ((lastElapsed ?: 0L) > 0L) {
                                        _state.update { it.copy(missionElapsedSec = null, missionCompleted = true, lastMissionElapsedSec = lastElapsed) }

                                        // ✅ Send mission status ENDED to backend (crash-safe)
                                        try {
                                            WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_ENDED)
                                        } catch (e: Exception) {
                                            Log.e("TelemetryRepo", "Failed to send ENDED status", e)
                                        }

                                        // 🔥 Disconnect WebSocket when mission ends
                                        try {
                                            Log.i("TelemetryRepo", "🔌 Closing WebSocket connection - mission ended")
                                            WebSocketManager.getInstance().disconnect()
                                        } catch (e: Exception) {
                                            Log.e("TelemetryRepo", "Failed to disconnect WebSocket", e)
                                        }

                                        Log.i("TelemetryRepo", "✅ Mission completed - elapsed: ${lastElapsed}s (mode: $lastMode -> $mode)")
                                    } else {
                                        // No meaningful mission - just reset state without triggering completion
                                        _state.update { it.copy(missionElapsedSec = null) }
                                        Log.i("TelemetryRepo", "Mode changed from Auto to $mode but no mission was running")
                                    }
                                } else if (isPaused) {
                                    Log.i("TelemetryRepo", "Mission paused - keeping state frozen, NOT marking as completed")
                                } else {
                                    Log.d("TelemetryRepo", "Mission already marked completed, not re-marking")
                                }
                            }

                            // ISSUE FIX #2: Disable spray when mode changes from Auto to any other mode
                            Log.i("TelemetryRepo", "🚿 Mode changed from Auto to $mode - disabling spray")
                            sharedViewModel.disableSprayOnModeChange()

                            // Disable yaw hold when exiting Auto mode
                            Log.i("TelemetryRepo", "🧭 Mode changed from Auto to $mode - disabling yaw hold")
                            sharedViewModel.disableYawHold()
                        } else if (lastArmed == true && !armed) {
                            // Drone disarmed - cancel timer and DON'T show mission complete popup for disarm
                            missionTimerJob?.cancel()
                            missionTimerJob = null
                            _state.update { it.copy(missionElapsedSec = null) }
                            Log.i("TelemetryRepo", "Drone disarmed - timer stopped, no completion popup")

                            // Also disable spray when drone is disarmed for safety
                            Log.i("TelemetryRepo", "🚿 Drone disarmed - disabling spray for safety")
                            sharedViewModel.disableSprayOnModeChange()
                        }
                        lastMode = mode
                        lastArmed = armed
                    }
                }
        }
        // SYS_STATUS
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<SysStatus>()
                .collect { s ->
                    val vBatt = if (s.voltageBattery.toUInt() == 0xFFFFu) null else s.voltageBattery.toFloat() / 1000f
                    val pct = if (s.batteryRemaining.toInt() == -1) null else s.batteryRemaining.toInt()
                    val SENSOR_3D_GYRO = 1u
                    val present = (s.onboardControlSensorsPresent.value and SENSOR_3D_GYRO) != 0u
                    val enabled = (s.onboardControlSensorsEnabled.value and SENSOR_3D_GYRO) != 0u
                    val healthy = (s.onboardControlSensorsHealth.value and SENSOR_3D_GYRO) != 0u
                    val armable = present && enabled && healthy
                    _state.update { it.copy(voltage = vBatt, batteryPercent = pct, armable = armable) }
                }
        }

        // RADIO_STATUS for RC battery percentage
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<RadioStatus>()
                .collect { radioStatus ->
                    // RC battery percentage (0-100, 255 = unknown/not available)
                    val rcBattPct = if (radioStatus.remnoise.toInt() == 255) {
                        null  // RC battery not available
                    } else {
                        radioStatus.remnoise.toInt()  // remnoise field contains RC battery %
                    }

                    // Enhanced logging for RC battery verification
                    Log.d("RCBattery", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d("RCBattery", "✅ RADIO_STATUS Message Received")
                    Log.d("RCBattery", "   remnoise field: ${radioStatus.remnoise.toInt()}")
                    Log.d("RCBattery", "   RC Battery %: ${rcBattPct ?: "N/A"}")
                    Log.d("RCBattery", "   rssi: ${radioStatus.rssi.toInt()}")
                    Log.d("RCBattery", "   remrssi: ${radioStatus.remrssi.toInt()}")
                    Log.d("RCBattery", "   State updated: rcBatteryPercent = ${rcBattPct ?: "N/A"}%")

                    // ═══ RC BATTERY FAILSAFE ═══
                    // Trigger RTL if RC battery is critically low (0% or below) and drone is armed
                    if (rcBattPct != null && rcBattPct <= 0 && state.value.armed && !rcBatteryFailsafeTriggered) {
                        Log.e("RCBattery", "⚠️⚠️⚠️ RC BATTERY FAILSAFE TRIGGERED ⚠️⚠️⚠️")
                        Log.e("RCBattery", "   RC Battery: $rcBattPct%")
                        Log.e("RCBattery", "   Armed: ${state.value.armed}")
                        Log.e("RCBattery", "   Current Mode: ${state.value.mode}")
                        Log.e("RCBattery", "   INITIATING EMERGENCY RTL...")

                        // Mark failsafe as triggered to prevent multiple RTL commands
                        rcBatteryFailsafeTriggered = true

                        // Launch coroutine to trigger RTL
                        scope.launch {
                            try {
                                val rtlSuccess = changeMode(MavMode.RTL)
                                if (rtlSuccess) {
                                    Log.e("RCBattery", "✅ EMERGENCY RTL ACTIVATED - RC BATTERY CRITICAL")
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "⚠️ RC BATTERY CRITICAL (${rcBattPct}%) - RTL ACTIVATED",
                                            type = NotificationType.ERROR
                                        )
                                    )
                                    // Announce via TTS
                                    sharedViewModel.announceRCBatteryFailsafe(rcBattPct)
                                } else {
                                    Log.e("RCBattery", "❌ FAILED TO ACTIVATE RTL - RC BATTERY FAILSAFE")
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "❌ RC BATTERY FAILSAFE: Failed to activate RTL",
                                            type = NotificationType.ERROR
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("RCBattery", "❌ Exception during RC battery failsafe RTL", e)
                            }
                        }
                    }
                    // Reset failsafe flag when battery recovers and drone is disarmed
                    else if (!state.value.armed && rcBatteryFailsafeTriggered) {
                        Log.d("RCBattery", "✓ Drone disarmed - resetting RC battery failsafe flag")
                        rcBatteryFailsafeTriggered = false
                    }

                    _state.update { it.copy(rcBatteryPercent = rcBattPct) }
                    Log.d("RCBattery", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                    _state.update { it.copy(rcBatteryPercent = rcBattPct) }
                }
        }

        // STATUSTEXT for arming failures and other messages
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<Statustext>()

                .collect { status ->
                    val message = status.text.toString()
                    val type = when (status.severity.value) {
                        MavSeverity.EMERGENCY.value, MavSeverity.ALERT.value, MavSeverity.CRITICAL.value, MavSeverity.ERROR.value -> NotificationType.ERROR
                        MavSeverity.WARNING.value -> NotificationType.WARNING
                        else -> NotificationType.INFO
                    }
                    sharedViewModel.addNotification(Notification(message, type))
                }
        }

        // MISSION_CURRENT for mission progress and waypoint tracking
        var lastMissionSeq = -1
        scope.launch {
            mavFrame
                .filter {
                    val detected = state.value.fcuDetected
                    val matchesId = it.systemId == fcuSystemId
                    // DEBUG: Log filter conditions
                    if (it.message is MissionCurrent) {
                        Log.i("DEBUG_MISSION", "MISSION_CURRENT received - fcuDetected=$detected, systemId=${it.systemId}, fcuSystemId=$fcuSystemId, matches=$matchesId")
                    }
                    detected && matchesId
                }
                .map { it.message }
                .filterIsInstance<MissionCurrent>()
                .collect { missionCurrent ->
                    val currentSeq = missionCurrent.seq.toInt()
                    
                    // Capture current mode for consistent checks
                    val currentMode = state.value.mode

                    // DEBUG LOG: Track mode and waypoint updates
                    Log.i("DEBUG_MISSION", "MISSION_CURRENT: seq=$currentSeq, mode=$currentMode, modeCheck=${currentMode?.equals("Auto", ignoreCase = true)}")

                    // Update current waypoint in state
                    _state.update { it.copy(currentWaypoint = currentSeq) }
                    Log.d("MavlinkRepo", "Mission progress: waypoint $currentSeq")

                    // Track last AUTO waypoint (Mission Planner protocol)
                    // Only update lastAutoWaypoint when in AUTO mode and waypoint is non-zero
                    if (currentMode?.equals("Auto", ignoreCase = true) == true && currentSeq != 0) {
                        _state.update { it.copy(lastAutoWaypoint = currentSeq) }
                        Log.d("MavlinkRepo", "Updated lastAutoWaypoint to: $currentSeq (mode=$currentMode)")
                    }

                    // Update SharedViewModel
                    sharedViewModel.updateCurrentWaypoint(currentSeq)
                    Log.d("MavlinkRepo", "Updated SharedViewModel currentWaypoint to: $currentSeq")

                    if (currentSeq != lastMissionSeq) {
                        lastMissionSeq = currentSeq
                        sharedViewModel.addNotification(
                            Notification(
                                "Executing waypoint #${lastMissionSeq}",
                                NotificationType.INFO
                            )
                        )
                    }
                }
        }

        // MISSION_ITEM_REACHED - Fallback for tracking waypoints (some ArduPilot versions don't send MISSION_CURRENT)
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<MissionItemReached>()
                .collect { missionItemReached ->
                    val reachedSeq = missionItemReached.seq.toInt()

                    // Capture current mode for consistent checks
                    val currentMode = state.value.mode

                    // DEBUG LOG: Track waypoint reached
                    Log.i("DEBUG_MISSION", "MISSION_ITEM_REACHED: seq=$reachedSeq, mode=$currentMode, modeCheck=${currentMode?.equals("Auto", ignoreCase = true)}")

                    // Update current waypoint in state (as fallback if MISSION_CURRENT not available)
                    _state.update { it.copy(currentWaypoint = reachedSeq) }
                    Log.d("MavlinkRepo", "Mission item reached: waypoint $reachedSeq")

                    // Track last AUTO waypoint (Mission Planner protocol)
                    // Only update lastAutoWaypoint when in AUTO mode and waypoint is non-zero
                    if (currentMode?.equals("Auto", ignoreCase = true) == true && reachedSeq != 0) {
                        _state.update { it.copy(lastAutoWaypoint = reachedSeq) }
                        Log.d("MavlinkRepo", "Updated lastAutoWaypoint to: $reachedSeq (mode=$currentMode) [from MISSION_ITEM_REACHED]")
                    }

                    // Update SharedViewModel
                    sharedViewModel.updateCurrentWaypoint(reachedSeq)
                    Log.d("MavlinkRepo", "Updated SharedViewModel currentWaypoint to: $reachedSeq")

                    sharedViewModel.addNotification(
                        Notification(
                            "Reached waypoint #${reachedSeq}",
                            NotificationType.INFO
                        )
                    )
                }
        }

        // MISSION_ACK for mission upload status
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<MissionAck>()
                .collect { missionAck ->
                    // CRITICAL: Ignore ACKs during mission upload process
                    // The uploadMissionWithAck function handles its own ACKs internally
                    if (isMissionUploadInProgress) {
                        Log.d("MissionUpload", "Global listener: Ignoring ACK during upload (handled by upload function)")
                        return@collect
                    }

                    val message = "Mission upload: ${missionAck.type.entry?.name ?: "UNKNOWN"}"
                    val type = if (missionAck.type.value == MavMissionResult.MAV_MISSION_ACCEPTED.value) NotificationType.SUCCESS else NotificationType.ERROR
                    sharedViewModel.addNotification(Notification(message, type))
                }
        }

        // GPS_RAW_INT
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<GpsRawInt>()
                .collect { gps ->
                    val sats = gps.satellitesVisible.toInt().takeIf { it >= 0 }
                    val hdop = if (gps.eph.toUInt() == 0xFFFFu) null else gps.eph.toFloat() / 100f
                    _state.update { it.copy(sats = sats, hdop = hdop) }
                }
        }

        // MAG_CAL_PROGRESS for compass calibration progress
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<MagCalProgress>()
                .collect { progress ->
                    Log.d("CompassCalVM", "📨 MAG_CAL_PROGRESS received:")
                    Log.d("CompassCalVM", "   └─ Compass ID: ${progress.compassId}")
                    Log.d("CompassCalVM", "   └─ Status: ${progress.calStatus.entry?.name ?: "UNKNOWN"}")
                    Log.d("CompassCalVM", "   └─ Completion: ${progress.completionPct}%")
                    Log.d("CompassCalVM", "   └─ Attempt: ${progress.attempt}")
                    Log.d("CompassCalVM", "   └─ Direction: X=${progress.directionX}, Y=${progress.directionY}, Z=${progress.directionZ}")
                    _magCalProgress.emit(progress)
                }
        }

        // MAG_CAL_REPORT for compass calibration final report
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<MagCalReport>()
                .collect { report ->
                    Log.d("CompassCalVM", "📊 MAG_CAL_REPORT received:")
                    Log.d("CompassCalVM", "   └─ Compass ID: ${report.compassId}")
                    Log.d("CompassCalVM", "   └─ Status: ${report.calStatus.entry?.name ?: "UNKNOWN"}")
                    Log.d("CompassCalVM", "   └─ Fitness: ${report.fitness}")
                    Log.d("CompassCalVM", "   └─ Offsets: X=${report.ofsX}, Y=${report.ofsY}, Z=${report.ofsZ}")
                    Log.d("CompassCalVM", "   └─ Autosaved: ${report.autosaved}")
                    _magCalReport.emit(report)
                }
        }

        // RC_CHANNELS for radio control calibration
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<RcChannels>()
                .collect { rcChannelsData ->
                    Log.d("RCCalVM", "📻 RC_CHANNELS received: ch1=${rcChannelsData.chan1Raw} ch2=${rcChannelsData.chan2Raw} ch3=${rcChannelsData.chan3Raw} ch4=${rcChannelsData.chan4Raw}")

                    // Monitor RC7 for spray system status
                    val rc7Value = rcChannelsData.chan7Raw.toInt()
                    val sprayEnabled = rc7Value > 1500 // PWM > 1500 = spray ON

                    Log.d("Spray Telemetry", "🎮 RC7 channel: $rc7Value PWM, Spray ${if (sprayEnabled) "ENABLED" else "DISABLED"}")

                    // Check if spray status changed
                    val previousSprayEnabled = state.value.sprayTelemetry.sprayEnabled
                    if (sprayEnabled != previousSprayEnabled) {
                        // Spray status changed - add notification and show popup
                        val notificationMessage = if (sprayEnabled) "Sprayer Enabled" else "Sprayer Disabled"
                        val notificationType = if (sprayEnabled) NotificationType.SUCCESS else NotificationType.INFO

                        sharedViewModel.addNotification(Notification(notificationMessage, notificationType))
                        sharedViewModel.showSprayStatusPopup(notificationMessage)

                        Log.i("Spray Telemetry", "🚨 Spray status changed: $notificationMessage")
                    }

                    _state.update { state ->
                        state.copy(
                            sprayTelemetry = state.sprayTelemetry.copy(
                                sprayEnabled = sprayEnabled,
                                rc7Value = rc7Value
                            )
                        )
                    }

                    _rcChannels.emit(rcChannelsData)
                }
        }

        // PARAM_VALUE for parameter reading
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<ParamValue>()
                .collect { paramValue ->
                    val paramName = paramValue.paramId.toString().trim()
                    Log.d("ParamVM", "📝 PARAM_VALUE received: $paramName = ${paramValue.paramValue}")

                    // Handle spray telemetry parameters
                    when (paramName) {
                        "BATT2_MONITOR" -> {
                            val monitorType = paramValue.paramValue.toInt()
                            Log.i("Spray Telemetry", "📊 BATT2_MONITOR parameter read: $monitorType")

                            if (monitorType != 11) {
                                Log.e("Spray Telemetry", "⚠️⚠️⚠️ CONFIGURATION ERROR ⚠️⚠️⚠️")
                                Log.e("Spray Telemetry", "BATT2_MONITOR = $monitorType")
                                Log.e("Spray Telemetry", "Expected: 11 (Fuel Flow sensor)")
                                Log.e("Spray Telemetry", "Flow sensor will NOT work with current setting!")

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor not configured! BATT2_MONITOR should be 11, currently $monitorType",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
                                Log.i("Spray Telemetry", "✅ BATT2_MONITOR correctly set to 11 (Fuel Flow)")
                            }

                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt2MonitorType = monitorType
                                    )
                                )
                            }
                        }

                        "BATT2_CAPACITY" -> {
                            val capacityMah = paramValue.paramValue.toInt()
                            Log.i("Spray Telemetry", "📊 BATT2_CAPACITY parameter read: $capacityMah mAh (${capacityMah/1000f} L)")

                            if (capacityMah == 0) {
                                Log.e("Spray Telemetry", "⚠️ BATT2_CAPACITY is 0 - tank capacity not configured!")
                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor capacity not set! Configure BATT2_CAPACITY parameter",
                                        NotificationType.WARNING
                                    )
                                )
                            }

                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt2CapacityMah = capacityMah
                                    )
                                )
                            }
                        }

                        "BATT2_AMP_PERVLT" -> {
                            val ampPerVolt = paramValue.paramValue
                            Log.i("Spray Telemetry", "📊 BATT2_AMP_PERVLT parameter read: $ampPerVolt")

                            if (ampPerVolt == 0f) {
                                Log.e("Spray Telemetry", "⚠️ BATT2_AMP_PERVLT is 0 - flow sensor NOT calibrated!")
                                Log.e("Spray Telemetry", "This is why you're getting 0 flow rate even when spray is enabled!")

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor not calibrated! Set BATT2_AMP_PERVLT parameter",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
                                Log.i("Spray Telemetry", "✅ BATT2_AMP_PERVLT calibrated: $ampPerVolt")
                            }

                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt2AmpPerVolt = ampPerVolt
                                    )
                                )
                            }
                        }

                        "BATT2_CURR_PIN" -> {
                            val currPin = paramValue.paramValue.toInt()
                            Log.i("Spray Telemetry", "📊 BATT2_CURR_PIN parameter read: $currPin")

                            if (currPin == -1 || currPin == 0) {
                                Log.e("Spray Telemetry", "⚠️ BATT2_CURR_PIN not configured ($currPin) - sensor not connected!")
                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor pin not configured! Set BATT2_CURR_PIN parameter",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
                                Log.i("Spray Telemetry", "✅ BATT2_CURR_PIN configured: pin $currPin")
                            }

                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt2CurrPin = currPin
                                    )
                                )
                            }
                        }

                        "BATT3_CAPACITY" -> {
                            val capacityMah = paramValue.paramValue.toInt()
                            Log.i("Spray Telemetry", "📊 BATT3_CAPACITY parameter read: $capacityMah mAh (${capacityMah/1000f} L)")

                            if (capacityMah == 0) {
                                Log.w("Spray Telemetry", "⚠️ BATT3_CAPACITY is 0 - level sensor capacity not configured")
                            }

                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt3CapacityMah = capacityMah
                                    )
                                )
                            }
                        }

                        "BATT3_VOLT_PIN" -> {
                            val voltPin = paramValue.paramValue.toInt()
                            Log.i("Spray Telemetry", "📊 BATT3_VOLT_PIN parameter read: $voltPin")

                            if (voltPin == -1 || voltPin == 0) {
                                Log.w("Spray Telemetry", "⚠️ BATT3_VOLT_PIN not configured ($voltPin)")
                            } else {
                                Log.i("Spray Telemetry", "✅ BATT3_VOLT_PIN configured: pin $voltPin")
                            }
                        }

                        "BATT3_VOLT_MULT" -> {
                            val voltMult = paramValue.paramValue
                            Log.i("Spray Telemetry", "📊 BATT3_VOLT_MULT parameter read: $voltMult")

                            // Store the multiplier
                            _state.update { state ->
                                state.copy(
                                    sprayTelemetry = state.sprayTelemetry.copy(
                                        batt3VoltMult = voltMult
                                    )
                                )
                            }

                            // Warn if multiplier is high (typical for battery monitoring, not level sensors)
                            if (voltMult > 2.0f) {
                                Log.w("Spray Telemetry", "⚠️⚠️⚠️ HIGH VOLTAGE MULTIPLIER DETECTED ⚠️⚠️⚠️")
                                Log.w("Spray Telemetry", "   BATT3_VOLT_MULT = $voltMult")
                                Log.w("Spray Telemetry", "   This is typical for BATTERY monitoring with voltage divider")
                                Log.w("Spray Telemetry", "   For a LEVEL SENSOR, consider setting BATT3_VOLT_MULT = 1.0")
                                Log.w("Spray Telemetry", "   Current readings are being multiplied by ${voltMult}x!")

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Level sensor VOLT_MULT=$voltMult (high). Consider setting to 1.0 for level sensors.",
                                        NotificationType.WARNING
                                    )
                                )
                            } else {
                                Log.i("Spray Telemetry", "✅ BATT3_VOLT_MULT looks reasonable for level sensor: $voltMult")
                            }
                        }
                    }

                    // After receiving any spray parameter, validate complete configuration
                    if (paramName.startsWith("BATT2_") || paramName.startsWith("BATT3_")) {
                        validateSprayConfiguration()
                    }

                    _paramValue.emit(paramValue)
                }
        }

        // AUTOPILOT_VERSION for drone identification
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<AutopilotVersion>()
                .collect { autopilotVersion ->
                    try {
                        // Extract UID - prefer uid2 over uid if uid2 is non-zero
                        val primaryUid = if (autopilotVersion.uid2.any { it.toInt() != 0 }) {
                            // Convert uid2 (18 bytes) to hex string
                            autopilotVersion.uid2.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                        } else if (autopilotVersion.uid != 0UL) {
                            // Convert uid (8 bytes) to hex string
                            "%016x".format(autopilotVersion.uid)
                        } else {
                            null
                        }

                        // Also store uid2 separately if it exists and is different from uid
                        val secondaryUid = if (autopilotVersion.uid2.any { it.toInt() != 0 }) {
                            val uid2Hex = autopilotVersion.uid2.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                            val uidHex = "%016x".format(autopilotVersion.uid)
                            if (uid2Hex != uidHex) uid2Hex else null
                        } else null

                        // Format firmware version (4 bytes: major.minor.patch.type)
                        val fwVersion = autopilotVersion.flightSwVersion
                        val major = (fwVersion shr 24) and 0xFFu
                        val minor = (fwVersion shr 16) and 0xFFu
                        val patch = (fwVersion shr 8) and 0xFFu
                        val fwType = fwVersion and 0xFFu
                        val formattedFirmware = "$major.$minor.$patch (type: $fwType)"

                        Log.i("DroneID", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.i("DroneID", "✅ AUTOPILOT_VERSION received:")
                        Log.i("DroneID", "  Primary UID: $primaryUid")
                        if (secondaryUid != null) {
                            Log.i("DroneID", "  Secondary UID: $secondaryUid")
                        }
                        Log.i("DroneID", "  Vendor ID: ${autopilotVersion.vendorId}")
                        Log.i("DroneID", "  Product ID: ${autopilotVersion.productId}")
                        Log.i("DroneID", "  Firmware: $formattedFirmware")
                        Log.i("DroneID", "  Board Version: ${autopilotVersion.boardVersion}")
                        Log.i("DroneID", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                        _state.update { state ->
                            state.copy(
                                droneUid = primaryUid,
                                droneUid2 = secondaryUid,
                                vendorId = autopilotVersion.vendorId.toInt(),
                                productId = autopilotVersion.productId.toInt(),
                                firmwareVersion = formattedFirmware,
                                boardVersion = autopilotVersion.boardVersion.toInt()
                            )
                        }

                        // Announce drone ID via TTS
                        if (primaryUid != null) {
                            val shortUid = primaryUid.takeLast(8) // Last 8 characters for brevity
                            sharedViewModel.speak("Drone identified. UID ending in $shortUid")
                        }

                    } catch (e: Exception) {
                        Log.e("DroneID", "Error processing AUTOPILOT_VERSION", e)
                    }
                }
        }

        // Mission progress logging: MISSION_ITEM_REACHED, MISSION_CURRENT, and mode


        // Helper to request mission items from FCU and return as list
        suspend fun requestMissionItemsFromFcu(timeoutMs: Long = 5000): List<MissionItemInt> {
            val items = mutableListOf<MissionItemInt>()
            val expectedCountDeferred = CompletableDeferred<Int?>()
            val perSeqMap = mutableMapOf<Int, CompletableDeferred<Unit>>()
            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        is MissionItemInt -> {
                            items.add(msg)
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        else -> {}
                    }
                }
            }
            val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
            val expectedCount = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() } ?: 0
            for (seq in 0 until expectedCount) {
                val seqDeferred = CompletableDeferred<Unit>()
                perSeqMap[seq] = seqDeferred
                val reqItem = MissionRequestInt(targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort())
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                withTimeoutOrNull(1500L) { seqDeferred.await() }
                perSeqMap.remove(seq)
            }
            delay(200)
            job.cancel()
            return items.sortedBy { it.seq.toInt() }
        }
    }

    suspend fun sendCommand(command: MavCmd, param1: Float = 0f, param2: Float = 0f, param3: Float = 0f, param4: Float = 0f, param5: Float = 0f, param6: Float = 0f, param7: Float = 0f) {
        val commandLong = CommandLong(
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId,
            command = command.wrap(),
            confirmation = 0u,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            param5 = param5,
            param6 = param6,
            param7 = param7
        )
        try {
            connection.trySendUnsignedV2(
                gcsSystemId,
                gcsComponentId, commandLong
            )
            Log.d("MavlinkRepo", "Sent COMMAND_LONG: cmd=${command} p1=$param1 p2=$param2 p3=$param3 p4=$param4 p5=$param5 p6=$param6 p7=$param7")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to send command", e)
        }
    }

    /**
     * Send a raw command using command ID (for ArduPilot-specific commands not in standard MAVLink).
     */
    suspend fun sendCommandRaw(commandId: UInt, param1: Float = 0f, param2: Float = 0f, param3: Float = 0f, param4: Float = 0f, param5: Float = 0f, param6: Float = 0f, param7: Float = 0f) {
        val commandLong = CommandLong(
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId,
            command = MavEnumValue.fromValue(commandId),
            confirmation = 0u,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            param5 = param5,
            param6 = param6,
            param7 = param7
        )
        try {
            connection.trySendUnsignedV2(
                gcsSystemId,
                gcsComponentId, commandLong
            )
            Log.d("MavlinkRepo", "Sent COMMAND_LONG (raw): cmdId=$commandId p1=$param1 p2=$param2 p3=$param3 p4=$param4 p5=$param5 p6=$param6 p7=$param7")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to send raw command", e)
        }
    }

    /**
     * Send COMMAND_ACK message to autopilot.
     * This is used in ArduPilot's conversational calibration protocol where the GCS
     * sends ACK messages back to the autopilot to confirm user actions.
     */
    suspend fun sendCommandAck(
        commandId: UInt,
        result: MavResult,
        progress: UByte = 0u,
        resultParam2: Int = 0
    ) {
        val commandAck = CommandAck(
            command = MavEnumValue.fromValue(commandId),
            result = result.wrap(),
            progress = progress,
            resultParam2 = resultParam2,
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId
        )
        try {
            connection.trySendUnsignedV2(
                gcsSystemId,
                gcsComponentId,
                commandAck
            )
            Log.d("MavlinkRepo", "Sent COMMAND_ACK: cmd=$commandId result=${result.name} progress=$progress")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to send COMMAND_ACK", e)
        }
    }

    /**
     * Send RC_CHANNELS_OVERRIDE message to control specific RC channels.
     * This is used for real-time PWM control of spray systems and other RC-controlled peripherals.
     *
     * @param channel The RC channel number (1-18)
     * @param pwmValue The PWM value (typically 1000-2000, use 0 or UINT16_MAX to release channel)
     */
    suspend fun sendRcChannelOverride(channel: Int, pwmValue: UShort) {
        // RC_CHANNELS_OVERRIDE has 18 channels, use UINT16_MAX (65535) to not override a channel
        val noOverride: UShort = 65535u

        val rcOverride = RcChannelsOverride(
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId,
            chan1Raw = if (channel == 1) pwmValue else noOverride,
            chan2Raw = if (channel == 2) pwmValue else noOverride,
            chan3Raw = if (channel == 3) pwmValue else noOverride,
            chan4Raw = if (channel == 4) pwmValue else noOverride,
            chan5Raw = if (channel == 5) pwmValue else noOverride,
            chan6Raw = if (channel == 6) pwmValue else noOverride,
            chan7Raw = if (channel == 7) pwmValue else noOverride,
            chan8Raw = if (channel == 8) pwmValue else noOverride,
            chan9Raw = if (channel == 9) pwmValue else noOverride,
            chan10Raw = if (channel == 10) pwmValue else noOverride,
            chan11Raw = if (channel == 11) pwmValue else noOverride,
            chan12Raw = if (channel == 12) pwmValue else noOverride,
            chan13Raw = if (channel == 13) pwmValue else noOverride,
            chan14Raw = if (channel == 14) pwmValue else noOverride,
            chan15Raw = if (channel == 15) pwmValue else noOverride,
            chan16Raw = if (channel == 16) pwmValue else noOverride,
            chan17Raw = if (channel == 17) pwmValue else noOverride,
            chan18Raw = if (channel == 18) pwmValue else noOverride
        )

        try {
            connection.trySendUnsignedV2(
                gcsSystemId,
                gcsComponentId,
                rcOverride
            )
            Log.d("MavlinkRepo", "Sent RC_CHANNELS_OVERRIDE: ch$channel = $pwmValue PWM")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to send RC_CHANNELS_OVERRIDE", e)
        }
    }

    /**
     * Send DO_SET_SERVO command - controls servo/motor output directly.
     * Note: This sets servo output, not RC input. The servo number corresponds to
     * SERVO outputs (SERVO1_FUNCTION, SERVO2_FUNCTION, etc.), not RC channels.
     *
     * For ArduPilot:
     * - Servo 1-8 typically map to MAIN outputs
     * - Servo 9+ map to AUX outputs
     *
     * @param servoNumber Servo output number (1-based)
     * @param pwmValue PWM value (typically 1000-2000)
     */
    suspend fun sendServoCommand(servoNumber: Int, pwmValue: Int) {
        sendCommand(
            MavCmd.DO_SET_SERVO,
            param1 = servoNumber.toFloat(),
            param2 = pwmValue.toFloat()
        )
        Log.i("MavlinkRepo", "Sent DO_SET_SERVO: servo=$servoNumber PWM=$pwmValue")
    }

    /**
     * Send pre-arm checks command to validate vehicle is ready to arm
     * Returns true if pre-arm checks pass, false otherwise
     */
    suspend fun sendPrearmChecks(): Boolean {
        Log.i("MavlinkRepo", "[Pre-arm] Sending RUN_PREARM_CHECKS command...")
        try {
            sendCommand(
                MavCmd.RUN_PREARM_CHECKS,
                0f  // param1: not used
            )
            Log.i("MavlinkRepo", "[Pre-arm] Command sent, waiting for status messages...")
            
            // Wait a bit for pre-arm status messages to arrive via STATUSTEXT
            // These will be automatically displayed via the existing STATUSTEXT handler
            delay(2000)
            
            // Check if vehicle became armable after pre-arm checks
            val armable = state.value.armable
            Log.i("MavlinkRepo", "[Pre-arm] Vehicle armable status: $armable")
            return armable
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "[Pre-arm] Failed to send pre-arm checks", e)
            return false
        }
    }

    /**
     * Arm the vehicle with retry logic and force-arm fallback
     * @param forceArm If true, uses force-arm immediately (param2 = 2989.0f)
     * @return true if armed successfully, false otherwise
     */
    suspend fun arm(forceArm: Boolean = false): Boolean {
        if (!state.value.armable && !forceArm) {
            Log.w("MavlinkRepo", "[Arm] Vehicle not armable, skipping arm command")
            sharedViewModel.addNotification(
                Notification("Vehicle not armable. Check pre-arm status.", NotificationType.ERROR)
            )
            return false
        }

        val maxAttempts = 3
        val retryDelays = listOf(1000L, 2000L, 3000L) // Progressive backoff delays
        
        for (attempt in 1..maxAttempts) {
            try {
                val param2 = if (forceArm || attempt == maxAttempts) ArmMagicValues.FORCE_ARM else 0f
                val armType = if (param2 == ArmMagicValues.FORCE_ARM) "FORCE-ARM" else "ARM"
                
                Log.i("MavlinkRepo", "[Arm] Attempt $attempt/$maxAttempts - Sending $armType command...")
                sendCommand(
                    MavCmd.COMPONENT_ARM_DISARM,
                    1f,      // param1: 1 = arm
                    param2   // param2: 0 = normal, FORCE_ARM = force-arm (Mission Planner magic value)
                )
                
                // Wait for arming to complete
                delay(1500)
                
                // Check if vehicle is now armed
                if (state.value.armed) {
                    Log.i("MavlinkRepo", "[Arm] ✅ Vehicle armed successfully on attempt $attempt")
                    sharedViewModel.addNotification(
                        Notification("Vehicle armed successfully", NotificationType.SUCCESS)
                    )
                    return true
                } else {
                    Log.w("MavlinkRepo", "[Arm] ⚠️ Attempt $attempt failed - vehicle not armed")
                    if (attempt < maxAttempts) {
                        Log.i("MavlinkRepo", "[Arm] Retrying in ${retryDelays[attempt-1]}ms...")
                        delay(retryDelays[attempt-1])
                    }
                }
            } catch (e: Exception) {
                Log.e("MavlinkRepo", "[Arm] Exception on attempt $attempt", e)
                if (attempt < maxAttempts) {
                    delay(retryDelays[attempt-1])
                }
            }
        }
        
        Log.e("MavlinkRepo", "[Arm] ❌ Failed to arm vehicle after $maxAttempts attempts")
        sharedViewModel.addNotification(
            Notification("Failed to arm vehicle. Check STATUSTEXT messages for details.", NotificationType.ERROR)
        )
        return false
    }

    suspend fun disarm() {
        sendCommand(
            MavCmd.COMPONENT_ARM_DISARM,
            0f  // 0 = disarm
        )
        Log.i("MavlinkRepo", "Disarm command sent")
    }

    /**
     * Change vehicle mode (ArduPilot: param1=1, param2=customMode)
     * Waits for Heartbeat confirmation.
     */
    suspend fun changeMode(customMode: UInt): Boolean {
        sendCommand(
            MavCmd.DO_SET_MODE,
            1f,                   // param1: MAV_MODE_FLAG_CUSTOM_MODE_ENABLED (always 1 for ArduPilot)
            customMode.toFloat(), // param2: custom mode (e.g., 3u for AUTO)
            0f, 0f, 0f, 0f, 0f
        )
        // Wait for Heartbeat to confirm mode change - increased timeout for Bluetooth/real hardware
        val timeoutMs = 8000L // Increased from 5s to 8s for real hardware reliability
        val start = System.currentTimeMillis()
        val expectedMode = when (customMode) {
            3u -> "Auto"
            0u -> "Stabilize"
            5u -> "Loiter"
            6u -> "RTL"
            9u -> "Land"
            17u -> "Brake"
            else -> "Unknown"
        }
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (state.value.mode?.contains(expectedMode, ignoreCase = true) == true) {
                Log.i("MavlinkRepo", "Mode changed to ${state.value.mode}")
                return true
            }
            delay(200)
        }
        Log.e("MavlinkRepo", "Mode change to ${customMode} not confirmed in Heartbeat")
        return false
    }

    /**
     * Uploads a mission using the MAVLink mission protocol handshake.
     * Returns true if ACK received, false otherwise.
     */
    @Suppress("DEPRECATION")
    suspend fun uploadMissionWithAck(missionItems: List<MissionItemInt>, timeoutMs: Long = 45000): Boolean {
        // Mark upload as in progress to prevent global listener from showing notifications
        isMissionUploadInProgress = true

        try {
            if (!state.value.fcuDetected) {
                Log.e("MissionUpload", "❌ FCU not detected")
                throw IllegalStateException("FCU not detected")
            }
            if (missionItems.isEmpty()) {
                Log.w("MissionUpload", "⚠️ No items to upload")
                return false
            }

            // Validate sequence numbering
            val sequences = missionItems.map { it.seq.toInt() }.sorted()
            if (sequences != (0 until missionItems.size).toList()) {
                Log.e("MissionUpload", "❌ Invalid sequence - Expected: 0-${missionItems.size-1}, Got: $sequences")
                throw IllegalStateException("Invalid mission sequence")
            }

            // Quick validation of critical mission parameters
            missionItems.forEachIndexed { idx, item ->
                if (item.command.value in listOf(16u, 22u)) { // NAV_WAYPOINT or NAV_TAKEOFF
                    val lat = item.x / 1e7
                    val lon = item.y / 1e7
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        Log.e("MissionUpload", "❌ Invalid coords at seq=$idx: lat=$lat, lon=$lon")
                        throw IllegalArgumentException("Invalid coordinates at waypoint $idx")
                    }
                    if (item.z < 0f || item.z > 10000f) {
                        Log.e("MissionUpload", "❌ Invalid altitude at seq=$idx: ${item.z}m")
                        throw IllegalArgumentException("Invalid altitude at waypoint $idx")
                    }
                }
            }

            Log.i("MissionUpload", "═══════════════════════════════════════")
            Log.i("MissionUpload", "Starting upload: ${missionItems.size} items")
            Log.i("MissionUpload", "FCU: sys=$fcuSystemId comp=$fcuComponentId")
            Log.i("MissionUpload", "GCS: sys=$gcsSystemId comp=$gcsComponentId")
            Log.i("MissionUpload", "═══════════════════════════════════════")

            // Phase 1: Clear existing mission
            Log.i("MissionUpload", "Phase 1/2: Clearing existing mission...")

            var clearSuccess = false
            for (attempt in 1..2) {
                Log.d("MissionUpload", "MISSION_CLEAR_ALL attempt $attempt/2")

                // Use CompletableDeferred to avoid race condition
                val clearAckDeferred = CompletableDeferred<Boolean>()

                val clearCollectorJob = AppScope.launch {
                    mavFrame
                        .filter { it.systemId == fcuSystemId && it.componentId == fcuComponentId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        .collect { ack ->
                            Log.d("MissionUpload", "Clear phase ACK received: ${ack.type.entry?.name ?: ack.type.value}")
                            if (ack.type.value == MavMissionResult.MAV_MISSION_ACCEPTED.value) {
                                if (!clearAckDeferred.isCompleted) {
                                    clearAckDeferred.complete(true)
                                }
                            }
                        }
                }

                // Small delay to ensure collector is running
                delay(50)

                val clearAll = MissionClearAll(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearAll)

                val ackReceived = withTimeoutOrNull(3000L) {
                    clearAckDeferred.await()
                } ?: false

                clearCollectorJob.cancel()

                if (ackReceived) {
                    clearSuccess = true
                    Log.i("MissionUpload", "✅ Mission cleared on attempt $attempt")
                    break
                } else if (attempt < 2) {
                    Log.w("MissionUpload", "⚠️ Clear timeout, retrying...")
                    delay(500L)
                }
            }

            if (!clearSuccess) {
                Log.e("MissionUpload", "❌ Failed to clear mission after 2 attempts")
                return false
            }

            delay(500L)
            Log.d("MissionUpload", "Clear complete, proceeding to upload...")

            // Phase 2: Upload mission items
            Log.i("MissionUpload", "Phase 2/2: Uploading ${missionItems.size} items...")

            val missionCount = MissionCount(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                count = missionItems.size.toUShort(),
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            )

            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
            Log.i("MissionUpload", "Sent MISSION_COUNT=${missionItems.size}, awaiting MISSION_REQUEST...")

            val finalAckDeferred = CompletableDeferred<Pair<Boolean, String>>()
            val sentSeqs = mutableSetOf<Int>()
            var firstRequestReceived = false
            var lastRequestTime = System.currentTimeMillis()

            // Simplified resend logic - only if no response
            val resendJob = AppScope.launch {
                delay(3000L)
                if (!firstRequestReceived && !finalAckDeferred.isCompleted) {
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
                    Log.w("MissionUpload", "⚠️ Resent MISSION_COUNT (no response after 3s)")
                }
            }

            // Unified watchdog - simpler timeout logic
            val watchdogJob = AppScope.launch {
                while (isActive && !finalAckDeferred.isCompleted) {
                    delay(2000)
                    if (firstRequestReceived) {
                        val timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime
                        if (timeSinceLastRequest > 10000L) {
                            Log.e("MissionUpload", "❌ Upload stalled - no FCU response for 10s (${sentSeqs.size}/${missionItems.size} sent)")
                            finalAckDeferred.complete(false to "Upload stalled - no FCU response")
                            break
                        }
                    }
                }
            }

            // Main message collector
            val collectorJob = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    if (finalAckDeferred.isCompleted ||
                        frame.systemId != fcuSystemId ||
                        frame.componentId != fcuComponentId) {
                        return@collect
                    }

                    when (val msg = frame.message) {
                        is MissionRequestInt, is MissionRequest -> {
                            if (!firstRequestReceived) {
                                Log.i("MissionUpload", "✅ First MISSION_REQUEST received - upload starting")
                            }
                            firstRequestReceived = true
                            lastRequestTime = System.currentTimeMillis()

                            val seq = if (msg is MissionRequestInt) msg.seq.toInt() else (msg as MissionRequest).seq.toInt()

                            if (seq !in 0 until missionItems.size) {
                                Log.e("MissionUpload", "❌ Invalid seq requested: $seq (valid: 0-${missionItems.size-1})")
                                finalAckDeferred.complete(false to "Invalid sequence $seq")
                                return@collect
                            }

                            val item = missionItems[seq].copy(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                seq = seq.toUShort()
                            )

                            // Adaptive delay: 50ms for BT/serial
                            if (seq > 0) delay(50L)

                            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, item)
                            sentSeqs.add(seq)

                            // Log progress: first, last, and every 10 items for verification
                            if (seq == 0 || seq == missionItems.size - 1 || seq % 10 == 0) {
                                val cmdName = item.command.entry?.name ?: "CMD_${item.command.value}"
                                Log.i("MissionUpload", "→ Sent seq=$seq: $cmdName (${sentSeqs.size}/${missionItems.size})")
                            }
                        }

                        is MissionAck -> {
                            if (!firstRequestReceived) {
                                Log.d("MissionUpload", "Ignoring ACK from clear phase")
                                return@collect
                            }

                            val ackType = msg.type.entry?.name ?: msg.type.value.toString()
                            Log.i("MissionUpload", "MISSION_ACK received: $ackType (${sentSeqs.size}/${missionItems.size} sent)")

                            when (msg.type.value) {
                                MavMissionResult.MAV_MISSION_ACCEPTED.value -> {
                                    // Verify all items sent before accepting
                                    if (sentSeqs.size == missionItems.size) {
                                        Log.i("MissionUpload", "✅ All items confirmed, accepting upload")
                                        finalAckDeferred.complete(true to "")
                                    } else {
                                        Log.w("MissionUpload", "⚠️ Premature ACCEPTED ACK (${sentSeqs.size}/${missionItems.size}), waiting for more items...")
                                    }
                                }
                                MavMissionResult.MAV_MISSION_INVALID_SEQUENCE.value -> {
                                    Log.e("MissionUpload", "❌ INVALID_SEQUENCE error")
                                    Log.e("MissionUpload", "   Sent sequences: ${sentSeqs.sorted()}")
                                    finalAckDeferred.complete(false to "Invalid sequence error")
                                }
                                MavMissionResult.MAV_MISSION_DENIED.value -> {
                                    Log.e("MissionUpload", "❌ DENIED by FCU")
                                    finalAckDeferred.complete(false to "Mission denied")
                                }
                                MavMissionResult.MAV_MISSION_ERROR.value -> {
                                    Log.e("MissionUpload", "❌ ERROR from FCU")
                                    finalAckDeferred.complete(false to "Mission error")
                                }
                                MavMissionResult.MAV_MISSION_UNSUPPORTED_FRAME.value -> {
                                    Log.e("MissionUpload", "❌ UNSUPPORTED_FRAME")
                                    finalAckDeferred.complete(false to "Unsupported frame type")
                                }
                                MavMissionResult.MAV_MISSION_NO_SPACE.value -> {
                                    Log.e("MissionUpload", "❌ NO_SPACE on FCU")
                                    finalAckDeferred.complete(false to "Not enough space")
                                }
                                in listOf(
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM1.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM2.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM3.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM4.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM5_X.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM6_Y.value,
                                    MavMissionResult.MAV_MISSION_INVALID_PARAM7.value
                                ) -> {
                                    Log.e("MissionUpload", "❌ INVALID_PARAM (error: ${msg.type.value})")
                                    finalAckDeferred.complete(false to "Invalid parameter")
                                }
                                MavMissionResult.MAV_MISSION_OPERATION_CANCELLED.value -> {
                                    Log.w("MissionUpload", "⚠️ CANCELLED by FCU")
                                    finalAckDeferred.complete(false to "Upload cancelled")
                                }
                                else -> {
                                    Log.w("MissionUpload", "⚠️ Unknown ACK type: ${msg.type.value}")
                                    finalAckDeferred.complete(false to "Unknown error")
                                }
                            }
                        }
                    }
                }
            }

            // Wait for first request (simplified timeout)
            var waitTime = 0L
            while (!firstRequestReceived && !finalAckDeferred.isCompleted && waitTime < 10000L) {
                delay(100)
                waitTime += 100
            }

            if (!firstRequestReceived && !finalAckDeferred.isCompleted) {
                Log.e("MissionUpload", "❌ No MISSION_REQUEST from FCU after 10s")
                Log.e("MissionUpload", "   This usually means:")
                Log.e("MissionUpload", "   1. Invalid mission structure (check seq 0 is HOME)")
                Log.e("MissionUpload", "   2. FCU rejected the MISSION_COUNT")
                Log.e("MissionUpload", "   3. Communication issue with FCU")
                finalAckDeferred.complete(false to "No response from FCU")
            }

            // Wait for final result
            val (success, errorMsg) = withTimeoutOrNull(timeoutMs) {
                finalAckDeferred.await()
            } ?: (false to "Upload timeout (${timeoutMs}ms)")

            collectorJob.cancel()
            resendJob.cancel()
            watchdogJob.cancel()

            if (success) {
                Log.i("MissionUpload", "═══════════════════════════════════════")
                Log.i("MissionUpload", "✅ SUCCESS - Mission uploaded!")
                Log.i("MissionUpload", "Total items: ${missionItems.size}")
                Log.i("MissionUpload", "All sequences confirmed: ${sentSeqs.sorted()}")
                Log.i("MissionUpload", "═══════════════════════════════════════")
            } else {
                Log.e("MissionUpload", "═══════════════════════════════════════")
                Log.e("MissionUpload", "❌ FAILED - $errorMsg")
                Log.e("MissionUpload", "Items sent: ${sentSeqs.size}/${missionItems.size}")
                Log.e("MissionUpload", "Sequences sent: ${sentSeqs.sorted()}")
                Log.e("MissionUpload", "═══════════════════════════════════════")
            }

            return success
        } catch (e: Exception) {
            Log.e("MissionUpload", "❌ Upload exception: ${e.message}", e)
            return false
        } finally {
            // Always reset flag when upload completes (success or failure)
            isMissionUploadInProgress = false
            Log.d("MissionUpload", "Upload process complete - re-enabling global ACK listener")
        }
    }

    suspend fun requestMissionAndLog(timeoutMs: Long = 5000) {
        if (!state.value.fcuDetected) {
            Log.w("MavlinkRepo", "FCU not detected; cannot request mission")
            return
        }
        try {
            val received = mutableListOf<Pair<Int, String>>()
            val expectedCountDeferred = CompletableDeferred<Int?>()
            val perSeqMap = mutableMapOf<Int, CompletableDeferred<Unit>>()

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            Log.i("MavlinkRepo", "Readback: MISSION_COUNT=${msg.count} from sys=${frame.systemId}")
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        is MissionItemInt -> {
                            val lat = msg.x / 1e7
                            val lon = msg.y / 1e7
                            Log.i("MavlinkRepo", "Readback: MISSION_ITEM_INT seq=${msg.seq} lat=$lat lon=$lon alt=${msg.z}")
                            received.add(msg.seq.toInt() to "INT: lat=$lat lon=$lon alt=${msg.z}")
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionItem -> {
                            Log.i("MavlinkRepo", "Readback: MISSION_ITEM seq=${msg.seq} x=${msg.x} y=${msg.y} z=${msg.z}")
                            received.add(msg.seq.toInt() to "FLT: x=${msg.x} y=${msg.y} z=${msg.z}")
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionAck -> {
                            Log.i("MavlinkRepo", "Readback: MISSION_ACK type=${msg.type}")
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
                Log.i("MavlinkRepo", "Sent MISSION_REQUEST_LIST to FCU")
            } catch (e: Exception) {
                Log.e("MavlinkRepo", "Failed to send MISSION_REQUEST_LIST", e)
            }

            val expectedCount = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() } ?: run {
                Log.w("MavlinkRepo", "Did not receive MISSION_COUNT from FCU within timeout")
                job.cancel()
                return
            }

            Log.i("MavlinkRepo", "Expecting $expectedCount mission items - requesting each item")

            for (seq in 0 until expectedCount) {
                val seqDeferred = CompletableDeferred<Unit>()
                perSeqMap[seq] = seqDeferred
                try {
                    val reqItem = MissionRequestInt(targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort())
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                    Log.d("MavlinkRepo", "Sent MISSION_REQUEST_INT for seq=$seq")
                } catch (e: Exception) {
                    Log.e("MavlinkRepo", "Failed to send MISSION_REQUEST_INT seq=$seq", e)
                }

                val got = withTimeoutOrNull(1500L) {
                    seqDeferred.await()
                    true
                } ?: false

                if (!got) {
                    Log.w("MavlinkRepo", "Did not receive item for seq=$seq within timeout")
                }

                perSeqMap.remove(seq)
            }

            delay(200)
            job.cancel()

            Log.i("MavlinkRepo", "Mission readback complete: expected=$expectedCount items=${received.size}")
            received.sortedBy { it.first }.forEach { (seq, desc) -> Log.i("MavlinkRepo", "Item #$seq -> $desc") }
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Error during mission readback", e)
        }
    }

    /**
     * Retrieve all waypoints from the flight controller.
     * Returns a list of MissionItemInt objects representing the current mission.
     */
    suspend fun getAllWaypoints(timeoutMs: Long = 10000): List<MissionItemInt>? {
        if (!state.value.fcuDetected) {
            Log.w("ResumeMission", "FCU not detected; cannot retrieve mission")
            return null
        }
        
        try {
            val receivedItems = mutableListOf<MissionItemInt>()
            val expectedCountDeferred = CompletableDeferred<Int?>()
            val perSeqMap = mutableMapOf<Int, CompletableDeferred<Unit>>()

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            Log.i("ResumeMission", "Received MISSION_COUNT=${msg.count} from FC")
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        is MissionItemInt -> {
                            Log.d("ResumeMission", "Received MISSION_ITEM_INT seq=${msg.seq} cmd=${msg.command.value}")
                            receivedItems.add(msg)
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionAck -> {
                            Log.d("ResumeMission", "Received MISSION_ACK type=${msg.type}")
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
                Log.i("ResumeMission", "Sent MISSION_REQUEST_LIST to FCU")
            } catch (e: Exception) {
                Log.e("ResumeMission", "Failed to send MISSION_REQUEST_LIST", e)
                job.cancel()
                return null
            }

            val expectedCount = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() } ?: run {
                Log.w("ResumeMission", "Did not receive MISSION_COUNT from FCU within timeout")
                job.cancel()
                return null
            }

            Log.i("ResumeMission", "Expecting $expectedCount mission items - requesting each item")

            for (seq in 0 until expectedCount) {
                val seqDeferred = CompletableDeferred<Unit>()
                perSeqMap[seq] = seqDeferred
                
                try {
                    val reqItem = MissionRequestInt(targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort())
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                    Log.d("ResumeMission", "Sent MISSION_REQUEST_INT for seq=$seq")
                } catch (e: Exception) {
                    Log.e("ResumeMission", "Failed to send MISSION_REQUEST_INT seq=$seq", e)
                }

                val got = withTimeoutOrNull(2000L) { // 2s timeout per item (allows for FC processing + network latency)
                    seqDeferred.await()
                    true
                } ?: false

                if (!got) {
                    Log.w("ResumeMission", "Did not receive item for seq=$seq within timeout")
                }

                perSeqMap.remove(seq)
            }

            // Small delay to ensure all MAVLink messages are processed before canceling collector
            delay(200)
            job.cancel()

            // Sort items by sequence number
            val sortedItems = receivedItems.sortedBy { it.seq.toInt() }
            
            Log.i("ResumeMission", "Mission retrieval complete: expected=$expectedCount received=${sortedItems.size}")
            
            if (sortedItems.size != expectedCount) {
                Log.w("ResumeMission", "⚠️ Received ${sortedItems.size} items but expected $expectedCount")
            }
            
            return sortedItems
            
        } catch (e: Exception) {
            Log.e("ResumeMission", "Error during mission retrieval", e)
            return null
        }
    }

    /**
     * Get the mission count from the flight controller.
     * Returns the number of mission items stored in the FC.
     */
    suspend fun getMissionCount(timeoutMs: Long = 5000): Int? {
        if (!state.value.fcuDetected) {
            Log.w("ResumeMission", "FCU not detected; cannot get mission count")
            return null
        }
        
        try {
            val expectedCountDeferred = CompletableDeferred<Int?>()

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            Log.d("ResumeMission", "Received MISSION_COUNT=${msg.count}")
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
                Log.d("ResumeMission", "Sent MISSION_REQUEST_LIST to get count")
            } catch (e: Exception) {
                Log.e("ResumeMission", "Failed to send MISSION_REQUEST_LIST", e)
                job.cancel()
                return null
            }

            val count = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() }
            job.cancel()
            
            return count
            
        } catch (e: Exception) {
            Log.e("ResumeMission", "Error getting mission count", e)
            return null
        }
    }

    /**
     * Start the mission after uploading.
     * Replicates the Dart/Flutter workflow:
     * 1. Arm the vehicle
     * 2. Send MISSION_START as CommandLong
     * 3. Set mode to AUTO
     */
    suspend fun startMission(): Boolean {
        Log.i("MavlinkRepo", "[Mission Start] Initiating mission start workflow...")
        if (!state.value.fcuDetected) {
            Log.w("MavlinkRepo", "[Mission Start] Cannot start mission - FCU not detected")
            sharedViewModel.addNotification(
                Notification("Cannot start mission - FCU not detected", NotificationType.ERROR)
            )
            return false
        }

        // Step 0: Run pre-arm checks
        try {
            Log.i("MavlinkRepo", "[Mission Start] Running pre-arm checks...")
            sharedViewModel.addNotification(
                Notification("Running pre-arm checks...", NotificationType.INFO)
            )
            val prearmOk = sendPrearmChecks()
            if (!prearmOk) {
                Log.w("MavlinkRepo", "[Mission Start] Pre-arm checks failed")
                sharedViewModel.addNotification(
                    Notification("Pre-arm checks failed. Check STATUSTEXT messages.", NotificationType.ERROR)
                )
                // Continue anyway - the arm() function will handle retries
            } else {
                Log.i("MavlinkRepo", "[Mission Start] Pre-arm checks passed")
                sharedViewModel.addNotification(
                    Notification("Pre-arm checks passed", NotificationType.SUCCESS)
                )
            }
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "[Mission Start] Failed to run pre-arm checks", e)
            // Continue anyway - the arm() function will handle retries
        }

        // Step 1: Arm the vehicle with retry logic
        try {
            Log.i("MavlinkRepo", "[Mission Start] Arming vehicle...")
            val armed = arm(forceArm = false)
            if (!armed) {
                Log.e("MavlinkRepo", "[Mission Start] Failed to arm vehicle")
                sharedViewModel.addNotification(
                    Notification("Failed to arm vehicle. Check pre-arm status.", NotificationType.ERROR)
                )
                return false
            }
            Log.i("MavlinkRepo", "[Mission Start] Vehicle armed successfully")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "[Mission Start] Exception while arming vehicle", e)
            sharedViewModel.addNotification(
                Notification("Exception while arming: ${e.message}", NotificationType.ERROR)
            )
            return false
        }

        // Step 2: Send MISSION_START as CommandLong
        try {
            Log.i("MavlinkRepo", "[Mission Start] Sending MISSION_START command...")
            sendMissionStartCommand()
            Log.i("MavlinkRepo", "[Mission Start] MISSION_START command sent")
            delay(500)
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "[Mission Start] Failed to send MISSION_START command", e)
            return false
        }

        // Step 3: Set mode to AUTO
        try {
            Log.i("MavlinkRepo", "[Mission Start] Setting mode to AUTO...")
            val modeChanged = changeMode(MavMode.AUTO)
            Log.i("MavlinkRepo", "[Mission Start] Set mode to AUTO, result=$modeChanged")
            delay(500)
            if (!modeChanged) {
                Log.e("MavlinkRepo", "[Mission Start] Failed to switch to AUTO mode")
                sharedViewModel.addNotification(
                    Notification("Failed to switch to AUTO mode. Check if mission has NAV_TAKEOFF.", NotificationType.ERROR)
                )
                return false
            }
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "[Mission Start] Failed to set AUTO mode", e)
            return false
        }

        Log.i("MavlinkRepo", "[Mission Start] Mission start workflow complete. Vehicle should be in AUTO mode.")
        sharedViewModel.addNotification(
            Notification("Mission started successfully", NotificationType.SUCCESS)
        )
        return true
    }

    /**
     * Sends MISSION_START as CommandLong (param1=0, param2=0, ...)
     */
    suspend fun sendMissionStartCommand() {
        val cmd = CommandLong(
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId,
            command = MavCmd.MISSION_START.wrap(),
            confirmation = 0u,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            param5 = 0f,
            param6 = 0f,
            param7 = 0f
        )
        connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, cmd)
    }

    suspend fun closeConnection() {
        try {
            // Mark this as an intentional disconnect to prevent auto-reconnect
            intentionalDisconnect = true
            Log.i("MavlinkRepo", "User-initiated disconnect - auto-reconnect disabled")
            // Attempt to close the TCP connection gracefully
            connection.close()
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Error closing TCP connection", e)
        }
    }

    // Haversine formula for distance in meters
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }

    // Format speed for human-readable display
    private fun formatSpeed(speed: Float?): String? {
        if (speed == null) return null
        return when {
//            speed >= 1000f -> String.format("%.3f km/s", speed / 1000f)
            speed >= 1f -> String.format("%.3f m/s", speed)
//            speed >= 0.01f -> String.format("%.1f cm/s", speed * 100f)
//            speed > 0f -> String.format("%.1f mm/s", speed * 1000f)
            else -> "0 m/s"
        }
    }

    // Format time for human-readable display
    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    // Format distance for human-readable display
    private fun formatDistance(meters: Float): String {
        return String.format("%.1f m", meters)
    }

    suspend fun sendCommandLong(command: CommandLong) {
        try {
            connection.trySendUnsignedV2(
                gcsSystemId,
                gcsComponentId,
                command
            )
            Log.d("MavlinkRepo", "Sent COMMAND_LONG (custom): $command")
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to send custom COMMAND_LONG", e)
        }
    }

    /**
     * Set the current mission waypoint
     * @param seq Waypoint sequence number to resume from
     */
    suspend fun setCurrentWaypoint(seq: Int): Boolean {
        return try {
            val cmd = CommandLong(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                command = MavCmd.DO_SET_MISSION_CURRENT.wrap(),
                confirmation = 0u,
                param1 = seq.toFloat(),
                param2 = 0f,
                param3 = 0f,
                param4 = 0f,
                param5 = 0f,
                param6 = 0f,
                param7 = 0f
            )

            Log.i("MavlinkRepo", "Setting current waypoint to: $seq")
            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, cmd)
            delay(500)

            true
        } catch (e: Exception) {
            Log.e("MavlinkRepo", "Failed to set current waypoint", e)
            false
        }
    }

    /**
     * Validate spray system configuration and update state accordingly
     */
    private fun validateSprayConfiguration() {
        val spray = state.value.sprayTelemetry

        // Check if we have received all critical parameters
        val hasMonitorType = spray.batt2MonitorType != null
        val hasCapacity = spray.batt2CapacityMah > 0
        val hasCalibration = spray.batt2AmpPerVolt != null
        val hasPin = spray.batt2CurrPin != null

        val parametersReceived = hasMonitorType && hasCapacity && hasCalibration && hasPin

        // Validate configuration correctness
        val monitorCorrect = spray.batt2MonitorType == 11
        val capacitySet = spray.batt2CapacityMah > 0
        val calibrationSet = (spray.batt2AmpPerVolt ?: 0f) != 0f
        val pinConfigured = (spray.batt2CurrPin ?: -1) > 0

        val configurationValid = monitorCorrect && capacitySet && calibrationSet && pinConfigured

        // Generate error message if configuration is invalid
        val configurationError = if (!configurationValid) {
            buildString {
                if (!monitorCorrect) append("BATT2_MONITOR must be 11. ")
                if (!capacitySet) append("BATT2_CAPACITY not set. ")
                if (!calibrationSet) append("BATT2_AMP_PERVLT not calibrated. ")
                if (!pinConfigured) append("BATT2_CURR_PIN not configured. ")
            }.trim()
        } else null

        // Update state with validation results
        _state.update { state ->
            state.copy(
                sprayTelemetry = state.sprayTelemetry.copy(
                    parametersReceived = parametersReceived,
                    configurationValid = configurationValid,
                    configurationError = configurationError
                )
            )
        }

        // Log final validation status
        if (parametersReceived) {
            if (configurationValid) {
                Log.i("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i("Spray Telemetry", "✅ SPRAY CONFIGURATION VALID")
                Log.i("Spray Telemetry", "   Monitor Type: ${spray.batt2MonitorType} (Fuel Flow)")
                Log.i("Spray Telemetry", "   Capacity: ${spray.batt2CapacityMah} mAh")
                Log.i("Spray Telemetry", "   Calibration: ${spray.batt2AmpPerVolt}")
                Log.i("Spray Telemetry", "   Pin: ${spray.batt2CurrPin}")
                Log.i("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                sharedViewModel.addNotification(
                    Notification(
                        "Spray system configured correctly",
                        NotificationType.SUCCESS
                    )
                )
            } else {
                Log.e("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.e("Spray Telemetry", "❌ SPRAY CONFIGURATION INVALID")
                Log.e("Spray Telemetry", "   Error: $configurationError")
                Log.e("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            }
        }
    }

    /**
     * Request spray telemetry capacity parameters from the FCU.
     * This reads BATT2_CAPACITY and BATT3_CAPACITY to dynamically configure
     * the spray system instead of using hardcoded values.
     */
    private suspend fun requestSprayCapacityParameters() {
        if (!state.value.fcuDetected) {
            Log.w("Spray Telemetry", "Cannot request parameters - FCU not detected")
            return
        }

        Log.i("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i("Spray Telemetry", "📤 Requesting spray system parameters from FCU...")
        Log.i("Spray Telemetry", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        try {
            val parametersToRequest = listOf(
                "BATT2_MONITOR",      // Sensor type (should be 11 for Fuel Flow)
                "BATT2_CAPACITY",     // Tank capacity in mAh
                "BATT2_AMP_PERVLT",   // Flow sensor calibration factor
                "BATT2_CURR_PIN",     // Flow sensor pin configuration
                "BATT3_CAPACITY",     // Tank capacity for level sensor
                "BATT3_VOLT_PIN",     // Level sensor pin configuration
                "BATT3_VOLT_MULT"     // Level sensor voltage multiplier (important for calibration!)
            )

            for ((index, paramId) in parametersToRequest.withIndex()) {
                val request = ParamRequestRead(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    paramId = paramId,
                    paramIndex = -1
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, request)
                Log.d("Spray Telemetry", "📤 Sent PARAM_REQUEST_READ for $paramId")

                // Small delay between requests to avoid overwhelming FCU
                if (index < parametersToRequest.size - 1) {
                    delay(100)
                }
            }

            Log.i("Spray Telemetry", "✅ All parameter requests sent - waiting for PARAM_VALUE responses")
        } catch (e: Exception) {
            Log.e("Spray Telemetry", "❌ Failed to request spray capacity parameters", e)
        }
    }

    /**
     * Filter waypoints for resume mission (mid-flight).
     *
     * For MID-FLIGHT RESUME (drone already flying):
     * - Keep HOME (waypoint 0)
     * - Skip ALL waypoints BEFORE resume point (including TAKEOFF - drone is already in the air)
     * - Keep ALL waypoints from resume point onward
     *
     * Result structure: HOME (seq 0) + Resume Location WP (seq 1) + Remaining waypoints (seq 2+)
     * NO TAKEOFF included since drone is already flying!
     *
     * The resume location waypoint is inserted at the drone's exact GPS position where it was paused.
     * This ensures the drone resumes from its actual paused position, not from the next waypoint.
     *
     * @param allWaypoints Complete mission from flight controller
     * @param resumeWaypointSeq The waypoint sequence number to resume from
     * @param resumeLatitude The latitude where drone was paused (optional - if null, no resume WP inserted)
     * @param resumeLongitude The longitude where drone was paused (optional - if null, no resume WP inserted)
     * @param resumeAltitude The altitude for the resume waypoint (uses target waypoint altitude if not specified)
     * @return Filtered list of waypoints: HOME + Resume Location WP + remaining waypoints
     */
    suspend fun filterWaypointsForResume(
        allWaypoints: List<MissionItemInt>,
        resumeWaypointSeq: Int,
        resumeLatitude: Double? = null,
        resumeLongitude: Double? = null,
        resumeAltitude: Float? = null
    ): List<MissionItemInt> {
        val filtered = mutableListOf<MissionItemInt>()

        Log.i("ResumeMission", "═══ Filtering Mission for Resume (Mid-Flight) ═══")
        Log.i("ResumeMission", "Original mission: ${allWaypoints.size} waypoints")
        Log.i("ResumeMission", "Resume from waypoint: $resumeWaypointSeq")
        Log.i("ResumeMission", "Resume location: lat=$resumeLatitude, lon=$resumeLongitude, alt=$resumeAltitude")
        Log.i("ResumeMission", "Structure: HOME (seq 0) + Resume Location WP (seq 1) + Remaining waypoints")
        Log.i("ResumeMission", "NOTE: NO TAKEOFF - drone is already flying")

        // Log original mission for debugging
        Log.i("ResumeMission", "--- Original Mission Items ---")
        allWaypoints.forEach { wp ->
            val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
            Log.i("ResumeMission", "  seq=${wp.seq}: $cmdName frame=${wp.frame.value} alt=${wp.z}")
        }
        Log.i("ResumeMission", "------------------------------")

        // Find the target waypoint to get its altitude if resumeAltitude is not provided
        val targetWaypoint = allWaypoints.find { it.seq.toInt() == resumeWaypointSeq }
        val effectiveAltitude = resumeAltitude ?: targetWaypoint?.z ?: 50f

        for (waypoint in allWaypoints) {
            val seq = waypoint.seq.toInt()
            val cmdId = waypoint.command.value

            // Always keep HOME (waypoint 0)
            if (seq == 0) {
                val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
                Log.i("ResumeMission", "✅ Keeping HOME (seq=$seq, cmd=$cmdName, frame=${waypoint.frame.value})")
                filtered.add(waypoint)

                // Insert resume location waypoint right after HOME if we have valid coordinates
                if (resumeLatitude != null && resumeLongitude != null) {
                    val resumeWp = MissionItemInt(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        seq = 1u, // Will be resequenced later
                        frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_RELATIVE_ALT_INT),
                        command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                        current = 0u,
                        autocontinue = 1u,
                        param1 = 0f, // Hold time
                        param2 = 0f, // Acceptance radius
                        param3 = 0f, // Pass through radius
                        param4 = 0f, // Yaw angle
                        x = (resumeLatitude * 1E7).toInt(),
                        y = (resumeLongitude * 1E7).toInt(),
                        z = effectiveAltitude
                    )
                    Log.i("ResumeMission", "✅ INSERTING Resume Location WP: lat=$resumeLatitude, lon=$resumeLongitude, alt=$effectiveAltitude")
                    filtered.add(resumeWp)
                }
                continue
            }

            // For waypoints BEFORE resume point - SKIP ALL including TAKEOFF
            // Drone is already flying, we don't need takeoff or any previous waypoints
            if (seq < resumeWaypointSeq) {
                val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
                Log.d("ResumeMission", "⏭️ Skipping pre-resume waypoint (seq=$seq, cmd=$cmdName)")
                continue
            }

            // Keep ALL waypoints from resume point onward
            val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
            Log.d("ResumeMission", "✅ Keeping waypoint (seq=$seq, cmd=$cmdName)")
            filtered.add(waypoint)
        }

        Log.i("ResumeMission", "Filtered: ${allWaypoints.size} → ${filtered.size} waypoints")
        if (resumeLatitude != null && resumeLongitude != null) {
            Log.i("ResumeMission", "Structure: HOME + Resume Location WP + ${filtered.size - 2} remaining waypoints")
        } else {
            Log.i("ResumeMission", "Structure: HOME + ${filtered.size - 1} waypoints starting from target waypoint")
        }
        Log.i("ResumeMission", "═══════════════════════════════════════════════════════")

        return filtered
    }

    /**
     * Re-sequence waypoints to 0, 1, 2, 3...
     * Marks HOME (waypoint 0) as current.
     * Ensures proper target system/component are set for upload.
     *
     * @param waypoints List of waypoints to re-sequence
     * @return Re-sequenced list with sequential numbering
     */
    suspend fun resequenceWaypoints(waypoints: List<MissionItemInt>): List<MissionItemInt> {
        if (waypoints.isEmpty()) {
            Log.w("ResumeMission", "No waypoints to resequence")
            return emptyList()
        }

        Log.i("ResumeMission", "═══ Re-sequencing ${waypoints.size} waypoints ═══")
        
        val resequenced = waypoints.mapIndexed { index, waypoint ->
            // Set current=1 only for HOME (seq 0), all others current=0
            val newCurrent = if (index == 0) 1u else 0u
            
            // Create the resequenced waypoint with proper target system/component
            waypoint.copy(
                seq = index.toUShort(),
                current = newCurrent.toUByte(),
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId
            ).also {
                val cmdName = it.command.entry?.name ?: "CMD_${it.command.value}"
                val lat = it.x / 1e7
                val lon = it.y / 1e7
                Log.i("ResumeMission", "Resequenced: old_seq=${waypoint.seq} → new_seq=$index, cmd=$cmdName, frame=${it.frame.value}, alt=${it.z}m, lat=$lat, lon=$lon, current=$newCurrent")
            }
        }
        
        // Validation check
        val sequences = resequenced.map { it.seq.toInt() }
        val expected = (0 until resequenced.size).toList()
        if (sequences != expected) {
            Log.e("ResumeMission", "❌ Resequencing FAILED! Expected: $expected, Got: $sequences")
        } else {
            Log.i("ResumeMission", "✅ Resequencing successful: ${resequenced.size} waypoints (0..${resequenced.size-1})")
        }
        
        // Log final mission structure
        Log.i("ResumeMission", "═══ Final Resume Mission Structure ═══")
        resequenced.forEachIndexed { idx, wp ->
            val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
            Log.i("ResumeMission", "  [$idx] $cmdName frame=${wp.frame.value} target=${wp.targetSystem}:${wp.targetComponent}")
        }
        Log.i("ResumeMission", "═════════════════════════════════════")

        return resequenced
    }
}

