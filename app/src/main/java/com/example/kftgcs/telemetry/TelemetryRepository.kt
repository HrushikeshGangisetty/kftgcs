package com.example.kftgcs.telemetry

import com.divpundir.mavlink.adapters.coroutines.tryConnect
import com.divpundir.mavlink.adapters.coroutines.trySendUnsignedV2
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.connection.StreamState
import com.divpundir.mavlink.definitions.common.*
import com.divpundir.mavlink.definitions.minimal.*
import com.divpundir.mavlink.definitions.ardupilotmega.MagCalProgress
import com.divpundir.mavlink.definitions.common.MagCalReport
import com.example.kftgcs.telemetry.AppScope
import com.example.kftgcs.telemetry.TelemetryState
import com.example.kftgcs.telemetry.extractDroneUniqueId

import com.example.kftgcs.utils.AppStrings
import com.example.kftgcs.telemetry.connections.MavConnectionProvider
import com.example.kftgcs.fence.FenceConfiguration
import com.example.kftgcs.fence.FenceStatus
import com.example.kftgcs.fence.FenceZone
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.example.kftgcs.auth.KFTAuth
import com.example.kftgcs.auth.AuthResult
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


// MAVLink flight modes (ArduPilot values)
object MavMode {
    const val STABILIZE: UInt = 0u
    const val LOITER: UInt = 5u
    const val AUTO: UInt = 3u
    const val GUIDED: UInt = 4u // GUIDED mode for copter takeoff
    const val RTL: UInt = 6u // RTL (Return to Launch) mode
    const val LAND: UInt = 9u // Add LAND mode for explicit landing
    const val POSHOLD: UInt = 16u // POSHOLD (Position Hold) - holds position precisely like Hover
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

/**
 * Sprayer tank-empty detection state machine.
 *
 * Replaces the old spaghetti of independent booleans, timestamps and sample
 * counters. Each state is time-boxed via [MavlinkTelemetryRepository] stateEntryTime,
 * which is reset on every transition — that single property is what makes the
 * priming grace period and the empty-debounce window immune to stale timestamps.
 */
enum class SprayerState {
    IDLE,                 // Sprayer off / not commanded — nothing to watch
    PRIMING,              // Pump just turned on; ignore flow while it primes
    ACTIVE_FLOW,          // Spraying with healthy flow; watch for flow dropping out
    DEBOUNCING_EMPTY,     // Flow dropped to ~0; confirming it's empty (not an air bubble)
    TANK_EMPTY_LOCKED     // Confirmed empty; alert fired once, await sprayer-off to reset
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

    // Battery voltage smoothing (EMA filter for SYS_STATUS fallback)
    private var smoothedVoltage: Float? = null
    // Low alpha = more smoothing. 0.1 gives ~9-second EMA time-constant at 1 Hz.
    // Kept low because this path is only a fallback; BATTERY_STATUS cell-sum takes priority.
    private val VOLTAGE_ALPHA = 0.1f

    // Voltage from BATTERY_STATUS (sum of cell voltages — no 65.535V UShort limit).
    // When populated, this overrides the SYS_STATUS voltage which caps at 65.535V.
    private var battStatusVoltage: Float? = null

    // Track if disconnection was intentional (user-initiated)
    private var intentionalDisconnect = false

    // Diagnostic info
    private val _lastFailure = MutableStateFlow<Throwable?>(null)
    val lastFailure: StateFlow<Throwable?> = _lastFailure.asStateFlow()

    // Connection
    val connection = provider.createConnection()
    lateinit var mavFrame: SharedFlow<MavFrame<out MavMessage<*>>>
        private set

    // Track last heartbeat time from FCU (thread-safe using AtomicLong)
    private val lastFcuHeartbeatTime = AtomicLong(0L)
    private val HEARTBEAT_TIMEOUT_MS = 8000L // Increased to 8 seconds for Bluetooth reliability

    // KFT Auth state
    private val _authStatus = MutableStateFlow(AuthResult.FAILED)
    val authStatusFlow: StateFlow<AuthResult> = _authStatus.asStateFlow()
    private val lastAuthAttemptTime = AtomicLong(0L)
    @Volatile private var activeAuthJob: kotlinx.coroutines.Job? = null

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

    // GCS-side battery-voltage failsafe flag, driven by SharedViewModel's voltage failsafe.
    private var voltageFailsafeActive = false

    /** Called by SharedViewModel when the battery-voltage failsafe fires (true) or clears (false). */
    fun setVoltageFailsafeActive(active: Boolean) {
        if (voltageFailsafeActive != active) {
            voltageFailsafeActive = active
            updateFailsafeState()
        }
    }

    /** Pushes the combined GCS failsafe state (voltage OR RC-battery) into telemetry state. */
    private fun updateFailsafeState() {
        val active = voltageFailsafeActive || rcBatteryFailsafeTriggered
        _state.update { it.copy(failsafeActive = active) }
    }

    // Tracks last BATT3 tank-level % to fire the "Tank Low" warning on the 15% crossing.
    private var lastTankLevelPercent: Int? = null

    // ── Sprayer tank-empty state machine ───────────────────────────────────────
    // Replaces the old spaghetti of booleans/timestamps/sample-counters (the
    // removed zeroFlowStartTime / pumpTurnedOnTime / consecutiveZeroFlowSamples /
    // tankEmptyNotificationShown). Fixes three field bugs:
    //   1. "Missed Empty": TANK_EMPTY_LOCKED now releases to IDLE the moment the
    //      sprayer is commanded off (pilot override / mode change), so a refilled
    //      or re-emptied tank can trigger the alert again.
    //   2. "5-Second Delay": empty is confirmed by a time-based debounce
    //      (DEBOUNCE_DURATION_MS), not a consecutive-sample count that was slow at
    //      ArduPilot's 1Hz BATT2 telemetry rate.
    //   3. "Takeoff False Positive": stateEntryTime is reset on every transition,
    //      so the PRIMING window always measures from pump-on and comfortably
    //      covers a ~1.8s pump prime — it can't be short-circuited by a stale time.
    // @Volatile: these are read on the BATT2 collector but also written via
    // resetAutoModeSprayDetection() from the MISSION_CURRENT / MISSION_ITEM_REACHED
    // collectors, so they cross coroutine threads — volatile guarantees visibility.
    @Volatile private var sprayerState = SprayerState.IDLE
    @Volatile private var stateEntryTime = 0L
    private val PRIMING_DURATION_MS = 2000L         // Grace period after pump ON; flow ignored (covers pump prime)
    private val DEBOUNCE_DURATION_MS = 1500L        // Low flow must persist this long to declare empty (air-bubble tolerant)
    // Empty means flow ≈ 0 regardless of spray rate, so a small fixed L/min cut-off is both fast and robust.
    private val LOW_FLOW_THRESHOLD_LPM = 0.2f       // Flow at/below 0.2 L/min while spraying = "no effective flow"

    // Flow-rate display hold: BATT2 occasionally reports -1 (no reading) for a frame, which would flick
    // the on-screen flow to "N/A". Hold the last valid value briefly so the display stays stable.
    private var lastValidFlowLpm: Float? = null
    private var lastValidFlowTime: Long = 0L
    private val FLOW_DISPLAY_HOLD_MS = 2000L

    // Diagnostic: wall-clock of the previous BATT2 (flow sensor) frame, used to log the ACTUAL
    // per-instance arrival rate. ArduPilot round-robins all battery instances through one
    // BATTERY_STATUS slot, so this is the real rate BATT2 gets — the number to watch when
    // chasing flow "lag" and to confirm the SET_MESSAGE_INTERVAL(147) request is honored.
    private var lastBatt2FrameTime: Long = 0L

    // AUTO mode spray tracking
    // In AUTO mode, sprayer is controlled by DO_SET_SERVO, DO_SPRAYER, or ArduPilot Sprayer library
    // We detect spray activity by flow rate > 0 (which means spray command is active)
    // When flow drops to 0 while spray was active, that indicates tank empty
    private var autoModeSprayDetected = false  // TRUE when flow > 0 detected during current AUTO mission
    private var lastPositiveFlowTime: Long? = null  // Timestamp when flow > 0 was last detected

    // Mission-end phase detection
    // Set TRUE when we detect the mission has reached its end phase (DO_SPRAYER(0), LOITER, RTL, LAND)
    // This prevents false "Tank Empty" alerts when the sprayer is intentionally turned off by the mission
    private var missionEndPhaseActive = false

    // ═══ Tank-empty false-positive guard ═══
    // A genuinely full tank ALWAYS produces healthy flow shortly after the pump turns on.
    // A flow sensor that is disconnected / mis-wired / mis-calibrated reports a steady 0,
    // which is indistinguishable from "empty" to the flow comparison. So we require at least
    // one healthy-flow reading per spray pass BEFORE allowing the machine to latch TANK_EMPTY.
    // If low flow persists but healthy flow was NEVER seen, it's a sensor/config fault — we
    // warn the pilot instead of falsely declaring empty (and crucially do NOT change flight mode).
    // Reset to false on every IDLE→PRIMING transition (start of a new spray pass).
    @Volatile private var hasSeenHealthyFlow = false
    // One-shot guards so the field warnings fire once per spray pass, not every BATT2 tick.
    private var sensorFaultWarned = false       // "no flow ever seen" warning (reset each PRIMING)
    private var configInvalidWarned = false     // "monitoring inactive" warning (reset when sprayer off)
    private var lastZeroFlowWarnTime = 0L       // debounce for the raw-0-while-enabled warning
    private val ZERO_FLOW_WARN_INTERVAL_MS = 10000L

    /**
     * Reset all AUTO mode spray detection state.
     * Called when spray is explicitly disabled (e.g., mode change from Auto)
     * to prevent false "Tank Empty" alerts.
     * NOTE: Does NOT reset missionEndPhaseActive — that flag persists until
     * the drone leaves AUTO mode or a new spray pass starts (flow > 0 detected).
     */
    fun resetAutoModeSprayDetection() {
        LogUtils.i("TankEmpty", "🔄 resetAutoModeSprayDetection() called - clearing spray/tank state (was: autoSpray=$autoModeSprayDetected, missionEnd=$missionEndPhaseActive, sprayerState=$sprayerState)")
        autoModeSprayDetected = false
        lastPositiveFlowTime = null
        // Sprayer is being turned off → release the tank-empty state machine to IDLE.
        transitionTo(SprayerState.IDLE)
    }

    /**
     * Sprayer state machine transition helper. Updates [sprayerState] and stamps
     * [stateEntryTime] so each state can time-box itself (the PRIMING grace window
     * and the empty-debounce window both measure from this).
     *
     * Entering [SprayerState.TANK_EMPTY_LOCKED] fires the tank-empty side effects
     * exactly once: it is the only transition into that state, and same-state
     * transitions are a no-op.
     */
    private fun transitionTo(newState: SprayerState) {
        val previousState = sprayerState
        if (newState == previousState) {
            LogUtils.d("TankEmpty", "↩️ transitionTo($newState) ignored — already in $newState")
            return
        }
        val timeInPrevious = System.currentTimeMillis() - stateEntryTime
        LogUtils.i("TankEmpty", "🔀 Sprayer state: $previousState → $newState (spent ${timeInPrevious}ms in $previousState)")
        sprayerState = newState
        stateEntryTime = System.currentTimeMillis()

        // Start of a new spray pass: require fresh proof of healthy flow before this pass
        // can ever latch TANK_EMPTY, and re-arm the one-shot sensor-fault warning.
        if (newState == SprayerState.PRIMING) {
            hasSeenHealthyFlow = false
            sensorFaultWarned = false
        }
        // Sprayer commanded off: re-arm the config/zero-flow warnings for the next pass.
        if (newState == SprayerState.IDLE) {
            configInvalidWarned = false
            lastZeroFlowWarnTime = 0L
        }

        if (newState == SprayerState.TANK_EMPTY_LOCKED) {
            LogUtils.e("TankEmpty", "🚨 TANK EMPTY confirmed — dispatching notification + TTS + handleTankEmpty()")
            sharedViewModel.addNotification(
                Notification(
                    message = "Tank Empty! Sprayer is ON but no flow detected.",
                    type = NotificationType.WARNING
                )
            )
            sharedViewModel.announceTankEmpty()
            sharedViewModel.handleTankEmpty()
            LogUtils.i("TankEmpty", "✅ Tank-empty side effects dispatched (locked until sprayer commanded off)")
        }
    }

