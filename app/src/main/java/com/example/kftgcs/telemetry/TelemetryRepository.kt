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
import com.example.kftgcs.telemetry.connections.UdpDiagnostics
import com.example.kftgcs.fence.FenceConfiguration
import com.example.kftgcs.fence.FenceStatus
import com.example.kftgcs.fence.FenceZone
import com.example.kftgcs.grid.GridUtils
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
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

/**
 * FENCE_TYPE bitmask bits, verbatim from ArduPilot AC_Fence.cpp:
 *
 *     @Bitmask{Copter, Plane, Sub}: 0:Max altitude,1:Circle Centered on Home,
 *                                   2:Inclusion/Exclusion Circles+Polygons,3:Min altitude
 *
 * These fences are independent and combine freely — a Copter running FENCE_TYPE=7
 * enforces the altitude ceiling, the home-centred cylinder (FENCE_RADIUS) AND the
 * uploaded inclusion polygon simultaneously. The home cylinder is a separate fence
 * from any circle in the inclusion/exclusion list.
 */
const val FENCE_TYPE_ALT_MAX = 1
const val FENCE_TYPE_CIRCLE = 2
const val FENCE_TYPE_POLYGON = 4
const val FENCE_TYPE_ALT_MIN = 8

/**
 * MAV_SYS_STATUS_GEOFENCE — the geofence bit in SYS_STATUS onboard_control_sensors_*.
 * 0x100000 (bit 20), from MAVLink common.xml. One flag for ALL fence types: it cannot
 * distinguish a circle breach from a polygon or altitude breach.
 */
const val MAV_SYS_STATUS_GEOFENCE: UInt = 0x100000u

/**
 * MAV_PROTOCOL_CAPABILITY bits from AUTOPILOT_VERSION.capabilities that the fence
 * mission-protocol path depends on, from MAVLink common.xml.
 *
 * MISSION_FENCE says the vehicle accepts fence geometry as mission items with
 * mission_type = MAV_MISSION_TYPE_FENCE. Without it the only way to send a polygon is
 * the deprecated single FENCE_POINT message, which this GCS does not implement — so
 * attempting an upload would silently do nothing useful. ArduPilot 4.6.3 sets both bits;
 * the check exists so a vehicle that does NOT support them fails loudly instead of
 * leaving the pilot believing a fence was uploaded.
 */
const val MAV_PROTOCOL_CAPABILITY_MISSION_INT: UInt = 4u
const val MAV_PROTOCOL_CAPABILITY_MISSION_FENCE: UInt = 16_384u

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

/**
 * Frames buffered between the mavlink reader and the shared fan-out.
 *
 * Sized to absorb a full parameter-list burst (the largest sustained burst the FC produces) plus
 * ordinary telemetry, so a slow collector cannot push back far enough to make the library's
 * DROP_OLDEST buffer discard frames.
 */
private const val MAV_FRAME_BUFFER_CAPACITY = 2048

/**
 * How recently we must have sent a mission item for a repeat request of that same seq to
 * count as the FC's retransmit crossing our reply, rather than a genuine re-request.
 *
 * ArduPilot retransmits an unanswered MISSION_REQUEST after roughly half a second. Observed:
 * our reply to seq 0 took ~500 ms, the FC's retransmit for seq 0 arrived ~498 ms after that
 * reply went out, we answered it a second time, and the FC — which had already taken the
 * first copy and moved to seq 1 — rejected the upload with MAV_MISSION_INVALID_SEQUENCE.
 *
 * Normal request spacing on this link is ~150-200 ms, so 1.2 s sits well clear of both:
 * long enough to swallow a crossed retransmit, short enough that an item genuinely lost in
 * flight is still re-sent promptly.
 */
private const val MISSION_ITEM_RETRANSMIT_WINDOW_MS = 1200L

/**
 * How many MISSION_REQUEST_INTs a mission DOWNLOAD keeps in flight at once.
 *
 * The download used to request one item and await it before requesting the next, which cost a
 * full link round trip per item — the dominant cost in a resume (a 150-item spray grid took
 * 15-20 s to read back before anything was uploaded). Requesting a window at a time lets the
 * FC stream its answers and collapses those round trips.
 *
 * Kept modest deliberately. This link also carries video and the full telemetry stream, and
 * an over-large burst just moves the loss from "slow" to "dropped", costing another round.
 */
private const val MISSION_DOWNLOAD_WINDOW = 12

/**
 * Gap between the individual writes of a download burst. NOT a wait for the reply.
 *
 * Paces the burst so a serial/BT link's TX buffer does not overrun and silently drop
 * requests, while still keeping the whole window in flight together. Well under the ~150-200
 * ms natural request spacing noted above.
 */
private const val MISSION_DOWNLOAD_REQUEST_SPACING_MS = 15L

/**
 * How long to wait for one download window's items before working out what is still missing.
 *
 * Replaces the old flat 2 s PER ITEM. It covers a whole window rather than a single item, and
 * is a floor on progress rather than a per-item stall: anything absent when it expires is
 * simply re-requested in the next round, and the completeness check still refuses to return a
 * partial mission.
 */
private const val MISSION_DOWNLOAD_WINDOW_TIMEOUT_MS = 2500L

/**
 * PARAM_VALUE messages buffered for [MavlinkTelemetryRepository.paramValue] subscribers.
 *
 * Must exceed the FC's total parameter count — a full list arrives as one uninterrupted burst and
 * anything that doesn't fit is lost, since ArduPilot sends the list only once per request. 4096
 * leaves headroom above the ~1440 params seen on current airframes.
 */
private const val PARAM_VALUE_BUFFER_CAPACITY = 4096

/** Matches RC1_OPTION .. RC16_OPTION, capturing the channel number. */
private val RC_OPTION_PARAM_REGEX = Regex("""^RC(\d{1,2})_OPTION$""")

/**
 * Returned by [MavlinkTelemetryRepository.currentMissionTargetSeq] when the FC has not reported
 * any mission progress yet, so there is no honest answer to "which item is the drone flying to".
 *
 * Callers must treat this as "cannot prepare a resume", never as a sequence number. Guessing here
 * is what sent the drone to the start of the line.
 */
