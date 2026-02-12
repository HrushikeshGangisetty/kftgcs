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
import com.example.aerogcsclone.Telemetry.extractDroneUniqueId

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
    private var pumpTurnedOnTime: Long? = null  // Track when pump was turned ON for initial delay
    private val ZERO_FLOW_THRESHOLD_MS = 3000L // 3 seconds of zero flow = tank empty (increased for reliability)
    private val PUMP_STARTUP_DELAY_MS = 2000L  // 2 second delay after pump turns ON before checking flow

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
                    }
                    is StreamState.Inactive -> {
                        _state.update { it.copy(connected = false, fcuDetected = false) }
                        lastFcuHeartbeatTime.set(0L)
                        // Auto-reconnect disabled - user must manually reconnect via connection tab
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
                            setMessageRate(109u, 1f) // RADIO_STATUS (1Hz for RC battery monitoring)

                            // Request AUTOPILOT_VERSION for drone identification
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
                            } catch (e: Exception) {
                            }

                            // Request spray telemetry capacity parameters
                            delay(500) // Small delay to let message rates stabilize
                            requestSprayCapacityParameters()
                        }
                    } else if (!state.value.connected) {
                        // FCU was detected before but connection was lost, now it's back
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
                        // Emit to the shared flow for ViewModels to consume
                        _commandAck.emit(ack)
                    } catch (t: Throwable) {
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
                        // Emit to the shared flow for ViewModels to consume
                        _commandLong.emit(cmd)
                    } catch (t: Throwable) {
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
                        sharedViewModel.announceDroneArmed()
                    } else if (!currentArmed && previousArmedState) {
                        // Drone just disarmed - announce it
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

                    // Main battery (id=0)
                    if (b.id.toInt() == 0) {
                        val currentA = if (b.currentBattery.toInt() == -1) null else b.currentBattery / 100f
                        _state.update { it.copy(currentA = currentA) }
                    }
                    // Flow sensor (BATT2 - id=1)
                    else if (b.id.toInt() == 1) {

                        // Check for spray enabled but no flow detected
                        val currentSprayEnabled = state.value.sprayTelemetry.sprayEnabled
                        val currentRc7 = state.value.sprayTelemetry.rc7Value

                        if (currentSprayEnabled && b.currentBattery == 0.toShort()) {
                        }

                        // â•â•â• IMPROVED: Input validation and conversion â•â•â•
                        val flowRateLiterPerHour = FlowRateValidator.validateAndConvert(b.currentBattery)

                        // Apply filtering and spike detection for non-zero values
                        val filteredFlowRate = if (flowRateLiterPerHour != null && flowRateLiterPerHour > 0f) {
                            // Check for sensor spikes before adding to filter
                            if (flowRateFilter.detectSpike(flowRateLiterPerHour, threshold = 2.0f)) {

                                // Use current average instead of spike value
                                flowRateFilter.getAverage()
                            } else {
                                // Normal value - add to filter and get smoothed result
                                val smoothed = flowRateFilter.addValue(flowRateLiterPerHour)
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
                            ratePerMin
                        }

                        // Parse consumed volume (current_consumed in mAh = mL)
                        val consumedLiters = if (b.currentConsumed == -1) {
                            null
                        } else if (b.currentConsumed == 0) {
                            // 0 is valid for start of spraying
                            0f
                        } else {
                            val consumed = b.currentConsumed / 1000f  // Convert mAh (mL) to Liters
                            consumed
                        }

                        // Use capacity from parameters (read dynamically from FCU)
                        val flowCapacityLiters = state.value.sprayTelemetry.batt2CapacityMah / 1000f

                        val flowRemainingPercent = if (b.batteryRemaining.toInt() == -1) {
                            null
                        } else {
                            val remaining = b.batteryRemaining.toInt()
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

                        // ╔══╗ FLOW-BASED TANK EMPTY DETECTION ╔══╗
                        // Tank is considered empty when:
                        // 1. Sprayer is ON (rc7 enabled)
                        // 2. Flow rate is 0 for 3+ seconds
                        // 3. BATT2 is properly configured
                        val currentSprayEnabledForEmpty = state.value.sprayTelemetry.sprayEnabled
                        val configValid = state.value.sprayTelemetry.configurationValid
                        val flowIsZero = flowRateLiterPerMin == null || flowRateLiterPerMin == 0f

                        // Tank empty detection is based on spray enabled status (RC7) and flow rate
                        // No need to check pump current - we use flow sensor data directly
                        val sprayerIsOn = currentSprayEnabledForEmpty

                        if (sprayerIsOn && configValid) {
                            // Track when sprayer was turned ON
                            if (pumpTurnedOnTime == null) {
                                pumpTurnedOnTime = System.currentTimeMillis()
                                Log.d("TankEmpty", "⏱️ Sprayer turned ON - waiting ${PUMP_STARTUP_DELAY_MS}ms before checking flow")
                            }

                            val timeSincePumpOn = System.currentTimeMillis() - (pumpTurnedOnTime ?: 0L)

                            // Only check for zero flow after pump startup delay
                            if (timeSincePumpOn >= PUMP_STARTUP_DELAY_MS && flowIsZero) {
                                // Sprayer has been ON long enough and still no flow - start/continue timing
                                if (zeroFlowStartTime == null) {
                                    zeroFlowStartTime = System.currentTimeMillis()
                                    Log.d("TankEmpty", "⏱️ Zero flow timer started - sprayer is ON for ${timeSincePumpOn}ms but no flow detected")
                                } else {
                                    val zeroFlowDuration = System.currentTimeMillis() - zeroFlowStartTime!!

                                    if (zeroFlowDuration >= ZERO_FLOW_THRESHOLD_MS && !tankEmptyNotificationShown) {
                                        Log.w("TankEmpty", "🚨 TANK EMPTY! Sprayer ON for ${timeSincePumpOn}ms with ${zeroFlowDuration}ms of zero flow")
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
                            } else if (!flowIsZero) {
                                // Flow detected - reset zero flow timer
                                if (zeroFlowStartTime != null) {
                                    Log.d("TankEmpty", "⏱️ Zero flow timer reset - flow detected (${String.format("%.2f", flowRateLiterPerMin ?: 0f)} L/min)")
                                    zeroFlowStartTime = null
                                }

                                // Reset tank empty notification if flow is detected again (tank refilled)
                                if (tankEmptyNotificationShown) {
                                    Log.i("TankEmpty", "✓ Flow restored - tank refilled")
                                    tankEmptyNotificationShown = false
                                }
                            }
                        } else {
                            // Sprayer is OFF or config invalid - reset all timers
                            if (pumpTurnedOnTime != null || zeroFlowStartTime != null) {
                                val reason = when {
                                    !sprayerIsOn -> "sprayer disabled"
                                    else -> "config invalid"
                                }
                                Log.d("TankEmpty", "⏱️ All timers reset - $reason")
                                pumpTurnedOnTime = null
                                zeroFlowStartTime = null
                            }
                        }
                    }
                    // Level sensor (BATT3 - id=2)
                    else if (b.id.toInt() == 2) {

                        // â•â•â• DIAGNOSTIC: Log ALL voltage cells for debugging â•â•â•
                        b.voltages.forEachIndexed { index, voltage ->
                        }

                        // Get VOLT_MULT from parameters (if available)
                        val voltMult = state.value.sprayTelemetry.batt3VoltMult ?: 1.0f

                        // Parse raw voltage from level sensor
                        // Note: voltages[] in MAVLink is UShort (0-65535), representing millivolts
                        val rawVoltageUShort = b.voltages.firstOrNull()
                        val rawVoltageMv = rawVoltageUShort?.toInt()

                        // Check for UINT16_MAX (65535) which means "not available"
                        val validRawVoltageMv = if (rawVoltageMv == 65535 || rawVoltageMv == null) {
                            null
                        } else {
                            rawVoltageMv
                        }


                        // Calculate true sensor voltage (before FCU multiplied it)
                        val trueSensorVoltageMv = if (validRawVoltageMv != null && voltMult > 0) {
                            (validRawVoltageMv / voltMult).toInt()
                        } else {
                            validRawVoltageMv
                        }

                        // Apply voltage filter to smooth out fluctuations
                        val tankVoltageMv = if (validRawVoltageMv != null && validRawVoltageMv > 0) {
                            // Check for spike before adding to filter
                            if (tankVoltageFilter.size() >= 3 && tankVoltageFilter.detectSpike(validRawVoltageMv, maxDeviation = 100)) {
                                // Use last stable value instead of spike
                                tankVoltageFilter.getLastStable() ?: validRawVoltageMv
                            } else {
                                // Normal value - add to filter and get smoothed result
                                val filtered = tankVoltageFilter.addValue(validRawVoltageMv)
                                filtered
                            }
                        } else {
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
                                        0  // At or above empty voltage = empty
                                    }
                                    tankVoltageMv <= fullVoltageMv -> {
                                        100  // At or below full voltage = full
                                    }
                                    else -> {
                                        // Linear interpolation for inverted sensor
                                        // level% = (emptyV - currentV) / (emptyV - fullV) * 100
                                        val level = ((emptyVoltageMv - tankVoltageMv).toFloat() /
                                                (emptyVoltageMv - fullVoltageMv) * 100).toInt()
                                            .coerceIn(0, 100)
                                        level
                                    }
                                }
                            } else {
                                // Normal sensor: higher voltage = higher tank level
                                when {
                                    tankVoltageMv <= emptyVoltageMv -> {
                                        0  // At or below empty threshold
                                    }
                                    tankVoltageMv >= fullVoltageMv -> {
                                        100  // At or above full threshold
                                    }
                                    else -> {
                                        // Linear interpolation for normal sensor
                                        val level = ((tankVoltageMv - emptyVoltageMv).toFloat() /
                                                (fullVoltageMv - emptyVoltageMv) * 100).toInt()
                                            .coerceIn(0, 100)
                                        level
                                    }
                                }
                            }
                        } else {
                            null
                        }

                        // Use capacity from parameters (read dynamically from FCU)
                        val tankCapacityLiters = state.value.sprayTelemetry.batt3CapacityMah / 1000f


                        _state.update { state ->
                            state.copy(
                                sprayTelemetry = state.sprayTelemetry.copy(
                                    tankVoltageMv = tankVoltageMv,
                                    tankLevelPercent = tankLevelPercent,
                                    tankCapacityLiters = tankCapacityLiters
                                )
                            )
                        }

                        // NOTE: Tank empty detection is now handled by flow-based detection in BATT2 section
                        // BATT3 level is still tracked for display purposes only
                        // Low tank warning at 15% (still useful as an early warning)
                        if (tankLevelPercent != null) {
                            if (tankLevelPercent <= 15 && tankLevelPercent > 0 && lastTankLevelPercent != null && lastTankLevelPercent!! > 15) {
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
                            "Mode ${hb.customMode}"
                        }
                    }

                    // Log the parsed mode for verification

                    // Only update state if mode or armed status actually changed
                    if (mode != state.value.mode || armed != state.value.armed) {
                        _state.update { it.copy(armed = armed, mode = mode) }
                    } else {
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
                                sharedViewModel.onModeChangedToAuto()
                            }

                            missionTimerJob?.cancel()
                            missionTimerJob = scope.launch {
                                var elapsed = 0L
                                _state.update { it.copy(isMissionActive = true, missionElapsedSec = 0L, missionCompleted = false, lastMissionElapsedSec = null, missionCompletedHandled = false) }

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

                            // === NEW: Detect AUTO â†’ LOITER transition for "Add Resume Here" popup ===
                            // Only show popup if:
                            // 1. This is a user-initiated LOITER, not geofence-triggered
                            // 2. User selected Automatic mode (not Manual mode)
                            if (mode.equals("Loiter", ignoreCase = true) && !sharedViewModel.isGeofenceTriggeringModeChange && sharedViewModel.isPauseResumeEnabled()) {
                                // Get the current waypoint as the resume point
                                val resumeWaypoint = state.value.lastAutoWaypoint.takeIf { it > 0 }
                                    ?: state.value.currentWaypoint
                                    ?: 1


                                // Trigger the "Add Resume Here" popup in SharedViewModel
                                sharedViewModel.onModeChangedToLoiterFromAuto(resumeWaypoint)

                                // Keep the timer state frozen for resume
                                val lastElapsed = state.value.missionElapsedSec
                                if (lastElapsed != null && lastElapsed > 0L) {
                                    _state.update { it.copy(lastMissionElapsedSec = lastElapsed) }
                                }
                            } else if (mode.equals("Loiter", ignoreCase = true) && !sharedViewModel.isPauseResumeEnabled()) {
                                // User is in Manual mode - don't show resume popup
                            } else if (mode.equals("Loiter", ignoreCase = true)) {
                                // Geofence triggered this LOITER - don't show resume popup
                            } else {
                                // Only mark as completed if NOT paused AND not already marked
                                if (!isPaused && !state.value.missionCompleted) {
                                    val lastElapsed = state.value.missionElapsedSec
                                    // Only set missionCompleted if we had a meaningful mission (elapsed time > 0)
                                    if ((lastElapsed ?: 0L) > 0L) {
                                        _state.update { it.copy(isMissionActive = false, missionElapsedSec = null, missionCompleted = true, lastMissionElapsedSec = lastElapsed) }

                                        // âœ… Send mission status ENDED to backend (crash-safe)
                                        try {
                                            val wsManager = WebSocketManager.getInstance()
                                            wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_ENDED)
                                            wsManager.sendMissionEvent(
                                                eventType = "MISSION_ENDED",
                                                eventStatus = "INFO",
                                                description = "Mission completed successfully"
                                            )

                                            // ðŸ”¥ Send mission summary with all statistics
                                            val currentState = state.value
                                            val batteryEnd = currentState.batteryPercent ?: 0
                                            val totalDistance = currentState.totalDistanceMeters ?: 0f
                                            val flyingTimeMinutes = (lastElapsed ?: 0L) / 60.0
                                            val avgSpeed = if (flyingTimeMinutes > 0) (totalDistance / 1000.0) / (flyingTimeMinutes / 60.0) else 0.0 // km/h
                                            val totalSprayUsed = currentState.sprayTelemetry.consumedLiters?.toDouble() ?: 0.0

                                            // Calculate total acres from distance and spray width
                                            val sprayWidthMeters = 5.0 // Default spray width, should come from settings
                                            val totalAreaSqMeters = totalDistance * sprayWidthMeters
                                            val totalAcres = totalAreaSqMeters / 4046.86 // Convert square meters to acres

                                            wsManager.sendMissionSummary(
                                                totalAcres = totalAcres,
                                                totalSprayUsed = totalSprayUsed,
                                                flyingTimeMinutes = flyingTimeMinutes,
                                                averageSpeed = avgSpeed,
                                                batteryStart = wsManager.missionBatteryStart,
                                                batteryEnd = batteryEnd,
                                                alertsCount = wsManager.missionAlertsCount,
                                                status = "COMPLETED"
                                            )
                                        } catch (e: Exception) {
                                        }

                                        // ðŸ”¥ Disconnect WebSocket when mission ends
                                        // WebSocket stays connected until user clicks OK in dialog

                                    } else {
                                        // No meaningful mission - just reset state without triggering completion
                                        _state.update { it.copy(isMissionActive = false, missionElapsedSec = null) }
                                    }
                                } else if (isPaused) {
                                } else {
                                }
                            }

                            // ISSUE FIX #2: Disable spray when mode changes from Auto to any other mode
                            sharedViewModel.disableSprayOnModeChange()

                            // Disable yaw hold when exiting Auto mode
                            sharedViewModel.disableYawHold()
                        } else if (lastArmed == true && !armed) {
                            // Drone disarmed - cancel timer and DON'T show mission complete popup for disarm
                            missionTimerJob?.cancel()
                            missionTimerJob = null
                            _state.update { it.copy(isMissionActive = false, missionElapsedSec = null) }

                            // Also disable spray when drone is disarmed for safety
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

                    // â•â•â• RC BATTERY FAILSAFE â•â•â•
                    // Trigger RTL if RC battery is critically low (0% or below) and drone is armed
                    if (rcBattPct != null && rcBattPct <= 0 && state.value.armed && !rcBatteryFailsafeTriggered) {

                        // Mark failsafe as triggered to prevent multiple RTL commands
                        rcBatteryFailsafeTriggered = true

                        // Launch coroutine to trigger RTL
                        scope.launch {
                            try {
                                val rtlSuccess = changeMode(MavMode.RTL)
                                if (rtlSuccess) {
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "âš ï¸ RC BATTERY CRITICAL (${rcBattPct}%) - RTL ACTIVATED",
                                            type = NotificationType.ERROR
                                        )
                                    )
                                    // Announce via TTS
                                    sharedViewModel.announceRCBatteryFailsafe(rcBattPct)
                                } else {
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "âŒ RC BATTERY FAILSAFE: Failed to activate RTL",
                                            type = NotificationType.ERROR
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                    // Reset failsafe flag when battery recovers and drone is disarmed
                    else if (!state.value.armed && rcBatteryFailsafeTriggered) {
                        rcBatteryFailsafeTriggered = false
                    }

                    _state.update { it.copy(rcBatteryPercent = rcBattPct) }

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

                    // Update current waypoint in state
                    _state.update { it.copy(currentWaypoint = currentSeq) }

                    // Track last AUTO waypoint (Mission Planner protocol)
                    // Only update lastAutoWaypoint when in AUTO mode and waypoint is non-zero
                    if (currentMode?.equals("Auto", ignoreCase = true) == true && currentSeq != 0) {
                        _state.update { it.copy(lastAutoWaypoint = currentSeq) }
                    }

                    // Update SharedViewModel
                    sharedViewModel.updateCurrentWaypoint(currentSeq)

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

                    // Update current waypoint in state (as fallback if MISSION_CURRENT not available)
                    _state.update { it.copy(currentWaypoint = reachedSeq) }

                    // Track last AUTO waypoint (Mission Planner protocol)
                    // Only update lastAutoWaypoint when in AUTO mode and waypoint is non-zero
                    if (currentMode?.equals("Auto", ignoreCase = true) == true && reachedSeq != 0) {
                        _state.update { it.copy(lastAutoWaypoint = reachedSeq) }
                    }

                    // Update SharedViewModel
                    sharedViewModel.updateCurrentWaypoint(reachedSeq)

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

                    // Monitor RC7 for spray system status
                    val rc7Value = rcChannelsData.chan7Raw.toInt()
                    val sprayEnabled = rc7Value > 1500 // PWM > 1500 = spray ON


                    // Check if spray status changed
                    val previousSprayEnabled = state.value.sprayTelemetry.sprayEnabled
                    if (sprayEnabled != previousSprayEnabled) {
                        // Spray status changed - add notification and show popup
                        val notificationMessage = if (sprayEnabled) "Sprayer Enabled" else "Sprayer Disabled"
                        val notificationType = if (sprayEnabled) NotificationType.SUCCESS else NotificationType.INFO

                        sharedViewModel.addNotification(Notification(notificationMessage, notificationType))
                        sharedViewModel.showSprayStatusPopup(notificationMessage)

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

                    // Handle spray telemetry parameters
                    when (paramName) {
                        "BATT2_MONITOR" -> {
                            val monitorType = paramValue.paramValue.toInt()

                            if (monitorType != 11) {

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor not configured! BATT2_MONITOR should be 11, currently $monitorType",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
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

                            if (capacityMah == 0) {
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

                            if (ampPerVolt == 0f) {

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor not calibrated! Set BATT2_AMP_PERVLT parameter",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
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

                            if (currPin == -1 || currPin == 0) {
                                sharedViewModel.addNotification(
                                    Notification(
                                        "Flow sensor pin not configured! Set BATT2_CURR_PIN parameter",
                                        NotificationType.ERROR
                                    )
                                )
                            } else {
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

                            if (capacityMah == 0) {
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

                            if (voltPin == -1 || voltPin == 0) {
                            } else {
                            }
                        }

                        "BATT3_VOLT_MULT" -> {
                            val voltMult = paramValue.paramValue

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

                                sharedViewModel.addNotification(
                                    Notification(
                                        "Level sensor VOLT_MULT=$voltMult (high). Consider setting to 1.0 for level sensors.",
                                        NotificationType.WARNING
                                    )
                                )
                            } else {
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

        // OpenDroneID BASIC_ID for drone identification
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<OpenDroneIdBasicId>()
                .collect { basicIdMessage ->
                    try {
                        Log.i("TelemetryRepo", "📥 OPEN_DRONE_ID_BASIC_ID received from FC")

                        // Extract drone identifier using the new logic
                        val droneIdentifier = extractDroneUniqueId(basicIdMessage)

                        if (droneIdentifier != null) {
                            Log.i("TelemetryRepo", "✅ Extracted Drone ID: ${droneIdentifier.serialNumber} (Type: ${droneIdentifier.idType})")

                            // Update telemetry state with the serial number as droneUid
                            _state.update { state ->
                                state.copy(
                                    droneUid = droneIdentifier.serialNumber,
                                    droneUid2 = droneIdentifier.idOrMac, // Store MAC/ID as secondary
                                    // Keep existing vendor/product/firmware info from AUTOPILOT_VERSION if available
                                    vendorId = state.vendorId,
                                    productId = state.productId,
                                    firmwareVersion = state.firmwareVersion,
                                    boardVersion = state.boardVersion
                                )
                            }

                            // 🔥 CRITICAL FIX: Update WebSocketManager with real drone UID
                            try {
                                val wsManager = WebSocketManager.getInstance()
                                wsManager.droneUid = droneIdentifier.serialNumber
                                Log.i("TelemetryRepo", "✅ Updated WebSocketManager with droneUid: '${droneIdentifier.serialNumber}'")
                            } catch (e: Exception) {
                                Log.e("TelemetryRepo", "❌ Failed to update WebSocketManager droneUid", e)
                            }

                            // Announce drone ID via TTS
                            val shortUid = droneIdentifier.serialNumber.takeLast(8) // Last 8 characters for brevity
                            sharedViewModel.speak("Drone identified. Serial number ending in $shortUid")
                            Log.i("TelemetryRepo", "🎤 Announced drone serial number via TTS")
                        } else {
                            Log.w("TelemetryRepo", "⚠️ No valid drone identifier found in OPEN_DRONE_ID_BASIC_ID message")
                        }

                    } catch (e: Exception) {
                        Log.e("TelemetryRepo", "❌ Error processing OPEN_DRONE_ID_BASIC_ID", e)
                    }
                }
        }

        // Still process AUTOPILOT_VERSION for firmware/hardware info but not for droneUid
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<AutopilotVersion>()
                .collect { autopilotVersion ->
                    try {
                        Log.i("TelemetryRepo", "📥 AUTOPILOT_VERSION received from FC (for firmware info only)")

                        // Format firmware version (4 bytes: major.minor.patch.type)
                        val fwVersion = autopilotVersion.flightSwVersion
                        val major = (fwVersion shr 24) and 0xFFu
                        val minor = (fwVersion shr 16) and 0xFFu
                        val patch = (fwVersion shr 8) and 0xFFu
                        val fwType = fwVersion and 0xFFu
                        val formattedFirmware = "$major.$minor.$patch (type: $fwType)"

                        _state.update { state ->
                            state.copy(
                                // Keep existing droneUid from OpenDroneID
                                droneUid = state.droneUid,
                                droneUid2 = state.droneUid2,
                                // Update firmware/hardware info
                                vendorId = autopilotVersion.vendorId.toInt(),
                                productId = autopilotVersion.productId.toInt(),
                                firmwareVersion = formattedFirmware,
                                boardVersion = autopilotVersion.boardVersion.toInt()
                            )
                        }


                    } catch (e: Exception) {
                        Log.e("TelemetryRepo", "❌ Error processing AUTOPILOT_VERSION", e)
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
    }

    /**
     * Send pre-arm checks command to validate vehicle is ready to arm
     * Returns true if pre-arm checks pass, false otherwise
     */
    suspend fun sendPrearmChecks(): Boolean {
        try {
            sendCommand(
                MavCmd.RUN_PREARM_CHECKS,
                0f  // param1: not used
            )
            
            // Wait a bit for pre-arm status messages to arrive via STATUSTEXT
            // These will be automatically displayed via the existing STATUSTEXT handler
            delay(2000)
            
            // Check if vehicle became armable after pre-arm checks
            val armable = state.value.armable
            return armable
        } catch (e: Exception) {
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
                
                sendCommand(
                    MavCmd.COMPONENT_ARM_DISARM,
                    1f,      // param1: 1 = arm
                    param2   // param2: 0 = normal, FORCE_ARM = force-arm (Mission Planner magic value)
                )
                
                // Wait for arming to complete
                delay(1500)
                
                // Check if vehicle is now armed
                if (state.value.armed) {
                    sharedViewModel.addNotification(
                        Notification("Vehicle armed successfully", NotificationType.SUCCESS)
                    )
                    return true
                } else {
                    if (attempt < maxAttempts) {
                        delay(retryDelays[attempt-1])
                    }
                }
            } catch (e: Exception) {
                if (attempt < maxAttempts) {
                    delay(retryDelays[attempt-1])
                }
            }
        }
        
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
                return true
            }
            delay(200)
        }
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
                throw IllegalStateException("FCU not detected")
            }
            if (missionItems.isEmpty()) {
                return false
            }

            // Validate sequence numbering
            val sequences = missionItems.map { it.seq.toInt() }.sorted()
            if (sequences != (0 until missionItems.size).toList()) {
                throw IllegalStateException("Invalid mission sequence")
            }

            // Quick validation of critical mission parameters
            missionItems.forEachIndexed { idx, item ->
                if (item.command.value in listOf(16u, 22u)) { // NAV_WAYPOINT or NAV_TAKEOFF
                    val lat = item.x / 1e7
                    val lon = item.y / 1e7
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        throw IllegalArgumentException("Invalid coordinates at waypoint $idx")
                    }
                    if (item.z < 0f || item.z > 10000f) {
                        throw IllegalArgumentException("Invalid altitude at waypoint $idx")
                    }
                }
            }


            // Phase 1: Clear existing mission

            var clearSuccess = false
            for (attempt in 1..2) {

                // Use CompletableDeferred to avoid race condition
                val clearAckDeferred = CompletableDeferred<Boolean>()

                val clearCollectorJob = AppScope.launch {
                    mavFrame
                        .filter { it.systemId == fcuSystemId && it.componentId == fcuComponentId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        .collect { ack ->
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
                    break
                } else if (attempt < 2) {
                    delay(500L)
                }
            }

            if (!clearSuccess) {
                return false
            }

            delay(500L)

            // Phase 2: Upload mission items

            val missionCount = MissionCount(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                count = missionItems.size.toUShort(),
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            )

            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)

            val finalAckDeferred = CompletableDeferred<Pair<Boolean, String>>()
            val sentSeqs = mutableSetOf<Int>()
            var firstRequestReceived = false
            var lastRequestTime = System.currentTimeMillis()

            // Simplified resend logic - only if no response
            val resendJob = AppScope.launch {
                delay(3000L)
                if (!firstRequestReceived && !finalAckDeferred.isCompleted) {
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
                }
            }

            // Unified watchdog - simpler timeout logic
            val watchdogJob = AppScope.launch {
                while (isActive && !finalAckDeferred.isCompleted) {
                    delay(2000)
                    if (firstRequestReceived) {
                        val timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime
                        if (timeSinceLastRequest > 10000L) {
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
                            }
                            firstRequestReceived = true
                            lastRequestTime = System.currentTimeMillis()

                            val seq = if (msg is MissionRequestInt) msg.seq.toInt() else (msg as MissionRequest).seq.toInt()

                            if (seq !in 0 until missionItems.size) {
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
                            }
                        }

                        is MissionAck -> {
                            if (!firstRequestReceived) {
                                return@collect
                            }

                            val ackType = msg.type.entry?.name ?: msg.type.value.toString()

                            when (msg.type.value) {
                                MavMissionResult.MAV_MISSION_ACCEPTED.value -> {
                                    // Verify all items sent before accepting
                                    if (sentSeqs.size == missionItems.size) {
                                        finalAckDeferred.complete(true to "")
                                    } else {
                                    }
                                }
                                MavMissionResult.MAV_MISSION_INVALID_SEQUENCE.value -> {
                                    finalAckDeferred.complete(false to "Invalid sequence error")
                                }
                                MavMissionResult.MAV_MISSION_DENIED.value -> {
                                    finalAckDeferred.complete(false to "Mission denied")
                                }
                                MavMissionResult.MAV_MISSION_ERROR.value -> {
                                    finalAckDeferred.complete(false to "Mission error")
                                }
                                MavMissionResult.MAV_MISSION_UNSUPPORTED_FRAME.value -> {
                                    finalAckDeferred.complete(false to "Unsupported frame type")
                                }
                                MavMissionResult.MAV_MISSION_NO_SPACE.value -> {
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
                                    finalAckDeferred.complete(false to "Invalid parameter")
                                }
                                MavMissionResult.MAV_MISSION_OPERATION_CANCELLED.value -> {
                                    finalAckDeferred.complete(false to "Upload cancelled")
                                }
                                else -> {
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
            } else {
            }

            return success
        } catch (e: Exception) {
            return false
        } finally {
            // Always reset flag when upload completes (success or failure)
            isMissionUploadInProgress = false
        }
    }

    suspend fun requestMissionAndLog(timeoutMs: Long = 5000) {
        if (!state.value.fcuDetected) {
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
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        is MissionItemInt -> {
                            val lat = msg.x / 1e7
                            val lon = msg.y / 1e7
                            received.add(msg.seq.toInt() to "INT: lat=$lat lon=$lon alt=${msg.z}")
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionItem -> {
                            received.add(msg.seq.toInt() to "FLT: x=${msg.x} y=${msg.y} z=${msg.z}")
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionAck -> {
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
            } catch (e: Exception) {
            }

            val expectedCount = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() } ?: run {
                job.cancel()
                return
            }


            for (seq in 0 until expectedCount) {
                val seqDeferred = CompletableDeferred<Unit>()
                perSeqMap[seq] = seqDeferred
                try {
                    val reqItem = MissionRequestInt(targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort())
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                } catch (e: Exception) {
                }

                val got = withTimeoutOrNull(1500L) {
                    seqDeferred.await()
                    true
                } ?: false

                if (!got) {
                }

                perSeqMap.remove(seq)
            }

            delay(200)
            job.cancel()

        } catch (e: Exception) {
        }
    }

    /**
     * Retrieve all waypoints from the flight controller.
     * Returns a list of MissionItemInt objects representing the current mission.
     */
    suspend fun getAllWaypoints(timeoutMs: Long = 10000): List<MissionItemInt>? {
        if (!state.value.fcuDetected) {
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
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        is MissionItemInt -> {
                            receivedItems.add(msg)
                            perSeqMap[msg.seq.toInt()]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
                        }
                        is MissionAck -> {
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
            } catch (e: Exception) {
                job.cancel()
                return null
            }

            val expectedCount = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() } ?: run {
                job.cancel()
                return null
            }


            for (seq in 0 until expectedCount) {
                val seqDeferred = CompletableDeferred<Unit>()
                perSeqMap[seq] = seqDeferred
                
                try {
                    val reqItem = MissionRequestInt(targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort())
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                } catch (e: Exception) {
                }

                val got = withTimeoutOrNull(2000L) { // 2s timeout per item (allows for FC processing + network latency)
                    seqDeferred.await()
                    true
                } ?: false

                if (!got) {
                }

                perSeqMap.remove(seq)
            }

            // Small delay to ensure all MAVLink messages are processed before canceling collector
            delay(200)
            job.cancel()

            // Sort items by sequence number
            val sortedItems = receivedItems.sortedBy { it.seq.toInt() }
            
            
            if (sortedItems.size != expectedCount) {
            }
            
            return sortedItems
            
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get the mission count from the flight controller.
     * Returns the number of mission items stored in the FC.
     */
    suspend fun getMissionCount(timeoutMs: Long = 5000): Int? {
        if (!state.value.fcuDetected) {
            return null
        }
        
        try {
            val expectedCountDeferred = CompletableDeferred<Int?>()

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            expectedCountDeferred.complete(msg.count.toInt())
                        }
                        else -> {}
                    }
                }
            }

            try {
                val req = MissionRequestList(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
            } catch (e: Exception) {
                job.cancel()
                return null
            }

            val count = withTimeoutOrNull(timeoutMs) { expectedCountDeferred.await() }
            job.cancel()
            
            return count
            
        } catch (e: Exception) {
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
        if (!state.value.fcuDetected) {
            sharedViewModel.addNotification(
                Notification("Cannot start mission - FCU not detected", NotificationType.ERROR)
            )
            return false
        }

        // Step 0: Run pre-arm checks
        try {
            sharedViewModel.addNotification(
                Notification("Running pre-arm checks...", NotificationType.INFO)
            )
            val prearmOk = sendPrearmChecks()
            if (!prearmOk) {
                sharedViewModel.addNotification(
                    Notification("Pre-arm checks failed. Check STATUSTEXT messages.", NotificationType.ERROR)
                )
                // Continue anyway - the arm() function will handle retries
            } else {
                sharedViewModel.addNotification(
                    Notification("Pre-arm checks passed", NotificationType.SUCCESS)
                )
            }
        } catch (e: Exception) {
            // Continue anyway - the arm() function will handle retries
        }

        // Step 1: Arm the vehicle with retry logic
        try {
            val armed = arm(forceArm = false)
            if (!armed) {
                sharedViewModel.addNotification(
                    Notification("Failed to arm vehicle. Check pre-arm status.", NotificationType.ERROR)
                )
                return false
            }
        } catch (e: Exception) {
            sharedViewModel.addNotification(
                Notification("Exception while arming: ${e.message}", NotificationType.ERROR)
            )
            return false
        }

        // Step 2: Send MISSION_START as CommandLong
        try {
            sendMissionStartCommand()
            delay(500)
        } catch (e: Exception) {
            return false
        }

        // Step 3: Set mode to AUTO
        try {
            val modeChanged = changeMode(MavMode.AUTO)
            delay(500)
            if (!modeChanged) {
                sharedViewModel.addNotification(
                    Notification("Failed to switch to AUTO mode. Check if mission has NAV_TAKEOFF.", NotificationType.ERROR)
                )
                return false
            }
        } catch (e: Exception) {
            return false
        }

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
            // Attempt to close the TCP connection gracefully
            connection.close()
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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

            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, cmd)
            delay(500)

            true
        } catch (e: Exception) {
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

                sharedViewModel.addNotification(
                    Notification(
                        "Spray system configured correctly",
                        NotificationType.SUCCESS
                    )
                )
            } else {
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
            return
        }


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

                // Small delay between requests to avoid overwhelming FCU
                if (index < parametersToRequest.size - 1) {
                    delay(100)
                }
            }

        } catch (e: Exception) {
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


        // Log original mission for debugging
        allWaypoints.forEach { wp ->
            val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
        }

        // Find the target waypoint to get its altitude if resumeAltitude is not provided
        val targetWaypoint = allWaypoints.find { it.seq.toInt() == resumeWaypointSeq }
        val effectiveAltitude = resumeAltitude ?: targetWaypoint?.z ?: 50f

        for (waypoint in allWaypoints) {
            val seq = waypoint.seq.toInt()
            val cmdId = waypoint.command.value

            // Always keep HOME (waypoint 0)
            if (seq == 0) {
                val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
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
                    filtered.add(resumeWp)
                }
                continue
            }

            // For waypoints BEFORE resume point - SKIP ALL including TAKEOFF
            // Drone is already flying, we don't need takeoff or any previous waypoints
            if (seq < resumeWaypointSeq) {
                val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
                continue
            }

            // Keep ALL waypoints from resume point onward
            val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
            filtered.add(waypoint)
        }

        if (resumeLatitude != null && resumeLongitude != null) {
        } else {
        }

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
            return emptyList()
        }

        
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
            }
        }
        
        // Validation check
        val sequences = resequenced.map { it.seq.toInt() }
        val expected = (0 until resequenced.size).toList()
        if (sequences != expected) {
        } else {
        }
        
        // Log final mission structure
        resequenced.forEachIndexed { idx, wp ->
            val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
        }

        return resequenced
    }
}