    // MAG_CAL_PROGRESS flow for compass calibration progress
    private val _magCalProgress = MutableSharedFlow<MagCalProgress>(replay = 0, extraBufferCapacity = 10)
    val magCalProgress: SharedFlow<MagCalProgress> = _magCalProgress.asSharedFlow()

    // MAG_CAL_REPORT flow for compass calibration final report
    private val _magCalReport = MutableSharedFlow<MagCalReport>(replay = 0, extraBufferCapacity = 10)
    val magCalReport: SharedFlow<MagCalReport> = _magCalReport.asSharedFlow()

    // RC_CHANNELS flow for radio control calibration
    private val _rcChannels = MutableSharedFlow<RcChannels>(replay = 0, extraBufferCapacity = 10)
    val rcChannels: SharedFlow<RcChannels> = _rcChannels.asSharedFlow()

    // SERVO_OUTPUT_RAW flow for the Servo Output screen's live position bars.
    // replay = 1 so a screen opened mid-stream immediately sees the latest values.
    private val _servoOutputRaw = MutableSharedFlow<ServoOutputRaw>(replay = 1, extraBufferCapacity = 10)
    val servoOutputRaw: SharedFlow<ServoOutputRaw> = _servoOutputRaw.asSharedFlow()

    // PARAM_VALUE flow for parameter reading
    // Buffer capacity set high to handle PARAM_REQUEST_LIST bulk responses (hundreds of params)
    private val _paramValue = MutableSharedFlow<ParamValue>(replay = 0, extraBufferCapacity = 1024)
    val paramValue: SharedFlow<ParamValue> = _paramValue.asSharedFlow()

    // ════════════════════════════════════════════════════════════════
    // GEOFENCE STATUS (ArduPilot Native Fence System)
    // ════════════════════════════════════════════════════════════════
    private val _fenceStatus = MutableStateFlow(FenceStatus())
    val fenceStatus: StateFlow<FenceStatus> = _fenceStatus.asStateFlow()

    /**
     * Shared auth launcher with debouncing — triggers KFT auth handshake.
     * Safe to call from any coroutine; won't interfere with BT/TCP connections.
     */
    private fun launchAuthentication(reason: String) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastAuthAttemptTime.get()

        android.util.Log.w("KFTAuth", "launchAuthentication called: reason=$reason")

        // Debounce: don't attempt auth more than once every 2 seconds
        if (now - lastAttempt < 2000L) {
            android.util.Log.w("KFTAuth", "Auth debounced (last attempt ${now - lastAttempt}ms ago)")
            return
        }
        lastAuthAttemptTime.set(now)

        // Cancel any in-flight auth to prevent race conditions
        activeAuthJob?.let {
            android.util.Log.w("KFTAuth", "Cancelling previous auth job")
            it.cancel()
        }