const val MISSION_PROGRESS_UNKNOWN = -1

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

    // ── Partial cell-sum detection (see resolvePackVoltage) ─────────────────────────────
    // How many cells went into the last battStatusVoltage sum. When the FC omits
    // voltagesExt on a >10S pack, this drops below expectedCellCount and the sum is a
    // fraction of the true pack voltage — a 12S pack reporting only 10 cells reads ~36.7V
    // instead of ~44V, which lands at/below the 42V critical threshold and fired an RTL on
    // a healthy battery.
    private var battStatusCellCount: Int = 0

    // True when the FC is using the MAVLink "whole pack total in voltages[0]" convention
    // rather than per-cell reporting. Such a frame legitimately has one populated slot, so
    // the cell-count guard must not treat it as a 1-of-N partial sum.
    private var battStatusIsPackTotal: Boolean = false

    // Largest plausible cell count observed on this link. Learned rather than configured
    // because the fleet mixes 6S and 12S. Only ever increases: the failure mode is always
    // MISSING cells, never extra ones, so the maximum seen on a healthy frame is the truth.
    private var expectedCellCount: Int = 0

    // EMA of the RESOLVED pack voltage. Applied on the output of resolvePackVoltage so both
    // the BATTERY_STATUS and SYS_STATUS paths are smoothed identically — previously only the
    // SYS_STATUS fallback was filtered, leaving the 10Hz primary path able to single-frame
    // trip the critical failsafe on motor-spinup sag.
    private var smoothedPackVoltage: Float? = null
    // 0.25 ≈ a 0.4s time-constant at 10Hz: fast enough to track a real discharge, slow
    // enough that one bad frame moves the output by a quarter of its error.
    private val PACK_VOLTAGE_ALPHA = 0.25f

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

    // How often the DISTANCE_SENSOR staleness watchdog ticks. Well under SENSOR_STALE_AFTER_MS so a
    // dropped rangefinder reading clears within ~1.5-1.75s rather than lingering on screen.
    private val STALE_SENSOR_CHECK_INTERVAL_MS = 250L

    // KFT Auth state
    private val _authStatus = MutableStateFlow(AuthResult.FAILED)
    val authStatusFlow: StateFlow<AuthResult> = _authStatus.asStateFlow()
    private val lastAuthAttemptTime = AtomicLong(0L)
    @Volatile private var activeAuthJob: kotlinx.coroutines.Job? = null

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

    // Previous GLOBAL_POSITION_INT altitude sample, used to derive the climb rate the
    // altitude-ceiling failsafe sizes its action margin from. See the collector for why
    // VFR_HUD.climb is not used.
    private var lastClimbAltM: Float? = null
    private var lastClimbAtMs: Long = 0L
    /**
     * Low-pass filtered climb rate, in m/s.
     *
     * The raw two-sample difference below is honest but noisy: at a 0.15 s interval, 0.3 m
     * of barometric wobble reads as 2 m/s of "climb". The altitude ceiling sizes its
     * stopping distance from this number, so that noise used to push the trigger several
     * metres further down the envelope than the vehicle's actual motion justified. An EMA
     * over [CLIMB_EMA_TAU_S] removes it at the cost of ~0.3 s of lag — an order of
     * magnitude less than the VFR_HUD barometric filter this derivation replaced.
     *
     * The RAW value still goes out as climbRate (the "is it moving?" tests and the UI want
     * responsiveness); the filtered one goes out as climbRateSmoothed, and only the
     * predictive maths uses it.
     */
    private var climbEmaMps: Float? = null
    /** EMA time constant for [climbEmaMps]. */
    private val CLIMB_EMA_TAU_S = 0.3f
    private var isMissionUploadInProgress = false  // Track if mission upload is actively in progress (not just clearing)

    /**
     * Serializes everything that speaks the MISSION-type mission protocol: upload and clear.
     *
     * These used to run concurrently on unrelated scopes — clearMissionCompletely() fires
     * from screen navigation, the post-disarm auto-clear fires from the telemetry collector,
     * and an upload fires from the plan screen. All three exchange MISSION_CLEAR_ALL /
     * MISSION_COUNT / MISSION_ACK on one link with no correlation id, so their acks are
     * indistinguishable once in flight. Filtering on missionType stops a FENCE ack from
     * being mistaken for ours, but only mutual exclusion stops one MISSION operation's ack
     * from satisfying another's wait.
     */
    private val missionProtocolMutex = kotlinx.coroutines.sync.Mutex()

    // COMMAND_ACK flow for calibration and other commands
    private val _commandAck = MutableSharedFlow<CommandAck>(replay = 0, extraBufferCapacity = 10)
    val commandAck: SharedFlow<CommandAck> = _commandAck.asSharedFlow()

    // COMMAND_LONG flow for incoming commands from FC (e.g., ACCELCAL_VEHICLE_POS)
    private val _commandLong = MutableSharedFlow<CommandLong>(replay = 0, extraBufferCapacity = 10)
    val commandLong: SharedFlow<CommandLong> = _commandLong.asSharedFlow()

    // RC Battery failsafe tracking
    private var rcBatteryFailsafeTriggered = false
    // When the RC battery reading first went critical (0 = not currently critical). The
    // reading must hold for RC_BATT_DEBOUNCE_MS before RTL fires — see the RADIO_STATUS
    // collector for why a single 0 is not trustworthy on this field.
    private var rcBattCriticalSince = 0L
    private val RC_BATT_DEBOUNCE_MS = 3000L

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

    // ═══ Mission progress source arbitration ═══
    // MISSION_CURRENT (the item being flown TO) and MISSION_ITEM_REACHED (the item just
    // completed) both describe mission progress but are one apart, so mixing them corrupts
    // the resume point. MISSION_CURRENT wins whenever the FC is sending it; this timestamp
    // says whether it still is. @Volatile: written on the MISSION_CURRENT collector, read on
    // the MISSION_ITEM_REACHED one.
    @Volatile private var lastMissionCurrentAtMs = 0L
    private val MISSION_CURRENT_STALE_MS = 5000L

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

    /**
     * Same proof, but scoped to the whole SPRAY SESSION rather than one pass.
     *
     * [hasSeenHealthyFlow] resets on every IDLE→PRIMING transition, and a spray mission
     * embeds DO_SPRAYER(0) at each line-end / DO_SPRAYER(1) at each line-start — so the
     * sprayer cycles IDLE→PRIMING at EVERY line. That made the per-pass latch ask for fresh
     * proof of flow on each line, which the tank cannot give once it has run dry:
     *
     *   line N   : healthy flow → hasSeenHealthyFlow = true → a dry tank here latches EMPTY ✓
     *   tank empties at the end of line N
     *   line N+1 : PRIMING resets the latch → flow is 0 from the very first tick →
     *              hasSeenHealthyFlow stays false → DEBOUNCING_EMPTY takes the
     *              "sensor/config fault" branch → a warning, and NO tank-empty action ✗
     *
     * The tank empties once; the proof of a working sensor does not need re-earning every
     * line. This latch remembers that the sprayer HAS produced real flow at some point in
     * this spray session, so a later dry line is correctly read as an empty tank rather than
     * a broken sensor. It is deliberately NOT reset in [transitionTo] — only when the spray
     * session genuinely ends (leaving AUTO / mission end / disconnect), via
     * [resetSprayFlowEvidence].
     *
     * The false-positive protection this was guarding is preserved: a truly dead sensor
     * never sets this flag either, so it still reports a fault rather than an empty tank.
     */
    @Volatile private var hasSeenHealthyFlowThisSession = false

    // One-shot guards so the field warnings fire once per spray pass, not every BATT2 tick.
    private var sensorFaultWarned = false       // "no flow ever seen" warning (reset each PRIMING)
    private var configInvalidWarned = false     // "monitoring inactive" warning (reset when sprayer off)

    // One-shot guard for the "Spray system configured correctly" notification.
    // validateSprayConfiguration() runs on EVERY BATT2_*/BATT3_* PARAM_VALUE, and the FC re-sends
    // that whole block on each param refresh, so notifying on each valid pass spammed the panel with
    // the same line many times in a row. Notify only on the transition into the valid state; reset
    // when configuration goes invalid (or on disconnect) so a genuine re-configuration notifies again.
    private var sprayConfigValidNotified = false
    private var lastZeroFlowWarnTime = 0L       // debounce for the raw-0-while-enabled warning
    private val ZERO_FLOW_WARN_INTERVAL_MS = 10000L

    // ── Sprayer switch channel discovery (RCx_OPTION = 15) ──
    // Last known RCx_OPTION value per channel, so the resolved spray channel can be recomputed when
    // any one of them changes (including a channel being un-assigned from Sprayer).
    private val rcOptionValues = mutableMapOf<Int, Int>()

    /**
     * Record one RCx_OPTION value and re-resolve which RC channel drives the sprayer.
     *
     * The lowest channel with option [RC_OPTION_SPRAYER] wins (ties are a misconfiguration; picking
     * deterministically beats flapping between them). When no channel claims the sprayer we keep the
     * historical [DEFAULT_SPRAY_RC_CHANNEL] so existing RC7 installs behave exactly as before.
     */
    private fun handleRcOptionParam(channel: Int, option: Int) {
        val previous = rcOptionValues.put(channel, option)
        if (previous == option) return   // unchanged (the FC re-sends the whole block on refresh)

        val resolved = rcOptionValues.filterValues { it == RC_OPTION_SPRAYER }.keys.minOrNull()
        val newChannel = resolved ?: DEFAULT_SPRAY_RC_CHANNEL
        val current = state.value.sprayTelemetry

        if (current.sprayRcChannel == newChannel && current.sprayRcChannelResolved == (resolved != null)) {
            return
        }

        if (resolved != null) {
            LogUtils.i("SprayControl", "Sprayer switch resolved to RC$newChannel (RC${newChannel}_OPTION=$RC_OPTION_SPRAYER)")
        } else {
            LogUtils.w("SprayControl", "No RCx_OPTION=$RC_OPTION_SPRAYER found — falling back to RC$DEFAULT_SPRAY_RC_CHANNEL for spray monitoring")
        }

        _state.update { st ->
            st.copy(
                sprayTelemetry = st.sprayTelemetry.copy(
                    sprayRcChannel = newChannel,
                    sprayRcChannelResolved = resolved != null
                )
            )
        }
    }

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
        // NOTE: hasSeenHealthyFlowThisSession is deliberately NOT cleared here. This runs at
        // every mission-end detection and every AUTO exit — including the ones that happen
        // between spray lines — which is exactly the churn the session latch exists to
        // survive. It is cleared by resetSprayFlowEvidence() when the spray session truly
        // ends (leaving AUTO, disarm, disconnect).
    }

    /**
     * Forget that the sprayer ever produced healthy flow.
     *
     * Ends the spray session for [hasSeenHealthyFlowThisSession], so the next session must
     * earn its own proof before a dry tank can latch TANK_EMPTY. Without this the latch would
     * persist across flights and a flow sensor that failed between flights would be reported
     * as an empty tank instead of a sensor fault — the false positive the original per-pass
     * latch was written to prevent.
     */
    private fun resetSprayFlowEvidence(reason: String) {
        if (hasSeenHealthyFlowThisSession) {
            LogUtils.i("TankEmpty", "🔄 Spray session ended ($reason) — flow evidence cleared, next session must re-prove the sensor")
        }
        hasSeenHealthyFlowThisSession = false
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

    // PARAM_VALUE flow for parameter reading.
    // Buffer must hold an entire PARAM_REQUEST_LIST response: the FC streams the list once, so a
    // message that doesn't fit is never re-sent. 1024 was below the ~1440 params on current
    // airframes, which silently truncated the Full Param List download.
    private val _paramValue = MutableSharedFlow<ParamValue>(
        replay = 0,
        extraBufferCapacity = PARAM_VALUE_BUFFER_CAPACITY
    )
    val paramValue: SharedFlow<ParamValue> = _paramValue.asSharedFlow()

    // ════════════════════════════════════════════════════════════════
    // GEOFENCE STATUS (ArduPilot Native Fence System)
    // ════════════════════════════════════════════════════════════════
    private val _fenceStatus = MutableStateFlow(FenceStatus())
    val fenceStatus: StateFlow<FenceStatus> = _fenceStatus.asStateFlow()

    /**
     * AUTOPILOT_VERSION.capabilities as last reported by the vehicle, or null if it has
     * not answered yet. Null means "unknown", NOT "unsupported" — see
     * [fenceMissionProtocolUnsupported].
     */
    private val _autopilotCapabilities = MutableStateFlow<UInt?>(null)
    val autopilotCapabilities: StateFlow<UInt?> = _autopilotCapabilities.asStateFlow()

    /**
     * Why the vehicle cannot take fence geometry over the mission protocol, or null if it
     * can (or if we simply do not know yet).
     *
     * Deliberately permissive when [_autopilotCapabilities] is null: AUTOPILOT_VERSION is
     * requested at connect but is not guaranteed to have arrived by the time the pilot
     * enables a geofence, and refusing an upload because a capability message was late
     * would be a worse failure than attempting one. This is the defensive check Mission
     * Planner makes, not a handshake.
     */
    private fun fenceMissionProtocolUnsupported(): String? {
        val caps = _autopilotCapabilities.value ?: return null
        val missing = buildList {
            if (caps and MAV_PROTOCOL_CAPABILITY_MISSION_FENCE == 0u) add("MISSION_FENCE")
            if (caps and MAV_PROTOCOL_CAPABILITY_MISSION_INT == 0u) add("MISSION_INT")
        }
        return if (missing.isEmpty()) null else missing.joinToString(" and ")
    }

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

            // ══ Stream rate budget ══════════════════════════════════════════════════════════
            // A 57600-baud SiK radio carries ~4.5-4.8 kB/s of MAVLink payload one-way. The
            // previous table asked for ~4.6 kB/s, which fit only because DISTANCE_SENSOR was
            // silent on most aircraft. On FCs running the radar Lua script both rangefinder
            // instances go live, the request tips past link capacity, and ArduPilot starts
            // deferring lower-priority streams — GLOBAL_POSITION_INT (5Hz) lost against 30Hz
            // BATTERY_STATUS and 20Hz ATTITUDE/VFR_HUD. Position then arrived at 1-2Hz, and the
            // altitude-ceiling failsafe acted on a fix up to a second old: the ~1m overshoot.
            //
            // So: raise the one stream the failsafes depend on, and cut the ones that only feed
            // human-readable UI. New budget ≈2.6 kB/s (~55% utilisation) with both rangefinders
            // live — position throughput doubles while total traffic nearly halves.
            setMessageRate(1u, 4f)     // SYS_STATUS - 4Hz for fast battery voltage monitoring
            setMessageRate(24u, 0.5f)  // GPS_RAW_INT - reduced from 1Hz for Bluetooth
            // GLOBAL_POSITION_INT is the SOLE source for the altitude-ceiling, max-range and
            // distance-to-home failsafes. 10Hz halves worst-case sampling staleness vs 5Hz
            // (0.2m instead of 0.4m of unseen climb at 2 m/s) and gives the age-aware action
            // margin a fresh fix to work from.
            setMessageRate(33u, 10f)   // GLOBAL_POSITION_INT - RAISED 5→10Hz (safety-critical)
            // VFR_HUD/ATTITUDE drive a numeric speed readout and the heading arrow. 10Hz is
            // already past what a human resolves on a text field, and Compose recomposition
            // smooths the arrow — 20Hz was spending ~800 B/s each for no perceptible gain.
            setMessageRate(74u, 10f)   // VFR_HUD - LOWERED 20→10Hz
            setMessageRate(30u, 10f)   // ATTITUDE - LOWERED 20→10Hz
            // BATTERY_STATUS carries BATT2 (flow sensor) and BATT3 (tank level) alongside the
            // main pack. ArduPilot's send_battery_status() emits only ONE instance per scheduled
            // tick and round-robins across the configured monitors, so the requested rate is
            // SHARED across them: with 3 monitors (main/flow/level), 12Hz yields ~4Hz per
            // instance. That still resolves the 5-sample flow filter well (~1.25s window) and
            // is ample for pack voltage now that resolvePackVoltage() applies an EMA and the
            // critical failsafe debounces — neither of which needs 10Hz to be trustworthy.
            // The old 30Hz request alone was ~2 kB/s, the single largest consumer on the link.
            setMessageRate(147u, 12f)  // BATTERY_STATUS - LOWERED 30→12Hz ≈ 4Hz per instance
            setMessageRate(65u, 1f)    // RC_CHANNELS - reduced from 2Hz for Bluetooth

            // Request RADIO_STATUS for RC battery monitoring
            setMessageRate(109u, 1f) // RADIO_STATUS (1Hz for RC battery monitoring)

            // HOME_POSITION - home rarely moves (only on arm/DO_SET_HOME), so a slow trickle is
            // enough to keep the "distance to home" readout honest without loading the link.
            setMessageRate(242u, 0.2f) // HOME_POSITION (every 5s)

            // DISTANCE_SENSOR - explicitly request this. Unlike the other messages above, this
            // one was never actively requested and only ever arrived because the FC happened to
            // include it in its own default stream rates — so a vehicle/param change that drops
            // it from those defaults silences the terrain + obstacle widgets with no error (they
            // still render their frame/labels, just no live reading). Like BATTERY_STATUS above,
            // the FC multiplexes two rangefinder instances (terrain + forward) onto this one
            // message ID and round-robins between them, so ask for double the desired per-sensor
            // rate.
            //
            // 8Hz total ≈ 4Hz per instance (~250ms), against the 1500ms
            // SENSOR_STALE_AFTER_MS watchdog that clears each reading independently.
            //
            // Sizing rule: the budget that matters is PER INSTANCE, not the aggregate. A
            // previous attempt at 4Hz total looked like it had 3x headroom but actually gave
            // each instance only ~500ms — and since ArduPilot rounds SET_MESSAGE_INTERVAL to
            // its scheduler loop and round-robins instances, the real gap drifted past 1500ms
            // and the terrain/obstacle widgets blanked to "N/A". 250ms nominal leaves ~6x
            // margin, which survives that jitter.
            //
            // Still well below the old 10Hz: this stream appears ONLY on Lua-equipped FCs,
            // which is what made the fence overshoot look like a Lua bug rather than a
            // link-budget one.
            setMessageRate(132u, 8f) // DISTANCE_SENSOR - 10→8Hz, ~4Hz per instance

            // OBSTACLE_DISTANCE - DIAGNOSTIC ONLY for now (see the ObstacleDistance collector's
            // comment above): not multiplexed like DISTANCE_SENSOR, PRX1 is a single logical
            // instance, so no round-robin doubling is needed. 2Hz is plenty for a live-capture
            // comparison against RNGFND2 without spending link budget on a stream nothing
            // consumes yet; raise this once real per-sector UI is built.
            setMessageRate(330u, 2f) // OBSTACLE_DISTANCE - diagnostic only

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

            // Ask for HOME_POSITION once immediately so the overlay isn't blank for up to 5s
            // after connecting to an already-armed/homed vehicle.
            val homePositionCmd = CommandLong(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                command = MavCmd.REQUEST_MESSAGE.wrap(),
                confirmation = 0u,
                param1 = 242f, // HOME_POSITION message ID
                param2 = 0f,
                param3 = 0f,
                param4 = 0f,
                param5 = 0f,
                param6 = 0f,
                param7 = 0f
            )
            try {
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, homePositionCmd)
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
     * Learn the pack's cell count from frames that are demonstrably trustworthy.
     *
     * A frame counts as evidence when the cell-sum agrees with SYS_STATUS within 10%, or
     * when SYS_STATUS is saturated (>=65V) and so cannot corroborate a genuinely larger
     * pack. The count only ever increases, because the failure mode is always MISSING
     * cells — an FC never invents extra ones — so the maximum ever seen on a healthy frame
     * is the real pack size.
     *
     * Deliberately NOT gated on `armed`: a 12S FC populates voltagesExt from power-up, so
     * learning on the ground gives the guard authority before the first arm. Gating on arm
     * would leave expectedCellCount at 0 (guard inert) during motor spin-up, which is
     * exactly when the sag that triggers a false failsafe occurs.
     */
    private fun learnCellCount() {
        if (battStatusIsPackTotal) return
        val n = battStatusCellCount
        if (n <= 0 || n > 14) return          // 14 = MAVLink's max (voltages + voltagesExt)
        val sum = battStatusVoltage ?: return
        val sys = smoothedVoltage
        val corroborated = sys == null || sys >= 65.0f || sum >= sys * 0.90f
        if (corroborated && n > expectedCellCount) {
            LogUtils.i("VoltageDbg", "Learned expectedCellCount=$n (was $expectedCellCount, sum=${sum}V sys=${sys}V)")
            expectedCellCount = n
        }
    }

    /**
     * Single point of truth for pack voltage, called from BOTH the BATTERY_STATUS and
     * SYS_STATUS collectors.
     *
     * Previously the partial-cell-sum guard lived only inside the SYS_STATUS collector at
     * 4Hz, while BATTERY_STATUS wrote the raw sum straight to state at ~10Hz — so the
     * guarded value was overwritten by an unguarded one most of the time. Routing both
     * through here means a bad sum can never reach the failsafe from one path while being
     * rejected on the other.
     *
     * Rejection ORDER matters. The cell-count test must run before the ratio test, because
     * a 10-of-12 partial sum is only 17% low and slips straight past a 20% ratio guard —
     * that is precisely why the original guard never caught this bug.
     */
    private fun resolvePackVoltage(): Float? {
        val cellSum = battStatusVoltage
        val sys = smoothedVoltage

        val resolved = when {
            cellSum == null -> sys

            // Fewer cells than we have confidently seen before ⇒ the FC dropped cells from
            // this frame and the sum is a fraction of the true pack voltage.
            !battStatusIsPackTotal && expectedCellCount > 0 &&
                battStatusCellCount in 1 until expectedCellCount -> {
                LogUtils.w("VoltageDbg",
                    "⚠️ REJECT partial cell-sum ${cellSum}V from $battStatusCellCount cells " +
                    "(expected $expectedCellCount) — using SYS_STATUS ${sys}V")
                sys
            }

            sys == null -> cellSum
            // SYS_STATUS is a UShort and saturates at 65.535V; above that the cell-sum is
            // the only source that can be right, so never fall back to the cap.
            sys >= 65.0f -> cellSum

            // Ratio backstop for partial sums we have no cell-count evidence for (e.g. the
            // very first frames, before expectedCellCount is learned). Tightened from 0.80:
            // 10/12 = 0.833 passed the old threshold, making it unable to catch a 12S pack
            // missing its voltagesExt cells. 0.90 catches that while still tolerating the
            // few-percent disagreement normal between a shunt reading and a cell-tap sum.
            cellSum < sys * 0.90f -> {
                LogUtils.w("VoltageDbg",
                    "⚠️ cell-sum ${cellSum}V is >10% below SYS_STATUS ${sys}V — " +
                    "possible partial cell-sum. Using SYS_STATUS.")
                sys
            }

            else -> cellSum
        } ?: return null

        // EMA on the resolved value, so a single sagged frame moves the output by only a
        // fraction of its error regardless of which source produced it.
        val prev = smoothedPackVoltage
        val out = if (prev == null) resolved
                  else PACK_VOLTAGE_ALPHA * resolved + (1 - PACK_VOLTAGE_ALPHA) * prev
        smoothedPackVoltage = out
        return out
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
                        // Drop the climb-rate reference sample: differentiating the first
                        // fix of a new session against an altitude from the previous one
                        // would hand the altitude failsafe a fabricated climb rate.
                        lastClimbAltM = null
                        lastClimbAtMs = 0L
                        climbEmaMps = null
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
                            // See the StreamState.Inactive path — stale reference sample.
                            lastClimbAltM = null
                            lastClimbAtMs = 0L
                            climbEmaMps = null
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

        // Shared message stream.
        //
        // The buffer here is load-bearing, not a micro-optimisation. Two facts collide:
        //
        //  1. The mavlink library's own connection flow is built with extraBufferCapacity = 128
        //     and BufferOverflow.DROP_OLDEST, so its socket reader NEVER blocks — when consumers
        //     fall behind it silently discards frames. Dropped frames are gone for good.
        //  2. A bare shareIn(replay = 0) has NO buffer, so it emits at the pace of the slowest of
        //     the ~40 collectors below.
        //
        // Together those meant a PARAM_REQUEST_LIST burst (1000+ PARAM_VALUEs back to back)
        // outran the fan-out, overflowed the library's 128-frame buffer, and lost hundreds of
        // params — on TCP, where the link itself cannot drop anything. ArduPilot streams the
        // parameter list exactly once, so the Full Param List screen stalled around 960/1440 and
        // no amount of refreshing recovered it.
        //
        // Buffering decouples the fan-out from the reader so a momentarily slow collector no
        // longer costs us frames. SUSPEND (not DROP_OLDEST) is deliberate: dropping here would
        // reintroduce the very silent data loss this is fixing.
        mavFrame = connection.mavFrame
            .buffer(MAV_FRAME_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)
            .shareIn(
                scope,
                SharingStarted.Eagerly,
                replay = 0
            )

        // Log raw messages. Also records that at least one frame actually parsed, which the UDP
        // failure dialog uses to tell "bytes arrived but nothing was MAVLink" apart from "the link
        // is fine, the autopilot just never sent a heartbeat".
        scope.launch {
            mavFrame.collect {
                if (!UdpDiagnostics.mavlinkFrameSeen) UdpDiagnostics.mavlinkFrameSeen = true
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
                            // Only filter out invalid negative readings - a genuine 0 (or
                            // any low value during a turn) should still be displayed as-is,
                            // not dropped to null/"N/A".
                            airspeed = hud.airspeed.takeIf { v -> v >= 0f },
                            groundspeed = hud.groundspeed.takeIf { v -> v >= 0f },
                            formattedAirspeed = formatSpeed(hud.airspeed.takeIf { v -> v >= 0f }),
                            formattedGroundspeed = formatSpeed(hud.groundspeed.takeIf { v -> v >= 0f }),
                            heading = normalizedHeading
                            // climbRate is deliberately NOT set here. It is derived from
                            // successive GLOBAL_POSITION_INT relative_alt samples instead —
                            // VFR_HUD.climb is baro-filtered and lags the true vertical speed
                            // in forward flight, which undersized the altitude failsafe's
                            // action margin. See the GLOBAL_POSITION_INT collector.
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

        // DISTANCE_SENSOR (132) - the FC multiplexes two rangefinders onto this one message and
        // distinguishes them by the `orientation` field, so we route each to its own stream:
        //   orientation 25 (PITCH_270, downward-facing) -> TerrainData   (distance to ground)
        //   orientation  0 (NONE, forward-facing)       -> ProximityData (forward obstacle distance)
        // Raw distances are centimetres; convert to metres. signalQuality raw 0 means unknown.
        // NOTE: OBSTACLE_DISTANCE (330) UI is intentionally NOT built yet — this vehicle's PRX1 is
        // configured PRX1_TYPE=4 ("RangeFinder"), which ArduPilot synthesizes FROM RNGFND2's single
        // forward point distance by projecting it across a narrow sector arc, rather than a genuinely
        // scanning radar. See the diagnostic-only collector below, which logs 330's raw sector array
        // alongside this stream's forward reading so that assumption can be confirmed from a live
        // capture before any per-sector UI is built.
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<DistanceSensor>()
                .collect { ds ->
                    val currentM = ds.currentDistance.toInt() / 100f
                    val minM = ds.minDistance.toInt() / 100f
                    val maxM = ds.maxDistance.toInt() / 100f
                    val quality = ds.signalQuality.toInt().takeIf { it in 1..100 }
                    when (ds.orientation.entry) {
                        MavSensorOrientation.MAV_SENSOR_ROTATION_PITCH_270 -> {
                            val terrain = TerrainData(
                                currentDistanceM = currentM,
                                minDistanceM = minM,
                                maxDistanceM = maxM,
                                isDownwardFacing = true,
                                signalQuality = quality
                            )
                            _state.update { it.copy(terrainData = terrain) }
                        }
                        MavSensorOrientation.MAV_SENSOR_ROTATION_NONE -> {
                            val proximity = ProximityData(
                                currentDistanceM = currentM,
                                minDistanceM = minM,
                                maxDistanceM = maxM,
                                signalQuality = quality
                            )
                            _state.update { it.copy(proximityData = proximity) }
                            // Logged under the same "ObstacleDistanceDbg" tag as the OBSTACLE_DISTANCE
                            // (330) collector above so the two streams can be diffed directly in
                            // logcat while confirming whether PRX1 (PRX1_TYPE=4) is genuinely
                            // synthesized from this RNGFND2 reading.
                            LogUtils.d(
                                "ObstacleDistanceDbg",
                                "DISTANCE_SENSOR(fwd/RNGFND2) currentDistance=${currentM}m " +
                                    "min=${minM}m max=${maxM}m quality=$quality"
                            )
                        }
                        else -> { /* other orientations are not used by the obstacle/terrain UI */ }
                    }
                }
        }

        // OBSTACLE_DISTANCE (330) - DIAGNOSTIC ONLY, not yet wired into any UI or TelemetryState
        // field. PRX1_TYPE=4 on this vehicle means ArduPilot builds this message FROM RNGFND2 (a
        // single forward point sensor), not from genuine per-sector radar returns — so before
        // building a multi-sector wedge UI we need to confirm from a live capture whether the
        // `distances[]` array actually varies sector-to-sector or is just RNGFND2's one distance
        // projected across a narrow arc (the expected outcome for PRX1_TYPE=4). Logs the full raw
        // array plus increment/min/max so it can be diffed against DISTANCE_SENSOR's forward
        // (orientation NONE) reading logged just above. Remove/replace once that's confirmed.
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<ObstacleDistance>()
                .collect { od ->
                    // UINT16_MAX (65535) = unknown/unused slot; max_distance+1 = "no obstacle" at
                    // that sector. Only log populated slots so the interesting values aren't buried
                    // in 72 mostly-empty entries.
                    val populated = od.distances
                        .mapIndexedNotNull { i, d ->
                            val raw = d.toInt()
                            if (raw == 0xFFFF) null else i to raw
                        }
                    LogUtils.d(
                        "ObstacleDistanceDbg",
                        "OBSTACLE_DISTANCE sensorType=${od.sensorType.entry} " +
                            "increment=${od.increment}deg incrementF=${od.incrementF}deg " +
                            "angleOffset=${od.angleOffset}deg frame=${od.frame.entry} " +
                            "min=${od.minDistance}cm max=${od.maxDistance}cm " +
                            "populatedSectors=${populated.size}/${od.distances.size} " +
                            "values(idx:cm)=$populated"
                    )
                }
        }

        // DISTANCE_SENSOR staleness watchdog. The collector above is purely event-driven, so with no
        // watchdog the last reading stays in TelemetryState forever once the stream stops — which is
        // exactly what "no target" looks like on the wire (ArduPilot stops relaying that rangefinder
        // instance). Clearing here rather than in the widget means every consumer of terrainData /
        // proximityData gets the same guarantee. Also clears the moment the FCU link drops, since the
        // heartbeat watchdog only flips connected/fcuDetected and leaves telemetry fields untouched.
        scope.launch {
            while (isActive) {
                delay(STALE_SENSOR_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val linkDown = !state.value.fcuDetected
                _state.update { current ->
                    val dropTerrain = current.terrainData?.let { linkDown || it.isStaleAt(now) } == true
                    val dropProximity = current.proximityData?.let { linkDown || it.isStaleAt(now) } == true
                    when {
                        dropTerrain && dropProximity -> current.copy(terrainData = null, proximityData = null)
                        dropTerrain -> current.copy(terrainData = null)
                        dropProximity -> current.copy(proximityData = null)
                        else -> current
                    }
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

                    // Announce armed/disarmed state transitions via TTS.
                    // NOTE: the voltage EMA reset on arm deliberately does NOT live here any
                    // more — it moved to the HEARTBEAT collector's arm transition. Hanging it
                    // off this stream meant the reset depended on the very telemetry that
                    // degrades when the link saturates.
                    if (currentArmed && !previousArmedState) {
                        sharedViewModel.announceDroneArmed()
                    } else if (!currentArmed && previousArmedState) {
                        sharedViewModel.announceDroneDisarmed()
                    }

                    // Update state with position data only
                    // NOTE: Flight tracking removed - now handled by UnifiedFlightTracker
                    //
                    // NOT throttled. throttledStateUpdate shared ONE global timestamp across
                    // every caller, so an unrelated high-rate message could consume the window
                    // and cause a position sample to be DROPPED (not deferred) — after which
                    // the altitude/range failsafes evaluated a stale fix. Position is now the
                    // most safety-critical field in the state, so it goes straight through, on
                    // the same reasoning VFR_HUD already documents above.
                    // ══ Climb rate, derived here rather than taken from VFR_HUD ══
                    // VFR_HUD.climb is a filtered barometric rate and LAGS the true vertical
                    // speed, badly so in fast forward flight (pitch + throttle) where the
                    // baro sees airflow over the airframe. The altitude wall projects the
                    // vehicle's stopping altitude from this number, so a climb rate that
                    // reads low means the brake goes in too late. Differentiating the SAME
                    // relative_alt signal the ceiling is judged against keeps the projection
                    // consistent with the altitude it is protecting, and makes it respond
                    // immediately to a real climb.
                    //
                    // The raw quotient is what the wall's "is it moving?" tests want; the EMA
                    // below is what its arithmetic wants. Both are published.
                    val nowMs = System.currentTimeMillis()
                    val prevAlt = lastClimbAltM
                    val dtS = (nowMs - lastClimbAtMs) / 1000f
                    // Ignore samples too close together (noise dominates the quotient) or too
                    // far apart (the gap spans a telemetry dropout and the average is stale).
                    val derivedClimb = if (prevAlt != null && dtS >= 0.15f && dtS <= 2f) {
                        ((relAltM - prevAlt) / dtS).takeIf { it.isFinite() }
                    } else {
                        null
                    }
                    if (prevAlt == null || dtS >= 0.15f) {
                        lastClimbAltM = relAltM
                        lastClimbAtMs = nowMs
                    }

                    // Feed the EMA. alpha is derived from the ACTUAL sample interval rather
                    // than fixed, so the filter keeps the same time constant whether position
                    // is arriving at 10 Hz or has degraded to 2 Hz under a saturated link —
                    // a fixed alpha would over-smooth (and therefore under-report a real
                    // climb) exactly when the link is worst and the margin matters most.
                    if (derivedClimb != null) {
                        val alpha = (dtS / (CLIMB_EMA_TAU_S + dtS)).coerceIn(0f, 1f)
                        val prevEma = climbEmaMps
                        climbEmaMps = if (prevEma == null) derivedClimb
                                      else prevEma + alpha * (derivedClimb - prevEma)
                    }

                    _state.update {
                        it.copy(
                            altitudeMsl = altAMSLm,
                            altitudeRelative = relAltM,
                            latitude = lat,
                            longitude = lon,
                            // Keep the last good value when this sample could not produce one,
                            // so a single odd interval does not blank the failsafe's input.
                            climbRate = derivedClimb ?: it.climbRate,
                            climbRateSmoothed = climbEmaMps ?: it.climbRateSmoothed,
                            positionReceivedAtMs = nowMs
                        )
                    }

                    // Update previous armed state for next iteration
                    previousArmedState = currentArmed
                }
        }

        // HOME_POSITION - the FC's launch / RTL point, used for the "distance to home" readout.
        scope.launch {
            mavFrame
                .filter { state.value.fcuDetected && it.systemId == fcuSystemId }
                .map { it.message }
                .filterIsInstance<HomePosition>()
                .collect { hp ->
                    val lat = hp.latitude / 10_000_000.0
                    val lon = hp.longitude / 10_000_000.0
                    // ArduPilot reports 0/0 before home is set; treat that as "no home yet"
                    // rather than a point off the coast of Africa.
                    if (lat != 0.0 || lon != 0.0) {
                        _state.update { it.copy(homeLatitude = lat, homeLongitude = lon) }
                    }
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
                        // The spec's "not supported" sentinel for voltagesExt is 0, but builds
                        // have been seen emitting 0xFFFF here as well. Filtering only 0 let a
                        // 0xFFFF slot add 65.535V to the total — inflating the pack reading and
                        // MASKING a genuinely low battery, the inverse of the false-trigger bug.
                        val validExt  = b.voltagesExt.filter { it.toInt() != 0xFFFF && it.toInt() > 0 }

                        val allValid = validMain + validExt
                        val sumV = allValid.sumOf { it.toLong() }.toFloat() / 1000f

                        // MAVLink also allows the FC to report the WHOLE pack total in
                        // voltages[0] with every other slot at the sentinel. One populated slot
                        // holding more than any single cell could (>= 15V) is that convention,
                        // not a 1-of-N partial sum — flag it so the cell-count guard in
                        // resolvePackVoltage() does not reject such frames forever.
                        val isPackTotal = allValid.size == 1 && sumV >= 15f

                        if (allValid.isNotEmpty()) {
                            battStatusVoltage = sumV
                            battStatusCellCount = allValid.size
                            battStatusIsPackTotal = isPackTotal
                        }

                        learnCellCount()
                        val resolved = resolvePackVoltage()

                        // Diagnostic log — shows raw cell data in logcat under tag "VoltageDbg"
                        LogUtils.d("VoltageDbg",
                            "BATT_STATUS id=0 | voltages=${b.voltages.toList()} | " +
                            "voltagesExt=${b.voltagesExt.toList()} | " +
                            "sum=${sumV}V cells=${allValid.size} expected=$expectedCellCount " +
                            "packTotal=$isPackTotal sys=${smoothedVoltage}V → resolved=${resolved}V"
                        )

                        _state.update { s ->
                            s.copy(
                                currentA = currentA,
                                // resolvePackVoltage() arbitrates between this cell-sum and the
                                // SYS_STATUS fallback; never write the raw sum directly.
                                voltage = resolved ?: s.voltage
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

                        // ═══ Transition-aware suppression ═══
                        // Missions embed DO_SPRAYER(0) at each line-end and DO_SPRAYER(1) at each
                        // line-start, so spray is intentionally OFF while flying the horizontal
                        // connector between lines. During those transitions flow legitimately drops
                        // to ~0 — which must NOT be read as "tank empty". Ask the mission whether spray
                        // is commanded ON at the current sequence; when it is commanded OFF (a
                        // transition), sprayerIsOn goes false → state machine returns to IDLE → no
                        // false tank-empty. Unknown/manual missions return true (behavior unchanged).
                        val missionCommandsSprayOn = if (isInAutoMode) {
                            sharedViewModel.isSprayCommandedActiveAt(state.value.currentWaypoint ?: -1)
                        } else true

                        // In AUTO mode, spraying is done via mission commands (DO_SET_SERVO/DO_SPRAYER),
                        // NOT via RC7. So we also check autoModeSprayDetected to know spray is active.
                        // Also skip if missionEndPhaseActive — the mission has intentionally stopped spraying.
                        val sprayerIsOn = (currentSprayEnabledForEmpty || (isInAutoMode && autoModeSprayDetected && missionCommandsSprayOn)) && !isInNonSprayMode && !missionEndPhaseActive

                        // ═══ TANK EMPTY DEBUG LOGS ═══
                        LogUtils.d("TankEmpty", "━━━ Tank Empty Check ━━━ mode=$currentMode | rawCA=${b.currentBattery} | flowRate=$flowRateLiterPerMin L/min | gap=${batt2GapMs}ms (~${"%.1f".format(batt2Hz)}Hz) | flowIsLow=$flowIsLow | flowIsHealthy=$flowIsHealthy | lowThreshold=$LOW_FLOW_THRESHOLD_LPM L/min | configValid=$configValid")
                        LogUtils.d("TankEmpty", "  sprayEnabled=$currentSprayEnabledForEmpty | autoSprayDetected=$autoModeSprayDetected | isAutoMode=$isInAutoMode | missionSprayOn=$missionCommandsSprayOn | isInNonSprayMode=$isInNonSprayMode | missionEnd=$missionEndPhaseActive | sprayerIsOn=$sprayerIsOn")
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
                                // Session-scoped proof: survives the IDLE→PRIMING cycle that
                                // happens at every line boundary, so a tank that empties
                                // mid-mission still latches EMPTY on the following line.
                                if (flowIsHealthy && !hasSeenHealthyFlowThisSession) {
                                    hasSeenHealthyFlowThisSession = true
                                    LogUtils.i("TankEmpty", "💧 First healthy flow this spray session — sensor proven, later dry lines will latch TANK_EMPTY")
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
                                        // Either proof works. The per-pass latch covers a tank
                                        // that empties DURING this line; the session latch covers
                                        // one that emptied on a previous line and left this line
                                        // dry from its first tick — the case the per-pass latch
                                        // alone misread as a sensor fault.
                                        if (hasSeenHealthyFlow || hasSeenHealthyFlowThisSession) {
                                            // We saw real flow earlier, then it stopped → tank really is empty.
                                            val proof = if (hasSeenHealthyFlow) "this pass" else "earlier this session"
                                            LogUtils.e("TankEmpty", "🚨 Low flow persisted ${timeInState}ms after healthy flow ($proof, mode=$currentMode) → TANK_EMPTY_LOCKED")
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
                            // The spray session is genuinely over here (the drone has left
                            // AUTO), as opposed to the between-lines sprayer cycling that
                            // resetAutoModeSprayDetection() handles — so the flow evidence
                            // goes with it.
                            resetSprayFlowEvidence("left AUTO mode")
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
                            // Reset the voltage smoothing on arm so a pre-arm or motor-spinup
                            // dip cannot anchor the filters for the whole flight. Driven off
                            // HEARTBEAT rather than GLOBAL_POSITION_INT because the heartbeat
                            // is the one stream that survives link saturation.
                            smoothedVoltage = null
                            smoothedPackVoltage = null
                            LogUtils.d("VoltageDbg", "ARM detected — voltage smoothing reset")
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
                                // Resume continues from the item the drone was flying TOWARDS,
                                // never from one it had already reached.
                                val resumeWaypoint = currentMissionTargetSeq()

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
                                            // Only report to backend if a session was opened (drone took off).
                                            if (wsManager.sessionOpenedForFlight) {
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

                                            // Total ("normal") acres = geodesic plot/field area when known,
                                            // else a swept-path estimate. Sprayed acres = sprayed distance × swath.
                                            val swath = sharedViewModel.currentSwathMeters
                                            val totalAcres = sharedViewModel.currentFieldAreaAcres
                                                ?: GridUtils.sweptAcres(totalDistance.toDouble(), swath)
                                            val totalSprayedAcres = GridUtils.sweptAcres(
                                                (currentState.totalSprayedDistanceMeters ?: 0f).toDouble(), swath
                                            )

                                            wsManager.sendMissionSummary(
                                                totalAcres = totalAcres,
                                                totalSprayUsed = totalSprayUsed,
                                                flyingTimeMinutes = flyingTimeMinutes,
                                                averageSpeed = avgSpeed,
                                                alertsCount = wsManager.missionAlertsCount,
                                                status = "COMPLETED",
                                                totalSprayedAcres = totalSprayedAcres
                                            )
                                            }
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

                            // Get current mission state before updating.
                            // NOTE: deliberately NOT falling back to lastMissionElapsedSec here —
                            // that field carries over from a PREVIOUS flight (it's only cleared
                            // when AUTO newly starts, at line ~2052) and survives arm/disarm
                            // cycles in between. Falling back to it made a purely manual disarm
                            // (never entered AUTO this flight) look like "a mission just flew",
                            // which fed missionActuallyFlown below and wiped a freshly uploaded,
                            // not-yet-flown mission off the FC — the "Auto init failed" /
                            // "Failed to upload mission" bug.
                            val lastElapsed = state.value.missionElapsedSec
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
                                    // MANUAL end/summary is owned by UnifiedFlightTracker — only report here
                                    // for AUTO (prevents the double-send), and only if a backend session was
                                    // opened this flight (drone took off), else we'd enqueue a phantom mission.
                                    if (wasInAutoMode && wsManager.sessionOpenedForFlight) {
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
                                    val swath = sharedViewModel.currentSwathMeters
                                    val totalAcres = sharedViewModel.currentFieldAreaAcres
                                        ?: GridUtils.sweptAcres(totalDistance.toDouble(), swath)
                                    val totalSprayedAcres = GridUtils.sweptAcres(
                                        (currentState.totalSprayedDistanceMeters ?: 0f).toDouble(), swath
                                    )

                                    wsManager.sendMissionSummary(
                                        totalAcres = totalAcres,
                                        totalSprayUsed = totalSprayUsed,
                                        flyingTimeMinutes = flyingTimeMinutes,
                                        averageSpeed = avgSpeed,
                                        alertsCount = wsManager.missionAlertsCount,
                                        status = "COMPLETED",
                                        totalSprayedAcres = totalSprayedAcres
                                    )
                                    }
                                } catch (e: Exception) {
                                    // Ignore WebSocket errors
                                }
                            } else {
                                // No meaningful mission or already handled - just reset state
                                _state.update { it.copy(isMissionActive = false, missionElapsedSec = null) }
                            }

                            // ═══ THE FINISHED MISSION IS LEFT ON THE FC ═══
                            //
                            // This used to wipe the mission off the flight controller here,
                            // automatically, on every disarm that followed a flown mission.
                            // The reasoning was sound (a stray switch to AUTO re-flies the
                            // whole grid) but the behaviour was not the operator's to choose:
                            // it also destroyed a mission they wanted to fly again, it fired
                            // on disarms they did not think of as "the end", and whether the
                            // drone still held a mission depended on internal state
                            // (`missionActuallyFlown`, `isPaused`) that nobody could see.
                            //
                            // Clearing is now explicit: the Clear Mission button on the home
                            // screen, which is only offered while disarmed and asks first.
                            // See SharedViewModel.clearMissionFromFcConfirmed().
                            val missionActuallyFlown = (lastElapsed ?: 0L) > 0L
                            if (missionActuallyFlown && !isPaused) {
                                LogUtils.i("MissionClear", "Mission finished and drone disarmed — mission LEFT on the FC (clear it from the home screen if you want it gone)")
                                sharedViewModel.onMissionLeftOnFcAfterCompletion()
                            }

                            // The flight is over, so the next one must re-prove the flow
                            // sensor before a dry tank counts as empty.
                            resetSprayFlowEvidence("disarmed")

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

                    // Arbitration now lives in resolvePackVoltage(), shared with the
                    // BATTERY_STATUS collector. Keeping a second copy of the guard here was the
                    // bug: this one ran at 4Hz while the unguarded BATTERY_STATUS path wrote
                    // the same field at 10Hz and won most of the time.
                    val resolvedVoltage = resolvePackVoltage()

                    LogUtils.d("VoltageDbg",
                        "SYS_STATUS vRaw=${vBattRaw}V smooth=${vBattSmoothed}V " +
                        "battStat=${battStatusVoltage}V → resolved=${resolvedVoltage}V"
                    )

                    val pct = if (s.batteryRemaining.toInt() == -1) null else s.batteryRemaining.toInt()
                    val SENSOR_3D_GYRO = 1u
                    val present = (s.onboardControlSensorsPresent.value and SENSOR_3D_GYRO) != 0u
                    val enabled = (s.onboardControlSensorsEnabled.value and SENSOR_3D_GYRO) != 0u
                    val healthy = (s.onboardControlSensorsHealth.value and SENSOR_3D_GYRO) != 0u
                    val armable = present && enabled && healthy
                    _state.update { it.copy(
                        // Keep the last good value if this resolve produced nothing — writing
                        // an unconditional null here could blank out a valid BATTERY_STATUS
                        // reading whenever SYS_STATUS reports its 0xFFFF sentinel.
                        voltage = resolvedVoltage ?: it.voltage,
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
                    // Trigger RTL if RC battery is critically low (0% or below) and drone is armed
                    // Fires on <= 1% rather than <= 0%, and only when the reading has been
                    // sustained. RADIO_STATUS.remnoise is the remote RF NOISE FLOOR on a
                    // standard SiK radio — the field is repurposed as a battery level only by
                    // the specific RC hardware this app ships with. A clean link legitimately
                    // reports noise 0, so treating a bare 0 as "battery empty" would command
                    // an unprovoked RTL on any vehicle whose radio reports true noise. A real
                    // battery drains through 5→3→2→1 and stays there; a noise floor that
                    // momentarily touches 0 does not, so the dwell requirement separates them.
                    // Captured non-null so the failsafe block below can use the percentage
                    // without a smart-cast (rcBattSustained hides the null check from the
                    // compiler, unlike the original inline `rcBattPct != null && ...` test).
                    val rcBattCriticalPct = rcBattPct?.takeIf { it <= 1 }
                    if (rcBattCriticalPct != null) {
                        if (rcBattCriticalSince == 0L) {
                            rcBattCriticalSince = System.currentTimeMillis()
                            LogUtils.w("RCBattery", "⏳ RC battery reads ${rcBattCriticalPct}% — confirming over ${RC_BATT_DEBOUNCE_MS}ms before RTL")
                        }
                    } else {
                        if (rcBattCriticalSince != 0L) {
                            LogUtils.i("RCBattery", "RC battery recovered to ${rcBattPct}% — RTL no longer pending")
                        }
                        rcBattCriticalSince = 0L
                    }
                    val rcBattSustained = rcBattCriticalSince != 0L &&
                        (System.currentTimeMillis() - rcBattCriticalSince) >= RC_BATT_DEBOUNCE_MS

                    if (rcBattSustained && rcBattCriticalPct != null &&
                        state.value.armed && !rcBatteryFailsafeTriggered) {

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
                                    sharedViewModel.announceRCBatteryFailsafe(rcBattCriticalPct)
                                    // ...and the popup every other failsafe shows. The RTL
                                    // announcement that follows appends this as its reason.
                                    sharedViewModel.showFailsafePopup("RC Battery Failsafe")
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
                        rcBattCriticalSince = 0L
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

                    // ...but NOT the fences that are armed independently of the GCS's polygon
                    // toggle. The home-centred range cylinder (FENCE_RADIUS + FENCE_TYPE bit 1)
                    // and the altitude fence (FENCE_ALT_MAX + bit 0, armed on every connect by
                    // SharedViewModel.armFcAltitudeFence) are both live regardless of whether a
                    // mission geofence is switched on, so their messages are always genuine.
                    // Suppressing them alongside the polygon's silently swallowed real 300m and
                    // altitude-ceiling breaches — the altitude case matching the SYS_STATUS gate
                    // in startFenceMonitoring, which had the same blind spot.
                    val isAlwaysArmedFenceMessage = message.contains("circle", ignoreCase = true) ||
                            message.contains("radius", ignoreCase = true) ||
                            message.contains("alt", ignoreCase = true)

                    if (isFenceMessage && !isAlwaysArmedFenceMessage &&
                        !sharedViewModel.geofenceEnabled.value) {
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

                    // ═══ FC-DECLARED FAILSAFES → POPUP ═══
                    // The GCS owns Battery / Max Altitude / Max Range / Tank Empty and pops those
                    // up itself. Everything the *flight controller* declares (radio, GCS link,
                    // EKF, terrain, its own battery failsafe, fence breach) reaches us only as
                    // STATUSTEXT — there is no status bit for them anywhere else in this repo —
                    // so they are matched here and given the same popup.
                    //
                    // Recovery and pre-arm chatter is excluded so the popup marks the activation
                    // only: ArduPilot emits "...Failsafe Cleared" / "PreArm: ..." with the same
                    // keywords, and those are not events the pilot needs a red banner for.
                    val isRecoveryOrPreArm = message.contains("clear", ignoreCase = true) ||
                            message.contains("resolved", ignoreCase = true) ||
                            message.contains("PreArm", ignoreCase = true)

                    if (!isRecoveryOrPreArm) {
                        // Fence breach has its own entry point: it is also detectable via
                        // the SYS_STATUS geofence bit (bit 20, MAV_SYS_STATUS_GEOFENCE), and
                        // notifyFenceBreach de-dupes the two paths. The
                        // SYS_STATUS path alone was not enough — it only reports while the FC
                        // holds the fence sensor enabled-and-unhealthy, which short breaches and
                        // some fence types never do, which is why breaches showed no popup.
                        if (isFenceMessage && message.contains("breach", ignoreCase = true)) {
                            // ArduPilot names the fence in the text ("Polygon breached",
                            // "Circle breached", "Max Alt breached"), which the SYS_STATUS
                            // geofence bit cannot tell us — it is one flag for every fence
                            // type. Pass the
                            // name through so the pilot is told WHICH boundary was crossed.
                            val which = when {
                                message.contains("circle", ignoreCase = true) -> "Range"
                                message.contains("polygon", ignoreCase = true) -> "Polygon"
                                message.contains("max alt", ignoreCase = true) -> "Max Altitude"
                                message.contains("min alt", ignoreCase = true) -> "Min Altitude"
                                else -> null
                            }
                            sharedViewModel.notifyFenceBreach("STATUSTEXT", which)
                        } else if (message.contains("failsafe", ignoreCase = true)) {
                            sharedViewModel.showFailsafePopup(failsafePopupLabel(message))
                        }
                    }
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

                    // MISSION_CURRENT is the authoritative navigation target: the item the FC
                    // is flying TO. Note the time so MISSION_ITEM_REACHED below knows it is
                    // only needed as a fallback.
                    lastMissionCurrentAtMs = System.currentTimeMillis()

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

                    // Record what has been completed. This seq is one BEHIND the navigation
                    // target, so it must never be written to currentWaypoint/lastAutoWaypoint
                    // while MISSION_CURRENT is arriving: pause/resume reads those as "where
                    // the mission was interrupted" and would send the drone back to the start
                    // of the line it had already flown half of.
                    _state.update { it.copy(lastReachedWaypoint = reachedSeq) }

                    // Fallback only, for firmware that does not emit MISSION_CURRENT. The
                    // target is the item AFTER the one just reached, so the two sources agree
                    // on what currentWaypoint means.
                    val missionCurrentIsLive =
                        System.currentTimeMillis() - lastMissionCurrentAtMs < MISSION_CURRENT_STALE_MS
                    if (!missionCurrentIsLive) {
                        val targetSeq = reachedSeq + 1
                        _state.update { it.copy(currentWaypoint = targetSeq) }
                        if (currentMode?.equals("Auto", ignoreCase = true) == true) {
                            _state.update { it.copy(lastAutoWaypoint = targetSeq) }
                        }
                        sharedViewModel.updateCurrentWaypoint(targetSeq)
                    }

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

                    // Monitor the sprayer switch channel for spray system status.
                    // The channel is whichever one has RCx_OPTION = 15 (resolved from params on
                    // connect); until that resolves it stays at the historical default of RC7.
                    val sprayChannel = state.value.sprayTelemetry.sprayRcChannel
                    val rc7Value = rcChannelsData.rawForChannel(sprayChannel)
                    val sprayEnabled = rc7Value != null && rc7Value > 1500 // PWM > 1500 = spray ON


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

                    // ── Sprayer switch channel discovery ──
                    // Any RCx_OPTION tells us whether channel x is the sprayer switch (value 15).
                    // Handled before the when() below so it works alongside the BATT* cases.
                    // The startsWith guard keeps the regex off the hot path: a PARAM_REQUEST_LIST
                    // download pushes 1000+ messages through here and this collector must not become
                    // the slow link in the fan-out.
                    if (paramName.startsWith("RC")) RC_OPTION_PARAM_REGEX.matchEntire(paramName)?.let { match ->
                        val channel = match.groupValues[1].toIntOrNull()
                        if (channel != null && channel in 1..MAX_RC_OPTION_CHANNEL) {
                            handleRcOptionParam(channel, paramValue.paramValue.toInt())
                        }
                    }

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
                        // Capability bitmask — the fence mission-protocol path checks this
                        // before attempting an upload. See fenceMissionProtocolUnsupported().
                        val caps = autopilotVersion.capabilities.value
                        if (_autopilotCapabilities.value != caps) {
                            _autopilotCapabilities.value = caps
                            Timber.i(
                                "Capabilities: 0x%08X (MISSION_INT=%b, MISSION_FENCE=%b)",
                                caps.toInt(),
                                caps and MAV_PROTOCOL_CAPABILITY_MISSION_INT != 0u,
                                caps and MAV_PROTOCOL_CAPABILITY_MISSION_FENCE != 0u
                            )
                        }

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


        // A local mission-download helper used to live here. It was dead code — nothing ever
        // called it — and it was a landmine: it opened a MISSION_REQUEST_LIST transfer without
        // holding missionProtocolMutex and without the closing MISSION_ACK, so the first caller
        // to wire it up would have left the FC retransmitting into the next upload.
        // getAllWaypoints() is the one supported mission download.
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
     * Raw PWM of one RC channel from an RC_CHANNELS message, addressed by channel number.
     *
     * [RcChannels] exposes the channels as 18 separate fields with no array accessor, so reading a
     * channel chosen at runtime (the sprayer switch, whose channel comes from RCx_OPTION) needs
     * this mapping. Returns null for a channel outside 1..18.
     */
    private fun RcChannels.rawForChannel(channel: Int): Int? = when (channel) {
        1 -> chan1Raw
        2 -> chan2Raw
        3 -> chan3Raw
        4 -> chan4Raw
        5 -> chan5Raw
        6 -> chan6Raw
        7 -> chan7Raw
        8 -> chan8Raw
        9 -> chan9Raw
        10 -> chan10Raw
        11 -> chan11Raw
        12 -> chan12Raw
        13 -> chan13Raw
        14 -> chan14Raw
        15 -> chan15Raw
        16 -> chan16Raw
        17 -> chan17Raw
        18 -> chan18Raw
        else -> null
    }?.toInt()

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
        // Pre-arm failsafe acknowledgement. The popup raised on connect lists the voltage
        // thresholds and failsafe actions this vehicle is configured with; nothing arms
        // until the pilot has confirmed them. Checked ahead of the armable test so
        // force-arm cannot slip past it either.
        if (sharedViewModel.isPreflightAcknowledgementPending()) {
            sharedViewModel.addNotification(
                Notification("Confirm the failsafe settings popup before arming.", NotificationType.ERROR)
            )
            return false
        }

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
     * Change vehicle mode (ArduPilot: param1=1, param2=customMode).
     * Waits for Heartbeat confirmation, RETRYING the command if it does not take.
     *
     * Every GCS-side failsafe actuates through this one function — battery, altitude
     * ceiling, max range, tank empty, RC battery, obstacle, link loss. It used to send
     * DO_SET_MODE exactly ONCE and then poll for 8s. Because [sendCommand] swallows send
     * exceptions and MAVLink COMMAND_LONG is unacknowledged/unreliable, a single dropped
     * packet on a busy or marginal link meant the failsafe silently did nothing: the caller
     * logged a failure, the one-shot latch was already consumed, and nothing ever retried.
     * With BATT_FS_CRT_ACT forced to 0 there is no FC-side fallback either.
     *
     * So: re-send on a fixed cadence for the whole timeout window. The FC ignores a
     * DO_SET_MODE that asks for the mode it is already in, so re-sending is harmless.
     */
    suspend fun changeMode(customMode: UInt): Boolean {
        val expectedMode = when (customMode) {
            3u -> "Auto"
            0u -> "Stabilize"
            4u -> "Guided"
            5u -> "Loiter"
            6u -> "RTL"
            9u -> "Land"
            16u -> "PosHold"
            17u -> "Brake"
            // The pilot modes below are not modes the GCS commands on its own initiative;
            // they are here so the altitude wall can hand control BACK to whichever mode the
            // pilot was flying when it intervened. Without a name, changeMode refuses the
            // request outright (see below) and the vehicle would stay parked in BRAKE.
            1u -> "Acro"
            2u -> "AltHold"
            7u -> "Circle"
            11u -> "Drift"
            13u -> "Sport"
            else -> "Unknown"
        }

        // "Unknown" would match nothing, so a mode we cannot name could never be confirmed
        // and would burn the full timeout before reporting failure. Fail fast and loudly
        // instead — this is a programming error, not a link problem.
        if (expectedMode == "Unknown") {
            LogUtils.e("ModeChange", "✗ changeMode($customMode): unmapped mode, cannot confirm — refusing to guess")
            return false
        }

        val timeoutMs = 8000L      // real-hardware/Bluetooth allowance
        val resendEveryMs = 1000L  // ~8 attempts across the window
        val pollEveryMs = 200L

        val start = System.currentTimeMillis()
        var lastSendAt = 0L
        var attempts = 0

        while (System.currentTimeMillis() - start < timeoutMs) {
            val now = System.currentTimeMillis()
            if (now - lastSendAt >= resendEveryMs) {
                lastSendAt = now
                attempts++
                sendCommand(
                    MavCmd.DO_SET_MODE,
                    1f,                   // param1: MAV_MODE_FLAG_CUSTOM_MODE_ENABLED (always 1 for ArduPilot)
                    customMode.toFloat(), // param2: custom mode (e.g., 3u for AUTO)
                    0f, 0f, 0f, 0f, 0f
                )
                if (attempts > 1) {
                    LogUtils.w("ModeChange", "↻ $expectedMode not confirmed yet — resending DO_SET_MODE (attempt $attempts)")
                }
            }

            // Confirm against the heartbeat's reported mode.
            if (state.value.mode?.contains(expectedMode, ignoreCase = true) == true) {
                if (attempts > 1) {
                    LogUtils.i("ModeChange", "✓ $expectedMode confirmed after $attempts attempts (${System.currentTimeMillis() - start}ms)")
                }
                return true
            }
            delay(pollEveryMs)
        }

        LogUtils.e("ModeChange", "✗ $expectedMode NOT confirmed after $attempts attempts / ${timeoutMs}ms — vehicle mode is ${state.value.mode}")
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
    ): Boolean = missionProtocolMutex.withLock {
        // Serialized against clearMissionFromFC: both speak the MISSION-type protocol on one
        // link, and their MISSION_ACKs are indistinguishable once in flight. Filtering on
        // missionType keeps FENCE traffic out; only this lock keeps two MISSION operations
        // from consuming each other's acks.
        //
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


            // ═══ Phase 1: Clear the existing mission, and PROVE it is gone ═══
            //
            // The contract is: nothing of Phase 2 happens until the FC has confirmed the
            // clear. Two things that looked like they did that, but didn't:
            //
            //  1. The ack collector was launched and then given `delay(50)` to "ensure it is
            //     running". mavFrame is shareIn(replay = 0), so a subscription that has not
            //     landed yet does not merely arrive late — it misses the frame entirely. A
            //     fast FC answers inside 50 ms, the ack is dropped, and we then burn the full
            //     3 s timeout on an ack that already came and went. The send now goes out
            //     from onSubscription, so it is issued only once the collector is attached —
            //     the same fix setParameter/readParameter already use in this codebase.
            //
            //  2. An ACCEPTED ack is not proof the FC's mission is empty. So after the ack we
            //     read the count back and require 0. This is the actual "only start after
            //     acknowledgment" guarantee: not "an ack arrived", but "the FC says it has no
            //     mission". If the readback says otherwise we retry the clear rather than
            //     uploading onto a mission that is still there.
            var clearSuccess = false
            for (attempt in 1..3) {
                val clearAll = MissionClearAll(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )

                val ack = withTimeoutOrNull(3000L) {
                    mavFrame
                        .onSubscription {
                            val ok = connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearAll)
                            LogUtils.i("MissionUpload", "📤 MISSION_CLEAR_ALL sent (attempt $attempt, sendOk=$ok)")
                        }
                        .filter { it.systemId == fcuSystemId && it.componentId == fcuComponentId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        // Only MISSION acks. Without this filter a FENCE ack — or the ack of a
                        // concurrent mission clear — satisfied this wait. missionType is a v2
                        // extension that decodes to 0 (= MISSION) on v1 frames, so this stays
                        // correct on older links.
                        .filter { it.missionType.value == MavMissionType.MISSION.value }
                        .first()
                }

                if (ack == null) {
                    LogUtils.w("MissionUpload", "⚠️ No clear ACK within 3s (attempt $attempt)")
                    if (attempt < 3) delay(500L)
                    continue
                }

                LogUtils.i("MissionUpload", "📬 Clear ACK: type=${ack.type.entry?.name ?: ack.type.value} opaqueId=${ack.opaqueId} (attempt $attempt)")

                if (ack.type.value != MavMissionResult.MAV_MISSION_ACCEPTED.value) {
                    LogUtils.w("MissionUpload", "⚠️ FC rejected the clear: ${ack.type.entry?.name ?: ack.type.value}")
                    if (attempt < 3) delay(500L)
                    continue
                }

                // Verify the clear actually took effect before trusting it.
                // getMissionCountLocked, NOT getMissionCount: we are inside
                // missionProtocolMutex.withLock here and the mutex is not reentrant, so the
                // public wrapper would deadlock and hang the upload until its 45s timeout.
                val remaining = getMissionCountLocked(timeoutMs = 3000L)
                if (remaining == null) {
                    // Could not read back. The ack was positive, so proceed rather than
                    // blocking an upload on a readback the FC may simply be slow to answer.
                    LogUtils.w("MissionUpload", "⚠️ Could not read back mission count after clear — proceeding on the ACK alone")
                    clearSuccess = true
                    break
                }
                if (remaining == 0) {
                    LogUtils.i("MissionUpload", "✓ Clear verified: FC reports 0 mission items")
                    clearSuccess = true
                    break
                }

                LogUtils.w("MissionUpload", "⚠️ FC still reports $remaining mission items after an ACCEPTED clear (attempt $attempt) — retrying")
                if (attempt < 3) delay(500L)
            }

            if (!clearSuccess) {
                LogUtils.e("MissionUpload", "✗ Could not clear the existing mission after 3 attempts — aborting before upload")
                return false
            }
            LogUtils.i("MissionUpload", "✓ Existing mission cleared — starting upload of ${missionItems.size} items")

            // Phase 2: Upload mission items

            val missionCount = MissionCount(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                count = missionItems.size.toUShort(),
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            )

            val countSendOk = connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
            LogUtils.i("MissionUpload", "📤 MISSION_COUNT=${missionItems.size} sent to sys=$fcuSystemId comp=$fcuComponentId (sendOk=$countSendOk)")

            val finalAckDeferred = CompletableDeferred<Pair<Boolean, String>>()
            // Concurrent: the collector runs on the shared flow and is dispatched across
            // Dispatchers.Default pool threads (the logs show request/send pairs hopping
            // tids), while the watchdog and the tail read size/sorted(). A plain HashSet
            // mutated from several threads can corrupt or miscount.
            val sentSeqs = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            // seq -> when we last sent that item. Drives the retransmit guard in the handler.
            val lastSentAtMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            // Atomic: written on the collector coroutine, read by the resend and watchdog
            // coroutines. Plain vars gave those no guaranteed visibility, so the watchdog
            // could judge a live upload against a stale lastRequestTime and call it stalled.
            val firstRequestReceived = java.util.concurrent.atomic.AtomicBoolean(false)
            val lastRequestTime = AtomicLong(System.currentTimeMillis())

            // Simplified resend logic - only if no response
            val resendJob = AppScope.launch {
                delay(3000L)
                if (!firstRequestReceived.get() && !finalAckDeferred.isCompleted) {
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
                }
            }

            // Unified watchdog - simpler timeout logic
            val watchdogJob = AppScope.launch {
                while (isActive && !finalAckDeferred.isCompleted) {
                    delay(2000)
                    if (firstRequestReceived.get()) {
                        val timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime.get()
                        if (timeSinceLastRequest > 10000L) {
                            LogUtils.e("MissionUpload", "✗ STALLED: ${timeSinceLastRequest}ms since the last request. Sent ${sentSeqs.size}/${missionItems.size} (highest seq=${sentSeqs.maxOrNull() ?: -1}) — FC stopped asking for items")
                            finalAckDeferred.complete(false to "Upload stalled - no FCU response")
                            break
                        }
                    }
                }
            }

            // Main message collector.
            //
            // MUST collect the buffered `mavFrame`, NOT raw `connection.mavFrame`. The
            // library's own connection flow is extraBufferCapacity=128 / DROP_OLDEST, so it
            // silently discards frames whenever a consumer falls behind — see the comment on
            // the `mavFrame` assignment for the full reasoning. This collector suspends while
            // it sends each item, and on a real link carrying the full telemetry stream
            // (10Hz position + 12Hz battery + attitude) those 128 slots overrun during the
            // suspension, dropping the FC's next MISSION_REQUEST_INT. Both sides then wait
            // forever and the upload reports a stall.
            //
            // This is why uploads worked in SITL but failed on the aircraft: an idle SITL
            // link never fills 128 frames. It failed at the SECOND waypoint specifically
            // because the per-item delay below is gated on seq > 0, so the very first
            // in-collector suspension happened right after item 0 was sent.
            val collectorJob = AppScope.launch {
                mavFrame.collect { frame ->
                    if (finalAckDeferred.isCompleted ||
                        frame.systemId != fcuSystemId ||
                        frame.componentId != fcuComponentId) {
                        return@collect
                    }

                    when (val msg = frame.message) {
                        is MissionRequestInt, is MissionRequest -> {
                            val wasFirst = !firstRequestReceived.getAndSet(true)
                            lastRequestTime.set(System.currentTimeMillis())

                            val seq = if (msg is MissionRequestInt) msg.seq.toInt() else (msg as MissionRequest).seq.toInt()
                            val reqKind = if (msg is MissionRequestInt) "MISSION_REQUEST_INT" else "MISSION_REQUEST"
                            if (wasFirst) {
                                LogUtils.i("MissionUpload", "📥 First $reqKind received (seq=$seq) — FC is pulling items")
                            }
                            LogUtils.i("MissionUpload", "📥 $reqKind seq=$seq")

                            if (seq !in 0 until missionItems.size) {
                                LogUtils.e("MissionUpload", "✗ FC requested seq=$seq but mission has ${missionItems.size} items — aborting")
                                finalAckDeferred.complete(false to "Invalid sequence $seq")
                                return@collect
                            }

                            // ═══ DROP STALE RETRANSMITS ═══
                            // THE bug behind MAV_MISSION_INVALID_SEQUENCE. If our reply to a
                            // request is slow, ArduPilot retransmits that request. The old code
                            // answered every request unconditionally, so a retransmit of seq N
                            // that arrived after the FC had already advanced to N+1 sent item N
                            // a second time — out of sequence — and the FC rejected the whole
                            // upload with INVALID_SEQUENCE (13). Observed exactly: seq 0 was
                            // requested at T+0.266 and again at T+0.792 (our first reply took
                            // 526 ms), we sent item 0 twice, and the next ack was error 13.
                            //
                            // A blanket "never send the same seq twice" would be wrong: the FC
                            // legitimately re-requests an item genuinely lost in flight, and
                            // then it is still waiting on that seq and we must answer.
                            //
                            // The two cases look identical in the request itself, so they are
                            // told apart by TIME. ArduPilot retransmits a request roughly half
                            // a second after asking, so a repeat arriving shortly after we
                            // replied is its retransmit crossing our in-flight item — the FC
                            // already has the item and has moved on, and answering again sends
                            // an item it no longer wants: INVALID_SEQUENCE. A repeat arriving
                            // much later means our item never landed and the FC is still stuck
                            // on that seq, so re-sending is exactly right.
                            //
                            // (An earlier attempt compared against the highest seq requested.
                            // That was wrong: the duplicate arrives AT the frontier, not below
                            // it — seq 0 repeated while the frontier was still 0 — so `seq <
                            // frontier` was false and the guard never fired.)
                            val sentAt = lastSentAtMs[seq]
                            if (sentAt != null && System.currentTimeMillis() - sentAt < MISSION_ITEM_RETRANSMIT_WINDOW_MS) {
                                LogUtils.w("MissionUpload", "⏭️ Ignoring duplicate request for seq=$seq (already sent ${System.currentTimeMillis() - sentAt}ms ago — FC's retransmit crossed our reply); answering it would trip INVALID_SEQUENCE")
                                return@collect
                            }
                            if (sentAt != null) {
                                LogUtils.w("MissionUpload", "↻ FC re-requested seq=$seq ${System.currentTimeMillis() - sentAt}ms after our send — treating as a genuine loss, re-sending")
                            }

                            val item = missionItems[seq].copy(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                seq = seq.toUShort()
                            )

                            // Sent inline, and deliberately WITHOUT the old per-item delay.
                            //
                            // The delay was pacing for BT/serial, but it ran ON the collector,
                            // which is precisely what let frames back up and cost us the next
                            // MISSION_REQUEST_INT. Sending from a spawned coroutine instead
                            // would fix the suspension but introduce two worse problems: items
                            // could reach the link out of order, and sentSeqs would record
                            // "sent" before the write actually happened — and the final ACK is
                            // gated on sentSeqs being complete. The buffered flow above is what
                            // actually fixes the drop, so the correct move is to keep the send
                            // inline, in order, and not block the stream at all.
                            val sendOk = connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, item)
                            sentSeqs.add(seq)
                            lastSentAtMs[seq] = System.currentTimeMillis()

                            // Emit progress update to UI
                            onProgress?.invoke(seq + 1, missionItems.size)

                            // Full detail on every item. This is the log that distinguishes
                            // "the FC never asked again" from "the FC rejected what we sent",
                            // and it must show the exact field values the FC is judging —
                            // frame and command are the usual culprits on a 4.6.x rejection.
                            val cmdName = item.command.entry?.name ?: "CMD_${item.command.value}"
                            val frameName = item.frame.entry?.name ?: "FRAME_${item.frame.value}"
                            LogUtils.i(
                                "MissionUpload",
                                "📤 seq=$seq $cmdName frame=$frameName " +
                                    "lat=${item.x / 1e7} lon=${item.y / 1e7} alt=${item.z} " +
                                    "p1=${item.param1} p2=${item.param2} p3=${item.param3} p4=${item.param4} " +
                                    "current=${item.current} autocontinue=${item.autocontinue} sendOk=$sendOk"
                            )
                            if (!sendOk) {
                                LogUtils.e("MissionUpload", "✗ Link REFUSED the write for seq=$seq — item never reached the FC")
                            }
                        }

                        is MissionAck -> {
                            if (!firstRequestReceived.get()) {
                                return@collect
                            }
                            // A FENCE ack must never complete a MISSION upload. The fence
                            // upload runs on its own scope and its ack lands on this same
                            // shared flow.
                            if (msg.missionType.value != MavMissionType.MISSION.value) {
                                LogUtils.i("MissionUpload", "Ignoring ${msg.missionType.entry?.name ?: msg.missionType.value} ack during mission upload")
                                return@collect
                            }

                            val ackType = msg.type.entry?.name ?: msg.type.value.toString()
                            // THE decisive line. Every branch below used to fail silently,
                            // so a rejection was indistinguishable from a timeout in the log.
                            LogUtils.i(
                                "MissionUpload",
                                "📬 MISSION_ACK type=$ackType (${msg.type.value}) missionType=${msg.missionType.entry?.name ?: msg.missionType.value} " +
                                    "after ${sentSeqs.size}/${missionItems.size} items sent (highest seq sent=${sentSeqs.maxOrNull() ?: -1})"
                            )

                            when (msg.type.value) {
                                MavMissionResult.MAV_MISSION_ACCEPTED.value -> {
                                    // Verify all items sent before accepting
                                    if (sentSeqs.size == missionItems.size) {
                                        LogUtils.i("MissionUpload", "✅ Mission accepted — all ${missionItems.size} items uploaded")
                                        finalAckDeferred.complete(true to "")
                                    } else {
                                        LogUtils.w("MissionUpload", "⚠️ FC sent ACCEPTED but only ${sentSeqs.size}/${missionItems.size} items were sent — ignoring, waiting for the rest")
                                    }
                                }
                                MavMissionResult.MAV_MISSION_INVALID_SEQUENCE.value -> {
                                    // Should no longer happen now that stale re-requests are
                                    // dropped. If it does, say what we had sent when it hit —
                                    // that is what identifies which item went out of order.
                                    LogUtils.e("MissionUpload", "✗ INVALID_SEQUENCE after ${sentSeqs.size}/${missionItems.size} (sent=${sentSeqs.sorted()})")
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
                                    LogUtils.e("MissionUpload", "✗ Upload rejected: $ackType (raw ${msg.type.value})")
                                    finalAckDeferred.complete(false to "Unknown error ($ackType)")
                                }
                            }
                        }
                    }
                }
            }

            // Wait for first request (simplified timeout)
            var waitTime = 0L
            while (!firstRequestReceived.get() && !finalAckDeferred.isCompleted && waitTime < 10000L) {
                delay(100)
                waitTime += 100
            }

            if (!firstRequestReceived.get() && !finalAckDeferred.isCompleted) {
                LogUtils.e("MissionUpload", "✗ FC never requested a single item after MISSION_COUNT (waited ${waitTime}ms)")
                finalAckDeferred.complete(false to "No response from FCU")
            }

            // Wait for final result
            val (success, errorMsg) = withTimeoutOrNull(timeoutMs) {
                finalAckDeferred.await()
            } ?: (false to "Upload timeout (${timeoutMs}ms)")

            if (success) {
                LogUtils.i("MissionUpload", "✅ Upload complete: ${missionItems.size} items")
            } else {
                LogUtils.e("MissionUpload", "✗ Upload FAILED: $errorMsg (sent ${sentSeqs.size}/${missionItems.size}, seqs=${sentSeqs.sorted()})")
            }

            collectorJob.cancel()
            resendJob.cancel()
            watchdogJob.cancel()

            if (success) {
                // Progress tracked for the previous mission means nothing against the new one -
                // and a stale (larger) lastReachedWaypoint would push the next resume past the
                // end of this mission. Sequence numbering restarts, so clear it.
                _state.update {
                    it.copy(
                        currentWaypoint = null,
                        lastAutoWaypoint = -1,
                        lastReachedWaypoint = -1
                    )
                }
            }

            return success
        } catch (e: Exception) {
            LogUtils.e("MissionUpload", "✗ Upload threw: ${e.message}", e)
            return false
        } finally {
            // Always reset flag when upload completes (success or failure)
            isMissionUploadInProgress = false
        }
    }

    /**
     * Read the mission back from the FC and log it. Diagnostic only — nothing acts on it.
     *
     * This was a fourth hand-rolled mission download: sequential, 1.5s per item, reading the
     * RAW connection.mavFrame (which drops frames when a consumer falls behind, unlike the
     * buffered mavFrame), taking no lock, and — like every other copy — never closing the
     * transfer its MISSION_REQUEST_LIST opened. It also collected every item into a list and
     * then logged none of them, so it was an expensive no-op that left the FC retransmitting
     * into whatever the pilot did next.
     *
     * It now goes through getAllWaypoints, which is serialized, closes its transfer, and
     * refuses to return a partial mission. And it actually prints what it read.
     */
    suspend fun requestMissionAndLog(timeoutMs: Long = 5000) {
        if (!state.value.fcuDetected) {
            LogUtils.w("MissionReadback", "No FCU detected - nothing to read back")
            return
        }

        val items = getAllWaypoints(timeoutMs)
        if (items == null) {
            LogUtils.e("MissionReadback", "Could not read the mission back from the FC")
            return
        }

        LogUtils.i("MissionReadback", "Mission readback: ${items.size} item(s)")
        items.forEach { item ->
            LogUtils.i(
                "MissionReadback",
                "  seq=${item.seq} cmd=${item.command.entry?.name ?: item.command.value} " +
                    "lat=${item.x / 1e7} lon=${item.y / 1e7} alt=${item.z} " +
                    "p1=${item.param1} p2=${item.param2} p3=${item.param3} p4=${item.param4}"
            )
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
            // Concurrent + frontier guard, for the same reasons as uploadMissionWithAck: the
            // collector hops pool threads, and answering a stale re-request after the FC has
            // advanced trips MAV_MISSION_INVALID_SEQUENCE. Fences are small enough that this
            // has not bitten yet, but the defect is identical.
            val sentSeqs = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val lastSentAtMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()

            // Buffered `mavFrame`, not raw `connection.mavFrame` — same DROP_OLDEST hazard
            // that stalled mission upload at the second item. See uploadMissionWithAck.
            val job = AppScope.launch {
                mavFrame.collect { frame ->
                    when (val msg = frame.message) {
                        is MissionRequest, is MissionRequestInt -> {
                            val seq = if (msg is MissionRequestInt) msg.seq.toInt() else (msg as MissionRequest).seq.toInt()

                            if (seq !in 0 until fenceItems.size) {
                                finalAckDeferred.complete(false to "Invalid fence sequence $seq")
                                return@collect
                            }

                            // Drop retransmits that crossed our reply — see the guard in
                            // uploadMissionWithAck for why this is time-based.
                            val sentAt = lastSentAtMs[seq]
                            if (sentAt != null && System.currentTimeMillis() - sentAt < MISSION_ITEM_RETRANSMIT_WINDOW_MS) {
                                Timber.w("Geofence: ⏭️ Ignoring duplicate request for seq=$seq (sent ${System.currentTimeMillis() - sentAt}ms ago)")
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
                            lastSentAtMs[seq] = System.currentTimeMillis()
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

        // Serialize against uploadMissionWithAck. A download and an upload interleaving on the
        // same link is exactly what corrupted the resume handshake: the FC runs ONE mission
        // transfer state machine, so two overlapping transfers steal each other's acks.
        return missionProtocolMutex.withLock { getAllWaypointsLocked(timeoutMs) }
    }

    /** Body of [getAllWaypoints]. Callers must already hold [missionProtocolMutex]. */
    private suspend fun getAllWaypointsLocked(timeoutMs: Long): List<MissionItemInt>? {
        try {
            // Concurrent, and keyed by seq. The collector coroutine writes these while the
            // request loop below reads them to work out what is still missing — the old
            // sequential loop only ever awaited one deferred at a time and never inspected the
            // list, so plain collections were safe then and are not now. Keying by seq also
            // subsumes the old distinctBy: the FC re-sends an item whenever a request looks
            // unanswered, and duplicates used to reach consumers that reason positionally.
            val receivedItems = java.util.concurrent.ConcurrentHashMap<Int, MissionItemInt>()
            val expectedCountDeferred = CompletableDeferred<Int?>()
            val perSeqMap = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

            // Buffered flow — see uploadMissionWithAck.
            val job = AppScope.launch {
                mavFrame.collect { frame ->
                    if (frame.systemId != fcuSystemId || frame.componentId != fcuComponentId) {
                        return@collect
                    }
                    when (val msg = frame.message) {
                        is MissionCount -> {
                            if (msg.missionType.value == MavMissionType.MISSION.value &&
                                !expectedCountDeferred.isCompleted
                            ) {
                                expectedCountDeferred.complete(msg.count.toInt())
                            }
                        }
                        is MissionItemInt -> {
                            // Keep FENCE/RALLY items out of the mission list. missionType is a
                            // v2 extension that decodes to 0 (= MISSION) on v1 frames, so this
                            // stays correct on older links.
                            if (msg.missionType.value != MavMissionType.MISSION.value) {
                                return@collect
                            }
                            val seq = msg.seq.toInt()
                            receivedItems.putIfAbsent(seq, msg)
                            perSeqMap[seq]?.let { d -> if (!d.isCompleted) d.complete(Unit) }
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


            // ═══ Pipelined item fetch ═══
            //
            // This used to be strictly sequential: request seq, await it (up to 2s), then
            // request seq+1. That serialises one full link round trip per item, so a 150-item
            // spray grid cost 150 RTTs — 15-20s on a link that is also carrying video — and a
            // single dropped item cost a flat 2s before the retry. That is the bulk of the
            // 30-40s a resume took.
            //
            // Instead, keep a window of requests in flight and let the FC stream answers back.
            // The collector already records every MISSION_ITEM_INT it sees regardless of what
            // we asked for, so responses may arrive in any order. Then re-request only the
            // gaps, which on a healthy link is none.
            //
            // The correctness guarantee is UNCHANGED and does not depend on this loop: the
            // completeness check below still requires every seq in 0 until expectedCount and
            // discards a partial download outright. A partial mission must never reach
            // filterWaypointsForResume, which reasons by sequence number — that is what put
            // the drone on the wrong line.
            suspend fun requestMissing(rounds: Int) {
                for (round in 1..rounds) {
                    val missing = (0 until expectedCount).filter { it !in receivedItems.keys }
                    if (missing.isEmpty()) return

                    if (round > 1) {
                        LogUtils.w("MissionDownload", "⚠️ Re-requesting ${missing.size} missing item(s) (round $round/$rounds): ${missing.take(20)}")
                    }

                    // Send in a windowed burst rather than one-at-a-time. The window bounds
                    // how much the FC has to buffer and how much we lose if the link hiccups,
                    // while still collapsing N round trips into roughly one per window.
                    for (chunk in missing.chunked(MISSION_DOWNLOAD_WINDOW)) {
                        for (seq in chunk) {
                            if (!perSeqMap.containsKey(seq)) {
                                perSeqMap[seq] = CompletableDeferred()
                            }
                            try {
                                val reqItem = MissionRequestInt(
                                    targetSystem = fcuSystemId,
                                    targetComponent = fcuComponentId,
                                    seq = seq.toUShort(),
                                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                                )
                                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, reqItem)
                            } catch (e: Exception) {
                                LogUtils.w("MissionDownload", "⚠️ Could not send MISSION_REQUEST_INT for seq=$seq: ${e.message}")
                            }
                            // A small gap between writes, NOT a wait for the reply. This paces
                            // the burst so a serial/BT link's TX buffer does not overrun (which
                            // would drop requests and force another round), while still keeping
                            // every request of the window in flight together.
                            delay(MISSION_DOWNLOAD_REQUEST_SPACING_MS)
                        }

                        // Let the window's answers land before judging what is still missing.
                        //
                        // Skip any seq already in receivedItems rather than awaiting its
                        // deferred. An item can arrive between the putIfAbsent above and this
                        // wait — the FC streams answers while we are still sending the rest of
                        // the burst — and awaiting a deferred whose completing frame has
                        // already gone past would block for the whole window timeout on an
                        // item we actually have.
                        withTimeoutOrNull(MISSION_DOWNLOAD_WINDOW_TIMEOUT_MS) {
                            chunk.forEach { seq ->
                                if (seq !in receivedItems.keys) perSeqMap[seq]?.await()
                            }
                        }
                        chunk.forEach { perSeqMap.remove(it) }
                    }
                }
            }

            requestMissing(rounds = 3)

            // Small delay to ensure all MAVLink messages are processed before canceling collector
            delay(200)

            // Close the download.
            //
            // The MAVLink mission protocol ends a download with an ACK from the GCS. Without it
            // ArduPilot keeps its mission-transfer state machine OPEN and goes on retransmitting
            // items. The next thing a resume does is uploadMissionWithAck, whose first move is a
            // MISSION_CLEAR_ALL that waits 3s for a MISSION-type ack and then reads the count
            // back expecting 0 — and that window was being poisoned by this download's leftover
            // traffic. The clear failed, the upload aborted, and the pilot got "Resume point
            // failed. Do not switch to auto." while the FC still held the ORIGINAL mission, so
            // AUTO carried on from the FC's own index at the far end of the half-flown line.
            try {
                val downloadAck = MissionAck(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    type = MavEnumValue.of(MavMissionResult.MAV_MISSION_ACCEPTED),
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )
                val ackSent = connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, downloadAck)
                LogUtils.i("MissionDownload", "📤 MISSION_ACK sent to close the download (sendOk=$ackSent)")
            } catch (e: Exception) {
                LogUtils.w("MissionDownload", "⚠️ Could not send the closing MISSION_ACK: ${e.message}")
            }

            job.cancel()

            // Sort by sequence. Deduplication is inherent now that the collector stores items
            // in a map keyed by seq (putIfAbsent), which matters because the FC re-sends an
            // item whenever a MISSION_REQUEST_INT looks unanswered — often, on a loaded link,
            // and more so now that requests go out in bursts. Duplicate seqs reaching a
            // consumer that reasons positionally (filterWaypointsForResume,
            // resequenceWaypoints) build a mission with repeated legs in it.
            val sortedItems = receivedItems.values.sortedBy { it.seq.toInt() }

            // A partial mission must never reach filterWaypointsForResume. It looks like a valid
            // mission — distinctBy has already closed the gaps — but every consumer reasons by
            // sequence number, so a missing item around the resume point silently produces a
            // resumed mission for the wrong segment. Callers retry, and on a second failure
            // report "could not read the mission back from the drone", which is the truth.
            if (sortedItems.size != expectedCount) {
                val missing = (0 until expectedCount).toSet() - sortedItems.map { it.seq.toInt() }.toSet()
                LogUtils.e(
                    "MissionDownload",
                    "✗ Incomplete mission download: got ${sortedItems.size}/$expectedCount items " +
                        "(missing seqs=${missing.sorted()}) — discarding rather than returning a partial mission"
                )
                return null
            }

            LogUtils.i("MissionDownload", "✓ Mission download complete: $expectedCount items")
            return sortedItems

        } catch (e: Exception) {
            LogUtils.e("MissionDownload", "✗ Mission download threw: ${e.message}", e)
            return null
        }
    }

    /**
     * How many items the FC currently holds, WITHOUT downloading them.
     *
     * One round trip, used by the resume path to decide whether the mission on the FC is
     * still the one the GCS uploaded and cached. A full [getAllWaypoints] costs a request and
     * a reply per item, so on a large spray grid this probe is the difference between ~0.2s
     * and 15-20s.
     *
     * Kept as a named alias of [getMissionCount] because the call sites read better for it —
     * it says why the count is being asked for. It is the same single implementation; there is
     * deliberately no second one.
     */
    suspend fun getMissionCountForCacheCheck(timeoutMs: Long = 3000): Int? =
        getMissionCount(timeoutMs)

    /**
     * The one mission-count implementation. Callers must hold [missionProtocolMutex].
     *
     * MISSION_REQUEST_LIST OPENS ArduPilot's mission-transfer state machine — the FC will sit
     * there expecting to be pulled through the whole mission. [getAllWaypointsLocked] closes
     * it with a MISSION_ACK for exactly that reason, and so must this: an unclosed transfer
     * leaves the FC retransmitting items, which then poisons the MISSION_CLEAR_ALL window of
     * the very next upload. That is the failure that reported "resume point failed" while the
     * FC still held the ORIGINAL mission.
     */
    private suspend fun getMissionCountLocked(timeoutMs: Long): Int? {
        try {
            val countDeferred = CompletableDeferred<Int?>()

            // Buffered flow — see uploadMissionWithAck.
            val job = AppScope.launch {
                mavFrame.collect { frame ->
                    if (frame.systemId != fcuSystemId || frame.componentId != fcuComponentId) {
                        return@collect
                    }
                    val msg = frame.message
                    if (msg is MissionCount && msg.missionType.value == MavMissionType.MISSION.value) {
                        if (!countDeferred.isCompleted) countDeferred.complete(msg.count.toInt())
                    }
                }
            }

            val count = try {
                val req = MissionRequestList(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, req)
                withTimeoutOrNull(timeoutMs) { countDeferred.await() }
            } catch (e: Exception) {
                LogUtils.w("MissionCount", "⚠️ Could not send MISSION_REQUEST_LIST: ${e.message}")
                null
            }

            // Close the transfer this probe opened, whether or not the count arrived.
            try {
                val closingAck = MissionAck(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    type = MavEnumValue.of(MavMissionResult.MAV_MISSION_ACCEPTED),
                    missionType = MavEnumValue.of(MavMissionType.MISSION)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, closingAck)
            } catch (e: Exception) {
                LogUtils.w("MissionCount", "⚠️ Could not send the closing MISSION_ACK: ${e.message}")
            }

            job.cancel()
            return count

        } catch (e: Exception) {
            LogUtils.e("MissionCount", "✗ Mission count probe threw: ${e.message}", e)
            return null
        }
    }

    /**
     * How many MISSION items the FC currently holds.
     *
     * This used to have its own implementation, and that implementation was the single most
     * damaging thing in the mission stack. It sent MISSION_REQUEST_LIST — which OPENS
     * ArduPilot's mission-transfer state machine — with no missionType, accepted a
     * MISSION_COUNT from any source (so a FENCE or RALLY count answered it), took no lock, and
     * never sent the closing MISSION_ACK. The FC was left mid-transfer, retransmitting.
     *
     * uploadMissionWithAck calls this from its clear-verify step, so EVERY upload opened a
     * transfer and abandoned it in the instant before sending MISSION_COUNT. That is the
     * corruption the resume work kept rediscovering from a different direction: an upload that
     * aborts while the FC still holds the previous mission, so AUTO carries on from the FC's
     * own index and the drone flies the wrong line.
     *
     * There is now ONE implementation, [getMissionCountLocked], which holds
     * [missionProtocolMutex] and closes what it opens.
     *
     * Callers that already hold the mutex must call [getMissionCountLocked] directly — this
     * one would deadlock, as the mutex is not reentrant.
     */
    suspend fun getMissionCount(timeoutMs: Long = 5000): Int? {
        if (!state.value.fcuDetected) {
            return null
        }
        return missionProtocolMutex.withLock { getMissionCountLocked(timeoutMs) }
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
     * Start a RESUMED mission — deliberately NOT [startMission].
     *
     * [startMission] is the cold-start path: pre-arm checks, arm-with-retries, then
     * MAV_CMD_MISSION_START with param1 = 0. That last command means "begin at the first
     * item", and it is what broke resume. The sequence was:
     *
     *   1. The resumed mission is uploaded and DO_SET_MISSION_CURRENT(1) points the FC at
     *      the inserted transit waypoint.
     *   2. The pilot flicks to AUTO; onModeChangedToAuto calls startMission().
     *   3. MISSION_START resets the index straight back to 0, discarding step 1.
     *
     * The drone then flew the resumed mission from its start instead of from the resume
     * waypoint — landing it two or three waypoints back up the grid, re-flying ground it had
     * already covered. The arm/pre-arm work in startMission is wrong here too: the vehicle is
     * already armed and airborne, so pre-arm checks are meaningless and a failed "arm" would
     * abort a resume that needed no arming.
     *
     * This path therefore: verifies the vehicle is actually flying, re-asserts the mission
     * index, and only then puts it in AUTO. Setting the index BEFORE the mode change matters
     * — done the other way round the FC starts running item 0 in the window between the two.
     *
     * On the usual trigger the pilot has ALREADY flicked to AUTO (that mode change is what
     * calls this), so the changeMode below is a confirmed no-op and re-asserting the index is
     * the whole job. The FC may therefore fly a few seconds towards the wrong item before the
     * index lands; that is unavoidable once the pilot owns the switch, and is why
     * processResumePoint also sets the index at upload time — this is the backstop, not the
     * only attempt. resumeMission is written to work from either direction so the
     * GCS-initiated paths can share it.
     *
     * @param resumeSeq mission index the resumed mission must start from (the inserted
     *                  transit waypoint, normally 1)
     * @return true only if the index was confirmed AND the vehicle is in AUTO
     */
    suspend fun resumeMission(resumeSeq: Int = 1): Boolean {
        if (!state.value.fcuDetected) {
            sharedViewModel.addNotification(
                Notification("Cannot resume mission - FCU not detected", NotificationType.ERROR)
            )
            return false
        }

        // A resume only makes sense on an armed vehicle. If it somehow disarmed while parked,
        // fall back to the cold-start path rather than half-starting a mission in mid-air.
        if (!state.value.armed) {
            LogUtils.w("ResumeMission", "Vehicle is disarmed — falling back to the full start path")
            return startMission()
        }

        // Step 1: re-assert the mission index. This is the whole point of the function, so a
        // failure here aborts rather than handing the FC to AUTO with an unknown index.
        val indexSet = setCurrentWaypoint(resumeSeq)
        if (!indexSet) {
            LogUtils.e("ResumeMission", "✗ Could not set mission index to $resumeSeq — NOT switching to AUTO")
            sharedViewModel.addNotification(
                Notification(
                    "⛔ Resume aborted — the drone did not accept the resume point. It is still holding position.",
                    NotificationType.ERROR
                )
            )
            return false
        }

        // Step 2: AUTO. changeMode retries and confirms against the heartbeat, and returns
        // true immediately if the pilot already put the vehicle in AUTO by hand.
        val modeChanged = try {
            changeMode(MavMode.AUTO)
        } catch (e: Exception) {
            LogUtils.e("ResumeMission", "✗ Exception switching to AUTO", e)
            false
        }

        if (!modeChanged) {
            sharedViewModel.addNotification(
                Notification("Failed to switch to AUTO mode for resume.", NotificationType.ERROR)
            )
            return false
        }

        LogUtils.i("ResumeMission", "✅ Resume started from mission index $resumeSeq")
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
            // Capabilities describe the vehicle we are leaving, not the next one. Dropping
            // them back to "unknown" keeps the fence check permissive on reconnect rather
            // than judging a new FC by the old one's bitmask.
            _autopilotCapabilities.value = null
            // Mark this as an intentional disconnect to prevent auto-reconnect
            intentionalDisconnect = true
            // Reset voltage smoothing and the learned cell count. Cleared here rather than on
            // disarm because a battery swap requires a power cycle, which drops the link —
            // clearing on arm instead would throw away the guard's evidence every flight.
            smoothedVoltage = null
            smoothedPackVoltage = null
            battStatusVoltage = null
            battStatusCellCount = 0
            battStatusIsPackTotal = false
            expectedCellCount = 0
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
        // Always show the actual reading at 1 decimal place. Previously anything
        // below 1 m/s was clamped to a flat "0 m/s", which made ground speed
        // appear to drop to zero during turns (when the reading legitimately
        // dips below 1 m/s but is still non-zero).
        return String.format("%.1f m/s", speed)
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
     * Set the current mission waypoint, and confirm the flight controller took it.
     *
     * This used to fire DO_SET_MISSION_CURRENT once, sleep 500ms and return true
     * unconditionally. COMMAND_LONG is unacknowledged at the transport level, so a packet
     * dropped on a busy link reported success while the FC kept its old mission index — the
     * resume then continued from wherever the FC happened to be, several waypoints back up
     * the grid, with nothing in the logs to say why.
     *
     * So: re-send on a cadence for the whole window and confirm, exactly as [changeMode]
     * does. Confirmation accepts EITHER a COMMAND_ACK for DO_SET_MISSION_CURRENT or
     * MISSION_CURRENT reporting the requested seq — older ArduPilot builds update the index
     * and emit MISSION_CURRENT without ever ACKing the command. Re-sending is harmless: the
     * command is idempotent.
     *
     * @param seq Waypoint sequence number to resume from
     * @return true only if the FC was observed to accept the new index
     */
    suspend fun setCurrentWaypoint(seq: Int): Boolean {
        val timeoutMs = 4000L
        val resendEveryMs = 700L

        // The MISSION_CURRENT fallback below must only trust a report that arrived AFTER our
        // first send. uploadMissionWithAck nulls currentWaypoint, but on the paths that do not
        // re-upload, the FC's index can already read `seq` from the previous mission — and
        // accepting that would confirm a command we had not yet sent.
        val missionCurrentAtStart = lastMissionCurrentAtMs

        // Subscribe BEFORE the first send, or a fast ACK arrives while we are not listening.
        // AppScope, not the caller's scope: `scope` in this class is a local inside start().
        val ackSeen = CompletableDeferred<Boolean>()
        val ackJob = AppScope.launch {
            try {
                commandAck
                    .filter { it.command.value == MavCmd.DO_SET_MISSION_CURRENT.value }
                    .first()
                    .let { ack ->
                        val accepted = ack.result.entry == MavResult.ACCEPTED
                        if (!accepted) {
                            LogUtils.e(
                                "ResumeMission",
                                "✗ DO_SET_MISSION_CURRENT($seq) rejected by FC: ${ack.result.entry?.name ?: ack.result.value}"
                            )
                        }
                        ackSeen.complete(accepted)
                    }
            } catch (_: Throwable) {
                // Scope cancelled or flow terminated; the polling loop below still decides.
            }
        }

        try {
            val start = System.currentTimeMillis()
            var lastSendAt = 0L
            var attempts = 0

            while (System.currentTimeMillis() - start < timeoutMs) {
                val now = System.currentTimeMillis()
                if (now - lastSendAt >= resendEveryMs) {
                    lastSendAt = now
                    attempts++
                    try {
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
                        if (attempts > 1) {
                            LogUtils.w("ResumeMission", "↻ DO_SET_MISSION_CURRENT($seq) not confirmed — resending (attempt $attempts)")
                        }
                    } catch (e: Exception) {
                        LogUtils.e("ResumeMission", "✗ Failed to send DO_SET_MISSION_CURRENT($seq)", e)
                    }
                }

                if (ackSeen.isCompleted) {
                    val accepted = ackSeen.await()
                    if (accepted) {
                        LogUtils.i("ResumeMission", "✓ FC acknowledged mission index = $seq (attempt $attempts)")
                    }
                    return accepted
                }

                // Fallback confirmation for firmware that does not ACK this command. Only a
                // MISSION_CURRENT newer than our first send counts — see missionCurrentAtStart.
                //
                // Compare against the freshly-read seq rather than a captured one: uploadMissionWithAck
                // nulls currentWaypoint on success, and this function is called immediately after an
                // upload, so the value here starts out null and is only repopulated by the very
                // MISSION_CURRENT we are waiting for.
                if (lastMissionCurrentAtMs > missionCurrentAtStart && state.value.currentWaypoint == seq) {
                    LogUtils.i("ResumeMission", "✓ MISSION_CURRENT confirms mission index = $seq (attempt $attempts)")
                    return true
                }

                delay(150)
            }

            LogUtils.e("ResumeMission", "✗ FC never confirmed mission index = $seq after ${attempts} attempts (${timeoutMs}ms)")
            return false
        } finally {
            ackJob.cancel()
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

        // Notify only on the transition into "configured correctly", not on every param refresh.
        if (parametersReceived) {
            if (configurationValid) {
                if (!sprayConfigValidNotified) {
                    sprayConfigValidNotified = true
                    sharedViewModel.addNotification(
                        Notification(
                            "Spray system configured correctly",
                            NotificationType.SUCCESS
                        )
                    )
                }
            } else {
                // Configuration went invalid — re-arm so a later fix notifies again.
                sprayConfigValidNotified = false
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

        // Drop any RCx_OPTION values cached from a previously connected airframe so the sprayer
        // channel is resolved fresh from the values this FC is about to send back.
        rcOptionValues.clear()


        try {
            val parametersToRequest = listOf(
                "BATT2_MONITOR",      // Sensor type (should be 11 for Fuel Flow)
                "BATT2_CAPACITY",     // Tank capacity in mAh
                "BATT2_AMP_PERVLT",   // Flow sensor calibration factor
                "BATT2_CURR_PIN",     // Flow sensor pin configuration
                "BATT3_CAPACITY",     // Tank capacity for level sensor
                "BATT3_VOLT_PIN",     // Level sensor pin configuration
                "BATT3_VOLT_MULT"     // Level sensor voltage multiplier (important for calibration!)
            ) + (1..MAX_RC_OPTION_CHANNEL).map { "RC${it}_OPTION" }
            // RCx_OPTION tells us which RC channel is the sprayer switch (option 15). Without it we
            // would monitor RC7 on every airframe and miss tank-empty entirely on a setup that puts
            // spray enable on another channel.

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
     * The mission item the drone is flying TOWARDS — the sequence a resume has to continue from.
     *
     * Resuming from an item the drone has already reached is what sends it back to the start of
     * the line it was half way along, so this never returns a sequence at or below
     * [TelemetryState.lastReachedWaypoint].
     */
    fun currentMissionTargetSeq(): Int {
        val snapshot = state.value
        val known = snapshot.lastAutoWaypoint.takeIf { it > 0 }
            ?: snapshot.currentWaypoint

        // No MISSION_CURRENT seen yet. uploadMissionWithAck clears these counters on every
        // successful upload, so this is the normal state immediately after a resume has been
        // uploaded — and the old silent `?: 1` made a SECOND pause in that window resume from
        // index 1, the previous resume's transit waypoint, which sits several waypoints back up
        // the grid. That guess IS the reported bug, so we no longer make it: return the
        // MISSION_PROGRESS_UNKNOWN sentinel and let the caller refuse to prepare a resume. The
        // drone is holding in LOITER either way, and a pilot told "not ready" can wait a second
        // for MISSION_CURRENT; a pilot given a wrong seq flies the wrong line.
        if (known == null) {
            LogUtils.w(
                "ResumeMission",
                "⚠️ No mission progress known from the FC (lastAutoWaypoint=${snapshot.lastAutoWaypoint}, " +
                    "currentWaypoint=${snapshot.currentWaypoint}) — refusing to guess a resume target."
            )
            return MISSION_PROGRESS_UNKNOWN
        }

        return maxOf(known, snapshot.lastReachedWaypoint + 1).coerceAtLeast(1)
    }

    /**
     * True for mission items that carry a real target position (and therefore a usable
     * altitude). DO_* items are stored with x/y/z all zero, so they must never be used as the
     * altitude reference for an inserted waypoint — that would put it at ground level.
     */
    private fun MissionItemInt.hasPosition(): Boolean = x != 0 || y != 0

    /**
     * Filter waypoints for resume mission (mid-flight).
     *
     * For MID-FLIGHT RESUME (drone already flying):
     * - Keep HOME (waypoint 0)
     * - Skip ALL waypoints BEFORE resume point (including TAKEOFF - drone is already in the air)
     * - Keep ALL waypoints from resume point onward
     *
     * Result structure: HOME (seq 0) + Resume Location WP (seq 1) + [DO_SPRAYER(1)] + Remaining waypoints
     * NO TAKEOFF included since drone is already flying!
     *
     * The resume location waypoint is inserted at the drone's exact GPS position where it was paused.
     * This ensures the drone resumes from its actual paused position, not from the next waypoint.
     * It stays at seq 1 because every caller targets it with setCurrentWaypoint(1).
     *
     * When spray is being restored, the DO_SPRAYER(1) sits immediately AFTER that resume waypoint,
     * so the FC only runs it once the waypoint has been reached — the pump stays off for the
     * transit leg. Callers must NOT additionally fire an immediate spray-on command; doing that
     * is what made the drone spray all the way back to the resume point.
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

        // Altitude for the inserted resume waypoint: the next real waypoint the drone will fly
        // to. Looked up by "first positional item at or after the resume seq" rather than by
        // exact seq, because the resume seq can legitimately land on a DO_SPRAYER /
        // DO_CHANGE_SPEED item — those store z = 0, which would drop the resume waypoint to
        // ground level. Falls back to the last known positional item, then to 50 m.
        val targetWaypoint = allWaypoints
            .filter { it.seq.toInt() >= resumeWaypointSeq && it.hasPosition() && it.z > 0f }
            .minByOrNull { it.seq.toInt() }
            ?: allWaypoints.filter { it.hasPosition() && it.z > 0f }.maxByOrNull { it.seq.toInt() }
        val effectiveAltitude = resumeAltitude ?: targetWaypoint?.z ?: 50f

        for (waypoint in allWaypoints) {
            val seq = waypoint.seq.toInt()
            val cmdId = waypoint.command.value

            // Always keep HOME (waypoint 0)
            if (seq == 0) {
                val cmdName = waypoint.command.entry?.name ?: "CMD_$cmdId"
                filtered.add(waypoint)

                // Builds a DO_SPRAYER mission item. placeholderSeq is arbitrary —
                // resequenceWaypoints renumbers everything before upload.
                fun sprayerItem(placeholderSeq: UShort, on: Boolean) = MissionItemInt(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    seq = placeholderSeq,
                    frame = MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_RELATIVE_ALT_INT),
                    command = MavEnumValue.fromValue(MAV_CMD_DO_SPRAYER),
                    current = 0u,
                    autocontinue = 1u,
                    param1 = if (on) 1f else 0f, // 1 = START spraying, 0 = STOP
                    param2 = 0f,
                    param3 = 0f,
                    param4 = 0f,
                    x = 0,
                    y = 0,
                    z = 0f
                )

                // NOTE: no DO_SPRAYER(0) is embedded ahead of the resume waypoint on purpose —
                // that would shift the transit waypoint off seq 1, which every caller targets
                // with setCurrentWaypoint(1). The pump is instead shut off out-of-band, just
                // before AUTO is engaged (SharedViewModel.ensureSprayerOffForTransit).

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

                // Insert DO_SPRAYER(1) command right AFTER the resume waypoint, so the FC only
                // executes it once that waypoint has been REACHED — i.e. spray comes back on at
                // the resume point, not the instant AUTO is engaged.
                if (shouldInsertSprayerOn) {
                    filtered.add(sprayerItem(placeholderSeq = 2u, on = true))
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

        // Nothing of the ORIGINAL mission survived the resume point.
        //
        // The list is not empty in this case — it still holds HOME, the inserted resume waypoint
        // and possibly a DO_SPRAYER — so every caller's `isEmpty()` guard waves it through. The FC
        // then accepts a two-item mission, the drone flies to the resume point and the mission is
        // "complete": no error anywhere, and the rest of the grid silently never gets flown.
        //
        // This is reachable whenever resumeWaypointSeq overshoots the mission, which is exactly
        // what a stale lastReachedWaypoint does (currentMissionTargetSeq takes lastReachedWaypoint + 1).
        // Counted on the SOURCE list, not on `filtered`: the inserted resume waypoint is built
        // with seq = 1u, so counting the output would mistake it for a surviving mission item
        // whenever resumeWaypointSeq <= 1 and mask the very case this guard exists to catch.
        val keptFromOriginal = allWaypoints.count { it.seq.toInt() != 0 && it.seq.toInt() >= resumeWaypointSeq }
        if (keptFromOriginal == 0) {
            LogUtils.e(
                "ResumeMission",
                "✗ No mission items at or after seq=$resumeWaypointSeq (mission has ${allWaypoints.size} items, " +
                    "last seq=${allWaypoints.maxOfOrNull { it.seq.toInt() } ?: -1}) — refusing to build a resume that flies nowhere"
            )
            return emptyList()
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

                // Step 0: Does this vehicle take fence geometry as mission items at all?
                // Only refuses when the FC has actually told us it cannot (see
                // fenceMissionProtocolUnsupported) — an absent AUTOPILOT_VERSION does not block.
                fenceMissionProtocolUnsupported()?.let { missing ->
                    Timber.e("Geofence: ❌ Vehicle does not support $missing - cannot upload fence geometry")
                    sharedViewModel.addNotification(
                        Notification(
                            message = "❌ This drone does not support fence uploads ($missing missing)",
                            type = NotificationType.ERROR
                        )
                    )
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

                val appliedTypeBits = configureFenceParameters(configuration)

                if (appliedTypeBits == null) {
                    Timber.e("Geofence: ❌ Failed at Step 3: configureFenceParameters could not write the fence parameters")
                    return@withContext false
                }
                Timber.i("Geofence: Step 3 OK - fence parameters configured, FENCE_TYPE=$appliedTypeBits")

                // Step 4: Enable fence
                //
                // A plain write of 1 is not enough when the fence is ALREADY enabled — which
                // it now usually is, because SharedViewModel.armFcAltitudeFence() turns the
                // altitude fence on at connect. AC_Fence rebuilds its live _enabled_fences
                // mask only when FENCE_ENABLE changes VALUE, so the polygon bit that
                // configureFenceParameters just ORed into FENCE_TYPE would show up in the
                // parameter (and in every UI reading it back) while never being evaluated.
                // Bounce through 0 so the mask is rebuilt.
                //
                // Only on a disarmed aircraft: dropping the fence for 300 ms mid-flight is
                // not a trade worth making, and fence uploads are a ground-planning action.
                delay(500)
                if (readFenceParameter("FENCE_ENABLE")?.toInt() == 1 && !state.value.armed) {
                    Timber.i("Geofence: fence already enabled - bouncing FENCE_ENABLE so the new FENCE_TYPE goes live")
                    setFenceParameter("FENCE_ENABLE", 0f)
                    delay(300)
                }
                val enabled = enableFence(true)

                if (!enabled) {
                    Timber.e("Geofence: ❌ Failed at Step 4: enableFence(true) returned false")
                    return@withContext false
                }
                Timber.i("Geofence: Step 4 OK - fence enabled")

                // Step 5: Verify the fence is actually live by reading the parameters back.
                //
                // The result is now RETURNED rather than just logged. It used to be
                // discarded — uploadGeofence returned an unconditional `true` — so a fence
                // that was uploaded but not enforcing reported success all the way up to the
                // pilot, which is the failure mode this whole path exists to prevent.
                delay(300)
                val verified = verifyFenceEnabled(appliedTypeBits)
                if (!verified) {
                    Timber.e("Geofence: ❌ Failed at Step 5: the fence did not verify as live on the FC")
                    sharedViewModel.addNotification(
                        Notification(
                            message = "⚠️ Geofence uploaded but the drone did not confirm it is enforcing — check FENCE_ENABLE / FENCE_TYPE",
                            type = NotificationType.WARNING
                        )
                    )
                }
                Timber.i("Geofence: Step 5 - verifyFenceEnabled returned $verified")

                verified

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
                                // Vertex count of THIS polygon — NOT the total item count of
                                // the upload. ArduPilot (and Mission Planner's parser) use it
                                // as the "close the polygon" signal, so with two polygons in
                                // one fence each one's vertices carry its own count.
                                param1 = zone.points.size.toFloat(),
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
                            Timber.e("Geofence: ❌ Fence upload rejected, MISSION_ACK type=${ack.type.value}")
                            continuation.resume(false)
                        }
                    } else {
                        // No MISSION_ACK. This used to return true on the theory that "some
                        // FCs don't ACK fence uploads" — which let the caller go on to set
                        // FENCE_ENABLE=1 over geometry nothing had confirmed. ArduPilot 4.6.3
                        // always ACKs, so a missing ACK means the upload did not land.
                        //
                        // Rather than fail outright on one dropped packet, cross-check the
                        // vehicle's own stored item count. FENCE_TOTAL matching what we sent
                        // is stronger evidence than an ACK; anything else is a failure.
                        val storedCount = readFenceParameter("FENCE_TOTAL")?.toInt()
                        when {
                            storedCount == items.size -> {
                                Timber.w("Geofence: ⚠️ No MISSION_ACK, but FENCE_TOTAL=$storedCount matches the ${items.size} items sent - accepting")
                                continuation.resume(true)
                            }
                            storedCount == null -> {
                                Timber.e("Geofence: ❌ No MISSION_ACK and FENCE_TOTAL unreadable - treating the upload as FAILED")
                                continuation.resume(false)
                            }
                            else -> {
                                Timber.e("Geofence: ❌ No MISSION_ACK and FENCE_TOTAL=$storedCount != ${items.size} items sent - upload FAILED")
                                continuation.resume(false)
                            }
                        }
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
     * Configure fence parameters on the flight controller.
     *
     * FENCE_ACTION and FENCE_MARGIN are deliberately NOT written here. They are
     * operator-owned: DGCA requires the vehicle to act on the parameters actually set
     * on it, and this function used to stamp FENCE_ACTION=4 (Brake or Land) and
     * FENCE_MARGIN=3 onto the FC on every single fence upload, silently reverting
     * whatever the operator had configured. The GCS now reads both and reports them
     * (see SharedViewModel.syncFenceParametersOnConnect / getCurrentFenceAction).
     */
    private suspend fun configureFenceParameters(config: FenceConfiguration): Int? {
        try {
            // Set the home-centred cylinder radius (FENCE_RADIUS) before enabling its bit,
            // so the fence never goes live at a stale radius.
            config.circleRadiusMeters?.let { radius ->
                if (!setFenceParameter("FENCE_RADIUS", radius)) {
                    return null
                }
                delay(200)
            }

            // Set fence type bitmask. READ-MODIFY-WRITE, never recomputed from zero:
            // this used to start at 0 and OR in only the bits the current config implied,
            // which meant every mission-fence upload cleared the home-cylinder bit and
            // silently disarmed the 300m range fence.
            val currentType = readFenceParameter("FENCE_TYPE")?.toInt() ?: 0
            var fenceType = currentType
            config.zones.forEach { zone ->
                when (zone) {
                    is FenceZone.Polygon -> fenceType = fenceType or FENCE_TYPE_POLYGON
                    is FenceZone.Circle -> fenceType = fenceType or FENCE_TYPE_CIRCLE
                    else -> {}
                }
            }
            if (config.circleRadiusMeters != null || config.armCircleFence) {
                fenceType = fenceType or FENCE_TYPE_CIRCLE
            }
            if (config.altitudeMax != null || config.altitudeMin != null) {
                fenceType = fenceType or FENCE_TYPE_ALT_MAX
            }

            if (fenceType != currentType) {
                if (!setFenceParameter("FENCE_TYPE", fenceType.toFloat())) {
                    return null
                }
                delay(200)
            } else {
                Timber.i("Geofence: FENCE_TYPE already $currentType, no write needed")
            }

            // Set altitude limits if provided
            if (config.altitudeMax != null) {
                if (!setFenceParameter("FENCE_ALT_MAX", config.altitudeMax)) {
                    return null
                }
                delay(200)

                // RTL_ALT must stay under the ceiling we just wrote. If it does not, the
                // breach action begins by climbing to RTL_ALT — straight back through the
                // fence it is recovering from — and the vehicle can loop: breach, RTL,
                // climb, breach. syncRtlAltOnConnect enforces this at connect and from
                // Options; doing it here too means no upload path can leave the pair
                // inconsistent, including a caller that passes its own altitudeMax.
                clampRtlAltBelowFenceCeiling(config.altitudeMax)
                delay(200)
            }

            if (config.altitudeMin != null) {
                if (!setFenceParameter("FENCE_ALT_MIN", config.altitudeMin)) {
                    return null
                }
                delay(200)
            }

            return fenceType

        } catch (e: Exception) {
            Timber.e(e, "Geofence: failed configuring fence parameters")
            return null
        }
    }

    /**
     * Lower RTL_ALT if it sits at or above [fenceAltMaxM], the FC's altitude fence ceiling.
     *
     * Only ever LOWERED, never raised: an operator who deliberately set a conservative RTL
     * altitude keeps it. Units matter — RTL_ALT is CENTIMETRES, and RTL_ALT=0 means "return
     * at the current altitude", so the written value is floored rather than allowed to reach
     * zero. Same policy as [SharedViewModel.syncRtlAltOnConnect], whose constants it borrows
     * so there is one definition of "how far under the ceiling RTL belongs".
     *
     * Measured from FENCE_ALT_MAX, which sits FC_ALT_FENCE_SAFETY_OFFSET_M below the pilot's
     * nominal ceiling — so the target here is ~1 m lower than syncRtlAltOnConnect computes
     * from the ceiling itself. That is deliberate, not drift: RTL has to clear the fence that
     * is actually armed, and because both paths only ever LOWER RTL_ALT the difference
     * settles once rather than ratcheting down on every upload.
     *
     * Best-effort: a failure here is logged and surfaced but does not fail the fence upload.
     * A fence that is live with a too-high RTL_ALT is still better than no fence, and
     * SharedViewModel.handleAltitudeFailsafe brakes before RTL as the in-flight backstop.
     */
    private suspend fun clampRtlAltBelowFenceCeiling(fenceAltMaxM: Float): Boolean {
        val headroomM = sharedViewModel.RTL_ALT_BELOW_CEILING_M
        val floorM = sharedViewModel.RTL_ALT_MIN_M
        val desiredM = (fenceAltMaxM - headroomM).coerceAtLeast(floorM)

        if (desiredM >= fenceAltMaxM) {
            // Ceiling at or under the floor: no RTL altitude is both legal and sane.
            Timber.e("Geofence: FENCE_ALT_MAX=${fenceAltMaxM}m is too low for a safe RTL_ALT (floor ${floorM}m) - RTL will breach it")
            return false
        }

        val currentCm = readFenceParameter("RTL_ALT", timeoutMs = 4000L)
        if (currentCm == null) {
            Timber.w("Geofence: could not read RTL_ALT - cannot confirm RTL stays under the ${fenceAltMaxM}m fence ceiling")
            return false
        }

        val currentM = currentCm / 100f
        if (currentM <= desiredM) {
            Timber.i("Geofence: RTL_ALT=${currentM}m already clears the ${fenceAltMaxM}m fence ceiling - left as configured")
            return true
        }

        return if (setFenceParameter("RTL_ALT", desiredM * 100f)) {
            Timber.i("Geofence: ✓ RTL_ALT lowered ${currentM}m -> ${desiredM}m (${headroomM}m under the ${fenceAltMaxM}m fence ceiling)")
            // Said out loud, not just logged: an operator who set RTL_ALT deliberately and
            // then sees the drone return lower needs to know it was overridden on purpose.
            sharedViewModel.addNotification(
                Notification(
                    message = "RTL altitude lowered to ${desiredM.toInt()} m so an RTL stays under the ${fenceAltMaxM.toInt()} m fence ceiling",
                    type = NotificationType.INFO
                )
            )
            true
        } else {
            Timber.e("Geofence: ✗ Failed to lower RTL_ALT - an RTL may climb through the ${fenceAltMaxM}m fence ceiling")
            sharedViewModel.addNotification(
                Notification(
                    message = "⚠️ Could not lower RTL_ALT below the fence ceiling — an RTL may exceed it",
                    type = NotificationType.WARNING
                )
            )
            false
        }
    }

    /**
     * Enable or disable geofence on flight controller
     */
    suspend fun enableFence(enable: Boolean): Boolean {
        return setFenceParameter("FENCE_ENABLE", if (enable) 1.0f else 0.0f)
    }

    /**
     * Confirm the fence really is live, by reading back the parameters the whole thing rests on.
     *
     * Rewritten because the old version could not actually verify anything:
     *
     *  1. It matched PARAM_VALUE.param_id with `== "FENCE_ENABLE"`, WITHOUT stripping
     *     MAVLink's fixed-width NUL padding — the exact bug [setFenceParameter] documents.
     *     A perfectly good reply arrives as "FENCE_ENABLE" followed by NUL padding out
     *     to 16 bytes, and was discarded — so a live fence could verify as dead.
     *  2. It then requested FENCE_TYPE and FENCE_ACTION and threw both replies away, which
     *     was pure parameter traffic competing with the fence upload it was verifying.
     *  3. FENCE_ENABLE=1 alone is not enough. AC_Fence rebuilds its live _enabled_fences
     *     mask only when FENCE_ENABLE changes VALUE, so the bit that matters is whether
     *     FENCE_TYPE actually carries the fence we just uploaded.
     *
     * @param expectedTypeBits FENCE_TYPE bits this upload requires (e.g. the polygon bit).
     *   Verified as a subset, not an equality: other fences the operator armed stay set.
     */
    private suspend fun verifyFenceEnabled(expectedTypeBits: Int): Boolean {
        val enable = readFenceParameter("FENCE_ENABLE")?.toInt()
        if (enable != 1) {
            Timber.e("Geofence: ✗ verify failed - FENCE_ENABLE reads back as ${enable ?: "unreadable"}")
            return false
        }

        val type = readFenceParameter("FENCE_TYPE")?.toInt()
        if (type == null) {
            Timber.e("Geofence: ✗ verify failed - FENCE_TYPE unreadable")
            return false
        }
        if (type and expectedTypeBits != expectedTypeBits) {
            Timber.e("Geofence: ✗ verify failed - FENCE_TYPE=$type is missing bits from expected $expectedTypeBits")
            return false
        }

        Timber.i("Geofence: ✓ verified - FENCE_ENABLE=1, FENCE_TYPE=$type covers $expectedTypeBits")
        return true
    }

    /**
     * Set a fence-related parameter on the flight controller, retrying if it is not
     * confirmed.
     *
     * Two things made this fragile enough to lose a whole geofence:
     *
     *  1. It gave up after ONE unacknowledged PARAM_SET, and [configureFenceParameters]
     *     aborts the entire fence on the first failure. FENCE_TYPE — the parameter that
     *     actually switches the polygon on — is third in that chain, so a single dropped
     *     ack left the fence uploaded but NOT enforcing, and the drone flew straight
     *     through it.
     *  2. The ack was matched on paramId WITHOUT stripping MAVLink's NUL padding.
     *     PARAM_VALUE.param_id is a fixed 16-byte field, so "FENCE_TYPE" arrives as
     *     "FENCE_TYPE\u0000...". readParameter() strips this; here it did not, so a valid
     *     ack could be ignored. Any concurrent parameter activity (the connect-time
     *     failsafe reads run on the same 2s timescale as the fence upload debounce) makes
     *     both failure modes far more likely, since PARAM_VALUE traffic is interleaved.
     */
    suspend fun setFenceParameter(paramId: String, value: Float): Boolean {
        val attempts = 3
        repeat(attempts) { attempt ->
            val confirmed = trySetFenceParameterOnce(paramId, value)
            if (confirmed) {
                if (attempt > 0) {
                    Timber.i("Geofence: ✓ $paramId=$value confirmed on attempt ${attempt + 1}")
                }
                return true
            }
            Timber.w("Geofence: ↻ $paramId=$value not confirmed (attempt ${attempt + 1}/$attempts)")
            delay(300)
        }
        Timber.e("Geofence: ✗ $paramId=$value FAILED after $attempts attempts")
        return false
    }

    /**
     * Read one fence parameter from the FC, or null if it does not answer.
     *
     * Same subscribe-before-send discipline as [trySetFenceParameterOnce], and the same
     * NUL-padding strip: PARAM_VALUE.param_id is a fixed 16-byte field, so "FENCE_TYPE"
     * arrives as "FENCE_TYPE\u0000...".
     */
    suspend fun readFenceParameter(paramId: String, timeoutMs: Long = 3000L): Float? {
        return try {
            val valueDeferred = AppScope.async {
                withTimeoutOrNull(timeoutMs) {
                    mavFrame
                        .filter { it.systemId == fcuSystemId }
                        .map { it.message }
                        .filterIsInstance<ParamValue>()
                        .first { it.paramId.trim().replace("\u0000", "") == paramId }
                }
            }
            // Give the collector a moment to attach before the request goes out.
            delay(50)

            val request = ParamRequestRead(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                paramId = paramId,
                paramIndex = -1
            )
            connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, request)

            val result = valueDeferred.await()?.paramValue
            if (result == null) {
                Timber.w("Geofence: no response reading $paramId")
            } else {
                Timber.i("Geofence: read $paramId = $result")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Geofence: failed reading $paramId")
            null
        }
    }

    /** One PARAM_SET + ack round trip for [setFenceParameter]. */
    private suspend fun trySetFenceParameterOnce(paramId: String, value: Float): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val job = AppScope.launch {
                try {
                    // Start listening BEFORE the write, so a fast ack cannot arrive in the
                    // gap between sending and subscribing.
                    val ackDeferred = async {
                        withTimeoutOrNull(3000) {
                            mavFrame
                                .filter { it.systemId == fcuSystemId }
                                .map { it.message }
                                .filterIsInstance<ParamValue>()
                                // Strip MAVLink's fixed-width NUL padding before comparing.
                                .first { it.paramId.trim().replace("\u0000", "") == paramId }
                        }
                    }
                    // Give the collector a moment to attach.
                    delay(50)

                    val paramSet = ParamSet(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        paramId = paramId,
                        paramValue = value,
                        paramType = MavEnumValue.of(MavParamType.REAL32)
                    )
                    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, paramSet)

                    val ack = ackDeferred.await()
                    continuation.resume(ack != null && ack.paramValue == value)
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
                fenceMissionProtocolUnsupported()?.let { missing ->
                    Timber.e("Geofence: ❌ Vehicle does not support $missing - cannot download fence geometry")
                    return@withContext emptyList()
                }

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
     * Request fence items from FC using the mission protocol (FENCE type).
     *
     * Serialized on [missionProtocolMutex] like every other mission-protocol operation. The FC
     * runs ONE mission-transfer state machine and it is shared across MISSION, FENCE and RALLY
     * types — a fence download overlapping a mission upload means the two steal each other's
     * replies. This ran unlocked, so a geofence read triggered from the map while a mission was
     * uploading did exactly that.
     */
    private suspend fun requestFenceItemsFromFcu(): List<MissionItemInt> =
        missionProtocolMutex.withLock { requestFenceItemsFromFcuLocked() }

    /**
     * Body of [requestFenceItemsFromFcu]. Callers must hold [missionProtocolMutex].
     *
     * Closes the transfer it opens with a FENCE MISSION_ACK on every exit path, including the
     * empty and failed ones. MISSION_REQUEST_LIST opens ArduPilot's transfer state machine and
     * an unclosed one leaves the FC retransmitting into whatever comes next — see
     * [getMissionCountLocked] for the same reasoning on the MISSION side.
     */
    private suspend fun requestFenceItemsFromFcuLocked(): List<MissionItemInt> {
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

                    // Request each item.
                    //
                    // Each seq is retried, and a seq that never arrives fails the WHOLE
                    // download. A missing vertex used to be silently skipped, which is worse
                    // than it sounds: the polygon's param1 vertex count then never matches the
                    // vertices actually collected, so convertMissionItemsToFence drops the
                    // polygon and a partial download reads as "this drone has no fence".
                    // Failing loudly means the caller knows it does not have the truth.
                    val perSeqAttempts = 3
                    for (seq in 0 until count) {
                        var item: MissionItemInt? = null
                        repeat(perSeqAttempts) { attempt ->
                            if (item != null) return@repeat

                            // Subscribe before requesting so a fast reply cannot land in the
                            // gap, matching the discipline in readFenceParameter.
                            val itemDeferred = async {
                                withTimeoutOrNull(2000) {
                                    mavFrame
                                        .filter { it.systemId == fcuSystemId }
                                        .map { it.message }
                                        .filterIsInstance<MissionItemInt>()
                                        .first { it.seq.toInt() == seq && it.missionType.value == MavMissionType.FENCE.value }
                                }
                            }
                            delay(50)

                            connection.trySendUnsignedV2(
                                gcsSystemId, gcsComponentId,
                                MissionRequestInt(
                                    targetSystem = fcuSystemId,
                                    targetComponent = fcuComponentId,
                                    seq = seq.toUShort(),
                                    missionType = MavEnumValue.of(MavMissionType.FENCE)
                                )
                            )

                            item = itemDeferred.await()
                            if (item == null) {
                                Timber.w("Geofence: ↻ no fence item for seq=$seq (attempt ${attempt + 1}/$perSeqAttempts)")
                            }
                        }

                        val received = item
                        if (received == null) {
                            Timber.e("Geofence: ✗ fence download FAILED - seq=$seq never arrived after $perSeqAttempts attempts (expected $count items)")
                            continuation.resume(emptyList())
                            return@launch
                        }
                        receivedItems.add(received)
                        delay(50)
                    }

                    Timber.i("Geofence: ✓ downloaded all $count fence items")
                    continuation.resume(receivedItems)

                } catch (e: Exception) {
                    continuation.resume(emptyList())
                } finally {
                    // Close the transfer the MISSION_REQUEST_LIST above opened, on EVERY path:
                    // the zero-count early return, a seq that never arrived, a thrown
                    // exception, and success. Whichever way this download ends, the FC must not
                    // be left mid-transfer.
                    try {
                        connection.trySendUnsignedV2(
                            gcsSystemId, gcsComponentId,
                            MissionAck(
                                targetSystem = fcuSystemId,
                                targetComponent = fcuComponentId,
                                type = MavEnumValue.of(MavMissionResult.MAV_MISSION_ACCEPTED),
                                missionType = MavEnumValue.of(MavMissionType.FENCE)
                            )
                        )
                    } catch (e: Exception) {
                        Timber.w("Geofence: ⚠️ could not send the closing FENCE MISSION_ACK: ${e.message}")
                    }
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
    /**
     * Collapse an ArduPilot failsafe STATUSTEXT into the short label the popup shows.
     *
     * The raw wording varies by firmware version and battery index ("Radio Failsafe",
     * "Battery 1 low failsafe", "EKF Failsafe"), so the known families map to stable labels that
     * read the same as the GCS-side popups. Anything unrecognised keeps the FC's own wording
     * (truncated) rather than being dropped — an unknown failsafe still deserves the banner.
     */
    private fun failsafePopupLabel(message: String): String = when {
        message.contains("radio failsafe", ignoreCase = true) ||
                message.contains("rc failsafe", ignoreCase = true) -> "RC Failsafe"
        message.contains("gcs failsafe", ignoreCase = true) -> "GCS Link Failsafe"
        message.contains("ekf", ignoreCase = true) -> "EKF Failsafe"
        message.contains("terrain", ignoreCase = true) -> "Terrain Failsafe"
        message.contains("batt", ignoreCase = true) -> "Battery Failsafe"
        else -> message.trim().take(60)
    }

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
                    // Check the fence bit in onboard_control_sensors_health.
                    //
                    // MAV_SYS_STATUS_GEOFENCE is bit 20 (0x100000), per MAVLink common.xml.
                    // This previously masked 0x100, which is MAV_SYS_STATUS_SENSOR_LASER_POSITION
                    // — the rangefinder/proximity health bit. On an airframe with a proximity
                    // sensor that reports unhealthy (e.g. "PreArm: PRX1: No Data"), that read as
                    // a permanent fence breach: the popup fired the moment the fence went live
                    // at arming, while the FC never actually breached anything or changed mode.
                    val fenceHealthy = (sysStatus.onboardControlSensorsHealth.value and MAV_SYS_STATUS_GEOFENCE) != 0u
                    val fenceEnabled = (sysStatus.onboardControlSensorsEnabled.value and MAV_SYS_STATUS_GEOFENCE) != 0u
                    val fenceBreached = fenceEnabled && !fenceHealthy

                    // IMPORTANT: Only report a breach if a fence we actually armed is active.
                    // The FC may hold stale fence data from a previous session, and reporting
                    // on that produces false "approaching polygon fence" warnings and arm
                    // blocks — which is why this gate exists at all.
                    //
                    // But the gate used to be the POLYGON toggle alone, and that stopped
                    // being correct when SharedViewModel.armFcAltitudeFence() started arming
                    // the FC's altitude fence on EVERY connect, independently of the polygon.
                    // MAV_SYS_STATUS_GEOFENCE is one flag for all fence types, so gating on
                    // the polygon discarded every genuine altitude-ceiling breach the FC
                    // reported whenever the mission geofence happened to be switched off —
                    // i.e. on most flights. The FC fence is the layer that actually holds the
                    // ceiling at 400Hz, so its breach must reach the pilot.
                    //
                    // So: report when the GCS is flying a polygon, OR when FENCE_TYPE says
                    // the FC is holding an altitude/circle fence. A stale polygon with no
                    // other fence bits set is still suppressed, which is what this guard was
                    // for in the first place.
                    val gcsGeofenceEnabled = sharedViewModel.geofenceEnabled.value
                    val fcNonPolygonFenceArmed =
                        (sharedViewModel.fenceTypeBits.value ?: 0) and
                            (FENCE_TYPE_ALT_MAX or FENCE_TYPE_CIRCLE or FENCE_TYPE_ALT_MIN) != 0
                    if (!gcsGeofenceEnabled && !fcNonPolygonFenceArmed) {
                        // No fence we armed is active - ensure we report clean state
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

                    // Notify only on new breach detection. Routed through notifyFenceBreach so
                    // the popup/TTS/notification match every other failsafe and are de-duped
                    // against the STATUSTEXT path and SharedViewModel's own fenceStatus collector.
                    if (fenceBreached && !previousStatus.breached) {
                        withContext(Dispatchers.Main) {
                            sharedViewModel.notifyFenceBreach("SYS_STATUS/repo")
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
    /**
     * Remove the polygon/inclusion fence items from the FC WITHOUT disabling the fence.
     *
     * [clearGeofenceFromFC] also sets FENCE_ENABLE=0, which is right when the pilot turns the
     * geofence off, but wrong for clearing a stale polygon at connect: that would disarm the
     * home-centred range cylinder along with it. Here we clear only the stored fence items,
     * leaving FENCE_ENABLE and FENCE_TYPE as configured.
     */
    suspend fun clearFenceItemsOnly(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val clearCmd = MissionClearAll(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    missionType = MavEnumValue.of(MavMissionType.FENCE)
                )
                connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, clearCmd)

                val ack = withTimeoutOrNull(5000) {
                    mavFrame
                        .filter { it.systemId == fcuSystemId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        .first { it.missionType.value == MavMissionType.FENCE.value }
                }

                val ok = ack?.type?.value == MavMissionResult.MAV_MISSION_ACCEPTED.value
                if (ok) {
                    // The polygon is gone, so any polygon breach it was reporting is stale.
                    _fenceStatus.value = FenceStatus()
                }
                ok
            } catch (e: Exception) {
                false
            }
        }
    }

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
                    delay(200)

                    // Take the POLYGON fence down, not every fence.
                    //
                    // This used to be a flat enableFence(false), which was correct back when
                    // the polygon was the only reason FENCE_ENABLE was ever 1. It is not any
                    // more: SharedViewModel.armFcAltitudeFence() enables the fence at connect
                    // so the FC enforces the altitude ceiling itself, and clearing a mission
                    // polygon must not quietly hand that ceiling back to the telemetry link.
                    //
                    // So: clear bit 2 and keep the rest. FENCE_ENABLE only goes to 0 when the
                    // polygon was genuinely the last fence in the mask — or when FENCE_TYPE
                    // cannot be read, where the old behaviour is the safe thing to fall back
                    // to rather than leaving an enabled fence in an unknown state.
                    val currentType = readFenceParameter("FENCE_TYPE")?.toInt()
                    if (currentType == null) {
                        Timber.w("Geofence: could not read FENCE_TYPE while clearing - disabling the fence outright")
                        enableFence(false)
                    } else {
                        val remaining = currentType and FENCE_TYPE_POLYGON.inv()
                        if (remaining != currentType) {
                            setFenceParameter("FENCE_TYPE", remaining.toFloat())
                            delay(200)
                        }
                        if (remaining == 0) {
                            Timber.i("Geofence: polygon was the last fence in the mask - disabling FENCE_ENABLE")
                            enableFence(false)
                        } else {
                            // Bounce so AC_Fence rebuilds its live mask without the polygon.
                            Timber.i("Geofence: polygon bit cleared, FENCE_TYPE=$remaining stays armed")
                            if (remaining != currentType && !state.value.armed) {
                                enableFence(false)
                                delay(300)
                                enableFence(true)
                            }
                        }
                    }

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
            missionProtocolMutex.withLock {
            try {
                val clearAckDeferred = CompletableDeferred<Boolean>()

                val clearCollectorJob = AppScope.launch {
                    mavFrame
                        .filter { it.systemId == fcuSystemId && it.componentId == fcuComponentId }
                        .map { it.message }
                        .filterIsInstance<MissionAck>()
                        // Same reasoning as uploadMissionWithAck's clear: match only MISSION
                        // acks, or a fence ack (or another clear's ack) satisfies this one.
                        .filter { it.missionType.value == MavMissionType.MISSION.value }
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

                return@withLock ackReceived
            } catch (e: Exception) {
                false
            }
            }
        }
    }
}