        activeAuthJob = AppScope.launch {
            android.util.Log.w("KFTAuth", "Starting auth coroutine: $reason (fcuSys=$fcuSystemId fcuComp=$fcuComponentId)")
            try {
                val result = KFTAuth.authenticate(
                    connection = connection,
                    mavFrame = mavFrame,
                    gcsSystemId = gcsSystemId,
                    gcsComponentId = gcsComponentId,
                    fcuSystemId = fcuSystemId,
                    fcuComponentId = fcuComponentId
                )
                _authStatus.value = result

                when (result) {
                    AuthResult.AUTHENTICATED -> {
                        android.util.Log.w("KFTAuth", "✓ Authenticated with secure firmware")
                        // Now it's safe to request streams, params, etc.
                        delay(500) // Small delay to let firmware process the auth
                        requestPostAuthSetup()
                    }
                    AuthResult.LEGACY_FIRMWARE -> {
                        android.util.Log.w("KFTAuth", "→ Legacy firmware detected, no auth required")
                        // Streams were already accepted, but re-request to be safe
                        requestPostAuthSetup()
                    }
                    AuthResult.DENIED -> {
                        android.util.Log.e("KFTAuth", "✗ Authentication DENIED — wrong key or unauthorized app")
                    }
                    AuthResult.FAILED -> {
                        android.util.Log.e("KFTAuth", "✗ Authentication failed (connection issue)")
                        delay(2000)
                        launchAuthentication("retry after failure")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("KFTAuth", "Auth coroutine exception: ${e.message}", e)
            }
        }
    }

    /**
     * Sends all setup messages (stream requests, param requests, etc.) AFTER auth completes.
     * This ensures the firmware accepts our messages post-authentication.
     */
    private fun requestPostAuthSetup() {
        AppScope.launch {
            android.util.Log.i("KFTAuth", "Sending post-auth setup messages (streams, params, etc.)")


                // Testing for checking if command changes work post auth
//            // ===== TEMPORARY TEST: Verify commands work after auth =====
//            val setModeCmd = CommandLong(
//                targetSystem = fcuSystemId,
//                targetComponent = fcuComponentId,
//                command = MavCmd.DO_SET_MODE.wrap(),
//                confirmation = 0u,
//                param1 = 1f,  // MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
//                param2 = 3f,  // Auto mode
//                param3 = 0f, param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
//            )
//            try {
//                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, setModeCmd)
//                android.util.Log.w("KFTAuth", "TEST: Sent DO_SET_MODE STABILIZE")
//            } catch (e: Exception) {
//                android.util.Log.e("KFTAuth", "TEST: Failed to send mode: ${e.message}")
//            }
//
//            // Also request full parameter list
//            val paramRequest = ParamRequestList(
//                targetSystem = fcuSystemId,
//                targetComponent = fcuComponentId
//            )
//            try {
//                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, paramRequest)
//                android.util.Log.w("KFTAuth", "TEST: Sent PARAM_REQUEST_LIST")
//            } catch (e: Exception) {
//                android.util.Log.e("KFTAuth", "TEST: Failed to request params: ${e.message}")
//            }
//
//            delay(1000)
            // ===== END TEMPORARY TEST =====

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

            setMessageRate(1u, 4f)     // SYS_STATUS - 4Hz for fast battery voltage monitoring
            setMessageRate(24u, 0.5f)  // GPS_RAW_INT - reduced from 1Hz for Bluetooth
            setMessageRate(33u, 5f)    // GLOBAL_POSITION_INT - increased to 5Hz for smoother position updates
            setMessageRate(74u, 20f)   // VFR_HUD - 20Hz (50ms) for INSTANT speed updates (pilot critical)
            setMessageRate(30u, 20f)   // ATTITUDE - 20Hz (50ms) for smooth yaw updates (critical for nose position)
            // BATTERY_STATUS carries BATT2 (flow sensor) and BATT3 (tank level) alongside the
            // main pack. ArduPilot's send_battery_status() emits only ONE instance per scheduled
            // tick and round-robins across the configured monitors, so the requested rate is
            // SHARED across them: with 3 monitors (main/flow/level), the old 8Hz request gave the
            // BATT2 flow sensor only ~2.7Hz (~375ms between updates) — the source of the spray-flow
            // lag and the apparent transient 0s. Requesting 30Hz yields ~10Hz per instance, which
            // matches AP_BattMonitor's 10Hz internal update (the freshest BATT2 data possible) and
            // also shrinks the 5-sample flow filter's effective smoothing window from ~1.8s to ~0.5s.
            setMessageRate(147u, 30f)  // BATTERY_STATUS - 30Hz total ≈ 10Hz per instance (flow/level/main)
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

            // Start monitoring fence status from FC
            startFenceMonitoring()

            // ═══ LAYER 3: Query MISSION_COUNT from FC on connect ═══
            if (sharedViewModel.lastUploadedCount == 0) {
                try {
                    delay(1000) // Let connection stabilize
                    val fcMissionCount = getMissionCount()
                    if (fcMissionCount != null && fcMissionCount > 0) {
                        sharedViewModel.lastUploadedCount = fcMissionCount
                        LogUtils.i("SprayControl", "📋 FC mission count queried on connect: $fcMissionCount items (lastUploadedCount was 0)")
                    }
                } catch (e: Exception) {
                    LogUtils.w("SprayControl", "⚠️ Failed to query FC mission count on connect: ${e.message}")
                }
            }
        }
    }

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

                        // ===== KFT AUTH - INITIAL AUTHENTICATION =====
                        // All setup messages (stream requests, params, etc.) are sent ONLY after auth completes
                        android.util.Log.w("KFTAuth", "FCU detected! sysId=$fcuSystemId compId=$fcuComponentId — launching auth")
                        launchAuthentication("initial connection")
                    } else if (!state.value.connected) {
                        // FCU was detected before but connection was lost, now it's back
                        _state.update { state -> state.copy(connected = true) }
                    }
                }
        }

        // ─── Re-auth watcher: monitor heartbeat gaps, re-auth on link recovery ───
        scope.launch {
            var previousHeartbeatTime = 0L
            mavFrame
                .filter { frame ->
                    val msg = frame.message
                    msg is Heartbeat
                            && msg.type != MavType.GCS.wrap()
                            && msg.autopilot != MavAutopilot.INVALID.wrap()
                }
                .collect {
                    val now = System.currentTimeMillis()
                    val gap = if (previousHeartbeatTime == 0L) 0L else (now - previousHeartbeatTime)

                    // Firmware auth times out at 5 seconds of no heartbeat.
                    // We use 6 seconds as our trigger threshold with a small safety margin.
                    if (gap > 6000L && state.value.fcuDetected) {
                        android.util.Log.i("KFTAuth", "Heartbeat gap detected: ${gap}ms — triggering re-auth")
                        launchAuthentication("link recovery")
                    }

                    previousHeartbeatTime = now
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
                        // Drone just armed - announce it and reset EMA so a pre-arm or
                        // motor-spinup dip can't anchor the smoothed voltage for the whole flight.
                        sharedViewModel.announceDroneArmed()
                        smoothedVoltage = null
                        LogUtils.d("VoltageDbg", "ARM detected — smoothedVoltage reset to null")
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
                    LogUtils.d("Flow", "BATT MSG: id=${b.id}, currentBattery=${b.currentBattery}, currentConsumed=${b.currentConsumed}")

                    // Main battery (id=0)
                    if (b.id.toInt() == 0) {
                        val currentA = if (b.currentBattery.toInt() == -1) null else b.currentBattery / 100f

                        // ── Cell-voltage summation ──────────────────────────────────────────
                        // BATTERY_STATUS.voltages[]  → cells 1-10, UShort, 0xFFFF = not present
                        // BATTERY_STATUS.voltagesExt → cells 11-14, UShort, 0 = not supported
                        //   (note different sentinel — per MAVLink spec, 0 means unsupported in ext)
                        //
                        // For packs with >10 cells (11S, 12S, 13S, 14S …) ArduPilot puts the
                        // extra cells in voltagesExt. Omitting it caused the 12S pack to read
                        // ~42V (10 cells) instead of ~50V (12 cells) during flight.
                        //
                        // MAVLink also allows the FC to pack the TOTAL voltage into voltages[0]
                        // with the other slots set to 0xFFFF when individual cells aren't
                        // monitored — in that case summing voltages[0] alone gives the correct
                        // pack total, so the logic still works.
                        val validMain = b.voltages.filter { it.toInt() != 0xFFFF && it.toInt() > 0 }
                        // voltagesExt uses 0 (not 0xFFFF) as the "not supported" sentinel
                        val validExt  = b.voltagesExt.filter { it.toInt() != 0 }

                        val allValid = validMain + validExt

                        // Diagnostic log — shows raw cell data in logcat under tag "VoltageDbg"
                        LogUtils.d("VoltageDbg",
                            "BATT_STATUS id=0 | voltages=${b.voltages.toList()} | " +
                            "voltagesExt=${b.voltagesExt.toList()} | " +
                            "validMain=${validMain.size} validExt=${validExt.size} | " +
                            "sum=${allValid.sumOf { it.toLong() } / 1000f}V"
                        )

                        if (allValid.isNotEmpty()) {
                            battStatusVoltage = allValid.sumOf { it.toLong() }.toFloat() / 1000f
                        }

                        _state.update { s ->
                            s.copy(
                                currentA = currentA,
                                // Prefer BATTERY_STATUS cell-sum (no 65.5V overflow); SYS_STATUS
                                // handler fills voltage when battStatusVoltage is unavailable.
                                voltage = battStatusVoltage ?: s.voltage
                            )
                        }
                    }
                    // Flow sensor (BATT2 - id=1)
                    else if (b.id.toInt() == 1) {

                        // ── BATT2 arrival-rate diagnostic ──
                        // Logs the ACTUAL gap between flow-sensor frames so we can tell apart the two
                        // failure modes behind "flow looks wrong": telemetry lag (large/erratic gaps →
                        // SET_MESSAGE_INTERVAL not honored or link starved) vs. a genuine sensor signal
                        // (steady gaps but the raw value itself sits at/near 0).
                        val nowBatt2 = System.currentTimeMillis()
                        val batt2GapMs = if (lastBatt2FrameTime == 0L) 0L else nowBatt2 - lastBatt2FrameTime
                        lastBatt2FrameTime = nowBatt2
                        val batt2Hz = if (batt2GapMs > 0L) 1000f / batt2GapMs else 0f

                        // ── Flow debug logging: raw BATT2 MAVLink values + arrival rate ──
                        // rawCurrentBattery is the unprocessed int16 (centi-Amps) straight from the FC —
                        // compare it to the converted flowRate to see whether the sensor or our
                        // pipeline is producing the zeros.
                        LogUtils.d("Flow", "BATT2 RAW: id=${b.id}, rawCurrentBattery(cA)=${b.currentBattery}, gap=${batt2GapMs}ms (~${"%.1f".format(batt2Hz)}Hz), currentConsumed=${b.currentConsumed}, batteryRemaining=${b.batteryRemaining}, voltages=${b.voltages.toList()}")

                        // Check for spray enabled but no flow detected
                        val currentSprayEnabled = state.value.sprayTelemetry.sprayEnabled
                        val currentRc7 = state.value.sprayTelemetry.rc7Value

                        if (currentSprayEnabled && b.currentBattery == 0.toShort()) {
                            LogUtils.w("Flow", "WARN: Spray enabled (RC7=$currentRc7) but currentBattery=0 (no flow). Check BATT2_MONITOR=${state.value.sprayTelemetry.batt2MonitorType}, BATT2_CURR_PIN=${state.value.sprayTelemetry.batt2CurrPin}, BATT2_AMP_PERVLT=${state.value.sprayTelemetry.batt2AmpPerVolt}")

                            // Surface the wiring/calibration hint to the pilot (debounced). Gated on
                            // !hasSeenHealthyFlow so it NEVER fires during a genuine empty (which always
                            // sees healthy flow first and reports "Tank Empty" instead) — avoiding a
                            // contradictory "check wiring" message on a tank that simply ran dry.
                            val nowZeroWarn = System.currentTimeMillis()
                            if (!hasSeenHealthyFlow && nowZeroWarn - lastZeroFlowWarnTime >= ZERO_FLOW_WARN_INTERVAL_MS) {
                                lastZeroFlowWarnTime = nowZeroWarn
                                sharedViewModel.addNotification(
                                    Notification(
                                        message = "Spray ON but flow sensor reads 0 — verify flow sensor wiring / BATT2 calibration.",
                                        type = NotificationType.WARNING
                                    )
                                )
                            }
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

                        // Hold the last valid flow briefly so a single -1 (no-reading) frame doesn't
                        // flicker the on-screen flow to "N/A". Detection below still uses the real
                        // (possibly null) flowRateLiterPerMin — only the DISPLAY is smoothed here.
                        val nowFlow = System.currentTimeMillis()
                        if (flowRateLiterPerMin != null) {
                            lastValidFlowLpm = flowRateLiterPerMin
                            lastValidFlowTime = nowFlow
                        }
                        val displayFlowLpm = flowRateLiterPerMin
                            ?: lastValidFlowLpm?.takeIf { nowFlow - lastValidFlowTime <= FLOW_DISPLAY_HOLD_MS }

                        LogUtils.d("Flow", "BATT2 CONV: flowRate(L/h)=$flowRateLiterPerHour, filtered=$filteredFlowRate, flowRate(L/min)=$flowRateLiterPerMin, display=$displayFlowLpm")

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

                        // Format values for UI (uses the held value so the field doesn't flicker to N/A)
                        val formattedFlowRate = displayFlowLpm?.let {
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

                        // ═══════════════════════════════════════════════════════════════════
                        // AUTO MISSION SPRAY DETECTION
                        // Spray is considered "active" when:
                        // 1. RC7 is enabled (manual spray via RC), OR
                        // 2. Flow rate > 0 (spray enabled via DO_SET_SERVO, DO_SPRAYER, or Sprayer library)
                        // This ensures green spray lines are drawn even when RC7 is OFF during AUTO missions
                        // ═══════════════════════════════════════════════════════════════════
                        val rc7SprayEnabled = state.value.sprayTelemetry.sprayEnabled
                        val hasFlowDetected = flowRateLiterPerMin != null && flowRateLiterPerMin > 0f
                        val currentMode = state.value.mode
                        val isInAutoMode = currentMode?.equals("Auto", ignoreCase = true) == true

                        // Track AUTO mode spray activity via flow detection
                        // When flow > 0 is detected in AUTO mode, we know DO_SET_SERVO/DO_SPRAYER/Sprayer is active
                        if (hasFlowDetected) {
                            lastPositiveFlowTime = System.currentTimeMillis()
                            if (isInAutoMode && !autoModeSprayDetected) {
                                autoModeSprayDetected = true
                                // Clear mission-end flag when new spray activity is detected
                                // (handles mission restart or new mission without mode change)
                                if (missionEndPhaseActive) {
                                    LogUtils.i("TankEmpty", "🔄 Flow detected in AUTO mode — clearing missionEndPhaseActive (new spray pass)")
                                    missionEndPhaseActive = false
                                }
                            }
                        }

                        // sprayActive is TRUE when:
                        // AUTO mode: flow detected OR spray was previously detected (covers brief flow sensor gaps)
                        // MANUAL mode: RC7 is enabled AND actual flow > 0
                        //   (RC7 on but flow=0 should NOT show green - pump may not be running)
                        val sprayIsActive = if (isInAutoMode) {
                            hasFlowDetected || autoModeSprayDetected
                        } else {
                            // Manual mode: require actual flow to confirm spraying
                            rc7SprayEnabled && hasFlowDetected
                        }

                        LogUtils.d("Flow", "SPRAY: rc7=$rc7SprayEnabled, flowDetected=$hasFlowDetected, autoSpray=$autoModeSprayDetected, active=$sprayIsActive, consumed=$consumedLiters, remaining=$flowRemainingPercent%, mode=$currentMode")

                        _state.update { state ->
                            state.copy(
                                sprayTelemetry = state.sprayTelemetry.copy(
                                    flowRateLiterPerMin = flowRateLiterPerMin,
                                    consumedLiters = consumedLiters,
                                    flowCapacityLiters = flowCapacityLiters,
                                    flowRemainingPercent = flowRemainingPercent,
                                    formattedFlowRate = formattedFlowRate,
                                    formattedConsumed = formattedConsumed,
                                    sprayActive = sprayIsActive  // Set based on RC7 OR flow detection
                                )
                            )
                        }

                        // ╔══════════════════════════════════════════════════════════════════╗
                        // ║          FLOW-BASED TANK EMPTY DETECTION (state machine)         ║
                        // ╠══════════════════════════════════════════════════════════════════╣
                        // ║ Evaluated every BATT2 tick by the SprayerState machine below:    ║
                        // ║   IDLE → PRIMING          when sprayerIsOn && configValid         ║
                        // ║   PRIMING → ACTIVE_FLOW    after PRIMING_DURATION_MS (flow ignored)║
                        // ║   ACTIVE_FLOW → DEBOUNCING_EMPTY   when flow ≤ LOW_FLOW_THRESHOLD ║
                        // ║   DEBOUNCING_EMPTY → ACTIVE_FLOW   when flow recovers (air bubble) ║
                        // ║   DEBOUNCING_EMPTY → TANK_EMPTY_LOCKED after DEBOUNCE_DURATION_MS ║
                        // ║   any state → IDLE        when sprayerIsOn becomes false          ║
                        // ║                                                                  ║
                        // ║ sprayerIsOn already excludes non-spray modes (BRAKE/RTL/LAND)    ║
                        // ║ and mission-end, so it is the single "pump should be flowing"    ║
                        // ║ signal. A null flow reading (dropped frame) is neither low nor   ║
                        // ║ healthy, so it HOLDS the current state — it can't trigger or      ║
                        // ║ reset detection.                                                 ║
                        // ╚══════════════════════════════════════════════════════════════════╝

                        val currentSprayEnabledForEmpty = state.value.sprayTelemetry.sprayEnabled
                        val configValid = state.value.sprayTelemetry.configurationValid

                        // ═══ Tight absolute near-zero threshold for tank-empty detection ═══
                        // null  = no BATT2 reading this frame → UNKNOWN (neither low nor healthy), so a
                        //         dropped frame neither triggers nor resets the low-flow timer/counter.
                        // <=thr = effectively empty (flow ≈ 0 while the pump should be pushing liquid).
                        // > thr = healthy flow → resets the timer/counter.
                        val flowIsLow = flowRateLiterPerMin != null && flowRateLiterPerMin <= LOW_FLOW_THRESHOLD_LPM
                        val flowIsHealthy = flowRateLiterPerMin != null && flowRateLiterPerMin > LOW_FLOW_THRESHOLD_LPM

                        // ═══ Skip tank empty detection in non-spray modes ═══
                        // When failsafes (battery, geofence, RC) trigger a mode change to
                        // BRAKE/RTL/LAND/Smart_RTL, the sprayer physically stops and flow drops to 0.
                        // This is EXPECTED and should NOT trigger "Tank Empty".
                        val isInNonSprayMode = currentMode?.let { mode ->
                            mode.equals("Brake", ignoreCase = true) ||
                            mode.equals("RTL", ignoreCase = true) ||
                            mode.equals("Land", ignoreCase = true) ||
                            mode.equals("Smart_RTL", ignoreCase = true) ||
                            mode.equals("Auto_RTL", ignoreCase = true)
                        } ?: false

                        // ═══ LAYER 2: Real-time mission-end detection via stored mission items ═══
                        // Check if current waypoint corresponds to a mission-end command
                        // (DO_SPRAYER(0), NAV_LOITER_UNLIM, NAV_RTL, NAV_LAND).
                        // This fires INSIDE the BATT2 handler on every telemetry tick, so it
                        // catches mission-end even if MISSION_CURRENT was delayed by telemetry congestion.
                        if (isInAutoMode && autoModeSprayDetected && !missionEndPhaseActive) {
                            val currentWaypoint = state.value.currentWaypoint
                            if (currentWaypoint != null && sharedViewModel.isMissionEndSequence(currentWaypoint)) {
                                LogUtils.i("TankEmpty", "🛑 Mission-end detected in BATT2 handler (waypoint=$currentWaypoint is end-of-mission command) — resetting spray detection")
                                missionEndPhaseActive = true
                                resetAutoModeSprayDetection()
                            }
                        }

                        // In AUTO mode, spraying is done via mission commands (DO_SET_SERVO/DO_SPRAYER),
                        // NOT via RC7. So we also check autoModeSprayDetected to know spray is active.
                        // Also skip if missionEndPhaseActive — the mission has intentionally stopped spraying.
                        val sprayerIsOn = (currentSprayEnabledForEmpty || (isInAutoMode && autoModeSprayDetected)) && !isInNonSprayMode && !missionEndPhaseActive

                        // ═══ TANK EMPTY DEBUG LOGS ═══
                        LogUtils.d("TankEmpty", "━━━ Tank Empty Check ━━━ mode=$currentMode | rawCA=${b.currentBattery} | flowRate=$flowRateLiterPerMin L/min | gap=${batt2GapMs}ms (~${"%.1f".format(batt2Hz)}Hz) | flowIsLow=$flowIsLow | flowIsHealthy=$flowIsHealthy | lowThreshold=$LOW_FLOW_THRESHOLD_LPM L/min | configValid=$configValid")
                        LogUtils.d("TankEmpty", "  sprayEnabled=$currentSprayEnabledForEmpty | autoSprayDetected=$autoModeSprayDetected | isAutoMode=$isInAutoMode | isInNonSprayMode=$isInNonSprayMode | missionEnd=$missionEndPhaseActive | sprayerIsOn=$sprayerIsOn")
                        LogUtils.d("TankEmpty", "  sprayerState=$sprayerState | timeInState=${System.currentTimeMillis() - stateEntryTime}ms | lastPositiveFlow=$lastPositiveFlowTime")

                        // ── Evaluate the sprayer state machine on this telemetry tick ──
                        // flowIsLow/flowIsHealthy are both false when flow is null (dropped
                        // frame), which naturally HOLDS the current state rather than
                        // triggering or resetting it. timeInState drives the time-based
                        // PRIMING grace and empty-debounce windows.
                        val timeInState = System.currentTimeMillis() - stateEntryTime
                        when (sprayerState) {
                            SprayerState.IDLE -> {
                                // Begin priming the moment the sprayer is commanded on with valid telemetry.
                                if (sprayerIsOn && configValid) {
                                    LogUtils.i("TankEmpty", "🟢 Sprayer ON → PRIMING (${PRIMING_DURATION_MS}ms grace, flow ignored)")
                                    transitionTo(SprayerState.PRIMING)
                                } else if (sprayerIsOn && !configValid && !configInvalidWarned) {
                                    // Sprayer is ON but the spray config is invalid, so tank-empty
                                    // monitoring is INACTIVE. Surface this once so the pilot doesn't
                                    // assume they're protected. (Re-armed when the sprayer goes off.)
                                    configInvalidWarned = true
                                    val reason = state.value.sprayTelemetry.configurationError ?: "spray sensor parameters not configured"
                                    LogUtils.w("TankEmpty", "⚠️ Sprayer ON but configValid=false — tank-empty monitoring INACTIVE ($reason)")
                                    sharedViewModel.addNotification(
                                        Notification(
                                            message = "Tank-empty monitoring INACTIVE — $reason",
                                            type = NotificationType.WARNING
                                        )
                                    )
                                }
                            }

                            SprayerState.PRIMING -> {
                                when {
                                    // Pilot override / mode change before priming finished → reset.
                                    !sprayerIsOn -> transitionTo(SprayerState.IDLE)
                                    // Grace window elapsed (covers pump prime) → start watching flow.
                                    timeInState >= PRIMING_DURATION_MS -> {
                                        LogUtils.i("TankEmpty", "⏩ Priming complete (${timeInState}ms) → ACTIVE_FLOW")
                                        transitionTo(SprayerState.ACTIVE_FLOW)
                                    }
                                    // else: still priming — flow telemetry intentionally ignored.
                                }
                            }

                            SprayerState.ACTIVE_FLOW -> {
                                // Record that this spray pass has produced real flow at least once.
                                // This is the proof that the flow sensor is alive and the tank had
                                // liquid — without it we can't distinguish "empty" from "dead sensor".
                                if (flowIsHealthy && !hasSeenHealthyFlow) {
                                    hasSeenHealthyFlow = true
                                    LogUtils.i("TankEmpty", "💧 Healthy flow observed (${flowRateLiterPerMin} L/min) — tank-empty latch now armed")
                                }
                                when {
                                    !sprayerIsOn -> transitionTo(SprayerState.IDLE)
                                    // Flow fell to ~0 while spraying → start the empty debounce.
                                    flowIsLow -> {
                                        LogUtils.w("TankEmpty", "⏱️ Flow low (${flowRateLiterPerMin} L/min) → DEBOUNCING_EMPTY (${DEBOUNCE_DURATION_MS}ms)")
                                        transitionTo(SprayerState.DEBOUNCING_EMPTY)
                                    }
                                    // else: healthy flow — stay ACTIVE_FLOW.
                                }
                            }

                            SprayerState.DEBOUNCING_EMPTY -> {
                                when {
                                    !sprayerIsOn -> transitionTo(SprayerState.IDLE)
                                    // Flow recovered (air bubble / transient) → back to ACTIVE_FLOW.
                                    flowIsHealthy -> {
                                        LogUtils.i("TankEmpty", "✅ Flow recovered (${flowRateLiterPerMin} L/min) → ACTIVE_FLOW (air bubble)")
                                        transitionTo(SprayerState.ACTIVE_FLOW)
                                    }
                                    // Low flow persisted past the debounce window.
                                    timeInState >= DEBOUNCE_DURATION_MS -> {
                                        if (hasSeenHealthyFlow) {
                                            // We saw real flow earlier this pass, then it stopped → tank really is empty.
                                            LogUtils.e("TankEmpty", "🚨 Low flow persisted ${timeInState}ms after healthy flow (mode=$currentMode) → TANK_EMPTY_LOCKED")
                                            transitionTo(SprayerState.TANK_EMPTY_LOCKED)
                                        } else {
                                            // Flow was NEVER healthy this pass → this is a sensor/config fault,
                                            // NOT an empty tank. Warn once and do NOT change flight mode.
                                            // Stay in DEBOUNCING so a later genuine flow can still recover/arm.
                                            if (!sensorFaultWarned) {
                                                sensorFaultWarned = true
                                                LogUtils.e("TankEmpty", "⚠️ No spray flow EVER seen this pass (${timeInState}ms low) — treating as sensor/config fault, NOT tank empty")
                                                sharedViewModel.addNotification(
                                                    Notification(
                                                        message = "No spray flow detected — check flow sensor, wiring, and BATT2 calibration. (Tank-empty action suppressed.)",
                                                        type = NotificationType.WARNING
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    // else (flow null/unknown): HOLD — a dropped frame can't trigger or reset.
                                }
                            }

                            SprayerState.TANK_EMPTY_LOCKED -> {
                                // Alert already fired exactly once on entry (see transitionTo). Release
                                // only when the sprayer is commanded off — pilot override back to Loiter,
                                // mode change, or mission end — so a subsequent empty can re-trigger.
                                if (!sprayerIsOn) {
                                    LogUtils.i("TankEmpty", "⚪ Sprayer OFF → IDLE (tank-empty lock released)")
                                    transitionTo(SprayerState.IDLE)
                                }
                            }
                        }

                        // Reset AUTO mode spray detection when leaving AUTO mode
                        if (!isInAutoMode && (autoModeSprayDetected || missionEndPhaseActive)) {
                            autoModeSprayDetected = false
                            lastPositiveFlowTime = null
                            missionEndPhaseActive = false
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

                            // === NEW: Detect AUTO → LOITER or AUTO → BRAKE transition for "Add Resume Here" popup ===
                            // Only show popup if:
                            // 1. This is a user-initiated LOITER/BRAKE, not geofence-triggered
                            // 2. User selected Automatic mode (not Manual mode)
                            val isLoiterOrBrake = mode.equals("Loiter", ignoreCase = true) || mode.equals("Brake", ignoreCase = true)
                            if (isLoiterOrBrake && !sharedViewModel.isGeofenceTriggeringModeChange && sharedViewModel.isPauseResumeEnabled()) {
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
                            } else if (isLoiterOrBrake && !sharedViewModel.isPauseResumeEnabled()) {
                                // User is in Manual mode - don't show resume popup
                            } else if (isLoiterOrBrake) {
                                // Geofence triggered this LOITER/BRAKE - don't show resume popup
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
                            // Drone disarmed - check if we had an active mission to trigger completion dialog
                            missionTimerJob?.cancel()
                            missionTimerJob = null

                            // Get current mission state before updating
                            val lastElapsed = state.value.missionElapsedSec ?: state.value.lastMissionElapsedSec
                            val wasMissionActive = state.value.isMissionActive
                            val wasInAutoMode = lastMode?.equals("Auto", ignoreCase = true) == true
                            val isPaused = state.value.missionPaused
                            val alreadyCompleted = state.value.missionCompleted

                            // Show mission completion dialog if:
                            // 1. There was meaningful mission time (elapsed > 0)
                            // 2. Mission was not paused
                            // 3. Not already marked as completed
                            if ((lastElapsed ?: 0L) > 0L && !isPaused && !alreadyCompleted) {
                                _state.update { it.copy(
                                    isMissionActive = false,
                                    missionElapsedSec = null,
                                    missionCompleted = true,
                                    lastMissionElapsedSec = lastElapsed
                                )}

                                // Send mission status ENDED to backend
                                try {
                                    val wsManager = WebSocketManager.getInstance()
                                    wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_ENDED)
                                    wsManager.sendMissionEvent(
                                        eventType = "MISSION_ENDED",
                                        eventStatus = "INFO",
                                        description = "Mission completed - drone disarmed"
                                    )

                                    // Send mission summary
                                    val currentState = state.value
                                    val totalDistance = currentState.totalDistanceMeters ?: 0f
                                    val flyingTimeMinutes = (lastElapsed ?: 0L) / 60.0
                                    val avgSpeed = if (flyingTimeMinutes > 0) (totalDistance / 1000.0) / (flyingTimeMinutes / 60.0) else 0.0
                                    val totalSprayUsed = currentState.sprayTelemetry.consumedLiters?.toDouble() ?: 0.0
                                    val sprayWidthMeters = 5.0
                                    val totalAreaSqMeters = totalDistance * sprayWidthMeters
                                    val totalAcres = totalAreaSqMeters / 4046.86

                                    wsManager.sendMissionSummary(
                                        totalAcres = totalAcres,
                                        totalSprayUsed = totalSprayUsed,
                                        flyingTimeMinutes = flyingTimeMinutes,
                                        averageSpeed = avgSpeed,
                                        alertsCount = wsManager.missionAlertsCount,
                                        status = "COMPLETED"
                                    )
                                } catch (e: Exception) {
                                    // Ignore WebSocket errors
                                }
                            } else {
                                // No meaningful mission or already handled - just reset state
                                _state.update { it.copy(isMissionActive = false, missionElapsedSec = null) }
                            }

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
                    // SYS_STATUS.voltage_battery is a UShort → max 65.535V.
                    // It is used as a fallback only when BATTERY_STATUS cell voltages are
                    // unavailable (battStatusVoltage == null). For packs >65.5V, the FC must
                    // report per-cell voltages in BATTERY_STATUS so that battStatusVoltage wins.
                    val vBattRaw = if (s.voltageBattery.toUInt() == 0xFFFFu) null
                                   else s.voltageBattery.toFloat() / 1000f

                    // Apply EMA smoothing to the SYS_STATUS value (VOLTAGE_ALPHA = 0.1 → slow,
                    // gives ~9 s time-constant — keeps the fallback stable without anchoring on
                    // brief motor-spinup dips the way a higher alpha (0.3) would).
                    val vBattSmoothed = if (vBattRaw != null) {
                        val prev = smoothedVoltage
                        if (prev != null) {
                            (VOLTAGE_ALPHA * vBattRaw + (1 - VOLTAGE_ALPHA) * prev).also { smoothedVoltage = it }
                        } else {
                            vBattRaw.also { smoothedVoltage = it }
                        }
                    } else {
                        smoothedVoltage = null
                        null
                    }

                    // ── Sanity guard ─────────────────────────────────────────────────────────
                    // If battStatusVoltage is more than 20% below the SYS_STATUS value it is
                    // likely a partial cell-sum (e.g. FC not sending voltagesExt yet, or an
                    // edge-case mid-message). In that situation fall back to the SYS_STATUS
                    // value and log a warning so we can diagnose it from logcat.
                    // Exception: SYS_STATUS caps at 65.535V — for packs above that limit the
                    // cell-sum is the ONLY correct source, so the guard is skipped when
                    // vBattSmoothed is at/near that cap (>= 65.0V).
                    val resolvedVoltage = when {
                        battStatusVoltage == null -> vBattSmoothed
                        vBattSmoothed == null     -> battStatusVoltage
                        vBattSmoothed >= 65.0f    -> battStatusVoltage  // avoid cap fallback for 65V+ packs
                        battStatusVoltage!! < vBattSmoothed * 0.80f -> {
                            LogUtils.w("VoltageDbg",
                                "⚠️ battStatusVoltage (${battStatusVoltage}V) is >20% below " +
                                "SYS_STATUS (${vBattSmoothed}V) — possible partial cell-sum. " +
                                "Using SYS_STATUS. Check voltagesExt in logcat."
                            )
                            vBattSmoothed
                        }
                        else -> battStatusVoltage
                    }

                    LogUtils.d("VoltageDbg",
                        "SYS_STATUS vRaw=${vBattRaw}V smooth=${vBattSmoothed}V " +
                        "battStat=${battStatusVoltage}V → display=${resolvedVoltage}V"
                    )

                    val pct = if (s.batteryRemaining.toInt() == -1) null else s.batteryRemaining.toInt()
                    val SENSOR_3D_GYRO = 1u
                    val present = (s.onboardControlSensorsPresent.value and SENSOR_3D_GYRO) != 0u
                    val enabled = (s.onboardControlSensorsEnabled.value and SENSOR_3D_GYRO) != 0u
                    val healthy = (s.onboardControlSensorsHealth.value and SENSOR_3D_GYRO) != 0u
                    val armable = present && enabled && healthy
                    _state.update { it.copy(
                        voltage = resolvedVoltage,
                        batteryPercent = pct,
                        armable = armable
                    ) }
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
                        updateFailsafeState()

                        // ═══ FIX: Reset spray detection IMMEDIATELY before failsafe mode change ═══
                        // Prevents false "Tank Empty" when RC battery failsafe stops the sprayer
                        resetAutoModeSprayDetection()

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
                        updateFailsafeState()
                    }

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

                    // Filter out fence-related STATUSTEXT messages when GCS geofence is disabled.
                    // ArduPilot sends messages like "approaching polygon fence", "fence breach",
                    // "polygon fence error" etc. via STATUSTEXT even if GCS didn't intend to
                    // have a fence active. This happens when stale fence data remains on the FC
                    // from a previous session. Suppress these to prevent false warnings and
                    // confusion about why the drone won't arm.
                    val isFenceMessage = message.contains("fence", ignoreCase = true)
                    if (isFenceMessage && !sharedViewModel.geofenceEnabled.value) {
                        // GCS geofence is off but FC is sending fence messages - stale fence data
                        // Log it but don't show to user as a notification
                        Timber.w("Fence STATUSTEXT suppressed (geofence disabled in GCS): %s", message)
                        return@collect
                    }

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

                    // ═══ FIX: Reset spray detection when mission-end items reached ═══
                    // When MISSION_CURRENT advances to the final mission items (DO_SPRAYER(0) + RTL/LAND/LOITER),
                    // the sprayer is already off. Reset autoModeSprayDetected to prevent false
                    // "Tank Empty" alerts while still in AUTO mode during RTL/landing/hovering.
                    // Two independent checks for robustness:
                    //   1. Count-based: currentSeq >= totalMissionItems - 3 (fails if lastUploadedCount is 0)
                    //   2. Command-type: look up current item in stored mission items (fails if items not stored)
                    val totalMissionItems = sharedViewModel.lastUploadedCount
                    val isNearEnd = totalMissionItems > 0 && currentSeq >= totalMissionItems - 3
                    val isEndCommand = sharedViewModel.isMissionEndSequence(currentSeq)
                    if (isNearEnd || isEndCommand) {
                        if (autoModeSprayDetected || !missionEndPhaseActive) {
                            LogUtils.i("SprayControl", "🛑 Resetting auto spray detection - mission near end (currentSeq=$currentSeq, total=$totalMissionItems, isNearEnd=$isNearEnd, isEndCommand=$isEndCommand)")
                            missionEndPhaseActive = true
                            resetAutoModeSprayDetection()
                        }
                    }

                    if (currentSeq != lastMissionSeq) {
                        lastMissionSeq = currentSeq
                        // NOTE: Removed waypoint execution notification from notification panel
                        // The UI already shows current waypoint progress in the telemetry display
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

                    // ═══ FIX: Reset spray detection when mission-end items reached ═══
                    // When the drone reaches the final mission items (DO_SPRAYER(0) + RTL/LAND/LOITER),
                    // the sprayer is already off but autoModeSprayDetected is still true.
                    // This causes a false "Tank Empty" alert because:
                    //   sprayCommandActive = isInAutoMode && autoModeSprayDetected = true
                    //   flow = 0 (sprayer was turned off by mission) → triggers tank empty after 3s
                    // Fix: Reset spray detection via dual check:
                    //   1. Count-based: seq near end of mission (fails if lastUploadedCount is 0)
                    //   2. Command-type: item at seq is a terminal command (fails if items not stored)
                    val totalMissionItems = sharedViewModel.lastUploadedCount
                    val isNearEnd = totalMissionItems > 0 && reachedSeq >= totalMissionItems - 3
                    val isEndCommand = sharedViewModel.isMissionEndSequence(reachedSeq)
                    if (isNearEnd || isEndCommand) {
                        if (autoModeSprayDetected || !missionEndPhaseActive) {
                            LogUtils.i("SprayControl", "🛑 Resetting auto spray detection - last waypoint reached (seq=$reachedSeq, total=$totalMissionItems, isNearEnd=$isNearEnd, isEndCommand=$isEndCommand)")
                            missionEndPhaseActive = true
                            resetAutoModeSprayDetection()
                        }
                    }

                    // NOTE: Removed "Reached waypoint" notification from notification panel
                    // The UI already shows current waypoint progress in the telemetry display
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

                    // NOTE: Removed mission upload ACK notification from notification panel
                    // The upload progress is shown in the dedicated upload dialog
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

        // SERVO_OUTPUT_RAW — live PWM driving the Servo Output screen position bars.
        // High-frequency telemetry stream (NOT the parameter protocol).
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<ServoOutputRaw>()
                .collect { servo ->
                    _servoOutputRaw.emit(servo)
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
                        // Extract drone identifier using the new logic
                        val droneIdentifier = extractDroneUniqueId(basicIdMessage)

                        if (droneIdentifier != null) {

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
                            } catch (e: Exception) {
                                // Ignore
                            }

                            // Announce drone ID via TTS
                            val shortUid = droneIdentifier.serialNumber.takeLast(8) // Last 8 characters for brevity
                            sharedViewModel.speak("Drone identified. Serial number ending in $shortUid")
                        }

                    } catch (e: Exception) {
                        // Ignore
                    }
                }
        }

        // Process AUTOPILOT_VERSION for firmware/hardware info, and as the droneUid source
        // (chip UID) whenever OpenDroneID hasn't already supplied one
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<AutopilotVersion>()
                .collect { autopilotVersion ->
                    try {
                        // Format firmware version (4 bytes: major.minor.patch.type)
                        val fwVersion = autopilotVersion.flightSwVersion
                        val major = (fwVersion shr 24) and 0xFFu
                        val minor = (fwVersion shr 16) and 0xFFu
                        val patch = (fwVersion shr 8) and 0xFFu
                        val fwType = fwVersion and 0xFFu
                        val formattedFirmware = "$major.$minor.$patch (type: $fwType)"

                        _state.update { state ->
                            // 🔥 FALLBACK DRONE UID: If no OpenDroneID available, use the hardware
                            // Silicon Serial Number (uid2, 96-bit chip UID) from AUTOPILOT_VERSION.
                            // Identical hardware models share the same vendor/product/board-version
                            // IDs, so those alone can't tell physical units apart — uid2 can.
                            val fallbackDroneUid = if (state.droneUid.isNullOrBlank()) {
                                autopilotVersion.uid2.toChipUidHex()
                                    ?: autopilotVersion.uid.takeIf { it != 0uL }
                                        ?.toString(16)?.uppercase()?.padStart(16, '0')
                                    ?: run {
                                        // Last resort: no hardware UID reported at all (very old
                                        // firmware / SITL). May collide across identical hardware.
                                        val vendorId = autopilotVersion.vendorId.toInt()
                                        val productId = autopilotVersion.productId.toInt()
                                        val boardVersion = autopilotVersion.boardVersion.toInt()
                                        "FC_${vendorId}_${productId}_${boardVersion}"
                                    }
                            } else {
                                state.droneUid // Keep existing OpenDroneID
                            }

                            state.copy(
                                // Use fallback UID if OpenDroneID not available
                                droneUid = fallbackDroneUid,
                                droneUid2 = state.droneUid2,
                                // Update firmware/hardware info
                                vendorId = autopilotVersion.vendorId.toInt(),
                                productId = autopilotVersion.productId.toInt(),
                                firmwareVersion = formattedFirmware,
                                boardVersion = autopilotVersion.boardVersion.toInt()
                            )
                        }

                        // 🔥 Update WebSocketManager with drone UID (OpenDroneID or fallback)
                        val currentState = _state.value
                        if (!currentState.droneUid.isNullOrBlank()) {
                            try {
                                val wsManager = WebSocketManager.getInstance()
                                wsManager.droneUid = currentState.droneUid!!
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }


                    } catch (e: Exception) {
                        // Ignore
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
            16u -> "PosHold"
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
     * @param onProgress Optional callback for progress updates (currentItem, totalItems)
     */
    @Suppress("DEPRECATION")
    suspend fun uploadMissionWithAck(
        missionItems: List<MissionItemInt>,
        timeoutMs: Long = 45000,
        onProgress: ((currentItem: Int, totalItems: Int) -> Unit)? = null
    ): Boolean {
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

                            // Emit progress update to UI
                            onProgress?.invoke(seq + 1, missionItems.size)

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

    // ─────────────────────────────────────────────────────────────────────────
    //  DataFlash log download (MAVLink LOG_* protocol)
    //
    //  Mirrors the mission-download request/response idiom above: launch a
    //  collector on connection.mavFrame, fire the request with trySendUnsignedV2,
    //  and await completion with CompletableDeferred + withTimeoutOrNull. All
    //  blocking work runs on Dispatchers.IO so the UI never stutters.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch the list of DataFlash logs stored on the flight controller.
     *
     * Sends a single LOG_REQUEST_LIST (start=0, end=0xFFFF = "all entries") and
     * collects the LOG_ENTRY replies. The first reply reports numLogs (total count);
     * we accumulate entries keyed by id and finish once every entry has arrived or
     * [timeoutMs] elapses. An FC with zero logs reports numLogs=0 and we return an
     * empty list.
     *
     * @return the discovered logs sorted by id, or an empty list if none / no FC.
     */
    suspend fun requestLogList(timeoutMs: Long = 8000L): List<LogEntryInfo> =
        withContext(Dispatchers.IO) {
            if (!state.value.fcuDetected) return@withContext emptyList()

            val entries = mutableMapOf<Int, LogEntryInfo>()
            val done = CompletableDeferred<Unit>()
            // numLogs is reported on every LOG_ENTRY; -1 until the first one arrives.
            var expectedCount = -1

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    val msg = frame.message
                    if (msg is LogEntry) {
                        expectedCount = msg.numLogs.toInt()
                        if (expectedCount == 0) {
                            // No logs on the FC — a single empty LOG_ENTRY terminates the list.
                            if (!done.isCompleted) done.complete(Unit)
                            return@collect
                        }
                        entries[msg.id.toInt()] = LogEntryInfo(
                            id = msg.id.toInt(),
                            sizeBytes = msg.size.toLong() and 0xFFFFFFFFL,
                            timeUtcSec = msg.timeUtc.toLong() and 0xFFFFFFFFL
                        )
                        if (entries.size >= expectedCount && !done.isCompleted) {
                            done.complete(Unit)
                        }
                    }
                }
            }

            val req = LogRequestList(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                start = UShort.MIN_VALUE,        // 0 — first log
                end = UShort.MAX_VALUE           // 0xFFFF — "all logs"
            )
            // Some FCs drop the very first request (USB just enumerated); retransmit until the first
            // LOG_ENTRY arrives so a lost initial request doesn't look like "no logs".
            val resendJob = AppScope.launch {
                var attempts = 0
                while (isActive && expectedCount < 0 && attempts < 6) {
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
                    attempts++
                    delay(1500L)
                }
            }
            try {
                withTimeoutOrNull(timeoutMs) { done.await() }
            } catch (e: Exception) {
                LogUtils.e("LogDownload", "requestLogList failed", e)
            } finally {
                resendJob.cancel()
                job.cancel()
            }

            // Distinguishes "FC reports 0 logs" (index empty → use FTP fallback) from "we missed
            // packets" (numLogs > received) when triaging a blank list.
            LogUtils.i(
                "LogDownload",
                "requestLogList: FC numLogs=$expectedCount, received ${entries.size} entries"
            )
            entries.values.sortedBy { it.id }
        }

    /**
     * Download a single DataFlash log over MAVLink and return its raw bytes.
     *
     * Sends LOG_REQUEST_DATA for the whole log (ofs=0, count=0xFFFFFFFF) and
     * reassembles the LOG_DATA chunks (up to 90 bytes each) into a contiguous
     * buffer. Progress is reported via [onProgress] in the range 0f..1f. A chunk
     * carrying fewer than 90 bytes marks the end of the log. A LOG_REQUEST_END is
     * sent on completion so the FC stops transmitting.
     *
     * If no chunk arrives within an idle window the transfer fails (throws) so a
     * stalled link surfaces an error instead of hanging.
     *
     * @param id the log id from [LogEntryInfo].
     * @param sizeBytes the expected size from [LogEntryInfo] (used for progress and
     *   buffer allocation). If 0/unknown the buffer grows dynamically.
     */
    suspend fun downloadLog(
        id: Int,
        sizeBytes: Long,
        idleTimeoutMs: Long = 3000L,
        maxStallRetries: Int = 8,
        onProgress: (Float) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        if (!state.value.fcuDetected) throw IllegalStateException("No flight controller connected")

        // Pre-size when the size is known; otherwise start small and grow.
        var buffer = ByteArray(if (sizeBytes > 0) sizeBytes.toInt() else 64 * 1024)
        // Highest (ofs + len) written so far == bytes of the file we have. Atomic because it is
        // written by the collector coroutine and read by the watchdog loop below.
        val highWaterMark = AtomicInteger(0)
        // Wall-clock time of the most recent LOG_DATA chunk, for the idle watchdog.
        val lastDataAt = AtomicLong(System.currentTimeMillis())
        val finished = CompletableDeferred<Unit>()

        val job = AppScope.launch {
            connection.mavFrame.collect { frame ->
                val msg = frame.message
                if (msg is LogData && msg.id.toInt() == id) {
                    val ofs = (msg.ofs.toLong() and 0xFFFFFFFFL).toInt()
                    val count = msg.count.toInt() and 0xFF
                    val bytes = msg.data.take(count).map { it.toByte() }

                    val end = ofs + count
                    if (end > buffer.size) {
                        buffer = buffer.copyOf(maxOf(end, buffer.size * 2))
                    }
                    for (i in 0 until count) {
                        buffer[ofs + i] = bytes[i]
                    }
                    if (end > highWaterMark.get()) highWaterMark.set(end)
                    lastDataAt.set(System.currentTimeMillis())

                    if (sizeBytes > 0) {
                        onProgress((highWaterMark.get().toFloat() / sizeBytes).coerceIn(0f, 1f))
                    }

                    // A short final chunk, or reaching the expected size, ends the log.
                    val reachedEnd = (sizeBytes > 0 && highWaterMark.get() >= sizeBytes) || count < 90
                    if (reachedEnd && !finished.isCompleted) finished.complete(Unit)
                }
            }
        }

        try {
            // Initial request: stream the whole log from offset 0.
            sendLogRequest(id, ofs = 0)

            // Watchdog: instead of failing on the first gap, re-request from where we stopped so a
            // brief FC pause or a dropped packet resumes cleanly. Only give up after the FC stays
            // silent across [maxStallRetries] consecutive idle windows (~idleTimeoutMs each).
            var lastProgress = 0
            var stalls = 0
            val pollMs = 200L
            while (!finished.isCompleted) {
                delay(pollMs)
                if (finished.isCompleted) break

                val hwm = highWaterMark.get()
                if (hwm > lastProgress) {
                    // Data is flowing again — reset the stall counter.
                    lastProgress = hwm
                    stalls = 0
                    continue
                }

                if (System.currentTimeMillis() - lastDataAt.get() >= idleTimeoutMs) {
                    stalls++
                    if (stalls > maxStallRetries) {
                        throw java.io.IOException(
                            "Log download stalled (no data for ${idleTimeoutMs}ms at $hwm bytes " +
                                "after $maxStallRetries resume attempts)"
                        )
                    }
                    LogUtils.w(
                        "LogDownload",
                        "Stalled at $hwm bytes; resume attempt $stalls/$maxStallRetries"
                    )
                    // Re-request the remainder from the current offset and re-arm the window.
                    sendLogRequest(id, ofs = hwm)
                    lastDataAt.set(System.currentTimeMillis())
                }
            }

            try {
                val end = LogRequestEnd(targetSystem = fcuSystemId, targetComponent = fcuComponentId)
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, end)
            } catch (e: Exception) {
                LogUtils.w("LogDownload", "Failed to send LOG_REQUEST_END: ${e.message}")
            }

            onProgress(1f)
            // Trim to the actual number of bytes received.
            val hwm = highWaterMark.get()
            if (hwm == buffer.size) buffer else buffer.copyOf(hwm)
        } finally {
            job.cancel()
        }
    }

    /** Send a LOG_REQUEST_DATA for log [id] starting at byte [ofs], requesting the remainder. */
    private suspend fun sendLogRequest(id: Int, ofs: Int) {
        val req = LogRequestData(
            targetSystem = fcuSystemId,
            targetComponent = fcuComponentId,
            id = id.toUShort(),
            ofs = ofs.toUInt(),
            count = 0xFFFFFFFFu
        )
        connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
    }

    /** Build a MAVLink FTP client bound to the current connection and FC ids. */
    private fun newFtpClient() = MavlinkFtpClient(
        frames = connection.mavFrame,
        send = { msg -> connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, msg) },
        targetSystem = fcuSystemId,
        targetComponent = fcuComponentId
    )

    /**
     * List DataFlash `.bin` logs on the FC's SD card via MAVLink FTP (default `/APM/LOGS`).
     *
     * This is the fallback for FCs whose MAVLink LOG_REQUEST index comes back empty even though the
     * files are present on the card (our custom FC). Returns newest-first by file name.
     */
    suspend fun listSdLogs(dir: String = "/APM/LOGS"): List<SdLogEntry> =
        withContext(Dispatchers.IO) {
            if (!state.value.fcuDetected) return@withContext emptyList()
            val entries = newFtpClient().listDirectory(dir)
            entries
                .filter { !it.isDir && it.name.endsWith(".bin", ignoreCase = true) }
                .map { SdLogEntry(name = it.name, path = "$dir/${it.name}", sizeBytes = it.sizeBytes) }
                .sortedByDescending { it.name }
        }

    /**
     * Download a log from the FC's SD card by absolute [path] (from [listSdLogs]) over MAVLink FTP.
     * Progress is reported `0f..1f`. The `.bin` bytes returned are identical to a LOG_DATA download.
     */
    suspend fun downloadSdLog(path: String, onProgress: (Float) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            if (!state.value.fcuDetected) throw IllegalStateException("No flight controller connected")
            newFtpClient().downloadFile(path, onProgress)
        }

    /**
     * 🔥 Upload fence items to Flight Controller
     * Similar to uploadMissionWithAck but specifically for geofence points
     */
    suspend fun uploadFenceItems(fenceItems: List<MissionItemInt>, timeoutMs: Long = 30000): Boolean {
        if (!state.value.fcuDetected) {
            return false
        }

        if (fenceItems.isEmpty()) {
            return false
        }

        try {
            // Step 1: Send mission count for fence items
            val missionCount = MissionCount(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                count = fenceItems.size.toUShort(),
                missionType = MavEnumValue.of(MavMissionType.FENCE)
            )

            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)

            // Step 2: Wait for mission requests and send fence items
            val finalAckDeferred = CompletableDeferred<Pair<Boolean, String>>()
            val sentSeqs = mutableSetOf<Int>()

            val job = AppScope.launch {
                connection.mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionRequest, is MissionRequestInt -> {
                            val seq = if (msg is MissionRequestInt) msg.seq.toInt() else (msg as MissionRequest).seq.toInt()

                            if (seq !in 0 until fenceItems.size) {
                                finalAckDeferred.complete(false to "Invalid fence sequence $seq")
                                return@collect
                            }

                            val fenceItem = fenceItems[seq].copy(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                seq = seq.toUShort()
                            )

                            delay(50L) // Small delay for stability
                            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, fenceItem)
                            sentSeqs.add(seq)
                        }

                        is MissionAck -> {
                            if (msg.missionType.value == MavMissionType.FENCE.value) {
                                when (msg.type.value) {
                                    MavMissionResult.MAV_MISSION_ACCEPTED.value -> {
                                        finalAckDeferred.complete(true to "Fence upload successful")
                                    }
                                    MavMissionResult.MAV_MISSION_DENIED.value -> {
                                        finalAckDeferred.complete(false to "Fence upload denied")
                                    }
                                    MavMissionResult.MAV_MISSION_ERROR.value -> {
                                        finalAckDeferred.complete(false to "Fence upload error")
                                    }
                                    else -> {
                                        finalAckDeferred.complete(false to "Unknown fence upload result: ${msg.type.value}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Wait for completion
            val result = withTimeout(timeoutMs) {
                finalAckDeferred.await()
            }

            job.cancel()

            return result.first

        } catch (e: TimeoutCancellationException) {
            return false
        } catch (e: Exception) {
            return false
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
            // Stop fence monitoring to prevent stale state from old connection
            stopFenceMonitoring()
            // Mark this as an intentional disconnect to prevent auto-reconnect
            intentionalDisconnect = true
            // Reset voltage smoothing state
            smoothedVoltage = null
            battStatusVoltage = null
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
        resumeAltitude: Float? = null,
        restoreSpray: Boolean = false
    ): List<MissionItemInt> {
        val filtered = mutableListOf<MissionItemInt>()

        // MAV_CMD_DO_SPRAYER command ID (216)
        val MAV_CMD_DO_SPRAYER = 216u

        // Log original mission for debugging
        allWaypoints.forEach { wp ->
            val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
        }

        // Determine if spray should be restored:
        // Either explicitly requested via restoreSpray parameter, or
        // check the last DO_SPRAYER command before the resume point in the original mission
        var shouldInsertSprayerOn = restoreSpray
        if (!shouldInsertSprayerOn) {
            // Check the last DO_SPRAYER command before the resume point
            val lastSprayerCmd = allWaypoints
                .filter { it.seq.toInt() < resumeWaypointSeq && it.command.value == MAV_CMD_DO_SPRAYER }
                .maxByOrNull { it.seq.toInt() }
            if (lastSprayerCmd != null && lastSprayerCmd.param1 == 1f) {
                // The last sprayer command before resume point was ON, so spray was active
                shouldInsertSprayerOn = true
            }
        }

        if (shouldInsertSprayerOn) {
            // Will insert DO_SPRAYER(1) in resumed mission to restore spray
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

                // Insert DO_SPRAYER(1) command right after resume waypoint (or after HOME if no resume WP)
                // This ensures spray is turned ON as a mission item so FC executes it during AUTO mode
                if (shouldInsertSprayerOn) {
                    val sprayerOnWp = MissionItemInt(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        seq = 2u, // Will be resequenced later
                        frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_RELATIVE_ALT_INT),
                        command = MavEnumValue.fromValue(MAV_CMD_DO_SPRAYER),
                        current = 0u,
                        autocontinue = 1u,
                        param1 = 1f, // 1 = Enable/START spraying
                        param2 = 0f,
                        param3 = 0f,
                        param4 = 0f,
                        x = 0,
                        y = 0,
                        z = 0f
                    )
                    filtered.add(sprayerOnWp)
                    // DO_SPRAYER(1) mission item inserted after resume waypoint
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

    // ════════════════════════════════════════════════════════════════
    // GEOFENCE MANAGEMENT (ArduPilot Native Fence System)
    // ════════════════════════════════════════════════════════════════

    /**
     * Upload geofence to flight controller using ArduPilot's fence system.
     * This uploads the fence definition to FC memory, where it will be
     * enforced autonomously at 400Hz (vs GCS-based enforcement at ~5Hz).
     *
     * @param configuration Complete fence configuration with zones and parameters
     * @return true if upload successful, false otherwise
     */
    suspend fun uploadGeofence(configuration: FenceConfiguration): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Timber.i("Geofence: Starting upload - fcuSystemId=$fcuSystemId, fcuComponentId=$fcuComponentId, connected=${state.value.connected}, fcuDetected=${state.value.fcuDetected}")

                // 🔥 FIX: Verify connection is ready before attempting upload
                if (!state.value.connected || !state.value.fcuDetected) {
                    Timber.e("Geofence: ❌ Cannot upload - connected=${state.value.connected}, fcuDetected=${state.value.fcuDetected}")
                    return@withContext false
                }

                // Step 1: Convert fence zones to MAVLink mission items
                val fenceItems = convertFenceToMissionItems(configuration.zones)

                if (fenceItems.isEmpty()) {
                    Timber.e("Geofence: ❌ Failed at Step 1: convertFenceToMissionItems returned empty list")
                    return@withContext false
                }
                Timber.i("Geofence: Step 1 OK - ${fenceItems.size} fence items created")

                // Step 2: Upload fence using mission protocol with FENCE type
                val uploadSuccess = uploadFenceItemsInternal(fenceItems)

                if (!uploadSuccess) {
                    Timber.e("Geofence: ❌ Failed at Step 2: uploadFenceItemsInternal returned false")
                    return@withContext false
                }
                Timber.i("Geofence: Step 2 OK - fence items uploaded")

                // Step 3: Configure fence parameters
                delay(500)  // Let FC process fence upload

                val paramsSet = configureFenceParameters(configuration)

                if (!paramsSet) {
                    Timber.e("Geofence: ❌ Failed at Step 3: configureFenceParameters returned false")
                    return@withContext false
                }
                Timber.i("Geofence: Step 3 OK - fence parameters configured")

                // Step 4: Enable fence
                delay(500)
                val enabled = enableFence(true)

                if (!enabled) {
                    Timber.e("Geofence: ❌ Failed at Step 4: enableFence(true) returned false")
                    return@withContext false
                }
                Timber.i("Geofence: Step 4 OK - fence enabled")

                // Step 5: Verify fence is actually enabled by reading back parameters
                delay(300)
                val verified = verifyFenceEnabled()
                Timber.i("Geofence: Step 5 - verifyFenceEnabled returned $verified")

                true

            } catch (e: Exception) {
                Timber.e(e, "Geofence: ❌ Upload failed with exception")
                false
            }
        }
    }

    /**
     * Convert fence zones to MAVLink MISSION_ITEM_INT messages
     */
    private fun convertFenceToMissionItems(zones: List<FenceZone>): List<MissionItemInt> {
        val items = mutableListOf<MissionItemInt>()
        var seq = 0

        for (zone in zones) {
            when (zone) {
                is FenceZone.Polygon -> {
                    // Each vertex becomes a fence item
                    val command = if (zone.isInclusion) {
                        MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION
                    } else {
                        MavCmd.NAV_FENCE_POLYGON_VERTEX_EXCLUSION
                    }

                    for ((index, point) in zone.points.withIndex()) {
                        items.add(
                            MissionItemInt(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                seq = seq.toUShort(),
                                frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_INT),
                                command = MavEnumValue.of(command),
                                current = 0u,
                                autocontinue = 0u,
                                param1 = zone.points.size.toFloat(),  // Total vertex count
                                param2 = 0f,
                                param3 = 0f,
                                param4 = 0f,
                                x = (point.latitude * 1E7).toInt(),
                                y = (point.longitude * 1E7).toInt(),
                                z = 0f,
                                missionType = MavEnumValue.of(MavMissionType.FENCE)
                            )
                        )
                        seq++
                    }
                }

                is FenceZone.Circle -> {
                    val command = if (zone.isInclusion) {
                        MavCmd.NAV_FENCE_CIRCLE_INCLUSION
                    } else {
                        MavCmd.NAV_FENCE_CIRCLE_EXCLUSION
                    }

                    items.add(
                        MissionItemInt(
                            targetSystem = fcuSystemId,
                            targetComponent = fcuComponentId,
                            seq = seq.toUShort(),
                            frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_INT),
                            command = MavEnumValue.of(command),
                            current = 0u,
                            autocontinue = 0u,
                            param1 = zone.radiusMeters,  // Radius in meters
                            param2 = 0f,
                            param3 = 0f,
                            param4 = 0f,
                            x = (zone.center.latitude * 1E7).toInt(),
                            y = (zone.center.longitude * 1E7).toInt(),
                            z = 0f,
                            missionType = MavEnumValue.of(MavMissionType.FENCE)
                        )
                    )
                    seq++
                }

                is FenceZone.ReturnPoint -> {
                    items.add(
                        MissionItemInt(
                            targetSystem = fcuSystemId,
                            targetComponent = fcuComponentId,
                            seq = seq.toUShort(),
                            frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_INT),
                            command = MavEnumValue.of(MavCmd.NAV_FENCE_RETURN_POINT),
                            current = 0u,
                            autocontinue = 0u,
                            param1 = 0f,
                            param2 = 0f,
                            param3 = 0f,
                            param4 = 0f,
                            x = (zone.location.latitude * 1E7).toInt(),
                            y = (zone.location.longitude * 1E7).toInt(),
                            z = 0f,
                            missionType = MavEnumValue.of(MavMissionType.FENCE)
                        )
                    )
                    seq++
                }
            }
        }

        return items
    }

    /**
     * Upload fence items using mission protocol with FENCE mission type
     * Internal implementation with coroutine handling
     */
    private suspend fun uploadFenceItemsInternal(items: List<MissionItemInt>): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val job = AppScope.launch {
                try {
                    // Step 1: Clear existing fence (CRITICAL for preventing stale fence data)
                    val clearCmd = MissionClearAll(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        missionType = MavEnumValue.of(MavMissionType.FENCE)
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearCmd)

                    // Wait for clear ACK and verify it succeeded
                    val clearAck = withTimeoutOrNull(2000) {
                        mavFrame
                            .filter { it.systemId == fcuSystemId }
                            .map { it.message }
                            .filterIsInstance<MissionAck>()
                            .first { it.missionType.value == MavMissionType.FENCE.value }
                    }

                    if (clearAck == null) {
                        // No ACK received - retry once
                        Timber.w("Geofence: ⚠️ No ACK for fence clear, retrying...")
                        connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearCmd)
                        val retryAck = withTimeoutOrNull(2000) {
                            mavFrame
                                .filter { it.systemId == fcuSystemId }
                                .map { it.message }
                                .filterIsInstance<MissionAck>()
                                .first { it.missionType.value == MavMissionType.FENCE.value }
                        }
                        if (retryAck == null) {
                            Timber.w("Geofence: ⚠️ Fence clear retry also got no ACK - proceeding with upload anyway")
                        }
                    } else if (clearAck.type.value != MavMissionResult.MAV_MISSION_ACCEPTED.value) {
                        Timber.w("Geofence: ⚠️ Fence clear ACK was not ACCEPTED (type=${clearAck.type.value}) - proceeding anyway")
                    } else {
                        Timber.i("Geofence: ✅ Old fence cleared from FC successfully")
                    }
                    delay(500)

                    // Step 2: Send fence count
                    val countMsg = MissionCount(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        count = items.size.toUShort(),
                        missionType = MavEnumValue.of(MavMissionType.FENCE)
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, countMsg)

                    // Step 3: Wait for MISSION_REQUEST_INT/MISSION_REQUEST from FC and send items
                    val uploadedItems = mutableSetOf<Int>()
                    val timeout = 15000L  // 15 second timeout
                    val startTime = System.currentTimeMillis()
                    var lastRequestTime = startTime

                    while (uploadedItems.size < items.size) {
                        // Check timeout
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > timeout) {
                            continuation.resume(false)
                            return@launch
                        }

                        // Wait for requests from FC (shorter timeout per request)
                        val frame = withTimeoutOrNull(2000) {
                            mavFrame
                                .filter { it.systemId == fcuSystemId }
                                .first { msg ->
                                    val message = msg.message
                                    (message is MissionRequestInt && message.missionType.value == MavMissionType.FENCE.value) ||
                                    (message is MissionRequest && message.missionType.value == MavMissionType.FENCE.value)
                                }
                        }

                        if (frame == null) {
                            // No request received - resend count if we haven't gotten any requests
                            if (uploadedItems.isEmpty() && System.currentTimeMillis() - lastRequestTime > 2000) {
                                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, countMsg)
                                lastRequestTime = System.currentTimeMillis()
                            }
                            continue
                        }

                        when (val msg = frame.message) {
                            is MissionRequestInt -> {
                                val seq = msg.seq.toInt()
                                if (seq < items.size) {
                                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, items[seq])
                                    uploadedItems.add(seq)
                                    lastRequestTime = System.currentTimeMillis()
                                    delay(50)
                                }
                            }

                            is MissionRequest -> {
                                val seq = msg.seq.toInt()
                                if (seq < items.size) {
                                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, items[seq])
                                    uploadedItems.add(seq)
                                    lastRequestTime = System.currentTimeMillis()
                                    delay(50)
                                }
                            }
                        }
                    }

                    // Step 4: Wait for MISSION_ACK
                    val ack = withTimeoutOrNull(5000) {
                        mavFrame
                            .filter { it.systemId == fcuSystemId }
                            .map { it.message }
                            .filterIsInstance<MissionAck>()
                            .first { it.missionType.value == MavMissionType.FENCE.value }
                    }

                    if (ack != null) {
                        if (ack.type.value == MavMissionResult.MAV_MISSION_ACCEPTED.value) {
                            continuation.resume(true)
                        } else {
                            continuation.resume(false)
                        }
                    } else {
                        // No ACK received - but fence might still be uploaded
                        // Some FCs don't send ACK for fence uploads
                        // Return true anyway as the items were uploaded
                        continuation.resume(true)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    /**
     * Configure fence parameters on flight controller
     */
    private suspend fun configureFenceParameters(config: FenceConfiguration): Boolean {
        try {
            // Set fence action (what happens on breach)
            if (!setFenceParameter("FENCE_ACTION", config.action.value)) {
                return false
            }
            delay(200)

            // Set fence margin (safety buffer)
            if (!setFenceParameter("FENCE_MARGIN", config.margin)) {
                return false
            }
            delay(200)

            // Set fence type bitfield
            // Bit 0 (1) = Max altitude fence
            // Bit 1 (2) = Circle fence
            // Bit 2 (4) = Polygon fence
            var fenceType = 0
            config.zones.forEach { zone ->
                when (zone) {
                    is FenceZone.Polygon -> fenceType = fenceType or 4  // Bit 2 = polygon
                    is FenceZone.Circle -> fenceType = fenceType or 2   // Bit 1 = circle
                    else -> {}
                }
            }
            if (config.altitudeMax != null || config.altitudeMin != null) {
                fenceType = fenceType or 1  // Bit 0 = altitude
            }

            if (!setFenceParameter("FENCE_TYPE", fenceType.toFloat())) {
                return false
            }
            delay(200)

            // Set altitude limits if provided
            if (config.altitudeMax != null) {
                if (!setFenceParameter("FENCE_ALT_MAX", config.altitudeMax)) {
                    return false
                }
                delay(200)
            }

            if (config.altitudeMin != null) {
                if (!setFenceParameter("FENCE_ALT_MIN", config.altitudeMin)) {
                    return false
                }
                delay(200)
            }

            return true

        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Enable or disable geofence on flight controller
     */
    suspend fun enableFence(enable: Boolean): Boolean {
        return setFenceParameter("FENCE_ENABLE", if (enable) 1.0f else 0.0f)
    }

    /**
     * Verify that fence is actually enabled by reading back parameters
     */
    private suspend fun verifyFenceEnabled(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val job = AppScope.launch {
                try {
                    // Request FENCE_ENABLE parameter
                    val paramRequest = ParamRequestRead(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        paramId = "FENCE_ENABLE",
                        paramIndex = -1
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, paramRequest)

                    // Wait for response
                    val response = withTimeoutOrNull(2000) {
                        mavFrame
                            .filter { it.systemId == fcuSystemId }
                            .map { it.message }
                            .filterIsInstance<ParamValue>()
                            .first { it.paramId == "FENCE_ENABLE" }
                    }

                    if (response != null) {
                        val isEnabled = response.paramValue >= 1.0f

                        // Also check FENCE_TYPE to confirm polygon fence is configured
                        if (isEnabled) {
                            delay(100)
                            val typeRequest = ParamRequestRead(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                paramId = "FENCE_TYPE",
                                paramIndex = -1
                            )
                            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, typeRequest)

                            withTimeoutOrNull(2000) {
                                mavFrame
                                    .filter { it.systemId == fcuSystemId }
                                    .map { it.message }
                                    .filterIsInstance<ParamValue>()
                                    .first { it.paramId == "FENCE_TYPE" }
                            }

                            // Check FENCE_ACTION
                            delay(100)
                            val actionRequest = ParamRequestRead(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                paramId = "FENCE_ACTION",
                                paramIndex = -1
                            )
                            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, actionRequest)

                            withTimeoutOrNull(2000) {
                                mavFrame
                                    .filter { it.systemId == fcuSystemId }
                                    .map { it.message }
                                    .filterIsInstance<ParamValue>()
                                    .first { it.paramId == "FENCE_ACTION" }
                            }
                        }

                        continuation.resume(isEnabled)
                    } else {
                        continuation.resume(false)
                    }

                } catch (e: Exception) {
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    /**
     * Set a fence-related parameter on the flight controller
     */
    private suspend fun setFenceParameter(paramId: String, value: Float): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val job = AppScope.launch {
                try {
                    // Send PARAM_SET
                    val paramSet = ParamSet(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        paramId = paramId,
                        paramValue = value,
                        paramType = MavEnumValue.of(MavParamType.REAL32)
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, paramSet)

                    // Wait for PARAM_VALUE acknowledgment
                    val ack = withTimeoutOrNull(3000) {
                        mavFrame
                            .filter { it.systemId == fcuSystemId }
                            .map { it.message }
                            .filterIsInstance<ParamValue>()
                            .first { it.paramId == paramId }
                    }

                    if (ack != null && ack.paramValue == value) {
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }

                } catch (e: Exception) {
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    /**
     * Download current geofence from flight controller
     */
    suspend fun downloadGeofence(): List<FenceZone> {
        return withContext(Dispatchers.IO) {
            try {
                // Request fence items using mission protocol
                val fenceItems = requestFenceItemsFromFcu()

                // Convert mission items back to fence zones
                convertMissionItemsToFence(fenceItems)

            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Request fence items from FC using mission protocol
     */
    private suspend fun requestFenceItemsFromFcu(): List<MissionItemInt> {
        return suspendCancellableCoroutine { continuation ->
            val job = AppScope.launch {
                try {
                    val receivedItems = mutableListOf<MissionItemInt>()

                    // Request fence count
                    val requestList = MissionRequestList(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        missionType = MavEnumValue.of(MavMissionType.FENCE)
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, requestList)

                    // Wait for count
                    val countMsg = withTimeoutOrNull(5000) {
                        mavFrame
                            .filter { it.systemId == fcuSystemId }
                            .map { it.message }
                            .filterIsInstance<MissionCount>()
                            .first { it.missionType.value == MavMissionType.FENCE.value }
                    }

                    val count = countMsg?.count?.toInt() ?: 0

                    if (count == 0) {
                        continuation.resume(emptyList())
                        return@launch
                    }

                    // Request each item
                    for (seq in 0 until count) {
                        val request = MissionRequestInt(
                            targetSystem = fcuSystemId,
                            targetComponent = fcuComponentId,
                            seq = seq.toUShort(),
                            missionType = MavEnumValue.of(MavMissionType.FENCE)
                        )
                        connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, request)

                        val item = withTimeoutOrNull(2000) {
                            mavFrame
                                .filter { it.systemId == fcuSystemId }
                                .map { it.message }
                                .filterIsInstance<MissionItemInt>()
                                .first { it.seq.toInt() == seq && it.missionType.value == MavMissionType.FENCE.value }
                        }

                        if (item != null) {
                            receivedItems.add(item)
                        }

                        delay(50)
                    }

                    continuation.resume(receivedItems)

                } catch (e: Exception) {
                    continuation.resume(emptyList())
                }
            }

            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    /**
     * Convert downloaded mission items back to fence zones
     */
    private fun convertMissionItemsToFence(items: List<MissionItemInt>): List<FenceZone> {
        val zones = mutableListOf<FenceZone>()
        val polygonVertices = mutableMapOf<Boolean, MutableList<LatLng>>()  // isInclusion -> vertices
        var currentPolygonVertexCount = 0
        var currentPolygonIsInclusion = true

        for (item in items) {
            when (item.command.value) {
                MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION.value,
                MavCmd.NAV_FENCE_POLYGON_VERTEX_EXCLUSION.value -> {
                    val isInclusion = item.command.value == MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION.value
                    val vertexCount = item.param1.toInt()
                    val lat = item.x / 1E7
                    val lng = item.y / 1E7

                    // Check if we're starting a new polygon
                    if (currentPolygonVertexCount == 0 || isInclusion != currentPolygonIsInclusion) {
                        // If there's an existing polygon being built, save it first
                        if (polygonVertices[currentPolygonIsInclusion]?.isNotEmpty() == true) {
                            zones.add(FenceZone.Polygon(
                                points = polygonVertices[currentPolygonIsInclusion]!!.toList(),
                                isInclusion = currentPolygonIsInclusion
                            ))
                            polygonVertices[currentPolygonIsInclusion]?.clear()
                        }
                        currentPolygonVertexCount = vertexCount
                        currentPolygonIsInclusion = isInclusion
                    }

                    polygonVertices.getOrPut(isInclusion) { mutableListOf() }.add(LatLng(lat, lng))

                    // When we have all vertices, create polygon
                    if (polygonVertices[isInclusion]?.size == currentPolygonVertexCount) {
                        zones.add(FenceZone.Polygon(
                            points = polygonVertices[isInclusion]!!.toList(),
                            isInclusion = isInclusion
                        ))
                        polygonVertices[isInclusion]?.clear()
                        currentPolygonVertexCount = 0
                    }
                }

                MavCmd.NAV_FENCE_CIRCLE_INCLUSION.value,
                MavCmd.NAV_FENCE_CIRCLE_EXCLUSION.value -> {
                    val isInclusion = item.command.value == MavCmd.NAV_FENCE_CIRCLE_INCLUSION.value
                    val radius = item.param1
                    val lat = item.x / 1E7
                    val lng = item.y / 1E7

                    zones.add(FenceZone.Circle(
                        center = LatLng(lat, lng),
                        radiusMeters = radius,
                        isInclusion = isInclusion
                    ))
                }

                MavCmd.NAV_FENCE_RETURN_POINT.value -> {
                    val lat = item.x / 1E7
                    val lng = item.y / 1E7

                    zones.add(FenceZone.ReturnPoint(
                        location = LatLng(lat, lng)
                    ))
                }

                else -> {
                    // Unknown fence command
                }
            }
        }

        return zones
    }

    // Job reference for fence monitoring coroutine - allows cancellation on stop/reconnect
    private var fenceMonitoringJob: kotlinx.coroutines.Job? = null

    /**
     * Start monitoring fence status from flight controller.
     * This monitors SYS_STATUS for fence breach flags.
     * Should be called when connection is established.
     */
    fun startFenceMonitoring() {
        // Cancel any previous monitoring coroutine to prevent stale state
        stopFenceMonitoring()

        fenceMonitoringJob = AppScope.launch {
            // Monitor SYS_STATUS for fence breach flags
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<SysStatus>()
                .collect { sysStatus ->
                    // Check fence breach bit in onboard_control_sensors_health
                    // Bit 8 (0x100) = FENCE
                    val fenceHealthy = (sysStatus.onboardControlSensorsHealth.value and 0x100u) != 0u
                    val fenceEnabled = (sysStatus.onboardControlSensorsEnabled.value and 0x100u) != 0u
                    val fenceBreached = fenceEnabled && !fenceHealthy

                    // IMPORTANT: Only report breach if GCS geofence is actually enabled.
                    // The FC may have stale fence data from a previous session.
                    // If geofence is disabled in GCS, ignore FC fence status to prevent
                    // false "approaching polygon fence" warnings and arm blocks.
                    val gcsGeofenceEnabled = sharedViewModel.geofenceEnabled.value
                    if (!gcsGeofenceEnabled) {
                        // GCS says geofence is off - ensure we report clean state
                        if (_fenceStatus.value.enabled || _fenceStatus.value.breached) {
                            _fenceStatus.value = FenceStatus()
                        }
                        return@collect
                    }

                    val previousStatus = _fenceStatus.value

                    _fenceStatus.update { it.copy(
                        enabled = fenceEnabled,
                        breached = fenceBreached
                    )}

                    // Notify only on new breach detection
                    if (fenceBreached && !previousStatus.breached) {
                        // Notify user
                        withContext(Dispatchers.Main) {
                            sharedViewModel.addNotification(
                                Notification(
                                    message = "⚠️ Geofence breach! FC is handling...",
                                    type = NotificationType.WARNING
                                )
                            )
                            sharedViewModel.speak("Geofence breach detected")
                        }
                    }
                }
        }
    }

    /**
     * Stop fence monitoring coroutine and reset fence status.
     * Called on disconnection or when geofence is disabled.
     */
    fun stopFenceMonitoring() {
        fenceMonitoringJob?.cancel()
        fenceMonitoringJob = null
        _fenceStatus.value = FenceStatus()
    }

    /**
     * Clear all fence data from flight controller
     */
    suspend fun clearGeofenceFromFC(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Send MISSION_CLEAR_ALL with FENCE type
                val clearCmd = MissionClearAll(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.FENCE)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearCmd)

                // Wait for acknowledgment
                val ack = withTimeoutOrNull(5000) {
                    mavFrame
                        .filter { it.systemId == fcuSystemId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        .first { it.missionType.value == MavMissionType.FENCE.value }
                }

                if (ack?.type?.value == MavMissionResult.MAV_MISSION_ACCEPTED.value) {
                    // Disable fence
                    delay(200)
                    enableFence(false)

                    // Reset fence status
                    _fenceStatus.value = FenceStatus()

                    return@withContext true
                } else {
                    return@withContext false
                }

            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Clear all mission data from flight controller.
     * Sends MISSION_CLEAR_ALL with MISSION type to remove all waypoints from the FC.
     */
    suspend fun clearMissionFromFC(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
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

                delay(50)

                val clearCmd = MissionClearAll(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearCmd)

                val ackReceived = withTimeoutOrNull(5000L) {
                    clearAckDeferred.await()
                } ?: false

                clearCollectorJob.cancel()

                return@withContext ackReceived
            } catch (e: Exception) {
                false
            }
        }
    }
}
