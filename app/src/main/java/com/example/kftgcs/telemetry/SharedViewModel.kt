@file:Suppress("unused")
package com.example.kftgcs.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divpundir.mavlink.adapters.coroutines.trySendUnsignedV2
import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.Statustext
import com.example.kftgcs.BuildConfig
import com.example.kftgcs.GCSApplication
import com.example.kftgcs.usersettings.UserSettingsManager
import com.example.kftgcs.telemetry.TelemetryState
//import com.example.aerogcsclone.Telemetry.connections.BluetoothConnectionProvider
//import com.example.aerogcsclone.Telemetry.connections.MavConnectionProvider
import com.example.kftgcs.telemetry.connections.BluetoothConnectionProvider
import com.example.kftgcs.telemetry.connections.MavConnectionProvider
import com.example.kftgcs.telemetry.connections.TcpConnectionProvider
import com.example.kftgcs.telemetry.connections.UdpConnectionProvider
import com.example.kftgcs.telemetry.connections.UsbSerialConnectionProvider
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.example.kftgcs.utils.GeofenceUtils
import com.example.kftgcs.utils.LogUtils
import com.example.kftgcs.utils.TextToSpeechManager
import com.example.kftgcs.fence.FenceAction
import com.example.kftgcs.fence.FenceConfiguration
import com.example.kftgcs.fence.FenceStatus
import com.example.kftgcs.fence.FenceZone
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.example.kftgcs.grid.GridGenerator
import com.example.kftgcs.grid.GridUtils
import com.example.kftgcs.videotracking.CameraTrackingState
import com.example.kftgcs.videotracking.TrackingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import com.example.kftgcs.api.ApiService

enum class ConnectionType {
    TCP, UDP, BLUETOOTH, USB
}

/** Private broadcast action used to receive the USB device permission result. */
private const val ACTION_USB_PERMISSION = "com.example.kftgcs.USB_PERMISSION"

data class MissionUploadProgress(
    val stage: String,
    val currentItem: Int,
    val totalItems: Int,
    val message: String
) {
    val percentage: Int
        get() = if (totalItems > 0) ((currentItem.toFloat() / totalItems) * 100).toInt() else 0
}

@SuppressLint("MissingPermission")
data class PairedDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice
) {
    constructor(device: BluetoothDevice) : this(
        name = device.name ?: "Unknown Device",
        address = device.address,
        device = device
    )
}

/** A USB serial device discovered by usb-serial-for-android, analogous to [PairedDevice]. */
data class UsbDeviceInfo(
    val name: String,
    val device: UsbDevice
) {
    /** Stable identifier for selection comparisons (device path is unique while attached). */
    val id: String get() = device.deviceName
}

/** Drives the ARMING_CHECK safety-check dialog. See [SharedViewModel.armingCheckState]. */
enum class ArmingCheckState { HIDDEN, PROMPT_WRITE, WRITING, WRITE_FAILED, PROMPT_REBOOT }

/**
 * The failsafe configuration the pilot must acknowledge on every connection before the
 * drone can be armed. See [SharedViewModel.preflightFailsafeSummary].
 */
data class PreflightFailsafeSummary(
    /** Low voltage level 1 (warning) threshold in volts — mirrors BATT_LOW_VOLT. */
    val lowVoltLevel1: Float,
    /** Low voltage level 2 (critical) threshold in volts — mirrors BATT_CRT_VOLT. */
    val criticalVoltage: Float,
    /** Tank empty action; reads "HOVER" or "HOVER / RTL (Manual / Auto)" when they differ. */
    val tankEmptyAction: String,
    /** Action the GCS takes at the critical voltage: HOVER / RTL / LAND. */
    val batteryFailsafeAction: String,
    /**
     * What the FC does on a fence breach, from its FENCE_ACTION parameter — NOT a GCS
     * default. DGCA requires the drone to act on the parameters actually set on it, so the
     * pilot acknowledges the real configured behaviour here. "Unknown" if the read failed.
     */
    val fenceAction: String,
    /** Home-centred fence radius (FENCE_RADIUS), pre-formatted, e.g. "300 m". */
    val fenceRadius: String,
    /** Fence margin (FENCE_MARGIN), pre-formatted, e.g. "2.0 m". */
    val fenceMargin: String
)

class SharedViewModel : ViewModel() {

    // TextToSpeech manager for voice announcements
    private var ttsManager: TextToSpeechManager? = null

    // Telemetry state - must be declared before init block that uses it
    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    // Proximity-radar colour thresholds (metres). Hybrid: seeded from the vehicle's AVOID_DIST_MAX /
    // AVOID_MARGIN on connect (see seedRadarThresholdsFromVehicle), overridable by the pilot in
    // SensorSettingsScreen. Initialised from local storage so the UI has values before any connection.
    private val _radarThresholds = MutableStateFlow(loadRadarThresholdsFromPrefs())
    val radarThresholds: StateFlow<com.example.kftgcs.telemetry.RadarThresholds> =
        _radarThresholds.asStateFlow()

    // Battery failsafe tracking
    private var lastVoltageAlertLevel1Time = 0L
    private var lastVoltageAlertLevel2Time = 0L  // Track last Level 2 alert time for repeated warnings
    private var voltageAlertLevel2Triggered = false  // Once true, action won't fire again until disarm→arm
    private val VOLTAGE_ALERT_INTERVAL_MS = 3000L // Alert every 3 seconds for Level 1
    private val VOLTAGE_CRITICAL_INTERVAL_MS = 5000L // Re-alert every 5 seconds for Level 2 if still critical

    // ── Critical-voltage debounce ───────────────────────────────────────────────────────
    // The level-2 action is an irreversible mode change, but it used to fire on a SINGLE
    // frame at/below the threshold. A partial cell-sum or a motor-spinup sag is one or two
    // frames, so one bad reading commanded an RTL on a healthy 43-44V pack. Require the
    // condition to hold for BOTH a wall-clock duration and a sample count: the duration
    // alone is satisfiable by two frames either side of a link stall, and the count alone
    // is satisfiable by a burst. 1.5s costs ~0.05V of real discharge on a 12S pack.
    // The TTS warning still fires on the first sample — only the action waits.
    private val VOLTAGE_CRITICAL_DEBOUNCE_MS = 1500L
    private val VOLTAGE_CRITICAL_MIN_SAMPLES = 5
    private var voltageCriticalSince = 0L      // 0 = not currently below threshold
    private var voltageCriticalSamples = 0
    private var voltageCriticalWarned = false
    /**
     * True while the critical-battery action is genuinely in progress (voltage still low).
     * Distinct from [voltageAlertLevel2Triggered], which is the one-shot latch that stops
     * the action re-firing and only clears on disarm. Other failsafes must defer to the
     * live condition, not the latch — testing the latch meant one voltage trigger disabled
     * the altitude ceiling for the remainder of the flight even after the battery recovered.
     */
    private var voltageCriticalActive = false
    /** Recovery must exceed the threshold by this much before the condition is cleared. */
    private val VOLTAGE_RECOVERY_HYSTERESIS_V = 0.5f

    // ═════════════════════════════════════════════════════════════════════════════
    //  Altitude ceiling (FENCE_ALT_MAX) — TWO LAYERS
    // ═════════════════════════════════════════════════════════════════════════════
    //
    // The ceiling used to be a single one-shot failsafe: a fixed margin below the limit,
    // cross it, get the operator's FENCE_ACTION (usually RTL). That design could not satisfy
    // both of the things asked of it.
    //
    //   1. It taxed the top of the envelope unconditionally. The margin had a 6 m FLOOR, so
    //      on a 48 m ceiling the action fired at 42 m even in dead-level flight. A pilot who
    //      wanted to work at 47 m simply could not — the limit they set was not the limit
    //      they got.
    //   2. It still let the drone cross. Once the one-shot was consumed, the band between the
    //      trigger and the old 1.5 m backstop was covered by TTS and nothing else: ~4.5 m of
    //      unopposed climb, after which the re-command happened so close to the line that
    //      link latency plus vertical momentum carried the vehicle straight through it.
    //
    // Both follow from treating a CONSTRAINT as an EVENT. A trip-wire has to be set back from
    // the line (costing envelope) and fires once (permitting the overshoot). The replacement
    // is a wall: continuous, non-latching, costing nothing until the vehicle actually moves
    // toward the limit.
    //
    //   LAYER 1 — THE WALL ([handleAltitudeWall]). Every frame, project where the vehicle
    //   would come to rest if BRAKE were commanded now (fix age + command latency + v²/2a).
    //   If that projection reaches the line, BRAKE immediately. Level flight at 47.5 m under
    //   a 48 m ceiling projects to 47.5 m and is left entirely alone; a 4 m/s climb at 40 m
    //   projects to 47.6 m and is stopped at 40 m. The vehicle is then handed back to the
    //   pilot's own mode so work continues. No RTL, no latch, no notification.
    //
    //   LAYER 2 — THE BREACH ([handleAltitudeFailsafe]). The operator's FENCE_ACTION, fired
    //   only if the wall FAILED and the vehicle reached the line anyway. This is recovery,
    //   not prevention; prevention is layer 1's job and the FC's FENCE_ALT_MAX (written
    //   [FC_ALT_FENCE_SAFETY_OFFSET_M] lower still) is the third line under both.
    //
    // DGCA's requirement is that the boundary is not crossed. Layer 1 is what delivers that,
    // because it is the only one of the three that acts BEFORE the vehicle is committed.

    private var altitudeLimitActionTriggered = false // One-shot per arm cycle, like the voltage action
    private var lastAltitudeWarnTime = 0L
    private var lastAltitudeLimitTime = 0L
    private val ALTITUDE_WARN_INTERVAL_MS = 4000L
    private val ALTITUDE_LIMIT_INTERVAL_MS = 5000L
    /**
     * Largest "approaching the limit" warning band, in metres.
     *
     * Capped at a fraction of the ceiling as well (see [altitudeWarnMargin]): a flat 10 m
     * meant a 48 m ceiling started nagging at 38 m and never stopped, which is most of the
     * useful working height. The warning is also only spoken while the vehicle is actually
     * climbing — a drone parked below its ceiling is not approaching anything.
     */
    private val ALTITUDE_WARN_MARGIN_M = 10f
    /** The warning band may never exceed this fraction of the ceiling. */
    private val ALTITUDE_WARN_MAX_FRACTION = 0.2f

    // ─── Layer 1: the wall ───────────────────────────────────────────────────────

    /**
     * How far below the ceiling the wall holds the vehicle, in metres.
     *
     * This is the altitude the pilot loses, and the three layers have to stack in the right
     * order or the gentlest one never gets a turn:
     *
     *     ceiling − WALL_BUFFER   →  GCS wall: BRAKE, hand straight back, no RTL, no latch
     *     ceiling − FC offset     →  the FC's own fence: the operator's FENCE_ACTION
     *     ceiling                 →  GCS layer 2: last-resort FENCE_ACTION
     *
     * It is derived from [FC_ALT_FENCE_SAFETY_OFFSET_M] rather than set independently because
     * of what happens when the two cross. The FC fence is now armed at connect
     * ([armFcAltitudeFence]) and evaluated at 400Hz, so it is genuinely the first thing the
     * vehicle meets. With the old flat 0.5 m the wall sat at 47.5 m while the FC fence sat at
     * 47.0 m on a 48 m ceiling — the FC would fire its FENCE_ACTION (usually RTL) half a metre
     * BEFORE the wall's soft stop, and every approach to the ceiling would become a flight
     * home. Deriving it keeps the wall unconditionally below the FC line.
     *
     * The extra metre on top is what the projection cannot know: barometric bias between the
     * FC's altitude and the number the wall is judging. The stopping distance itself is not in
     * here — [projectedStopAltitude] handles that, proportionally to how fast the vehicle is
     * actually moving.
     */
    // A computed accessor, not an initialiser: FC_ALT_FENCE_SAFETY_OFFSET_M is declared
    // further down the class, and a property initialiser that reads a later property would
    // capture 0f. Evaluating on read also means the two can never drift apart.
    private val ALTITUDE_WALL_BUFFER_M: Float
        get() = FC_ALT_FENCE_SAFETY_OFFSET_M + 1.0f
    /**
     * Below this climb rate the predictive arm of the wall does not engage.
     *
     * A hovering multirotor's derived climb rate is not exactly zero even after smoothing,
     * and braking a vehicle that is not going anywhere would make the top of the envelope
     * unusable for exactly the reason this rework exists. The proximity arm of the wall
     * (vehicle already at the line) has no such gate, so a slow creep is still caught.
     */
    private val ALTITUDE_WALL_MIN_CLIMB_MPS = 0.3f
    /**
     * Upward drift at or below this, with the vehicle already at the wall line, is treated
     * as station-keeping noise rather than a climb.
     *
     * The proximity arm of the wall has to be gated on SOMETHING, or a vehicle handed back
     * to the pilot while parked on the line would re-trigger the wall on the very next
     * frame and sawtooth between BRAKE and Loiter forever. Gating on a small positive climb
     * breaks that loop while still catching the case it exists for: a slow creep that the
     * predictive arm ignores because the projected stop distance of a 0.2 m/s climb is
     * essentially zero.
     */
    private val ALTITUDE_WALL_CREEP_CLIMB_MPS = 0.15f
    /** True while the wall is holding the vehicle; cleared on hand-back or on disarm. */
    private var altitudeWallEngaged = false
    /** Guards against launching a second BRAKE/hand-back coroutine on the next frame. */
    private var altitudeWallBusy = false
    /** When the current wall engagement commanded BRAKE. */
    private var altitudeWallEngagedAtMs = 0L
    /**
     * The mode the pilot was flying when the wall intervened, to be restored on hand-back.
     * Null when the interrupted mode was not one a pilot flies (AUTO, RTL, LAND, GUIDED) —
     * handing those back would resume the very climb the wall just stopped, or restart a
     * mission leg the operator has not seen fail.
     */
    private var altitudeWallPreviousMode: UInt? = null
    private var altitudeWallPreviousModeName: String? = null
    /** Timestamps of recent wall engagements, used to detect a pilot holding up-stick. */
    private val altitudeWallFireTimes = ArrayDeque<Long>()
    /**
     * Once the wall has fired this many times inside [ALTITUDE_WALL_REFIRE_WINDOW_MS] it
     * stops handing control back and simply holds.
     *
     * Hand-back is right for a pilot who climbed into the limit and then flew on. It is
     * wrong for one holding the stick up: they would get a BRAKE/restore cycle every couple
     * of seconds, the vehicle would sawtooth against the ceiling, and each cycle spends a
     * little of the altitude budget. After three of them the wall concludes the climb demand
     * is standing, holds in BRAKE, and says so. The pilot takes a mode of their own to leave.
     */
    private val ALTITUDE_WALL_MAX_REFIRES = 3
    private val ALTITUDE_WALL_REFIRE_WINDOW_MS = 20_000L
    /** True once the re-fire cap has latched the wall into hold-without-hand-back. */
    private var altitudeWallHoldLatched = false
    /**
     * Descend this far below the ceiling to clear the hold latch.
     *
     * Measured from the ceiling, not from the wall line, for the same reason the breach
     * re-arm is (see [ALTITUDE_REARM_BELOW_CEILING_M]) — a fixed, predictable altitude the
     * pilot can actually fly to, rather than one that moves with the vehicle's own speed.
     */
    private val ALTITUDE_WALL_LATCH_CLEAR_M = 5f
    /**
     * How long the climb must stay arrested before the wall hands control back.
     *
     * Short, because the pilot is waiting: long enough that BRAKE has demonstrably settled
     * the vehicle, not so long that the interruption feels like a failsafe.
     */
    private val ALTITUDE_WALL_HANDBACK_HOLD_MS = 1500L
    /**
     * Give up on a hand-back that is taking this long and leave the vehicle in BRAKE.
     * Holding under the ceiling is always an acceptable outcome; guessing is not.
     */
    private val ALTITUDE_WALL_ARREST_TIMEOUT_MS = 6000L
    /**
     * Grace given to a freshly commanded BRAKE before layer 2 is allowed to escalate.
     *
     * The wall reports "holding" so the breach layer stays out of its way, and it can only
     * report that once the heartbeat confirms BRAKE — a round trip the vehicle spends still
     * flying. Without a grace window, a wall that engaged close to the line would be
     * overtaken by its own breach layer during that round trip and the pilot would get an
     * RTL for a stop that was about to succeed.
     *
     * Conditional on the vehicle being slow: the grace is only ever extended to a vehicle
     * climbing no faster than [ALTITUDE_ARRESTED_CLIMB_MPS], where two seconds buys at most
     * ~0.6 m. Anything climbing harder gets no grace at all and escalates immediately — the
     * ceiling must not be crossed, and a fast climb is not a stop in progress.
     */
    private val ALTITUDE_WALL_GRACE_MS = 2000L
    /** Rate limit on the wall's spoken announcements. */
    private val ALTITUDE_WALL_SPEAK_INTERVAL_MS = 5000L
    private var lastAltitudeWallSpeakTime = 0L

    // ─── Layer 2: the breach ──────────────────────────────────────────────────

    /**
     * How far below the ceiling the operator's FENCE_ACTION fires, in metres. Zero: it fires
     * when the vehicle has actually reached the configured limit.
     *
     * This is not a trigger point chosen to leave room for a stop — it is the point at which
     * we conclude the wall did not work. The old speed-aware 6-25 m margin lived here and was
     * what made the ceiling unusable; all of that prediction has moved into
     * [projectedStopAltitude], where it sizes a BRAKE rather than an RTL.
     *
     * Zero rather than a small positive margin for two reasons. First, it costs nothing:
     * firing RTL at 47.7 m instead of 48 m does not change whether a vehicle the wall failed
     * to stop crosses the line — by then the outcome is set by momentum, not by 0.3 m of
     * lead. Second, a margin here is actively harmful, because the wall deliberately parks
     * the vehicle in the last half-metre under the ceiling: a breach line inside that band
     * would turn every successful stop into an RTL the moment the wall handed control back.
     *
     * The rule this expresses: layer 1 prevents, layer 2 recovers. Only layer 1 is allowed to
     * have an opinion about altitude the pilot has not yet used.
     */
    private val ALTITUDE_BREACH_MARGIN_M = 0f
    /**
     * How far back below the CEILING the drone must descend before the breach action
     * re-arms, in metres.
     *
     * The one-shot exists so a pilot who deliberately takes back control is not fought on
     * every telemetry frame. It used to clear only on disarm, which meant a pilot who
     * recovered and later climbed into the ceiling again got no action for the rest of the
     * flight — the protection was single-use per power cycle.
     *
     * MEASURED FROM THE CEILING, NOT FROM THE TRIGGER. Hung off a speed-aware trigger the
     * band moved with climb rate, so a pilot who fired the action during a fast climb and
     * then settled into a hover was left ABOVE the re-arm point and the latch never cleared.
     *
     * 5 m rather than the old 12 m: the trigger no longer sits 6 m or more down the
     * envelope, so the band no longer has to clear it. It only has to sit clear of the wall
     * line, which it does by 4.5 m — comfortably more than noise or a descent overshoot.
     */
    private val ALTITUDE_REARM_BELOW_CEILING_M = 5f
    /**
     * A climb slower than this counts as "not climbing" for the purpose of re-arming.
     * Re-arm needs BOTH a descent below [ALTITUDE_REARM_BELOW_CEILING_M] and a vehicle
     * that is no longer heading for the ceiling, so the latch cannot clear on a brief dip
     * during a continuous climb and then immediately re-fire.
     */
    private val ALTITUDE_REARM_MAX_CLIMB_MPS = 0.5f
    /**
     * Upper clamp so a bogus climb rate or a stale fix cannot consume the whole envelope.
     *
     * 40 m rather than the old 25: with the deceleration figure lowered to a realistic
     * 2.0 m/s², a genuine 8 m/s climb already needs 24 m and a 10 m/s climb needs 35 — a
     * clamp below those would silently under-size the stop on exactly the climbs that most
     * need it. The clamp is a guard against a nonsense sample, not a policy about how much
     * altitude the wall may use, and the EMA on the climb rate now filters the nonsense that
     * made a tight clamp feel necessary.
     */
    private val ALTITUDE_MAX_STOP_DISTANCE_M = 40f
    /**
     * Seconds of latency budgeted between crossing the trigger point and the mode change
     * biting: DO_SET_MODE round trip + the FC's own mode-entry delay. The age of the
     * position fix is added on top of this at evaluation time, not folded into it.
     *
     * 0.8 s rather than 0.5: 0.5 s budgeted the link round trip but not the FC's own
     * mode-entry and attitude-transition delay, which is where the residual overshoot came
     * from.
     */
    private val ALTITUDE_LATENCY_S = 0.8f
    /**
     * Vertical deceleration assumed when sizing the stopping distance, m/s².
     *
     * 2.0 rather than ArduCopter's nominal PILOT_ACCEL_Z of 2.5: that parameter is the
     * commanded maximum, and the average actually achieved over a real stop is lower — a
     * sprayer with a loaded tank does not hit the book figure. Assuming less deceleration
     * buys a longer stopping distance, which is the conservative direction for a limit we
     * are not allowed to cross. The same reasoning, and the same correction, as
     * [MAX_RANGE_DECEL_MPS2].
     */
    private val ALTITUDE_DECEL_MPS2 = 2.0f
    /**
     * One evaluation interval, in seconds, budgeted on top of the fix age.
     *
     * The wall only gets to look at the vehicle when a position message arrives. The age term
     * accounts for how stale the CURRENT reading is; this accounts for the fact that the next
     * chance to act is a sample away. Without it the trigger is systematically one sample
     * late, and at 8 m/s one sample is 0.8 m — enough, on its own, to put the stop above the
     * line on exactly the fast climbs the wall exists for.
     */
    private val ALTITUDE_EVAL_INTERVAL_S = 0.15f
    /**
     * A climb at or below this counts as arrested, so the breach action may proceed.
     * Not zero: a braked multirotor settles with a little residual vertical noise, and
     * holding out for a true 0 would spend the whole settle budget every time.
     */
    private val ALTITUDE_ARRESTED_CLIMB_MPS = 0.3f
    /**
     * Longest the ceiling waits for BRAKE to stop the climb before handing over to the
     * operator's action anyway. Proceeding late beats not proceeding.
     */
    private val ALTITUDE_BRAKE_SETTLE_TIMEOUT_MS = 3000L
    /**
     * Headroom kept between RTL_ALT and the ceiling, in metres.
     *
     * RTL_ALT must sit far enough below the ceiling that RTL's climb stage cannot carry the
     * vehicle through it, with room for ArduPilot's own altitude tolerance on the way.
     *
     * Visible to [MavlinkTelemetryRepository] so the fence-upload path
     * (clampRtlAltBelowFenceCeiling) applies the same headroom rather than defining a
     * second, silently divergent one.
     */
    val RTL_ALT_BELOW_CEILING_M = 10f
    /**
     * Floor for a written RTL_ALT, in metres. ArduPilot treats RTL_ALT=0 as "return at the
     * current altitude", so a low ceiling must not drive the value to or below zero.
     */
    val RTL_ALT_MIN_M = 10f

    // ═══ Max range failsafe (GCS-enforced, limit read from FENCE_RADIUS) ═══
    //
    // Enforced here rather than by the FC's home-centred cylinder. The FC fence proved
    // unreliable to arm from the GCS: AC_Fence rebuilds its live _enabled_fences mask only
    // when FENCE_ENABLE changes value, so a FENCE_TYPE written to an already-enabled fence
    // updated the parameter (and everything the pilot could see) while the circle fence was
    // never actually evaluated — the drone flew past 1000m in Loiter with no breach.
    //
    // The GCS therefore owns enforcement, exactly as it does for the altitude ceiling, while
    // the LIMIT and the ACTION still come from the vehicle's own parameters (FENCE_RADIUS and
    // FENCE_ACTION) so DGCA's "acts on the parameters actually set" requirement still holds.
    private var maxRangeActionTriggered = false   // one-shot per breach, re-arms on recovery
    private var lastMaxRangeWarnTime = 0L
    private var lastMaxRangeLimitTime = 0L
    private val MAX_RANGE_WARN_INTERVAL_MS = 4000L
    private val MAX_RANGE_LIMIT_INTERVAL_MS = 5000L
    /** Warn this far inside the action point so the pilot can turn back first. */
    private val MAX_RANGE_WARN_LEAD_M = 25f
    /** Smallest buffer inside the radius where the action fires (near-hover case). */
    private val MAX_RANGE_MIN_ACTION_MARGIN_M = 12f
    /** Upper clamp so a bogus groundspeed can't shrink the usable envelope to nothing. */
    private val MAX_RANGE_MAX_ACTION_MARGIN_M = 60f
    /**
     * Latency between crossing the trigger and RTL biting: command round trip + mode entry.
     *
     * 1.4 s rather than 1.0: the measured 1-2 m overshoot at 8 m/s is ~0.2 s of flight, and
     * the missing time is the FC's own mode-entry + attitude-transition delay — RTL does not
     * begin decelerating the instant the mode change is acknowledged. Budgeting it here is
     * what makes the turn happen before the line instead of on it.
     */
    private val MAX_RANGE_LATENCY_S = 1.4f
    /**
     * Horizontal deceleration assumed when sizing the stopping distance, m/s².
     *
     * Lowered from 2.5: WPNAV_ACCEL is the commanded maximum, and the achieved average over
     * a real stop is lower — a heavy airframe with the sprayer tank loaded does not hit the
     * book figure. Assuming less deceleration buys a longer stopping distance, which is the
     * conservative direction for a fence we are not allowed to cross.
     */
    private val MAX_RANGE_DECEL_MPS2 = 2.0f
    /**
     * How far back inside the RADIUS the drone must return before the range action re-arms.
     *
     * Measured from the radius, not from the action threshold, for the same reason as the
     * altitude ceiling (see [ALTITUDE_REARM_BELOW_CEILING_M]): the threshold slides inward
     * with groundspeed, so a band hung off it moved with speed and could sit outside where
     * a pilot actually loitered after cancelling, leaving the latch stuck.
     */
    private val MAX_RANGE_REARM_INSIDE_RADIUS_M = 40f
    /**
     * A groundspeed below this counts as "not running for the fence" when re-arming. Re-arm
     * needs both a genuine return inside the radius and a vehicle that is not still charging
     * outward, so the latch cannot clear and instantly re-fire.
     */
    private val MAX_RANGE_REARM_MAX_SPEED_MPS = 2.0f
    /**
     * Hard backstop: within this distance of the radius (or beyond it), the action fires
     * again even if the one-shot is already consumed. Same reasoning the altitude ceiling
     * uses for [ALTITUDE_BREACH_MARGIN_M] — the latch may spare a pilot who is managing the
     * situation inside the envelope, never one about to cross the line.
     */
    private val MAX_RANGE_BACKSTOP_MARGIN_M = 3f

    /**
     * A position fix older than this tells us nothing usable about where the drone is now,
     * so the altitude/range failsafes decline to act on it and warn the pilot instead.
     * Acting on a multi-second-old fix is how a failsafe fires late or in the wrong place.
     */
    private val POSITION_STALE_HARD_MS = 3000L
    private val POSITION_STALE_WARN_INTERVAL_MS = 5000L
    private var lastPositionStaleWarnTime = 0L

    // NOTE: the Max Range failsafe's GCS-side state (one-shot latch, warn/limit timers,
    // speed-aware margin constants) lived here. The 300m limit is now enforced by the FC's
    // home-centred cylinder fence (FENCE_RADIUS + FENCE_TYPE bit 1), armed on connect by
    // syncFenceParametersOnConnect, so that the breach action follows the operator's
    // FENCE_ACTION instead of a hardcoded GCS-side RTL.

    init {
        // Setup emergency RTL callback for crash handler
        setupEmergencyRTLCallback()

        // NOTE: Mission waypoints are NO LONGER automatically cleared when mission completes.
        // The map lines should remain visible until user explicitly navigates back to
        // select a new flying mode. clearMissionFromMap() should be called when:
        // 1. User navigates to SelectFlyingMethodScreen
        // 2. User explicitly clears the mission
        // 3. User uploads a new mission

        // 🔥 AUTO-CONNECT WEBSOCKET: Observe isMissionActive and connect WebSocket automatically
        // This handles cases where mission starts via AUTO mode (after uploading) without clicking Start Mission button
        viewModelScope.launch {
            var wasActive = false
            _telemetryState.collect { state ->
                val isActive = state.isMissionActive
                if (isActive && !wasActive) {
                    // Mission just became active - auto-connect WebSocket if not already connected
                    onMissionBecameActive()
                }
                wasActive = isActive
            }
        }

        // 🔋 BATTERY VOLTAGE FAILSAFE MONITORING
        // Monitors battery voltage against user-configured thresholds
        // 🛑 ALTITUDE CEILING FAILSAFE MONITORING (FENCE_ALT_MAX)
        viewModelScope.launch {
            _telemetryState.collect { state ->
                // Only monitor when connected and armed (in flight)
                if (state.connected && state.armed) {
                    state.voltage?.let { handleBatteryVoltageFailsafe(it) }
                    state.altitudeRelative?.let { handleAltitudeFailsafe(it, state) }
                    handleMaxRangeFailsafe(state)
                } else if (!state.armed) {
                    // Reset tracking when disarmed — allows failsafe to fire again on next arm
                    voltageAlertLevel2Triggered = false
                    lastVoltageAlertLevel1Time = 0L
                    lastVoltageAlertLevel2Time = 0L
                    voltageCriticalSince = 0L
                    voltageCriticalSamples = 0
                    voltageCriticalWarned = false
                    voltageCriticalActive = false
                    latchedCellCount = null
                    repository?.setVoltageFailsafeActive(false)

                    altitudeLimitActionTriggered = false
                    lastAltitudeWarnTime = 0L
                    lastAltitudeLimitTime = 0L
                    // The wall is per-flight state too: a hold latched against a pilot who
                    // was leaning on the stick last flight must not greet them on the next
                    // one, and a remembered "previous mode" from a landed aircraft is stale.
                    altitudeWallEngaged = false
                    altitudeWallBusy = false
                    altitudeWallEngagedAtMs = 0L
                    altitudeWallPreviousMode = null
                    altitudeWallPreviousModeName = null
                    altitudeWallHoldLatched = false
                    altitudeWallFireTimes.clear()
                    lastAltitudeWallSpeakTime = 0L

                    maxRangeActionTriggered = false
                    lastMaxRangeWarnTime = 0L
                    lastMaxRangeLimitTime = 0L

                    lastPositionStaleWarnTime = 0L
                }

                // Latch the pack's cell count at the arm transition. Doing it here (rather
                // than re-deriving per frame inside getLowVoltLevel2) stops a single bad
                // reading mid-flight from reclassifying a 12S pack as 6S and silently
                // dropping the critical threshold from 42V to 21V.
                if (state.connected && state.armed && latchedCellCount == null) {
                    latchedCellCount = detectCellCount(state.voltage)
                    if (latchedCellCount != null) {
                        LogUtils.i("BatteryFailsafe", "🔒 Latched cell count for this arm cycle: ${latchedCellCount}S (pack=${state.voltage}V)")
                    }
                }
            }
        }

        // 🏠 RTL ANNOUNCEMENT — popup + TTS on every entry into RTL / Smart RTL, whatever
        // triggered it (a GCS failsafe, the FC's own failsafe, or the pilot's switch).
        viewModelScope.launch {
            _telemetryState
                .map { it.mode }
                .distinctUntilChanged()
                .collect { mode -> handleRtlModeAnnouncement(mode) }
        }
    }

    /**
     * Announce entry into RTL, mirroring the popup + TTS the other failsafes give.
     *
     * Watching the heartbeat mode rather than our own [TelemetryRepository.changeMode] calls is
     * deliberate: RTL also gets entered by the FC itself (radio failsafe, EKF failsafe, GCS link
     * loss) and by the pilot's mode switch, and the pilot needs to know the drone is coming home
     * in every one of those cases, not just the ones the GCS initiated.
     *
     * When a failsafe caused the RTL its reason is still appended, so replacing the
     * "Fence Breached" / "Battery Failsafe" popup a second earlier does not hide *why* it
     * happened. This matters more now that the 300m range limit is an FC fence: an RTL from
     * a radius breach is attributed via notifyFenceBreach's popup, within
     * FAILSAFE_REASON_LINGER_MS, rather than by a GCS-side trigger we control directly.
     */
    private fun handleRtlModeAnnouncement(mode: String?) {
        // Ignore the null → mode settling that happens before a link is up.
        if (!_telemetryState.value.connected) return
        val isRtl = mode.equals("RTL", ignoreCase = true) || mode.equals("Smart_RTL", ignoreCase = true)
        if (!isRtl) return

        val reason = lastFailsafePopupReason?.takeIf {
            System.currentTimeMillis() - lastFailsafePopupTime <= FAILSAFE_REASON_LINGER_MS
        }
        LogUtils.i("RTLAnnounce", "🏠 Entered $mode mode${reason?.let { " (after $it)" } ?: ""} — announcing")

        ttsManager?.speak("RTL Flight Mode")
        addNotification(
            Notification(
                message = if (reason != null) "🏠 RTL Flight Mode — $reason" else "🏠 RTL Flight Mode",
                type = NotificationType.WARNING
            )
        )
        showFailsafePopup(if (reason != null) "RTL Flight Mode — $reason" else "RTL Flight Mode")
    }

    /**
     * Execute a failsafe mode change, escalating if the vehicle does not take it.
     *
     * [MavlinkTelemetryRepository.changeMode] already re-sends DO_SET_MODE for its whole 8s
     * window, so a `false` here means the vehicle genuinely did not enter the mode — not that
     * one packet was lost. That is the case the failsafes previously only LOGGED: the
     * one-shot latch had already been consumed before the coroutine launched, so a failed
     * failsafe was never retried for the rest of the arm cycle, and with BATT_FS_CRT_ACT
     * forced to 0 there is no FC-side fallback to catch it.
     *
     * Escalation order: the configured action, then LAND as a last resort for anything that
     * was not already trying to descend. LAND is the safest universal fallback — it needs no
     * home position, no GPS-quality path home, and it terminates the flight. The pilot is
     * told loudly at every step, since the final fallback if this all fails is manual control.
     *
     * @return true if the vehicle reached the requested mode or the LAND fallback.
     */
    private suspend fun executeFailsafeModeChange(
        tag: String,
        targetMode: UInt,
        targetModeName: String
    ): Boolean {
        val ok = repo?.changeMode(targetMode) ?: false
        if (ok) {
            LogUtils.i(tag, "✅ $targetModeName activated")
            return true
        }

        LogUtils.e(tag, "❌ $targetModeName did NOT engage after retries — escalating")
        addNotification(Notification(
            message = "⚠️ $targetModeName did not engage. Take manual control.",
            type = NotificationType.ERROR
        ))
        ttsManager?.speak("Warning. $targetModeName failed. Take manual control.")

        // Already descending — there is nothing safer to escalate to.
        if (targetMode == MavMode.LAND) return false

        LogUtils.w(tag, "↻ Escalating to LAND as last-resort fallback")
        val landed = repo?.changeMode(MavMode.LAND) ?: false
        if (landed) {
            LogUtils.i(tag, "✅ LAND fallback engaged")
            addNotification(Notification(
                message = "LAND engaged as failsafe fallback.",
                type = NotificationType.ERROR
            ))
            ttsManager?.speak("Landing.")
        } else {
            LogUtils.e(tag, "❌ LAND fallback ALSO failed — vehicle mode is ${_telemetryState.value.mode}")
            addNotification(Notification(
                message = "🛑 Failsafe could not change flight mode. MANUAL CONTROL REQUIRED.",
                type = NotificationType.ERROR
            ))
            ttsManager?.speak("Failsafe failed. Manual control required.")
        }
        return landed
    }

    /**
     * Handle battery voltage failsafe monitoring.
     * Level 1: Alert only (TTS + notification every 3 seconds)
     * Level 2: Action (BRAKE/RTL/LAND) fires ONCE per arm cycle + TTS repeats every 5 seconds
     *
     * IMPORTANT: The Level 2 mode-change action is ONE-SHOT per arm cycle.
     * Once triggered, it will NOT re-trigger even if the pilot overrides the mode.
     * This prevents the annoying loop where pilot switches mode and failsafe fights back.
     * The action flag (voltageAlertLevel2Triggered) only resets on disarm.
     * TTS warnings continue so the pilot stays aware of the critical battery state.
     *
     * The Level 2 action is also DEBOUNCED (see [VOLTAGE_CRITICAL_DEBOUNCE_MS]): the voltage
     * must stay at/below the threshold for both a duration and a sample count before the mode
     * change is issued, because a single frame was never enough evidence to justify an
     * irreversible action. The pilot hears "Battery critical" on the first sample regardless.
     */
    private fun handleBatteryVoltageFailsafe(voltage: Float) {
        val context = GCSApplication.getInstance() ?: return

        val level1Threshold = getLowVoltLevel1(context)
        val level2Threshold = getLowVoltLevel2(context)
        val level2Action = getLowVoltLevel2Action(context)

        val now = System.currentTimeMillis()

        // Level 2 (critical) - takes priority
        if (voltage <= level2Threshold) {

            // ═══ DEBOUNCE: confirm the condition before an irreversible mode change ═══
            // One frame is not evidence. A partial cell-sum or a throttle-punch sag lasts one
            // or two frames, yet the action below commands RTL/BRAKE/LAND and latches for the
            // rest of the arm cycle. Require the condition to persist for BOTH a wall-clock
            // duration and a sample count: the duration alone can be satisfied by two frames
            // either side of a link stall, and the count alone by a burst. The pilot is told
            // immediately; only the mode change waits.
            voltageCriticalActive = true
            if (voltageCriticalSince == 0L) {
                voltageCriticalSince = now
                voltageCriticalSamples = 0
            }
            voltageCriticalSamples++

            if (!voltageCriticalWarned) {
                voltageCriticalWarned = true
                LogUtils.w("BatteryFailsafe", "⏳ Voltage ${voltage}V ≤ ${level2Threshold}V — debouncing ${VOLTAGE_CRITICAL_DEBOUNCE_MS}ms / ${VOLTAGE_CRITICAL_MIN_SAMPLES} samples before action")
                ttsManager?.speak("Battery critical")
            }

            val sustainedMs = now - voltageCriticalSince
            if (sustainedMs < VOLTAGE_CRITICAL_DEBOUNCE_MS ||
                voltageCriticalSamples < VOLTAGE_CRITICAL_MIN_SAMPLES) {
                return
            }

            // ═══ GEOFENCE PRIORITY: defer the voltage action while the fence is breached ═══
            // If the drone is currently breaching the geofence, the FC is already enforcing
            // the fence action (BRAKE / RTL-to-return-point) at 400Hz to pull it back inside.
            // Issuing our own DO_SET_MODE here would OVERRIDE that fence recovery — an RTL
            // action flies straight home through the fence, and even a BRAKE cancels the FC's
            // return-to-point and strands the drone outside. That is the "geofence stops
            // working when the voltage failsafe fires" bug.
            //
            // So while breached we suppress the mode change WITHOUT consuming the one-shot
            // (voltageAlertLevel2Triggered stays false). The instant the breach clears and the
            // drone is safely back inside the fence, the normal first-trigger path below fires
            // the action. The pilot still hears the critical-voltage TTS warning meanwhile.
            if (!voltageAlertLevel2Triggered &&
                _geofenceEnabled.value && _geofenceViolationDetected.value) {
                if (now - lastVoltageAlertLevel2Time >= VOLTAGE_CRITICAL_INTERVAL_MS) {
                    lastVoltageAlertLevel2Time = now
                    LogUtils.w("BatteryFailsafe", "⏸️ Critical voltage ${voltage}V but GEOFENCE BREACHED — deferring $level2Action so the FC fence recovery can finish (one-shot NOT consumed)")
                    ttsManager?.speak("Critical battery. Returning inside the fence first.")
                }
                return
            }

            if (!voltageAlertLevel2Triggered) {
                // ═══ FIRST TRIGGER: Execute mode change action (one-shot per arm cycle) ═══
                voltageAlertLevel2Triggered = true
                lastVoltageAlertLevel2Time = now
                repository?.setVoltageFailsafeActive(true)

                LogUtils.i("BatteryFailsafe", "⚠️ CRITICAL: Battery voltage ${voltage}V <= ${level2Threshold}V - Triggering $level2Action (one-shot)")

                // ═══ Diagnostic: capture state when a critical-battery failsafe fires near a fence ═══
                // The GCS handles the action (BRAKE/RTL/LAND); the FC's own critical action is
                // forced to 0 (None) so it can't RTL through the geofence. This line records the
                // voltage/mode/fence context so any future "didn't stop at the fence" report can
                // be traced to the exact failsafe sequence.
                LogUtils.i("BatteryFailsafe", "🔎 FENCE-CONTEXT: voltage=${voltage}V crit=${level2Threshold}V mode=${_telemetryState.value.mode} geofenceEnabled=${_geofenceEnabled.value} fenceBreached=${_geofenceViolationDetected.value} action=$level2Action")

                // TTS alert — matches the "Battery Failsafe" popup text exactly.
                ttsManager?.speak("Battery Failsafe")

                // Add notification
                addNotification(
                    Notification(
                        message = "⚠️ CRITICAL BATTERY: ${String.format(Locale.US, "%.1f", voltage)}V - Activating $level2Action",
                        type = NotificationType.ERROR
                    )
                )
                showFailsafePopup("Battery Failsafe")

                // Execute the action
                viewModelScope.launch {
                    // ═══ FIX: Reset spray detection IMMEDIATELY before mode change ═══
                    // When battery failsafe triggers a mode change (e.g., to BRAKE),
                    // the sprayer physically stops and flow drops to 0.
                    // Without this reset, there's a race condition:
                    //   - FC enters BRAKE → flow drops to 0
                    //   - But heartbeat hasn't confirmed BRAKE yet → GCS thinks mode = Auto
                    //   - Tank empty detection sees: spray active + zero flow → false "Tank Empty!"
                    // By resetting spray detection NOW, we prevent this race condition.
                    repo?.resetAutoModeSprayDetection()
                    LogUtils.i("BatteryFailsafe", "🚿 Reset spray detection to prevent false Tank Empty")

                    // Note: "HOVER" or "LOITER" setting uses BRAKE mode to keep drone in place
                    val targetMode = when (level2Action.uppercase()) {
                        "RTL" -> MavMode.RTL
                        "LAND" -> MavMode.LAND
                        else -> MavMode.BRAKE // Use BRAKE mode for hover - keeps drone in place
                    }
                    
                    val targetModeName = when (targetMode) {
                        MavMode.RTL -> "RTL"
                        MavMode.LAND -> "LAND"
                        MavMode.BRAKE -> "BRAKE"
                        else -> level2Action
                    }

                    executeFailsafeModeChange("BatteryFailsafe", targetMode, targetModeName)

                    // Send event to WebSocket
                    try {
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "BATTERY_CRITICAL",
                            eventStatus = "CRITICAL",
                            description = "Battery voltage critical (${String.format(Locale.US, "%.1f", voltage)}V) - $targetModeName activated"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("BatteryFailsafe", "Failed to send battery critical event", e)
                    }
                }
            } else if (now - lastVoltageAlertLevel2Time >= VOLTAGE_CRITICAL_INTERVAL_MS) {
                // ═══ REPEAT: TTS warning only, NO mode change ═══
                // Action already fired this arm cycle. Just remind the pilot.
                lastVoltageAlertLevel2Time = now
                LogUtils.i("BatteryFailsafe", "⚠️ CRITICAL (repeat alert): Battery voltage ${voltage}V still below ${level2Threshold}V (action already taken this arm cycle)")
                ttsManager?.speak("Critical! Battery voltage ${String.format(Locale.US, "%.1f", voltage)} volts.")
            }
        }
        // ═══ RECOVERY ═══
        // Clear the debounce accumulator once the voltage climbs back above the threshold
        // (plus hysteresis, so a reading hovering on the boundary doesn't chatter). Without
        // this, brief dips scattered across a long flight would accumulate into a trigger.
        //
        // voltageCriticalActive also clears here so the altitude-ceiling failsafe stops
        // deferring; voltageAlertLevel2Triggered deliberately does NOT — the action stays
        // one-shot per arm cycle so it can never fight the pilot repeatedly.
        else if (voltage > level2Threshold + VOLTAGE_RECOVERY_HYSTERESIS_V) {
            if (voltageCriticalSince != 0L || voltageCriticalActive) {
                LogUtils.i("BatteryFailsafe", "🔋 Voltage recovered to ${voltage}V (> ${level2Threshold}V + ${VOLTAGE_RECOVERY_HYSTERESIS_V}V) — critical condition cleared")
            }
            voltageCriticalSince = 0L
            voltageCriticalSamples = 0
            voltageCriticalWarned = false
            voltageCriticalActive = false
        }

        // Level 1 (warning) - alert only, every 3 seconds
        if (voltage > level2Threshold && voltage <= level1Threshold) {
            if (now - lastVoltageAlertLevel1Time >= VOLTAGE_ALERT_INTERVAL_MS) {
                lastVoltageAlertLevel1Time = now
                LogUtils.i("BatteryFailsafe", "⚠️ WARNING: Battery voltage ${voltage}V <= ${level1Threshold}V")

                // TTS alert
                ttsManager?.speak("Warning! Battery voltage ${String.format(Locale.US, "%.1f", voltage)} volts.")

                // Add notification (less severe)
                addNotification(
                    Notification(
                        message = "⚠️ Low Battery: ${String.format(Locale.US, "%.1f", voltage)}V",
                        type = NotificationType.WARNING
                    )
                )
            }
        }
        // NOTE: recovery above clears the debounce and voltageCriticalActive, but NOT
        // voltageAlertLevel2Triggered — that resets only on DISARM, keeping the failsafe
        // action truly one-shot per arm cycle.
    }

    /**
     * Altitude ceiling, layer 2: the operator's FENCE_ACTION on a genuine breach.
     *
     * Also the host for layer 1 ([handleAltitudeWall]), which runs first on every frame and
     * is what actually keeps the vehicle under the limit. See the constants block for why
     * the ceiling is two layers rather than one trigger.
     *
     * Why the GCS enforces this at all rather than leaving it to the FC: the flight
     * controller only acts on FENCE_ALT_MAX when FENCE_ENABLE is on AND bit 0 (altitude) is
     * set in FENCE_TYPE. On a drone flying without a geofence uploaded neither is
     * guaranteed, so the ceiling would silently do nothing. This mirrors how the GCS already
     * owns the critical-battery action (see [handleBatteryVoltageFailsafe]) instead of
     * letting the FC race it.
     *
     * Layer 2 is deliberately quiet. It fires the configured action (HOVER/BRAKE, RTL, LAND)
     * once per breach, at [ALTITUDE_BREACH_MARGIN_M] below the ceiling, and ONLY when the
     * wall is not already holding the vehicle. If the wall is doing its job the pilot never
     * sees this code run.
     */
    private fun handleAltitudeFailsafe(altitude: Float, state: TelemetryState) {
        val context = GCSApplication.getInstance() ?: return
        if (!isAltitudeFailsafeEnabled(context)) return

        val ceiling = getMaxAltitude(context)
        if (ceiling <= 0f) return   // 0 / negative means "no ceiling configured"

        val action = getMaxAltitudeAction(context)
        val now = System.currentTimeMillis()

        // How old is the altitude we are about to judge? Under a saturated telemetry link
        // GLOBAL_POSITION_INT can degrade from 10Hz to 1-2Hz, and the drone keeps climbing
        // in the gap. Both the hard gate and the projection below depend on this.
        val positionAgeMs = state.positionReceivedAtMs?.let { now - it } ?: Long.MAX_VALUE

        if (positionAgeMs > POSITION_STALE_HARD_MS) {
            // Too old to act on. Say so rather than either trusting it or failing silently.
            if (now - lastPositionStaleWarnTime >= POSITION_STALE_WARN_INTERVAL_MS) {
                lastPositionStaleWarnTime = now
                LogUtils.w("AltitudeFailsafe", "⚠️ Position fix ${positionAgeMs}ms stale — altitude failsafe cannot evaluate (alt=${altitude}m)")
                ttsManager?.speak("Telemetry delayed")
            }
            return
        }

        // Two readings of the same signal, used for different questions.
        //   climbRaw    — unfiltered, answers "is the vehicle moving right now?" (arrest
        //                 detection, re-arm gating). Responsiveness matters more than noise.
        //   climbSmooth — EMA-filtered, answers "how far will it travel before it stops?".
        //                 Noise here would inflate the projected stop altitude and pull the
        //                 wall down the envelope, which is the failure mode this rework
        //                 exists to remove.
        val climbRaw = (state.climbRate ?: 0f).let { if (it.isFinite()) it else 0f }
        val climbSmooth = (state.climbRateSmoothed ?: state.climbRate ?: 0f)
            .let { if (it.isFinite()) it else 0f }

        val wallLine = ceiling - ALTITUDE_WALL_BUFFER_M
        val projectedAlt = projectedStopAltitude(altitude, climbSmooth, positionAgeMs)

        // ═══ LAYER 1: the wall ═══
        // Runs first and unconditionally. Returns true while it is actively holding the
        // vehicle — i.e. prevention is working and layer 2 must stay out of the way.
        val wallHolding = handleAltitudeWall(
            altitude = altitude,
            ceiling = ceiling,
            wallLine = wallLine,
            projectedAlt = projectedAlt,
            climbRaw = climbRaw,
            climbSmooth = climbSmooth,
            now = now
        )

        // ═══ LAYER 2: the breach ═══
        val breachThreshold = ceiling - ALTITUDE_BREACH_MARGIN_M

        // ═══ RECOVERED: re-arm the one-shot ═══
        // Checked before the action branch, not as an else-if, so it cannot be shadowed.
        //
        // The one-shot exists so a pilot who takes back control is not fought every frame,
        // but it used to clear only on disarm — a pilot who recovered and later climbed
        // into the ceiling again got no action for the rest of the flight. Re-arming on
        // genuine recovery restores protection for the second and subsequent breaches.
        //
        // The band is measured from the CEILING, and re-arming also requires the vehicle to
        // have stopped climbing, so a momentary dip during a continuous climb does not clear
        // the latch and immediately re-fire the action.
        val rearmAltitude = ceiling - ALTITUDE_REARM_BELOW_CEILING_M
        val climbingHard = climbRaw > ALTITUDE_REARM_MAX_CLIMB_MPS
        if (altitudeLimitActionTriggered && altitude < rearmAltitude && !climbingHard) {
            altitudeLimitActionTriggered = false
            lastAltitudeLimitTime = 0L
            // Log only — re-arming is internal housekeeping, not an event the pilot needs
            // in the notification list. It fires on recovery, i.e. once things are already
            // going right, and pairing it with the breach entry doubled the list's length
            // for every excursion.
            LogUtils.i("AltitudeFailsafe", "✓ Recovered to ${altitude}m (below ${rearmAltitude}m, climb=${climbRaw}m/s) — altitude action re-armed")
        }

        // ═══ LAYER 1b: the pilot asked for an ACTION at the limit, not a hold ═══
        //
        // "Action at Limit" (max_altitude_action) has always offered Hover / RTL / Land, but
        // it was only ever consulted at the breach line AND only when FENCE_ACTION could not
        // be read — and layer 1 exists precisely to stop the vehicle ever reaching that line.
        // So on a healthy vehicle, selecting RTL did nothing whatsoever: the drone stopped a
        // couple of metres short, control was handed back, and no action ever ran. The
        // dropdown was dead.
        //
        // HOVER preserves exactly that hand-back behaviour and remains the default, so normal
        // work near the ceiling is untouched. Anything else escalates once the wall has the
        // climb ARRESTED: the limit has been reached, the vehicle is stopped and stable, and
        // the pilot has said they want the breach action anyway.
        //
        // WHICH action is still the vehicle's own FENCE_ACTION (see [runAltitudeLimitAction]);
        // the dropdown decides whether to escalate, not what the escalation is.
        // [warnIfLimitActionMismatch] flags a disagreement between the two on connect, so it
        // is discovered on the ground rather than in the air.
        //
        // Placed AFTER the re-arm check above so a pilot who descends and climbs again gets
        // the action a second time, and gated on the one-shot so it fires once per approach
        // instead of on every frame the wall is holding. Setting that one-shot also makes
        // [handleAltitudeWall] stand down (its "LAYER 2 HAS THE VEHICLE" guard), so the wall
        // cannot re-engage BRAKE on top of the RTL this starts.
        if (wallHolding && !action.equals("HOVER", ignoreCase = true) &&
            !altitudeLimitActionTriggered
        ) {
            runAltitudeLimitAction(
                altitude = altitude,
                ceiling = ceiling,
                optionsAction = action,
                breached = false,
                isRefire = false,
                now = now
            )
            return
        }

        if (altitude >= breachThreshold) {

            // ═══ THE WALL IS HANDLING IT ═══
            // The vehicle is over the breach line but BRAKE has it stopped. That is the
            // system working, not a breach to escalate: commanding RTL on top of a
            // successful arrest would turn a 2-second interruption into a flight home.
            // The instant the arrest stops holding — the vehicle starts climbing again, or
            // BRAKE never engaged — wallHolding goes false and the action below fires.
            if (wallHolding) {
                if (now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS) {
                    lastAltitudeLimitTime = now
                    LogUtils.i("AltitudeFailsafe", "⛔ ${altitude}m is past the breach line ${breachThreshold}m (ceiling ${ceiling}m) but the wall has the climb arrested — holding, no $action")
                }
                return
            }

            // ═══ PRIORITY GUARDS: never cancel a higher-priority recovery ═══
            // While the FC is pulling the drone back inside a breached fence, or while the
            // critical-battery action is bringing it home, issuing our own DO_SET_MODE would
            // override that recovery and strand the drone. Suppress WITHOUT consuming the
            // one-shot, and keep warning the pilot.
            //
            // Unlike the old code this no longer yields on proximity alone — it yields only
            // while the competing recovery is demonstrably not making things worse (the
            // vehicle is level or descending). A recovery that is still carrying the drone
            // UP through the ceiling has forfeited its priority.
            //
            // NOTE: tests voltageCriticalActive (the LIVE condition), not the
            // voltageAlertLevel2Triggered one-shot latch. The latch never clears until
            // disarm, so testing it meant a single voltage trigger — including a spurious
            // one — disabled the altitude ceiling for the whole remaining flight.
            val deferReason = when {
                _geofenceEnabled.value && _geofenceViolationDetected.value -> "geofence recovery in progress"
                voltageCriticalActive -> "critical-battery action in progress"
                else -> null
            }
            if (!altitudeLimitActionTriggered && deferReason != null &&
                climbRaw <= ALTITUDE_ARRESTED_CLIMB_MPS
            ) {
                if (now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS) {
                    lastAltitudeLimitTime = now
                    LogUtils.w("AltitudeFailsafe", "⏸️ Altitude ${altitude}m past breach line ${breachThreshold}m (ceiling ${ceiling}m) but $deferReason and the climb is arrested — deferring $action (one-shot NOT consumed)")
                    ttsManager?.speak("Above altitude limit.")
                }
                return
            }

            // Fire on the first crossing, and fire AGAIN while the vehicle stays past the
            // line without recovering (rate-limited below) — a consumed one-shot may never
            // be the reason a breach goes unactioned.
            //
            // ═══ BUT NOT WHILE THE RECOVERY IS WORKING ═══
            // The re-fire exists for a breach that is NOT being handled. Once the vehicle is
            // in the commanded mode and no longer climbing, the action has done its job and
            // re-commanding it only interrupts it.
            //
            // This matters far more now that the action is two steps. Re-commanding RTL
            // alone was harmless (changeMode returns immediately when the heartbeat already
            // reports the mode), but re-running the BRAKE pre-step CANCELS the in-progress
            // RTL, waits for the climb to settle, then re-commands RTL — every 5s, forever.
            // And the vehicle cannot break the cycle itself: with RTL_ALT synced to
            // ceiling−[RTL_ALT_BELOW_CEILING_M], a drone stopped near the ceiling is ABOVE
            // RTL_ALT, so RTL cruises home level instead of descending and altitude never
            // falls to the ceiling−[ALTITUDE_REARM_BELOW_CEILING_M] re-arm point. The pilot
            // has to take Loiter and fly it down by hand. Hence: a recovery that is holding
            // or descending is left alone; only a vehicle still CLIMBING past the line gets
            // the action re-commanded.
            val recoveryHolding = !climbingHard && inCommandedRecoveryMode()
            val breachRefire = altitudeLimitActionTriggered && !recoveryHolding &&
                now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS

            if (!altitudeLimitActionTriggered || breachRefire) {
                runAltitudeLimitAction(
                    altitude = altitude,
                    ceiling = ceiling,
                    optionsAction = action,
                    breached = true,
                    isRefire = breachRefire,
                    now = now
                )
            } else if (now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS) {
                // ═══ REPEAT: TTS only ═══
                // Reached only when the latch is set AND a commanded recovery is holding the
                // vehicle, i.e. the action has already been taken and is working.
                lastAltitudeLimitTime = now
                LogUtils.i("AltitudeFailsafe", "⛔ Past altitude breach line: ${altitude}m (ceiling ${ceiling}m, $action already running and holding)")
                ttsManager?.speak("Above altitude limit. ${altitude.toInt()} meters.")
            }
        }
        // Approaching the ceiling — TTS + log only, so the pilot can level off themselves.
        //
        // Deliberately raises NO notification. A drone worked near its ceiling sits in this
        // band for minutes at a time, and at one entry every 4s the approach warning buried
        // the notification list — including the breach entries that actually matter. TTS is
        // the right channel for "you are getting close": it reaches a pilot who is looking
        // at the aircraft rather than the screen, and it does not accumulate. The
        // notification list is reserved for things that HAPPENED (the breach and its mode
        // change), not for things that merely might.
        //
        // Gated on an actual climb: the band is a fifth of the ceiling wide, a sprayer works
        // inside it for whole passes at a time, and announcing "approaching" to a vehicle
        // flying dead level is how the warning became background noise.
        else if (altitude >= ceiling - altitudeWarnMargin(ceiling) &&
            climbSmooth > ALTITUDE_WALL_CREEP_CLIMB_MPS
        ) {
            if (now - lastAltitudeWarnTime >= ALTITUDE_WARN_INTERVAL_MS) {
                lastAltitudeWarnTime = now
                LogUtils.i("AltitudeFailsafe", "⚠️ Approaching altitude limit: ${altitude}m of ${ceiling}m (climb=${climbSmooth}m/s)")
                ttsManager?.speak("Approaching altitude limit. ${altitude.toInt()} meters.")
            }
        }
        // NOTE: altitudeLimitActionTriggered re-arms mid-flight once the drone descends
        // ALTITUDE_REARM_BELOW_CEILING_M below the CEILING with the climb arrested (and on
        // disarm), so a second breach is protected just like the first.
    }

    /**
     * Run the configured action at the altitude ceiling.
     *
     * Shared by both paths that can decide the pilot needs more than a hold:
     *
     *   LAYER 1b — the wall has the climb arrested at the limit and the pilot's
     *   "Action at Limit" is RTL or Land rather than Hover ([breached] = false).
     *   LAYER 2  — the vehicle actually crossed the ceiling ([breached] = true).
     *
     * Extracted rather than duplicated: the BRAKE-before-RTL pre-step, the escalation to
     * LAND when a mode change is refused, the one-shot and the re-fire suppression are all
     * load-bearing, and a second copy of them would drift.
     *
     * WHICH MODE is always the vehicle's own FENCE_ACTION when it could be read, falling
     * back to [optionsAction] only when it could not — DGCA requires the drone to act on
     * the parameters actually set on it. The Options dropdown decides WHETHER to escalate,
     * not what the escalation is; [warnIfLimitActionMismatch] tells the pilot on the ground
     * when the two disagree, so a surprise never arrives in the air.
     *
     * @param breached true if the ceiling was crossed, false if the wall held at the limit.
     *   Only changes the wording — saying "breached" for a vehicle the wall stopped two
     *   metres short would be a lie in the log a regulator reads.
     * @param isRefire true when re-commanding an action already taken (layer 2 only).
     */
    private fun runAltitudeLimitAction(
        altitude: Float,
        ceiling: Float,
        optionsAction: String,
        breached: Boolean,
        isRefire: Boolean,
        now: Long
    ) {
        val reachedWord = if (breached) "breached" else "reached"
        val headlineWord = if (breached) "BREACH" else "LIMIT"
        // ═══ TRIGGER ═══
        altitudeLimitActionTriggered = true
        lastAltitudeLimitTime = now

        // The wall has been overtaken by events; drop its claim on the vehicle so it
        // cannot try to hand control back to the pilot in the middle of an RTL.
        releaseAltitudeWall("breach action taking over")

        // Announce the action the vehicle is actually configured with, not the
        // Options dropdown value — the mode below comes from FENCE_ACTION.
        val announcedAction = _fenceAction.value?.pilotLabel ?: optionsAction

        val climbNow = _telemetryState.value.climbRate ?: 0f
        when {
            isRefire -> LogUtils.w("AltitudeFailsafe", "⛔ ALTITUDE BREACH (repeat): ${altitude}m is still past the ${ceiling}m ceiling and not recovering — RE-COMMANDING $announcedAction, mode=${_telemetryState.value.mode}")
            breached -> LogUtils.e("AltitudeFailsafe", "⛔ ALTITUDE BREACH: ${altitude}m >= ${ceiling}m ceiling (climb=${climbNow}m/s) — the wall did not hold, triggering $announcedAction, mode=${_telemetryState.value.mode}")
            // Layer 1b: the wall DID hold. This is not a failure — it is the pilot
            // having asked for an action at the limit instead of a hover.
            else -> LogUtils.i("AltitudeFailsafe", "⛔ ALTITUDE LIMIT: wall holding at ${altitude}m under the ${ceiling}m ceiling, 'Action at Limit' is $optionsAction — escalating to $announcedAction, mode=${_telemetryState.value.mode}")
        }

        ttsManager?.speak("Altitude limit $reachedWord. Activating $announcedAction.")

        // One notification per breach, not per re-command. The re-fire keeps
        // re-issuing the mode change every ALTITUDE_LIMIT_INTERVAL_MS while the
        // drone sits past the line — that repetition is the safety behaviour and
        // must stay — but repeating the LIST entry alongside it would turn a single
        // event into a wall of identical rows. The re-fire still logs (above), still
        // speaks, and still refreshes the popup.
        if (!isRefire) {
            addNotification(
                Notification(
                    message = "⛔ ALTITUDE $headlineWord: ${String.format(Locale.US, "%.1f", altitude)}m of ${String.format(Locale.US, "%.0f", ceiling)}m ceiling — activating $announcedAction",
                    type = NotificationType.ERROR
                )
            )
        }
        showFailsafePopup("Max Altitude")

        viewModelScope.launch {
            // ═══ ARREST THE CLIMB FIRST ═══
            // RTL does not stop a climb. ArduCopter's RTL begins with RTL_Climb: if
            // the vehicle is below RTL_ALT it climbs UP to RTL_ALT before heading
            // home — i.e. the configured breach action drives the drone further
            // through the very ceiling it is meant to protect. syncRtlAltOnConnect
            // keeps RTL_ALT under the ceiling so the climb stage is a no-op, but a
            // vehicle we failed to write (or one an operator re-configured
            // mid-session) must still not sail through.
            //
            // So: BRAKE first to kill vertical motion, then hand over to the
            // operator's FENCE_ACTION. DGCA's "acts on the parameters actually set"
            // still holds — RTL still happens, it just no longer happens while
            // pointing the wrong way. Skipped when the action is itself a stop
            // (BRAKE) or a descent (LAND), neither of which climbs.
            val fenceAction = _fenceAction.value
            val targetMode = when (fenceAction) {
                FenceAction.RTL, FenceAction.SMART_RTL, FenceAction.SMART_RTL_LAND -> MavMode.RTL
                FenceAction.ALWAYS_LAND -> MavMode.LAND
                FenceAction.BRAKE -> MavMode.BRAKE
                // REPORT_ONLY: the operator asked for no automatic intervention.
                FenceAction.REPORT_ONLY -> null
                null -> when (optionsAction.uppercase()) {
                    "RTL" -> MavMode.RTL
                    "LAND" -> MavMode.LAND
                    else -> MavMode.BRAKE
                }
            }
            val targetModeName = when (targetMode) {
                MavMode.RTL -> "RTL"
                MavMode.LAND -> "LAND"
                MavMode.BRAKE -> "BRAKE"
                else -> "REPORT ONLY"
            }

            // Only RTL climbs. BRAKE is already the arrest, and LAND descends.
            val actionClimbs = targetMode == MavMode.RTL
            val alreadyBraking =
                _telemetryState.value.mode?.contains("Brake", ignoreCase = true) == true
            // The arrest is for a vehicle that is still going UP. Braking one that
            // has already stopped (or is coming down) buys nothing and, on a
            // re-fire, actively cancels the RTL that is recovering it.
            val stillClimbing = (_telemetryState.value.climbRate ?: 0f)
                .let { it.isFinite() && it > ALTITUDE_ARRESTED_CLIMB_MPS }
            // Never re-arrest on a re-fire: by then RTL is already running, and the
            // whole point of the re-fire is to nudge a vehicle that ISN'T recovering.
            val needsArrest = actionClimbs && !alreadyBraking && stillClimbing && !isRefire

            if (targetMode == null) {
                LogUtils.i("AltitudeFailsafe", "FENCE_ACTION=Report Only — alerting the pilot, taking no mode action")
            } else {
                if (!needsArrest && actionClimbs) {
                    LogUtils.i("AltitudeFailsafe", "Climb already arrested (climb=${_telemetryState.value.climbRate}m/s, mode=${_telemetryState.value.mode}, refire=$isRefire) — commanding $targetModeName directly")
                }
                if (needsArrest) {
                    LogUtils.i("AltitudeFailsafe", "🛑 Arresting climb with BRAKE before $targetModeName (RTL climbs to RTL_ALT and would breach the ceiling)")
                    if (executeFailsafeModeChange("AltitudeFailsafe", MavMode.BRAKE, "BRAKE")) {
                        // Let the brake actually bite before handing over. RTL's climb
                        // stage is skipped once the vehicle is at/above RTL_ALT, and a
                        // still-rising vehicle handed straight to RTL would resume the
                        // climb this step exists to stop.
                        awaitClimbArrested()
                    } else {
                        // executeFailsafeModeChange has already escalated to LAND and
                        // told the pilot. Do not then command RTL on top of a LAND that
                        // is bringing the vehicle down.
                        LogUtils.e("AltitudeFailsafe", "✗ Could not arrest the climb — skipping $targetModeName, vehicle is on the LAND fallback")
                        return@launch
                    }
                }

                LogUtils.i("AltitudeFailsafe", "Acting on FENCE_ACTION=${fenceAction?.pilotLabel ?: "unset, using Options '$optionsAction'"} → $targetModeName")
                executeFailsafeModeChange("AltitudeFailsafe", targetMode, targetModeName)
            }

            try {
                WebSocketManager.getInstance().sendMissionEvent(
                    eventType = "ALTITUDE_LIMIT",
                    eventStatus = "CRITICAL",
                    description = "Altitude ${String.format(Locale.US, "%.1f", altitude)}m $reachedWord ceiling ${String.format(Locale.US, "%.1f", ceiling)}m - $targetModeName activated"
                )
            } catch (e: Exception) {
                LogUtils.e("AltitudeFailsafe", "Failed to send altitude limit event", e)
            }
        }
    }

    /**
     * Altitude ceiling, layer 1: the wall.
     *
     * A continuous, non-latching climb limit. Every frame it asks one question — "if I
     * commanded BRAKE right now, would the vehicle still stop below the line?" — and the
     * moment the answer turns to no, it commands BRAKE. Nothing else. No FENCE_ACTION, no
     * one-shot, no notification: running into a ceiling is a limit being enforced, not an
     * emergency, and the pilot gets the vehicle straight back.
     *
     * Two arms, because one projection cannot cover both regimes:
     *
     *   PREDICTIVE — climbing faster than [ALTITUDE_WALL_MIN_CLIMB_MPS] and the projected
     *   stop altitude reaches the line. This is what stops a fast climb: at 4 m/s the
     *   projection runs ~7.6 m ahead of the vehicle, so the brake goes in 7.6 m early and
     *   the vehicle comes to rest just under the limit.
     *
     *   PROXIMITY — already at the line and still drifting up. The projection is useless
     *   here (a 0.2 m/s climb projects almost nowhere) but a slow creep crosses the ceiling
     *   just as surely as a fast one, so proximity plus any real upward motion is enough.
     *
     * Returns true while the wall is engaged AND succeeding, which is layer 2's signal to
     * stay silent. It goes false the instant BRAKE stops holding the climb, which is layer
     * 2's signal to escalate.
     *
     * @param climbRaw    unfiltered climb rate; answers "is it moving?"
     * @param climbSmooth EMA-filtered climb rate; answers "how far before it stops?"
     */
    private fun handleAltitudeWall(
        altitude: Float,
        ceiling: Float,
        wallLine: Float,
        projectedAlt: Float,
        climbRaw: Float,
        climbSmooth: Float,
        now: Long
    ): Boolean {
        val mode = _telemetryState.value.mode
        val inBrake = mode?.contains("Brake", ignoreCase = true) == true

        // A genuine descent clears the anti-sawtooth latch, so a pilot who backed off and
        // came down gets hand-back again on their next approach. Measured from the ceiling
        // for the same reason the breach re-arm is: a fixed altitude they can fly to.
        if (altitudeWallHoldLatched && altitude < ceiling - ALTITUDE_WALL_LATCH_CLEAR_M) {
            altitudeWallHoldLatched = false
            altitudeWallFireTimes.clear()
            LogUtils.i("AltitudeWall", "✓ Descended to ${altitude}m — hand-back re-enabled")
        }

        // The pilot (or another failsafe) has taken the vehicle out of BRAKE. The wall has no
        // claim on it any more; drop the engagement so we never try to "restore" a mode on
        // top of someone else's command. If the limit still needs defending, the trigger
        // below re-engages on this same frame.
        if (altitudeWallEngaged && !altitudeWallBusy && !inBrake) {
            releaseAltitudeWall("vehicle is in $mode, not BRAKE")
        }

        // ═══ LAYER 2 HAS THE VEHICLE ═══
        // Unconditional, and checked before anything else the wall might do. Once the breach
        // action has fired, layer 2 owns the aircraft: it runs its own BRAKE-then-FENCE_ACTION
        // sequence and re-commands it on a timer. A wall that kept engaging on top of that
        // would cancel the in-progress RTL every few seconds and the two layers would deadlock
        // against each other, which is worse than either alone. The wall comes back when the
        // breach latch re-arms on recovery.
        if (altitudeLimitActionTriggered) {
            releaseAltitudeWall("breach action owns the vehicle")
            return false
        }

        // ═══ SHOULD THE WALL BE UP? ═══
        val predictive = climbSmooth > ALTITUDE_WALL_MIN_CLIMB_MPS && projectedAlt >= wallLine
        val proximity = altitude >= wallLine && climbSmooth > ALTITUDE_WALL_CREEP_CLIMB_MPS
        val wantWall = predictive || proximity

        if (wantWall && !altitudeWallEngaged && !altitudeWallBusy) {
            // Do not brake a higher-priority recovery that is already flying the vehicle
            // somewhere safe and is NOT climbing into the limit. Same reasoning as layer 2's
            // priority guard, and the same escape hatch: a "recovery" still carrying the
            // drone up has forfeited its priority and gets braked like anything else.
            val competing = when {
                _geofenceEnabled.value && _geofenceViolationDetected.value -> "geofence recovery"
                voltageCriticalActive -> "critical-battery action"
                inCommandedRecoveryMode() -> "commanded recovery ($mode)"
                else -> null
            }
            if (competing != null && climbRaw <= ALTITUDE_ARRESTED_CLIMB_MPS) {
                if (now - lastAltitudeWallSpeakTime >= ALTITUDE_WALL_SPEAK_INTERVAL_MS) {
                    lastAltitudeWallSpeakTime = now
                    LogUtils.i("AltitudeWall", "Wall wanted at ${altitude}m (projected ${projectedAlt}m vs line ${wallLine}m) but $competing is holding the vehicle — standing off")
                }
                return false
            }

            // ═══ ENGAGE ═══
            // Capture the mode to give back BEFORE commanding BRAKE, and only if it is one a
            // pilot actually flies. AUTO/GUIDED/RTL/LAND come back null: restoring AUTO would
            // resume the mission leg that flew into the ceiling, and restoring RTL or LAND
            // would hand the vehicle back to a recovery the wall just interrupted.
            altitudeWallPreviousMode = pilotModeNumber(mode)
            altitudeWallPreviousModeName = mode
            altitudeWallEngaged = true
            altitudeWallEngagedAtMs = now
            altitudeWallBusy = true

            // Record the engagement for the sawtooth detector, and drop entries that have
            // aged out of the window.
            altitudeWallFireTimes.addLast(now)
            while (altitudeWallFireTimes.isNotEmpty() &&
                now - altitudeWallFireTimes.first() > ALTITUDE_WALL_REFIRE_WINDOW_MS
            ) {
                altitudeWallFireTimes.removeFirst()
            }
            if (altitudeWallFireTimes.size >= ALTITUDE_WALL_MAX_REFIRES && !altitudeWallHoldLatched) {
                altitudeWallHoldLatched = true
                LogUtils.w("AltitudeWall", "⚠️ Wall fired ${altitudeWallFireTimes.size} times in ${ALTITUDE_WALL_REFIRE_WINDOW_MS}ms — climb demand looks standing, hand-back suspended until the vehicle descends ${ALTITUDE_WALL_LATCH_CLEAR_M}m")
                addNotification(
                    Notification(
                        message = "⚠️ Holding at the ${String.format(Locale.US, "%.0f", ceiling)}m altitude limit. Descend or change mode to continue.",
                        type = NotificationType.WARNING
                    )
                )
            }

            val reason = if (predictive)
                "projected stop ${String.format(Locale.US, "%.1f", projectedAlt)}m ≥ line ${String.format(Locale.US, "%.1f", wallLine)}m at ${String.format(Locale.US, "%.1f", climbSmooth)}m/s"
            else
                "at the line (${String.format(Locale.US, "%.1f", altitude)}m ≥ ${String.format(Locale.US, "%.1f", wallLine)}m) and still rising ${String.format(Locale.US, "%.2f", climbSmooth)}m/s"
            LogUtils.i("AltitudeWall", "🧱 ALTITUDE WALL: braking at ${altitude}m of the ${ceiling}m ceiling — $reason (was in $mode)")

            if (now - lastAltitudeWallSpeakTime >= ALTITUDE_WALL_SPEAK_INTERVAL_MS) {
                lastAltitudeWallSpeakTime = now
                ttsManager?.speak("Altitude limit.")
            }

            viewModelScope.launch {
                try {
                    val ok = repo?.changeMode(MavMode.BRAKE) ?: false
                    if (!ok) {
                        // Do NOT escalate to LAND here the way the breach path does. The wall
                        // is a preventive stop, not a failsafe, and landing a working aircraft
                        // because one mode change was refused is far worse than the thing it
                        // would be preventing. Let go instead: the vehicle is still below the
                        // ceiling, and layer 2 is waiting at the breach line with the
                        // operator's own FENCE_ACTION if it really does cross.
                        LogUtils.e("AltitudeWall", "✗ BRAKE not confirmed — wall could not hold; the breach layer now owns the limit")
                        releaseAltitudeWall("BRAKE refused")
                        addNotification(
                            Notification(
                                message = "⚠️ Could not hold the altitude limit (BRAKE refused). Level off manually.",
                                type = NotificationType.ERROR
                            )
                        )
                        ttsManager?.speak("Altitude hold failed. Level off.")
                    }
                } finally {
                    altitudeWallBusy = false
                }
            }
            return false   // nothing is holding yet; BRAKE has only just been sent
        }

        // ═══ HAND BACK ═══
        if (altitudeWallEngaged && !altitudeWallBusy && inBrake) {
            val arrested = climbRaw <= ALTITUDE_ARRESTED_CLIMB_MPS
            val heldMs = now - altitudeWallEngagedAtMs

            // `!wantWall` matters: handing control back while the trigger condition is still
            // true would re-engage the wall on the very next frame, and the vehicle would
            // sawtooth between BRAKE and the pilot's mode until the re-fire cap noticed. In
            // BRAKE the climb decays to zero within a second or so, at which point the
            // condition clears on its own and the hand-back goes through.
            if (arrested && !wantWall && heldMs >= ALTITUDE_WALL_HANDBACK_HOLD_MS &&
                !altitudeWallHoldLatched
            ) {
                val restore = altitudeWallPreviousMode
                val restoreName = altitudeWallPreviousModeName
                if (restore == null) {
                    // Nothing safe to give back to (the wall interrupted AUTO, GUIDED, or a
                    // recovery mode). Holding in BRAKE under the ceiling is a correct and
                    // stable place to leave the aircraft; the pilot picks it up from here.
                    if (now - lastAltitudeWallSpeakTime >= ALTITUDE_WALL_SPEAK_INTERVAL_MS) {
                        lastAltitudeWallSpeakTime = now
                        LogUtils.i("AltitudeWall", "Holding in BRAKE at ${altitude}m — interrupted mode '$restoreName' is not one to hand back to")
                        ttsManager?.speak("Holding at altitude limit.")
                    }
                } else {
                    altitudeWallBusy = true
                    viewModelScope.launch {
                        try {
                            LogUtils.i("AltitudeWall", "↩️ Climb arrested at ${altitude}m — handing control back to $restoreName")
                            val ok = repo?.changeMode(restore) ?: false
                            if (ok) {
                                LogUtils.i("AltitudeWall", "✓ Returned to $restoreName; the wall stays armed and will re-engage if the climb resumes")
                                releaseAltitudeWall("handed back to $restoreName")
                            } else {
                                // Leaving it in BRAKE is the safe failure. Say so once rather
                                // than retrying every frame against a link that is refusing.
                                LogUtils.w("AltitudeWall", "✗ Could not restore $restoreName — leaving the vehicle holding in BRAKE")
                                altitudeWallHoldLatched = true
                                addNotification(
                                    Notification(
                                        message = "⚠️ Held at the altitude limit; could not return to $restoreName. Select a mode manually.",
                                        type = NotificationType.WARNING
                                    )
                                )
                                ttsManager?.speak("Holding at altitude limit.")
                            }
                        } finally {
                            altitudeWallBusy = false
                        }
                    }
                }
            } else if (!arrested && heldMs >= ALTITUDE_WALL_ARREST_TIMEOUT_MS) {
                // BRAKE is engaged but the vehicle is still going up after six seconds. That
                // is not a wall doing its job, and returning false here is what lets layer 2
                // escalate to the operator's FENCE_ACTION.
                if (now - lastAltitudeWallSpeakTime >= ALTITUDE_WALL_SPEAK_INTERVAL_MS) {
                    lastAltitudeWallSpeakTime = now
                    LogUtils.e("AltitudeWall", "✗ Still climbing ${climbRaw}m/s at ${altitude}m after ${heldMs}ms in BRAKE — the wall is not holding")
                }
                return false
            }
        }

        // "Holding" means the wall has a claim on the vehicle AND the climb has actually
        // stopped — either confirmed in BRAKE, or inside the grace window while the mode
        // change is still in flight. Anything less and layer 2 must be free to act; in
        // particular a vehicle still climbing hard is never "holding", whatever mode it is
        // reporting.
        return altitudeWallEngaged &&
            climbRaw <= ALTITUDE_ARRESTED_CLIMB_MPS &&
            (inBrake || now - altitudeWallEngagedAtMs <= ALTITUDE_WALL_GRACE_MS)
    }

    /** Drop the wall's claim on the vehicle without commanding anything. */
    private fun releaseAltitudeWall(reason: String) {
        if (!altitudeWallEngaged) return
        altitudeWallEngaged = false
        altitudeWallEngagedAtMs = 0L
        altitudeWallPreviousMode = null
        altitudeWallPreviousModeName = null
        LogUtils.i("AltitudeWall", "Wall released — $reason")
    }

    /**
     * ArduCopter custom-mode number for a mode a PILOT flies, or null for anything else.
     *
     * Deliberately partial. It is used only to decide what the altitude wall may hand
     * control back to, so every autonomous mode — AUTO, GUIDED, RTL, LAND, SMART_RTL — maps
     * to null: restoring one of those would either resume the mission leg that flew into the
     * ceiling or countermand a recovery already in progress. The absence of an entry is the
     * safety property, not an omission.
     */
    private fun pilotModeNumber(modeName: String?): UInt? =
        when (modeName?.trim()?.lowercase(Locale.US)) {
            "stabilize" -> MavMode.STABILIZE
            "acro" -> 1u
            "althold" -> 2u
            "loiter" -> MavMode.LOITER
            "circle" -> 7u
            "drift" -> 11u
            "sport" -> 13u
            "poshold" -> MavMode.POSHOLD
            else -> null
        }

    /**
     * Width of the "approaching the limit" warning band for a given ceiling, in metres.
     *
     * A flat [ALTITUDE_WARN_MARGIN_M] is right for a 120 m ceiling and absurd for a 48 m one,
     * where it would start warning at 38 m — below even the old trigger point, and across
     * most of the height a sprayer actually works at. Capping it at
     * [ALTITUDE_WARN_MAX_FRACTION] of the ceiling keeps the band proportionate to the
     * envelope it is warning about.
     */
    private fun altitudeWarnMargin(ceiling: Float): Float =
        minOf(ALTITUDE_WARN_MARGIN_M, ceiling * ALTITUDE_WARN_MAX_FRACTION)

    /**
     * Max range failsafe — GCS-enforced circular limit centred on HOME.
     *
     * The radius comes from the vehicle's FENCE_RADIUS and the breach action from its
     * FENCE_ACTION, so behaviour still follows the parameters actually set on the drone;
     * only the *enforcement* is ours. See the constants block for why the FC's own cylinder
     * fence is not relied on.
     *
     * The trigger sits a speed-aware margin inside the radius (see [maxRangeActionMargin]) so
     * a drone cruising outward at 8 m/s turns around before crossing the limit rather than
     * after. [MAX_RANGE_WARN_LEAD_M] before that is a warning zone so the pilot can turn back
     * themselves.
     *
     * One-shot per breach so a pilot who deliberately takes back control is not fought on
     * every frame; it re-arms once they return [MAX_RANGE_REARM_INSIDE_RADIUS_M] inside the
     * RADIUS with the vehicle slowed, so a second excursion is protected like the first.
     * Independently of the latch, reaching [MAX_RANGE_BACKSTOP_MARGIN_M] of the radius
     * always re-commands the action: the fence must not be crossed.
     */
    private fun handleMaxRangeFailsafe(state: TelemetryState) {
        // Limit comes from the vehicle. No parameter, no enforcement — we must not invent a
        // radius the operator never set.
        val radius = _fenceRadiusMeters.value?.takeIf { it > 0f } ?: return

        val lat = state.latitude ?: return
        val lon = state.longitude ?: return
        val homeLat = state.homeLatitude ?: return
        val homeLon = state.homeLongitude ?: return

        val distance = GeofenceUtils.haversineDistance(LatLng(homeLat, homeLon), LatLng(lat, lon))
        val now = System.currentTimeMillis()

        // Same staleness reasoning as the altitude ceiling: a fix this old cannot tell us
        // where the drone is relative to the boundary, so decline rather than guess.
        val positionAgeMs = state.positionReceivedAtMs?.let { now - it } ?: Long.MAX_VALUE
        if (positionAgeMs > POSITION_STALE_HARD_MS) {
            if (now - lastPositionStaleWarnTime >= POSITION_STALE_WARN_INTERVAL_MS) {
                lastPositionStaleWarnTime = now
                LogUtils.w("MaxRangeFailsafe", "Position fix ${positionAgeMs}ms stale — max range failsafe cannot evaluate (dist=${distance}m)")
                ttsManager?.speak("Telemetry delayed")
            }
            return
        }

        val margin = maxRangeActionMargin(state.groundspeed, positionAgeMs)
        val actionThreshold = radius - margin
        val warnThreshold = actionThreshold - MAX_RANGE_WARN_LEAD_M

        // ═══ RECOVERED: re-arm the one-shot ═══
        // Before the action branch, not as an else-if: actionThreshold moves with
        // groundspeed, so a re-arm band expressed as an else-if could be shadowed.
        //
        // Measured from the RADIUS, not from actionThreshold, and additionally requiring the
        // vehicle to have slowed down — see [MAX_RANGE_REARM_INSIDE_RADIUS_M]. A band hung
        // off the speed-aware threshold moved with groundspeed and could sit outside where
        // the pilot actually pulled back to, leaving the latch set so that the next run at
        // the fence hit the "action already taken" branch and crossed with TTS only.
        val rearmDistance = radius - MAX_RANGE_REARM_INSIDE_RADIUS_M
        val stillRunning = (state.groundspeed ?: 0f).let { it.isFinite() && it > MAX_RANGE_REARM_MAX_SPEED_MPS }
        if (maxRangeActionTriggered && distance < rearmDistance && !stillRunning) {
            maxRangeActionTriggered = false
            lastMaxRangeLimitTime = 0L
            // Log only, matching the altitude ceiling — internal housekeeping, not a pilot
            // event.
            LogUtils.i("MaxRangeFailsafe", "Back inside range (${distance}m, below ${rearmDistance}m at ${state.groundspeed}m/s) — range action re-armed")
        }

        // ═══ HARD BACKSTOP ═══
        // True once the drone is at (or within a whisker of) the radius itself, rather than
        // merely past the speed-aware trigger point. Neither the one-shot latch nor a
        // competing recovery may suppress the action here: DGCA requires the fence not to be
        // breached, and a recovery that has let the drone reach the line is not working.
        val atBackstop = distance >= radius - MAX_RANGE_BACKSTOP_MARGIN_M

        if (distance >= actionThreshold) {

            // Never cancel a higher-priority recovery already in progress. Suppress the
            // action WITHOUT consuming the one-shot, and keep warning the pilot.
            val deferReason = when {
                _geofenceEnabled.value && _geofenceViolationDetected.value -> "geofence recovery in progress"
                voltageCriticalActive -> "critical-battery action in progress"
                else -> null
            }
            if (!maxRangeActionTriggered && deferReason != null && !atBackstop) {
                if (now - lastMaxRangeLimitTime >= MAX_RANGE_LIMIT_INTERVAL_MS) {
                    lastMaxRangeLimitTime = now
                    LogUtils.w("MaxRangeFailsafe", "Range ${distance}m over threshold ${actionThreshold}m but $deferReason — deferring (one-shot NOT consumed)")
                    ttsManager?.speak("Beyond max range.")
                }
                return
            }

            // Fire on the first crossing, and fire AGAIN whenever the drone reaches the
            // backstop despite the latch — a consumed one-shot may never be the reason a
            // fence breach goes unactioned.
            val backstopRefire = maxRangeActionTriggered && atBackstop &&
                now - lastMaxRangeLimitTime >= MAX_RANGE_LIMIT_INTERVAL_MS

            if (!maxRangeActionTriggered || backstopRefire) {
                maxRangeActionTriggered = true
                lastMaxRangeLimitTime = now

                // Action from the vehicle's FENCE_ACTION, matching the altitude ceiling.
                val fenceAction = _fenceAction.value
                val targetMode = when (fenceAction) {
                    FenceAction.RTL, FenceAction.SMART_RTL, FenceAction.SMART_RTL_LAND -> MavMode.RTL
                    FenceAction.ALWAYS_LAND -> MavMode.LAND
                    FenceAction.BRAKE -> MavMode.BRAKE
                    FenceAction.REPORT_ONLY -> null
                    // No parameter read: RTL is the safe default for a distance breach —
                    // braking in place leaves the drone stranded at the edge of range.
                    null -> MavMode.RTL
                }
                val targetModeName = when (targetMode) {
                    MavMode.RTL -> "RTL"
                    MavMode.LAND -> "LAND"
                    MavMode.BRAKE -> "BRAKE"
                    else -> "REPORT ONLY"
                }
                val announced = fenceAction?.pilotLabel ?: "RTL"

                if (backstopRefire) {
                    LogUtils.w("MaxRangeFailsafe", "MAX RANGE BACKSTOP: ${distance}m is at/over the ${radius}m limit and the one-shot was already consumed — RE-COMMANDING $announced, mode=${state.mode}")
                } else {
                    LogUtils.i("MaxRangeFailsafe", "MAX RANGE: ${distance}m >= threshold ${actionThreshold}m (FENCE_RADIUS ${radius}m, ${margin}m margin at ${state.groundspeed}m/s) — triggering $announced, mode=${state.mode}")
                }

                ttsManager?.speak("Max range reached. Activating $announced.")

                // One notification per breach, not per re-command — see the altitude
                // ceiling's equivalent guard.
                if (!backstopRefire) {
                    addNotification(
                        Notification(
                            message = "⛔ MAX RANGE: ${String.format(Locale.US, "%.0f", distance)}m of ${String.format(Locale.US, "%.0f", radius)}m limit — activating $announced",
                            type = NotificationType.ERROR
                        )
                    )
                }
                showFailsafePopup("Max Range")

                viewModelScope.launch {
                    if (targetMode == null) {
                        LogUtils.i("MaxRangeFailsafe", "FENCE_ACTION=Report Only — alerting the pilot, taking no mode action")
                    } else {
                        executeFailsafeModeChange("MaxRangeFailsafe", targetMode, targetModeName)
                    }
                    try {
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MAX_RANGE",
                            eventStatus = "CRITICAL",
                            description = "Range ${String.format(Locale.US, "%.1f", distance)}m reached limit ${String.format(Locale.US, "%.1f", radius)}m - $targetModeName activated"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("MaxRangeFailsafe", "Failed to send max range event", e)
                    }
                }
            } else if (now - lastMaxRangeLimitTime >= MAX_RANGE_LIMIT_INTERVAL_MS) {
                // TTS only. Reached when the latch is set AND the drone is still inside the
                // backstop — past the speed-aware trigger but clear of the radius, the one
                // case where letting the pilot fly is right. At the backstop, backstopRefire
                // above takes the action branch instead.
                lastMaxRangeLimitTime = now
                LogUtils.i("MaxRangeFailsafe", "Past range trigger: ${distance}m of ${radius}m (action already taken; inside backstop)")
                ttsManager?.speak("Beyond max range. ${distance.toInt()} meters.")
            }
        }
        // Approaching the trigger point — TTS + log only, so the pilot can turn back
        // themselves. No notification, for the same reason as the altitude approach warning
        // above: a repeating "getting close" entry every 4s crowds out the breach records.
        else if (distance >= warnThreshold) {
            if (now - lastMaxRangeWarnTime >= MAX_RANGE_WARN_INTERVAL_MS) {
                lastMaxRangeWarnTime = now
                LogUtils.i("MaxRangeFailsafe", "Approaching max range: ${distance}m of ${radius}m (action at ${actionThreshold}m)")
                ttsManager?.speak("Approaching max range. ${distance.toInt()} meters.")
            }
        }
    }

    /**
     * How far inside the radius the range action fires, in metres.
     *
     * RTL is not instantaneous: the drone keeps flying outward for the command latency and
     * then for its braking distance. A flat buffer is not enough at cruise — at 8 m/s the
     * drone travels ~8m during the round trip and needs ~13m more to stop, so the margin
     * tracks speed:
     *
     *     margin = v · (age + [MAX_RANGE_LATENCY_S]) + v² / (2 · [MAX_RANGE_DECEL_MPS2])
     *
     * clamped to [[MAX_RANGE_MIN_ACTION_MARGIN_M], [MAX_RANGE_MAX_ACTION_MARGIN_M]]. At 8 m/s
     * that is 8·1.4 + 64/4 = 11.2 + 16 ≈ 27m, so on a 1000m fence RTL fires around 973m.
     * The earlier 1.0 s / 2.5 m/s² figures gave ~21m and the drone still crossed the line by
     * a metre or two at that speed — the FC's mode-entry delay and the real achieved
     * deceleration of a loaded airframe were both optimistic. Hovering falls back to the
     * 12m floor.
     *
     * positionAgeMs adds the distance already flown since the fix being judged was measured,
     * so the margin grows when the link degrades rather than silently under-budgeting.
     */
    private fun maxRangeActionMargin(groundspeed: Float?, positionAgeMs: Long = 0L): Float {
        val v = groundspeed?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val ageS = positionAgeMs.coerceAtLeast(0L) / 1000f
        val stoppingDistance = v * (ageS + MAX_RANGE_LATENCY_S) + (v * v) / (2f * MAX_RANGE_DECEL_MPS2)
        return stoppingDistance.coerceIn(MAX_RANGE_MIN_ACTION_MARGIN_M, MAX_RANGE_MAX_ACTION_MARGIN_M)
    }

    /**
     * True when the vehicle is already in a mode the altitude failsafe itself commands as a
     * recovery — i.e. the action has been taken and is running.
     *
     * Used to stop the hard backstop from re-commanding an action that is already working.
     * BRAKE counts: it is the arrest step, and a vehicle holding in BRAKE under the ceiling
     * is exactly the outcome the ceiling wants.
     */
    private fun inCommandedRecoveryMode(): Boolean {
        val mode = _telemetryState.value.mode ?: return false
        return mode.contains("RTL", ignoreCase = true) ||
            mode.contains("Brake", ignoreCase = true) ||
            mode.equals("Land", ignoreCase = true)
    }

    /**
     * Wait for the vertical climb to stop after a BRAKE, so the follow-on action is not
     * handed a still-rising vehicle.
     *
     * Bounded: BRAKE is decisive on a multirotor, but the failsafe must never park here
     * waiting on telemetry that has gone quiet — timing out and proceeding to RTL is
     * strictly better than doing nothing at all.
     */
    private suspend fun awaitClimbArrested(
        timeoutMs: Long = ALTITUDE_BRAKE_SETTLE_TIMEOUT_MS
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val climb = _telemetryState.value.climbRate
            if (climb != null && climb.isFinite() && climb <= ALTITUDE_ARRESTED_CLIMB_MPS) {
                LogUtils.i("AltitudeFailsafe", "✓ Climb arrested (${climb}m/s) after ${System.currentTimeMillis() - start}ms")
                return
            }
            delay(100L)
        }
        LogUtils.w("AltitudeFailsafe", "⚠️ Climb not confirmed arrested within ${timeoutMs}ms (climb=${_telemetryState.value.climbRate}m/s) — proceeding anyway")
    }

    /**
     * Distance the vehicle will still travel upward if BRAKE is commanded on this frame, in
     * metres. Three terms, with the fix age made explicit:
     *
     *     stop = v · (age + [ALTITUDE_LATENCY_S] + [ALTITUDE_EVAL_INTERVAL_S])
     *            + v² / (2 · [ALTITUDE_DECEL_MPS2])
     *
     * The age term is not decoration. A telemetry link carrying live rangefinder traffic can
     * degrade GLOBAL_POSITION_INT from 10 Hz to 1-2 Hz, and the drone keeps climbing through
     * the gap — acting on a one-second-old fix as though it were current is how the ceiling
     * used to be overshot even when the maths was otherwise right.
     *
     * Only a positive climb counts; a descending vehicle is not heading anywhere dangerous.
     * The result is clamped at [ALTITUDE_MAX_STOP_DISTANCE_M] so a single absurd climb-rate
     * sample cannot swallow the whole envelope.
     *
     * NOTE there is no lower clamp any more. The old version floored this at 6 m and used it
     * as an RTL trigger offset, which is precisely what made a 48 m ceiling behave like a
     * 42 m one. A vehicle in level flight has a stopping distance of zero, and the wall is
     * built to let it say so.
     */
    private fun altitudeStopDistance(climbRate: Float?, positionAgeMs: Long): Float {
        val v = climbRate?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val ageS = positionAgeMs.coerceAtLeast(0L) / 1000f
        val travel = v * (ageS + ALTITUDE_LATENCY_S + ALTITUDE_EVAL_INTERVAL_S) +
            (v * v) / (2f * ALTITUDE_DECEL_MPS2)
        return travel.coerceIn(0f, ALTITUDE_MAX_STOP_DISTANCE_M)
    }

    /**
     * Where the vehicle would come to rest if BRAKE were commanded right now.
     *
     * This is the single number the altitude wall is built around. Comparing a PROJECTION
     * against the limit, rather than the current altitude against a limit-minus-margin, is
     * what lets the ceiling be strict and generous at the same time: strict because the
     * projection accounts for momentum, latency and fix age before the vehicle is committed,
     * generous because a vehicle that is not climbing projects exactly where it already is
     * and is therefore left alone.
     */
    private fun projectedStopAltitude(
        altitude: Float,
        climbRate: Float?,
        positionAgeMs: Long
    ): Float = altitude + altitudeStopDistance(climbRate, positionAgeMs)

    /**
     * Called automatically when isMissionActive transitions from false to true.
     * This ensures WebSocket telemetry logging starts regardless of how the mission was initiated.
     */
    private fun onMissionBecameActive() {
        // The backend session (session_start → Mission) is no longer opened here. Opening it the
        // instant a mission "becomes active" (before takeoff) created phantom missions when the
        // drone armed but never flew. UnifiedFlightTracker.openBackendSession() now opens the
        // session from its takeoff gate, for both AUTO and MANUAL flights. Kept as a hook for
        // observability only.
        LogUtils.i("SharedVM", "🚀 Mission became active — backend session will open on takeoff")
    }

    /**
     * Clear all mission-related waypoints and polygons from the map.
     * Called when mission is completed or when user navigates to select a new mode.
     */
    fun clearMissionFromMap() {
        LogUtils.i("SharedVM", "Clearing mission data from map (including geofence)")
        _uploadedWaypoints.value = emptyList()
        _gridWaypoints.value = emptyList()
        _surveyPolygon.value = emptyList()
        _gridLines.value = emptyList()
        _planningWaypoints.value = emptyList()
        _obstacles.value = emptyList()  // Clear obstacle zones
        _missionAreaSqMeters.value = 0.0
        _missionAreaFormatted.value = "0 acres"
        _missionUploaded.value = false
        lastUploadedCount = 0
        lastUploadedMissionItems = emptyList()

        // Clear geofence when clearing mission
        clearGeofence()
    }

    /**
     * Clear all mission lines and waypoints from the map WITHOUT clearing geofence.
     * Called from the "Clear Map" button in Manual mode on the MainPage.
     */
    fun clearMapLinesOnly() {
        LogUtils.i("SharedVM", "Clearing map lines/waypoints only (geofence preserved)")
        _uploadedWaypoints.value = emptyList()
        _gridWaypoints.value = emptyList()
        _surveyPolygon.value = emptyList()
        _gridLines.value = emptyList()
        _planningWaypoints.value = emptyList()
        _obstacles.value = emptyList()
        _missionAreaSqMeters.value = 0.0
        _missionAreaFormatted.value = "0 acres"
        _missionUploaded.value = false
        lastUploadedCount = 0
        lastUploadedMissionItems = emptyList()
        // Geofence is intentionally NOT cleared here
        // Drop the flown trail. The trigger is kept for any observer still keyed to it; the
        // trail itself now lives here, so it must be cleared here too.
        clearDronePath()
        _clearDronePathTrigger.value++
    }

    /**
     * Clear mission completely - from both FC and map.
     * Also resets all pause/resume state.
     * Called when user navigates to home tab or goes back while mission is paused.
     */
    fun clearMissionCompletely() {
        LogUtils.i("SharedVM", "🧹 Clearing mission completely (FC + map + pause/resume state)")

        // Step 1: Clear mission from FC
        viewModelScope.launch {
            try {
                val cleared = repo?.clearMissionFromFC() ?: false
                if (cleared) {
                    LogUtils.i("SharedVM", "✅ Mission cleared from FC")
                } else {
                    LogUtils.w("SharedVM", "⚠️ Failed to clear mission from FC (may not be connected)")
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "❌ Error clearing mission from FC", e)
            }
        }

        // Step 2: Clear map data
        clearMissionFromMap()

        // Step 3: Reset all pause/resume state
        _resumePointLocation.value = null
        _resumePointWaypoint.value = null
        _resumeMissionReady.value = false
        _resumePreparationFailed.value = false
        _resumePreparationInProgress.value = false
        _showAddResumeHerePopup.value = false
        _pendingResumeLocation = null
        _missionPauseLocation = null
        _sprayWasActiveBeforePause = false

        // Step 4: Reset telemetry paused state
        _telemetryState.update {
            it.copy(
                missionPaused = false,
                pausedAtWaypoint = null
            )
        }

        LogUtils.i("SharedVM", "✅ Mission completely cleared")
    }

    /**
     * Clear the geofence polygon and disable geofence monitoring.
     * Called when navigating away from mission or when user disables geofence.
     * Uses Mission Planner-style approach to clear fence from FC.
     */
    fun clearGeofence() {
        LogUtils.i("SharedVM", "🔥 Clearing geofence from UI and FC")

        // STEP 1: Immediately clear ALL local state FIRST
        // This prevents stale fence state from causing false warnings or arm blocks
        stopFenceStatusMonitoring()
        _geofenceEnabled.value = false
        _geofencePolygon.value = emptyList()
        _fenceConfiguration.value = null
        _homePosition.value = null
        resetGeofenceState()

        // STEP 2: Then attempt to clear geofence on FC (best effort, async)
        viewModelScope.launch {
            try {
                val cleared = repo?.clearGeofenceFromFC() ?: false
                if (cleared) {
                    LogUtils.i("Geofence", "✅ Geofence cleared from FC")
                } else {
                    // Fallback: just disable the fence parameter
                    repo?.enableFence(false)
                    LogUtils.i("Geofence", "✅ Geofence disabled on FC (fallback)")
                }
            } catch (e: Exception) {
                LogUtils.e("Geofence", "❌ Failed to clear geofence from FC", e)
                // Best-effort fallback
                try { repo?.enableFence(false) } catch (_: Exception) {}
            }
        }

        LogUtils.i("SharedVM", "✅ Geofence cleared from UI and FC")
    }

    // Initialize TTS with context
    fun initializeTextToSpeech(context: Context) {
        if (ttsManager == null) {
            ttsManager = TextToSpeechManager(context)
            LogUtils.d("SharedVM", "TextToSpeech initialized")
        }
    }

    // Set the language for TTS
    fun setLanguage(languageCode: String) {
        ttsManager?.setLanguage(languageCode)
        // Also update the app-wide language for UI strings
        com.example.kftgcs.utils.AppStrings.setLanguage(languageCode)
        LogUtils.d("SharedVM", "Language set to: $languageCode")
    }

    // Announce language selection
    fun announceLanguageSelected(languageCode: String) {
        ttsManager?.announceLanguageSelected(languageCode)
    }

    // TTS announcement methods
    fun announceCalibrationStarted() {
        ttsManager?.announceCalibrationStarted()
    }

    fun announceCalibrationFinished(isSuccess: Boolean = true) {
        ttsManager?.announceCalibrationFinished(isSuccess)
    }

    fun announceCalibrationFinished() {
        ttsManager?.announceCalibrationFinished()
    }

    fun announceConnectionFailed() {
        ttsManager?.announceConnectionFailed()
    }

    // ========== USER SELECTED FLIGHT MODE (Manual vs Automatic) ==========
    // This tracks whether the user selected Manual or Automatic mode in SelectFlyingMethodScreen
    // Used to determine if pause/resume functionality should be enabled
    enum class UserFlightMode {
        AUTOMATIC,  // User selected Automatic - pause/resume enabled
        MANUAL      // User selected Manual - pause/resume disabled
    }

    private val _userSelectedFlightMode = MutableStateFlow(UserFlightMode.AUTOMATIC)
    val userSelectedFlightMode: StateFlow<UserFlightMode> = _userSelectedFlightMode.asStateFlow()

    // ========== MISSION TYPE (Grid vs Waypoint) ==========
    // This tracks the type of mission being executed
    enum class MissionType {
        NONE,       // No mission type selected
        GRID,       // Grid/survey mission
        WAYPOINT    // Waypoint mission
    }

    private val _selectedMissionType = MutableStateFlow(MissionType.NONE)
    val selectedMissionType: StateFlow<MissionType> = _selectedMissionType.asStateFlow()

    fun setMissionType(type: MissionType) {
        _selectedMissionType.value = type
        LogUtils.i("SharedVM", "📋 Mission type set to: ${type.name}")
    }

    // ========== GRID SETUP SOURCE (How the grid boundary was created) ==========
    // This tracks how the user created the grid boundary
    enum class GridSetupSource {
        NONE,           // No grid setup source selected
        KML_IMPORT,     // Grid boundary imported from KML file
        MAP_DRAW,       // Grid boundary drawn on map
        DRONE_POSITION, // Grid boundary based on drone position
        RC_CONTROL      // Grid boundary controlled by RC
    }

    private val _gridSetupSource = MutableStateFlow(GridSetupSource.NONE)
    val gridSetupSource: StateFlow<GridSetupSource> = _gridSetupSource.asStateFlow()

    fun setGridSetupSource(source: GridSetupSource) {
        _gridSetupSource.value = source
        LogUtils.i("SharedVM", "📋 Grid setup source set to: ${source.name}")
    }

    /**
     * Check if pause/resume functionality should be enabled
     * Returns true only if user selected Automatic mode
     */
    fun isPauseResumeEnabled(): Boolean {
        return _userSelectedFlightMode.value == UserFlightMode.AUTOMATIC
    }

    fun announceSelectedAutomatic() {
        _userSelectedFlightMode.value = UserFlightMode.AUTOMATIC
        LogUtils.i("SharedVM", "User selected AUTOMATIC mode - pause/resume ENABLED")
        ttsManager?.announceSelectedAutomatic()
    }

    fun announceSelectedManual() {
        _userSelectedFlightMode.value = UserFlightMode.MANUAL
        LogUtils.i("SharedVM", "User selected MANUAL mode - pause/resume DISABLED")
        ttsManager?.announceSelectedManual()
    }

    fun announceCalibration(calibrationType: String) {
        ttsManager?.announceCalibration(calibrationType)
    }

    fun announceIMUPosition(position: String) {
        // Use the once-per-key announcement to avoid repeated playback when UI triggers multiple events
        ttsManager?.announceIMUPositionOnce(position)
    }

    fun announceRebootDrone() {
        ttsManager?.announceRebootDrone()
    }

    fun announceDroneArmed() {
        ttsManager?.announceDroneArmed()
    }

    fun announceDroneDisarmed() {
        ttsManager?.announceDroneDisarmed()
    }

    fun announceRCBatteryFailsafe(batteryPercent: Int) {
        ttsManager?.speak("Warning! RC battery critical at $batteryPercent percent. Emergency RTL activated.")
    }

    fun announceTankEmpty() {
        ttsManager?.speak("Warning! Tank is empty. Please refill the tank.")
    }

    /**
     * Handle tank empty detection in ANY flight mode (AUTO or MANUAL).
     * This will:
     * 1. Change mode to the user's selected tank empty action (LOITER/RTL/LAND)
     * 2. The mode change detection will automatically trigger appropriate actions
     *
     * Called from TelemetryRepository when tank empty is detected.
     */
    fun handleTankEmpty() {
        viewModelScope.launch {
            try {
                val currentMode = _telemetryState.value.mode
                val currentWp = _telemetryState.value.currentWaypoint
                val lastAutoWp = _telemetryState.value.lastAutoWaypoint
                
                // Check if we're in AUTO mode (for mission status updates)
                val isInAutoMode = currentMode?.equals("Auto", ignoreCase = true) == true
                
                // Skip if already in a safe mode (RTL or LAND)
                if (currentMode?.equals("RTL", ignoreCase = true) == true ||
                    currentMode?.equals("Land", ignoreCase = true) == true) {
                    LogUtils.d("SharedVM", "Tank empty detected but already in safe mode ($currentMode) - skipping")
                    return@launch
                }

                // Get the user's selected tank empty action from settings.
                // Manual flight and Auto missions have separate configured actions.
                val context = GCSApplication.getInstance()
                val tankEmptyAction = if (context != null) {
                    getTankEmptyAction(context, isInAutoMode)
                } else {
                    "HOVER" // Default fallback
                }

                LogUtils.i("SharedVM", "=== TANK EMPTY DETECTED ===")
                LogUtils.i("SharedVM", "Current flight mode: $currentMode (isAuto=$isInAutoMode)")
                LogUtils.i("SharedVM", "User configured action (${if (isInAutoMode) "Auto" else "Manual"}): $tankEmptyAction")
                LogUtils.i("SharedVM", "Current waypoint: $currentWp, Last AUTO waypoint: $lastAutoWp")

                // Report Only: do not change flight mode. The drone keeps flying;
                // we simply notify the user and backend that the tank is empty.
                if (tankEmptyAction.equals("REPORT_ONLY", ignoreCase = true)) {
                    LogUtils.i("SharedVM", "Report Only selected - reporting tank empty without changing mode")
                    ttsManager?.speak("Tank Empty")
                    addNotification(
                        Notification(
                            message = "Tank empty - report only (drone continues flying)",
                            type = NotificationType.WARNING
                        )
                    )
                    showFailsafePopup("Tank Empty")
                    try {
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "TANK_EMPTY_REPORT",
                            eventStatus = "WARNING",
                            description = "Tank empty in ${currentMode ?: "Unknown"} mode - action: Report Only"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to send tank empty report status", e)
                    }
                    return@launch
                }

                // Determine the MAVLink mode based on user's setting
                // Note: "HOVER" or "LOITER" setting uses BRAKE mode to keep drone in place
                val targetMode = when (tankEmptyAction.uppercase()) {
                    "RTL" -> MavMode.RTL
                    "LAND" -> MavMode.LAND
                    else -> MavMode.BRAKE // Use BRAKE mode for hover - keeps drone in place
                }

                val modeName = when (targetMode) {
                    MavMode.RTL -> "RTL"
                    MavMode.LAND -> "LAND"
                    else -> "BRAKE"
                }

                LogUtils.i("SharedVM", "Switching to $modeName mode for tank empty")

                // Change mode to user's selected action.
                //
                // NOTE: deliberately does NOT use executeFailsafeModeChange(). Tank empty is a
                // mission-pause condition, not a safety-of-flight one — the drone is fine, it
                // just has nothing left to spray. Escalating a failed BRAKE to an automatic
                // LAND here could put the aircraft down in the middle of a field for a
                // non-emergency. changeMode() already retries internally, which is the right
                // amount of persistence for this case; if it still fails the pilot is told.
                val result = repo?.changeMode(targetMode) ?: false

                if (result) {
                    LogUtils.i("SharedVM", "$modeName mode command sent successfully")
                    
                    // TTS announcement for tank empty
                    ttsManager?.speak("Tank Empty")
                    showFailsafePopup("Tank Empty")

                    // Send mission status to backend (only in AUTO mode)
                    try {
                        if (isInAutoMode) {
                            WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_PAUSED)
                        }
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "TANK_EMPTY_PAUSE",
                            eventStatus = "WARNING",
                            description = "Tank empty in ${currentMode ?: "Unknown"} mode - action: $modeName"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to send tank empty pause status", e)
                    }
                } else {
                    LogUtils.e("SharedVM", "Failed to send $modeName mode command for tank empty")
                    addNotification(
                        Notification(
                            message = "Failed to switch to $modeName mode for tank refill",
                            type = NotificationType.ERROR
                        )
                    )
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Error handling tank empty", e)
            }
        }
    }

    /**
     * Legacy function for backward compatibility - redirects to handleTankEmpty()
     * @deprecated Use handleTankEmpty() instead
     */
    fun handleTankEmptyInAutoMode() {
        handleTankEmpty()
    }

    // ═══ SVD (DGCA inspection) build ═══
    // The SVD flavour ships without the Options page, so the pilot cannot configure the
    // failsafe actions at all. They are fixed at RTL, and the voltage thresholds are read
    // back from the flight controller instead of being pushed down — the FC's own
    // parameters are the single source of truth for the inspection.
    private val isSvdFlavor = BuildConfig.FLAVOR == "svd"
    private val svdFixedFailsafeAction = "RTL"

    /**
     * Get the user's tank empty action setting from SharedPreferences.
     * Manual flight and Auto missions are configured separately; [isAuto] selects which one.
     * Falls back to the legacy single "tank_empty_action" value for users who haven't
     * re-saved settings since the per-mode split.
     * On SVD it is fixed at RTL — there is no UI to change it.
     */
    private fun getTankEmptyAction(context: Context, isAuto: Boolean): String {
        if (isSvdFlavor) return svdFixedFailsafeAction
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        val legacy = prefs.getString("tank_empty_action", "HOVER") ?: "HOVER"
        val key = if (isAuto) "tank_empty_action_auto" else "tank_empty_action_manual"
        return prefs.getString(key, legacy) ?: legacy
    }

    /**
     * The tank empty action as a single display line for the pre-arm summary popup.
     * Collapses to one word when Manual and Auto agree, which is the common case.
     */
    private fun describeTankEmptyAction(context: Context): String {
        val manual = getTankEmptyAction(context, isAuto = false)
        val auto = getTankEmptyAction(context, isAuto = true)
        return if (manual.equals(auto, ignoreCase = true)) manual
        else "$manual / $auto (Manual / Auto)"
    }

    // ═══ Voltage thresholds — 12S defaults with a 6S fallback ═══
    // The out-of-the-box thresholds are DEFAULT_LOW_VOLT_1 / DEFAULT_LOW_VOLT_2 (43V /
    // 42V), which suit the 12S fleet. They cannot be applied blindly to a 6S pack: 42V
    // is a normal in-flight voltage for 12S but an impossible one for 6S, so a 6S drone
    // would sit below its "critical" threshold from the moment it powered on and trip
    // the failsafe on the first arm. So when live telemetry clearly shows a 6S pack we
    // derive the thresholds per-cell instead. An explicitly saved value beats both.
    private val PER_CELL_WARN_V = 3.6f   // per-cell low/warning voltage
    private val PER_CELL_CRIT_V = 3.5f   // per-cell critical voltage

    /**
     * Cell count latched at the arm transition and held for the whole arm cycle.
     *
     * [detectCellCount] used to be re-evaluated on every telemetry frame, so one bad reading
     * below the 6S/12S split reclassified a 12S pack as 6S and silently dropped the critical
     * threshold from 42V to 21V — a failsafe that no longer exists, which fails quietly in
     * the air. Latching means the thresholds a flight starts with are the ones it ends with.
     */
    private var latchedCellCount: Int? = null

    /**
     * Detect LiPo cell count from the live summed pack voltage.
     * 6S sits ~22-25V, 12S sits ~44-50V.
     *
     * The 26-38V gap is a deliberate dead band, not a gap to be filled: the only readings
     * that land there are partial cell-sums (a 12S pack missing its voltagesExt cells reads
     * ~36.7V) or a pack too dead to fly. Neither should be allowed to pick thresholds, so
     * they return null and the caller keeps the 12S-safe default. The old 35V split
     * classified that same ~36.7V partial sum as a healthy 12S purely by luck.
     */
    private fun detectCellCount(packVoltage: Float?): Int? {
        val v = packVoltage ?: return null
        return when {
            v >= 38f          -> 12   // 12S: full ~50.4V, nominal ~44.4V, critical ~42V
            v in 15f..26f     -> 6    // 6S: full ~25.2V, nominal ~22.2V, critical ~21V
            else              -> null // implausible or ambiguous — do not classify
        }
    }

    /**
     * Cell count to size the default thresholds with: the value latched at arm if we have
     * one, otherwise a live detection. Falling back to 43V/42V when neither is conclusive is
     * the safe direction — a 6S drone with a 42V threshold fails loudly on the ground, while
     * a 12S drone with a 21V threshold fails silently in flight.
     */
    private fun effectiveCellCount(packVoltage: Float?): Int? =
        latchedCellCount ?: detectCellCount(packVoltage)

    /** Default warning (level 1) voltage: 43V, or the per-cell figure on a known 6S pack. */
    private fun defaultWarnVoltage(packVoltage: Float?): Float =
        if (effectiveCellCount(packVoltage) == 6) 6 * PER_CELL_WARN_V else DEFAULT_LOW_VOLT_1

    /** Default critical (level 2) voltage: 42V, or the per-cell figure on a known 6S pack. */
    private fun defaultCritVoltage(packVoltage: Float?): Float =
        if (effectiveCellCount(packVoltage) == 6) 6 * PER_CELL_CRIT_V else DEFAULT_LOW_VOLT_2

    /**
     * Get the low-voltage level 1 (warning) threshold.
     * Honors an explicitly saved value; otherwise 43V, unless the live pack voltage
     * identifies a 6S drone that a 12S threshold would be nonsensical for.
     */
    private fun getLowVoltLevel1(context: Context): Float {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        if (prefs.contains("low_volt_level_1")) return prefs.getFloat("low_volt_level_1", DEFAULT_LOW_VOLT_1)
        return defaultWarnVoltage(_telemetryState.value.voltage)
    }

    /**
     * Get the low-voltage level 2 (critical) threshold.
     * Honors an explicitly saved value; otherwise 42V, with the same 6S fallback.
     */
    private fun getLowVoltLevel2(context: Context): Float {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        if (prefs.contains("low_volt_level_2")) return prefs.getFloat("low_volt_level_2", DEFAULT_LOW_VOLT_2)
        return defaultCritVoltage(_telemetryState.value.voltage)
    }

    /**
     * Get the user's low voltage level 2 (critical) action from SharedPreferences.
     * On SVD it is fixed at RTL — there is no UI to change it.
     */
    private fun getLowVoltLevel2Action(context: Context): String {
        if (isSvdFlavor) return svdFixedFailsafeAction
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getString("low_volt_level_2_action", "HOVER") ?: "HOVER"
    }

    // ═══ Altitude ceiling (FENCE_ALT_MAX) ═══

    /** Whether the altitude ceiling failsafe is armed. On by default — it's a safety limit. */
    private fun isAltitudeFailsafeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getBoolean("max_altitude_enabled", true)
    }

    /** Configured altitude ceiling in metres AGL — mirrors the FC's FENCE_ALT_MAX. */
    private fun getMaxAltitude(context: Context): Float {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getFloat("max_altitude", DEFAULT_MAX_ALTITUDE_M)
    }

    /** What the GCS does when the ceiling is reached: HOVER (BRAKE) / RTL / LAND. */
    private fun getMaxAltitudeAction(context: Context): String {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getString("max_altitude_action", "HOVER") ?: "HOVER"
    }

    /**
     * The altitude ceiling other components should respect (geofence upload, mission
     * planning). Returns the configured value, or null when the failsafe is disabled.
     */
    fun getAltitudeCeiling(): Float? {
        val context = GCSApplication.getInstance() ?: return null
        if (!isAltitudeFailsafeEnabled(context)) return null
        return getMaxAltitude(context).takeIf { it > 0f }
    }

    /**
     * Slack subtracted from the pilot's ceiling before it is written to the FC's
     * FENCE_ALT_MAX.
     *
     * ArduPilot does not stop AT FENCE_ALT_MAX — it detects the breach and then arrests the
     * climb, so the aircraft coasts past by its remaining vertical momentum. Writing the
     * pilot's ceiling verbatim therefore guaranteed a small exceedance: a 30 m setting
     * peaked at ~30.5 m. Biasing the FC limit down means the overshoot happens BELOW the
     * number the pilot (and an auditor) is holding us to.
     *
     * 1.0 m covers the ~0.5 m observed at normal climb rates with margin to spare, and
     * costs only that much usable altitude.
     */
    val FC_ALT_FENCE_SAFETY_OFFSET_M = 1.0f

    /**
     * The value to write to the FC's FENCE_ALT_MAX: the pilot's ceiling less
     * [FC_ALT_FENCE_SAFETY_OFFSET_M], floored so a very low ceiling cannot go non-positive
     * (which ArduPilot would read as "no altitude fence").
     */
    fun getFcAltitudeFenceMax(): Float? {
        val ceiling = getAltitudeCeiling() ?: return null
        return (ceiling - FC_ALT_FENCE_SAFETY_OFFSET_M).coerceAtLeast(1f)
    }

    fun speak(text: String) {
        ttsManager?.speak(text)
    }

    // ═══════════════════════════════════════════════════════════════
    //  VIDEO TRACKING ACTIONS
    // ═══════════════════════════════════════════════════════════════

    /** Handle single tap on video feed — initiate point tracking */
    fun onVideoTap(normX: Float, normY: Float) {
        viewModelScope.launch {
            trackingManager?.onVideoTap(normX, normY, _telemetryState.value)
        }
    }

    /** Handle drag on video feed — initiate rectangle tracking */
    fun onVideoDragComplete(startX: Float, startY: Float, endX: Float, endY: Float) {
        viewModelScope.launch {
            trackingManager?.onVideoDragComplete(startX, startY, endX, endY, _telemetryState.value)
        }
    }

    /** Handle long-press on video feed — move gimbal to point */
    fun onVideoLongPress(normX: Float, normY: Float) {
        viewModelScope.launch {
            trackingManager?.onVideoLongPress(normX, normY, _telemetryState.value)
        }
    }

    /** Stop active tracking */
    fun stopVideoTracking() {
        viewModelScope.launch {
            trackingManager?.stopTracking()
        }
    }

    /** Nudge gimbal by delta angles */
    fun nudgeGimbal(deltaPitchDeg: Float, deltaYawDeg: Float) {
        viewModelScope.launch {
            trackingManager?.nudgeGimbal(deltaPitchDeg, deltaYawDeg)
        }
    }

    /** Clear gimbal ROI / return to neutral */
    fun clearGimbalROI() {
        viewModelScope.launch {
            trackingManager?.gimbalController?.clearROI()
        }
    }

    /**
     * Reset TTS "spoken keys" so speakOnce can be used again for the same logical keys.
     * Call this at the start or end of a calibration run to allow announcements to replay.
     */
    fun resetTtsSpokenKeys() {
        ttsManager?.resetAllSpoken()
    }

    // --- Area (survey / mission) state ---
    // Area of the currently drawn survey polygon (sq meters)
    private val _surveyAreaSqMeters = MutableStateFlow(0.0)
    val surveyAreaSqMeters: StateFlow<Double> = _surveyAreaSqMeters.asStateFlow()

    // Formatted area string for display (reuses GridUtils formatting to match Grid statistics)
    private val _surveyAreaFormatted = MutableStateFlow("0 acres")
    val surveyAreaFormatted: StateFlow<String> = _surveyAreaFormatted.asStateFlow()

    // Area captured at the time of mission upload (so telemetry shows the uploaded mission's area)
    private val _missionAreaSqMeters = MutableStateFlow(0.0)
    val missionAreaSqMeters: StateFlow<Double> = _missionAreaSqMeters.asStateFlow()

    private val _missionAreaFormatted = MutableStateFlow("0 acres")
    val missionAreaFormatted: StateFlow<String> = _missionAreaFormatted.asStateFlow()

    // Mission upload progress state
    private val _missionUploadProgress = MutableStateFlow<MissionUploadProgress?>(null)
    val missionUploadProgress: StateFlow<MissionUploadProgress?> = _missionUploadProgress.asStateFlow()

    private fun updateSurveyArea() {
        val polygon = _surveyPolygon.value
        if (polygon.size >= 3) {
            val areaMeters = GridUtils.calculatePolygonArea(polygon)
            val formatted = GridUtils.calculateAndFormatPolygonArea(polygon)
            _surveyAreaSqMeters.value = areaMeters
            _surveyAreaFormatted.value = formatted
        } else {
            _surveyAreaSqMeters.value = 0.0
            _surveyAreaFormatted.value = "0 acres"
        }
    }

    // --- Connection State Management ---
    private val _connectionType = mutableStateOf(ConnectionType.TCP)
    val connectionType: State<ConnectionType> = _connectionType

    private val _ipAddress = mutableStateOf("10.0.2.2")
    val ipAddress: State<String> = _ipAddress

    private val _port = mutableStateOf("5762")
    val port: State<String> = _port

    // --- UDP ---
    // Local port the GCS listens on. Skydroid's own documentation says to connect the ground
    // station over UDP with the listening port set to 14551, so that is the default here.
    private val _udpLocalPort = mutableStateOf("14551")
    val udpLocalPort: State<String> = _udpLocalPort

    // Optional remote host. BLANK BY DEFAULT — this matters.
    //
    // Both reference ground stations bind and wait rather than inventing a target:
    //  - QGroundControl's UDPWorker::writeData sends only to user-configured targetHosts() and to
    //    _sessionTargets learned from received datagrams; with neither, it transmits nothing, and
    //    it adds no automatic localhost target.
    //  - Mission Planner opens a UdpClient on the port, transmits nothing unprompted, and takes the
    //    remote endpoint from the source address of the first packet it receives.
    //
    // Pre-filling 127.0.0.1 made us the odd one out: we seeded a peer nobody asked for and fired an
    // unsolicited heartbeat at it. Blank means pure listen mode, matching the vendor instructions
    // and both reference implementations.
    private val _udpRemoteHost = mutableStateOf("")
    val udpRemoteHost: State<String> = _udpRemoteHost

    // Port we transmit to, used only when a remote host is explicitly entered.
    private val _udpRemotePort = mutableStateOf("14550")
    val udpRemotePort: State<String> = _udpRemotePort

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val _selectedDevice = mutableStateOf<PairedDevice?>(null)
    val selectedDevice: State<PairedDevice?> = _selectedDevice

    fun onConnectionTypeChange(newType: ConnectionType) {
        _connectionType.value = newType
    }

    fun onIpAddressChange(newValue: String) {
        _ipAddress.value = newValue
    }

    fun onPortChange(newValue: String) {
        _port.value = newValue
    }

    fun onUdpLocalPortChange(newValue: String) {
        _udpLocalPort.value = newValue
    }

    fun onUdpRemoteHostChange(newValue: String) {
        _udpRemoteHost.value = newValue
    }

    fun onUdpRemotePortChange(newValue: String) {
        _udpRemotePort.value = newValue
    }

    @SuppressLint("MissingPermission")
    fun setPairedDevices(devices: Set<BluetoothDevice>) {
        // Sort devices: T12_ devices first, then others
        val sortedDevices = devices.map { PairedDevice(it) }.sortedWith(
            compareByDescending<PairedDevice> { it.name.startsWith("T12_", ignoreCase = true) }
                .thenBy { it.name }
        )
        _pairedDevices.value = sortedDevices
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter != null) {
            try {
                val pairedBtDevices = bluetoothAdapter.bondedDevices
                setPairedDevices(pairedBtDevices)
                LogUtils.d("SharedVM", "Refreshed ${pairedBtDevices.size} paired Bluetooth devices")
            } catch (se: SecurityException) {
                LogUtils.e("SharedVM", "Bluetooth permission missing: ${se.message}")
            }
        } else {
            LogUtils.e("SharedVM", "Bluetooth adapter not available")
        }
    }

    fun onDeviceSelected(device: PairedDevice) {
        _selectedDevice.value = device
    }

    // --- USB OTG Serial connection state ---
    private val _usbDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val usbDevices: StateFlow<List<UsbDeviceInfo>> = _usbDevices.asStateFlow()

    private val _selectedUsbDevice = mutableStateOf<UsbDeviceInfo?>(null)
    val selectedUsbDevice: State<UsbDeviceInfo?> = _selectedUsbDevice

    // Supported telemetry baud rates; 115200 is the ArduPilot/SiK default.
    private val _baudRate = mutableStateOf(115200)
    val baudRate: State<Int> = _baudRate

    // Cached UsbManager (from applicationContext) so connect() — which has no Context — can build
    // the provider. Populated whenever the USB device list is refreshed from the UI.
    private var usbManager: UsbManager? = null

    fun onUsbDeviceSelected(device: UsbDeviceInfo) {
        _selectedUsbDevice.value = device
    }

    fun clearUsbSelection() {
        _selectedUsbDevice.value = null
    }

    fun onBaudRateChange(newValue: Int) {
        _baudRate.value = newValue
    }

    /** Enumerate attached USB serial devices (FTDI/CP210x/CH340/Prolific/CDC). */
    fun refreshUsbDevices(context: Context) {
        val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            LogUtils.e("SharedVM", "USB service not available")
            return
        }
        this.usbManager = usbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val devices = drivers.map { driver ->
            val dev = driver.device
            val label = dev.productName ?: driver.javaClass.simpleName.removeSuffix("SerialDriver")
            UsbDeviceInfo(name = "$label (${dev.deviceName})", device = dev)
        }
        _usbDevices.value = devices
        // Drop a stale selection if the device was detached.
        if (devices.none { it.id == _selectedUsbDevice.value?.id }) {
            _selectedUsbDevice.value = null
        }
        LogUtils.d("SharedVM", "Refreshed ${devices.size} USB serial devices")
    }

    fun hasUsbPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.hasPermission(device)
    }

    /**
     * Request runtime permission for a USB device. The result is delivered via a one-shot
     * [BroadcastReceiver]; [onResult] is invoked with whether access was granted.
     */
    fun requestUsbPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            onResult(false)
            return
        }
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                try {
                    appContext.unregisterReceiver(this)
                } catch (e: Exception) {
                    // Already unregistered — ignore.
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    // --- Telemetry & Repository ---
    private var repo: MavlinkTelemetryRepository? = null

    // 🔥 FIX: Track all coroutines launched during connect() so they can be cancelled on disconnect.
    // Without this, orphaned collect coroutines keep updating _telemetryState after disconnect,
    // making the app think it's connected (has telemetry data) when repo is actually null.
    private var connectionJobs = mutableListOf<Job>()

    // --- Video Tracking Manager ---
    private var trackingManager: TrackingManager? = null
    private val _cameraTrackingState = MutableStateFlow(CameraTrackingState())
    val cameraTrackingState: StateFlow<CameraTrackingState> = _cameraTrackingState.asStateFlow()

    // Public accessor for repository (needed by ObstacleDetectionManager)
    val repository: MavlinkTelemetryRepository?
        get() = repo


    val isConnected: StateFlow<Boolean> = telemetryState
        .map { it.connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _calibrationStatus = MutableStateFlow<String?>(null)
    val calibrationStatus: StateFlow<String?> = _calibrationStatus.asStateFlow()

    private val _imuCalibrationStartResult = MutableStateFlow<Boolean?>(null)
    val imuCalibrationStartResult: StateFlow<Boolean?> = _imuCalibrationStartResult

    // Expose COMMAND_ACK flow for calibration and other commands
    val commandAck: SharedFlow<com.divpundir.mavlink.definitions.common.CommandAck>
        get() = repo?.commandAck ?: MutableSharedFlow()

    // Expose COMMAND_LONG flow for incoming commands from FC (e.g., ACCELCAL_VEHICLE_POS)
    val commandLong: SharedFlow<com.divpundir.mavlink.definitions.common.CommandLong>
        get() = repo?.commandLong ?: MutableSharedFlow()

    // Expose MAG_CAL_PROGRESS flow for compass calibration progress
    val magCalProgress: SharedFlow<com.divpundir.mavlink.definitions.ardupilotmega.MagCalProgress>
        get() = repo?.magCalProgress ?: MutableSharedFlow()

    // Expose MAG_CAL_REPORT flow for compass calibration final report
    val magCalReport: SharedFlow<com.divpundir.mavlink.definitions.common.MagCalReport>
        get() = repo?.magCalReport ?: MutableSharedFlow()

    // Expose RC_CHANNELS flow for RC calibration
    val rcChannels: SharedFlow<com.divpundir.mavlink.definitions.common.RcChannels>
        get() = repo?.rcChannels ?: MutableSharedFlow()

    // Expose SERVO_OUTPUT_RAW flow for the Servo Output screen's live position bars
    val servoOutputRaw: SharedFlow<com.divpundir.mavlink.definitions.common.ServoOutputRaw>
        get() = repo?.servoOutputRaw ?: MutableSharedFlow()

    // Expose PARAM_VALUE flow for parameter reading.
    // The no-repo fallback is a single long-lived instance: returning a fresh
    // MutableSharedFlow() on every property read means a collector that subscribes
    // before the repository is attached ends up on a throwaway flow and silently
    // never receives anything.
    private val noRepoParamValueFlow =
        MutableSharedFlow<com.divpundir.mavlink.definitions.common.ParamValue>()

    val paramValue: SharedFlow<com.divpundir.mavlink.definitions.common.ParamValue>
        get() = repo?.paramValue ?: noRepoParamValueFlow

    // --- Flight state management (for UnifiedFlightTracker) ---
    /**
     * Update flight state from UnifiedFlightTracker
     * This is the single source of truth for flight timing and distance
     */
    fun updateFlightState(
        isActive: Boolean,
        elapsedSeconds: Long,
        distanceMeters: Float,
        sprayedDistanceMeters: Float = 0f,
        completed: Boolean = false
    ) {
        // Calculate sprayed acres from sprayed distance using the active mission's effective
        // swath (auto = line spacing, manual = configured default). Single source of truth in GridUtils.
        val sprayedAcres = GridUtils.sweptAcres(sprayedDistanceMeters.toDouble(), currentSwathMeters).toFloat()

        _telemetryState.value = _telemetryState.value.copy(
            isMissionActive = isActive,
            missionElapsedSec = if (isActive) elapsedSeconds else null,
            totalDistanceMeters = if (isActive || completed) distanceMeters else null,
            totalSprayedDistanceMeters = if (isActive || completed) sprayedDistanceMeters else null,
            totalSprayedAcres = if (isActive || completed) sprayedAcres else null,
            missionCompleted = completed,
            // Holds the FINAL time of the last mission so the bottom-bar timer keeps showing it
            // after the flight ends. A new flight (isActive, elapsed reset to 0) clears the stale
            // carry-over; otherwise the previous mission's time is preserved.
            lastMissionElapsedSec = when {
                completed -> elapsedSeconds
                isActive && elapsedSeconds == 0L -> null
                else -> _telemetryState.value.lastMissionElapsedSec
            }
        )

        // NOTE: Mission waypoints are NO LONGER automatically cleared when mission completes.
        // The map lines should remain visible until user navigates to select a new flying mode.
        if (completed) {
            LogUtils.i("SharedVM", "Mission completed via updateFlightState - keeping map lines visible")
        }
    }

    /**
     * Mark the mission completed popup as handled to prevent it from showing again
     * This should be called after the popup is shown or skipped
     */
    fun markMissionCompletedHandled() {
        _telemetryState.value = _telemetryState.value.copy(missionCompletedHandled = true)
        LogUtils.i("SharedVM", "Mission completed handled - popup won't show again for this mission")
    }

    /**
     * Reset mission completed state - called when starting a new mission
     */
    fun resetMissionCompletedState() {
        _telemetryState.value = _telemetryState.value.copy(
            missionCompleted = false,
            missionCompletedHandled = false,
            lastMissionElapsedSec = null
        )
        LogUtils.i("SharedVM", "Mission completed state reset")
    }

    /**
     * A flown mission finished and the drone disarmed, and the mission is STILL on the FC.
     *
     * The GCS used to wipe it here automatically. It no longer does (see the disarm branch in
     * [TelemetryRepository]): clearing the flight controller is the operator's call, made
     * explicitly from the home screen. What is left is the duty to say so, because a mission
     * resident on the FC means a switch to AUTO will re-fly it, and that must not be a
     * surprise.
     *
     * The map lines are left up too, on purpose: they are now an accurate picture of what the
     * vehicle is still holding.
     */
    fun onMissionLeftOnFcAfterCompletion() {
        LogUtils.i("SharedVM", "Mission complete — still loaded on the FC, awaiting an explicit Clear Mission")
        _missionLoadedOnFc.value = true
        addNotification(
            Notification(
                message = "Mission complete. It is still loaded on the drone — use Clear Mission on the home screen to remove it.",
                type = NotificationType.INFO
            )
        )
    }

    /**
     * True when we have reason to believe the flight controller is holding a mission.
     *
     * Drives whether the home screen's Clear Mission button has anything to do. Deliberately
     * optimistic — set on upload and on mission completion, cleared only by a confirmed clear
     * — because offering the button when there is nothing to clear is harmless, while hiding
     * it when there IS something to clear is the failure that matters.
     */
    private val _missionLoadedOnFc = MutableStateFlow(false)
    val missionLoadedOnFc: StateFlow<Boolean> = _missionLoadedOnFc.asStateFlow()

    /** Progress/result of an operator-initiated Clear Mission, for the home screen dialog. */
    enum class ClearMissionState { IDLE, CLEARING, SUCCESS, FAILED }

    private val _clearMissionState = MutableStateFlow(ClearMissionState.IDLE)
    val clearMissionState: StateFlow<ClearMissionState> = _clearMissionState.asStateFlow()

    fun acknowledgeClearMissionResult() {
        _clearMissionState.value = ClearMissionState.IDLE
    }

    /**
     * Wipe the mission off the flight controller, at the operator's explicit request.
     *
     * The only path that clears the FC's mission now. Two hard preconditions, both checked
     * here rather than trusted to the UI:
     *
     *  - CONNECTED, or there is nothing to talk to and a "cleared" result would be a lie.
     *  - DISARMED. Pulling the mission out from under a vehicle that is flying it is how you
     *    strand a drone mid-air, and no confirmation dialog makes that acceptable.
     *
     * The geofence is preserved: it is a safety limit tied to the SITE, not to the mission,
     * and the pilot is very likely to fly again from the same spot.
     */
    fun clearMissionFromFcConfirmed() {
        if (_clearMissionState.value == ClearMissionState.CLEARING) return

        if (_telemetryState.value.armed) {
            LogUtils.w("MissionClear", "Refusing to clear the mission: the drone is ARMED")
            _clearMissionState.value = ClearMissionState.FAILED
            addNotification(
                Notification(
                    message = "⛔ Cannot clear the mission while the drone is armed",
                    type = NotificationType.ERROR
                )
            )
            return
        }

        if (!_telemetryState.value.connected) {
            LogUtils.w("MissionClear", "Refusing to clear the mission: not connected")
            _clearMissionState.value = ClearMissionState.FAILED
            addNotification(
                Notification(
                    message = "⛔ Not connected to the drone — nothing was cleared",
                    type = NotificationType.ERROR
                )
            )
            return
        }

        _clearMissionState.value = ClearMissionState.CLEARING
        viewModelScope.launch {
            try {
                val cleared = repo?.clearMissionFromFC() ?: false
                if (cleared) {
                    LogUtils.i("MissionClear", "🧹 Mission cleared from the FC at the operator's request")
                    _missionLoadedOnFc.value = false
                    _clearMissionState.value = ClearMissionState.SUCCESS

                    // Map lines only — the geofence stays, see above.
                    clearMapLinesOnly()

                    // A cleared mission has no resume target left, so drop the pause state
                    // with it rather than leaving a resume point pointing at nothing.
                    _resumePointLocation.value = null
                    _resumePointWaypoint.value = null
                    _resumeMissionReady.value = false
                    _resumePreparationFailed.value = false
                    _resumePreparationInProgress.value = false
                    _pendingResumeLocation = null
                    _missionPauseLocation = null
                    _sprayWasActiveBeforePause = false
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    lastUploadedMissionItems = emptyList()
                    _telemetryState.update {
                        it.copy(missionPaused = false, pausedAtWaypoint = null)
                    }

                    addNotification(
                        Notification(
                            message = "✅ Mission cleared from the drone",
                            type = NotificationType.SUCCESS
                        )
                    )
                    ttsManager?.speak("Mission cleared")
                } else {
                    // Not fatal, but it must not read as success: the mission is still there
                    // and a switch to AUTO will still fly it.
                    LogUtils.e("MissionClear", "✗ Clear Mission was not acknowledged — the mission is STILL on the FC")
                    _clearMissionState.value = ClearMissionState.FAILED
                    addNotification(
                        Notification(
                            message = "⚠️ The drone did not confirm the clear — the mission is still loaded",
                            type = NotificationType.WARNING
                        )
                    )
                }
            } catch (e: Exception) {
                LogUtils.e("MissionClear", "❌ Error clearing the mission from the FC", e)
                _clearMissionState.value = ClearMissionState.FAILED
                addNotification(
                    Notification(
                        message = "⚠️ Could not clear the mission: ${e.message}",
                        type = NotificationType.WARNING
                    )
                )
            }
        }
    }

    // --- Calibration helpers ---
    /**
     * Request MAG_CAL_PROGRESS and MAG_CAL_REPORT messages from the autopilot.
     * This is needed because these messages are not sent by default.
     */
    suspend fun requestMagCalMessages(hz: Float = 10f) {
        LogUtils.d("CompassCalVM", "========== REQUESTING MAG CAL MESSAGE STREAMING ==========")
        LogUtils.d("CompassCalVM", "Requesting MAG_CAL_PROGRESS (191) at $hz Hz")
        LogUtils.d("CompassCalVM", "Requesting MAG_CAL_REPORT (192) at $hz Hz")
        LogUtils.d("CompassCalVM", "Interval: ${if (hz > 0f) (1_000_000f / hz).toInt() else 0} microseconds")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 191f, // MAG_CAL_PROGRESS message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        LogUtils.d("CompassCalVM", "✓ MAG_CAL_PROGRESS message interval command sent")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 192f, // MAG_CAL_REPORT message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        LogUtils.d("CompassCalVM", "✓ MAG_CAL_REPORT message interval command sent")
        LogUtils.d("CompassCalVM", "========================================================")
    }

    /**
     * Stop MAG_CAL_PROGRESS and MAG_CAL_REPORT message streaming.
     * Sets the message interval to 0 (disabled).
     */
    suspend fun stopMagCalMessages() {
        LogUtils.d("CompassCalVM", "========== STOPPING MAG CAL MESSAGE STREAMING ==========")
        LogUtils.d("CompassCalVM", "Disabling MAG_CAL_PROGRESS (191) streaming")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 191f, // MAG_CAL_PROGRESS message ID
            param2 = 0f // 0 = disable streaming
        )
        LogUtils.d("CompassCalVM", "✓ MAG_CAL_PROGRESS streaming disabled")

        LogUtils.d("CompassCalVM", "Disabling MAG_CAL_REPORT (192) streaming")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 192f, // MAG_CAL_REPORT message ID
            param2 = 0f // 0 = disable streaming
        )
        LogUtils.d("CompassCalVM", "✓ MAG_CAL_REPORT streaming disabled")
        LogUtils.d("CompassCalVM", "========================================================")
    }

    /**
     * Await a COMMAND_ACK for the given command id within the timeout.
     * Returns the ack if received, or null if the timeout elapses.
     */
    suspend fun awaitCommandAck(commandId: UInt, timeoutMs: Long = 5000L): com.divpundir.mavlink.definitions.common.CommandAck? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                commandAck
                    .filter { it.command.value == commandId }
                    .first()
            }
        } catch (e: Exception) {
            LogUtils.e("SharedVM", "Error while awaiting COMMAND_ACK for $commandId", e)
            null
        }
    }

    /**
     * Request RC_CHANNELS messages from the autopilot at specified rate.
     * Message ID 65 for RC_CHANNELS.
     */
    suspend fun requestRCChannels(hz: Float = 10f) {
        LogUtils.d("RCCalVM", "========== REQUESTING RC_CHANNELS MESSAGE STREAMING ==========")
        LogUtils.d("RCCalVM", "Requesting RC_CHANNELS (65) at $hz Hz")
        LogUtils.d("RCCalVM", "Interval: ${if (hz > 0f) (1_000_000f / hz).toInt() else 0} microseconds")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 65f, // RC_CHANNELS message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        LogUtils.d("RCCalVM", "✓ RC_CHANNELS message interval command sent")
        LogUtils.d("RCCalVM", "==============================================================")
    }

    /**
     * Stop RC_CHANNELS message streaming.
     */
    suspend fun stopRCChannels() {
        LogUtils.d("RCCalVM", "========== STOPPING RC_CHANNELS MESSAGE STREAMING ==========")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 65f, // RC_CHANNELS message ID
            param2 = 0f // 0 = disable streaming
        )
        LogUtils.d("RCCalVM", "✓ RC_CHANNELS streaming disabled")
        LogUtils.d("RCCalVM", "=============================================================")
    }

    /**
     * Reboot the autopilot using MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN.
     *
     * Command: MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN (246)
     * Param1: 1 = Reboot autopilot
     * Param2-7: 0 (reserved)
     */
    suspend fun rebootAutopilot() {
        LogUtils.d("Calibration", "========== SENDING REBOOT COMMAND ==========")
        try {
            repo?.sendCommand(
                MavCmd.PREFLIGHT_REBOOT_SHUTDOWN,
                param1 = 1f, // 1 = Reboot autopilot
                param2 = 0f, // Companion computer (0 = no action)
                param3 = 0f, // Reserved
                param4 = 0f, // Reserved
                param5 = 0f, // Reserved
                param6 = 0f, // Reserved
                param7 = 0f  // Reserved
            )
            LogUtils.d("Calibration", "✓ Reboot command sent successfully")
        } catch (e: Exception) {
            LogUtils.e("Calibration", "❌ Failed to send reboot command", e)
        }
        LogUtils.d("Calibration", "============================================")
    }

    /**
     * Request ALL parameters from the autopilot using PARAM_REQUEST_LIST (MAVLink #21).
     * After this request, the autopilot will emit all parameters as PARAM_VALUE messages
     * via the paramValue SharedFlow. 
     * See: https://mavlink.io/en/services/parameter.html
     */
    suspend fun requestAllParameters() {
        repo?.let { repository ->
            try {
                val paramRequestList = com.divpundir.mavlink.definitions.common.ParamRequestList(
                    targetSystem = repository.fcuSystemId,
                    targetComponent = repository.fcuComponentId
                )
                repository.connection.trySendUnsignedV2(
                    repository.gcsSystemId,
                    repository.gcsComponentId,
                    paramRequestList
                )
                LogUtils.d("FullParamList", "📤 Sent PARAM_REQUEST_LIST")
            } catch (e: Exception) {
                LogUtils.e("FullParamList", "Failed to send PARAM_REQUEST_LIST", e)
            }
        }
    }

    /**
     * Request a parameter value from the autopilot by name.
     * The response will come via the paramValue flow.
     */
    suspend fun requestParameter(paramId: String) {
        repo?.let { repository ->
            try {
                val paramRequestRead = com.divpundir.mavlink.definitions.common.ParamRequestRead(
                    targetSystem = repository.fcuSystemId,
                    targetComponent = repository.fcuComponentId,
                    paramId = paramId,
                    paramIndex = -1
                )
                repository.connection.trySendUnsignedV2(
                    repository.gcsSystemId,
                    repository.gcsComponentId,
                    paramRequestRead
                )
                LogUtils.d("RCCalVM", "📤 Sent PARAM_REQUEST_READ for: $paramId")
            } catch (e: Exception) {
                LogUtils.e("RCCalVM", "Failed to request parameter $paramId", e)
            }
        }
    }

    /**
     * Request a single parameter by its index rather than its name.
     *
     * Used to fill gaps after a PARAM_REQUEST_LIST download: the autopilot streams the
     * list once and never repeats it, so any PARAM_VALUE dropped by the telemetry link
     * is gone unless we ask for that index again. The reply arrives on [paramValue] like
     * any other parameter.
     */
    suspend fun requestParameterByIndex(paramIndex: Int) {
        repo?.let { repository ->
            try {
                val paramRequestRead = com.divpundir.mavlink.definitions.common.ParamRequestRead(
                    targetSystem = repository.fcuSystemId,
                    targetComponent = repository.fcuComponentId,
                    paramId = "",
                    paramIndex = paramIndex.toShort()
                )
                repository.connection.trySendUnsignedV2(
                    repository.gcsSystemId,
                    repository.gcsComponentId,
                    paramRequestRead
                )
            } catch (e: Exception) {
                LogUtils.e("FullParamList", "Failed to request param index $paramIndex", e)
            }
        }
    }

    /**
     * Read a parameter value from the autopilot by name.
     * Subscribes to the paramValue flow FIRST, then sends PARAM_REQUEST_READ,
     * so the response is never missed due to race conditions.
     * Returns the float value or null if timed out / not connected.
     */
    suspend fun readParameter(paramId: String, timeoutMs: Long = 3000L): Float? {
        repo?.let { repository ->
            try {
                // Deferred result holder
                var result: Float? = null

                // Step 1: Start collecting BEFORE sending the request
                val collectJob = viewModelScope.launch {
                    paramValue.collect { pv ->
                        val name = pv.paramId.trim().replace("\u0000", "")
                        if (name == paramId) {
                            result = pv.paramValue
                        }
                    }
                }

                // Small delay to ensure collector is active
                delay(50)

                // Step 2: Send the request
                val paramRequestRead = com.divpundir.mavlink.definitions.common.ParamRequestRead(
                    targetSystem = repository.fcuSystemId,
                    targetComponent = repository.fcuComponentId,
                    paramId = paramId,
                    paramIndex = -1
                )
                repository.connection.trySendUnsignedV2(
                    repository.gcsSystemId,
                    repository.gcsComponentId,
                    paramRequestRead
                )
                LogUtils.d("OptionsVM", "📤 Sent PARAM_REQUEST_READ for: $paramId")

                // Step 3: Wait for the response with timeout
                val startTime = System.currentTimeMillis()
                while (result == null && System.currentTimeMillis() - startTime < timeoutMs) {
                    delay(50)
                }

                collectJob.cancel()

                if (result != null) {
                    LogUtils.d("OptionsVM", "📥 Received $paramId = $result")
                    return result
                } else {
                    LogUtils.e("OptionsVM", "⏱ Timeout reading $paramId after ${timeoutMs}ms")
                }
            } catch (e: Exception) {
                LogUtils.e("OptionsVM", "Failed to read parameter $paramId", e)
            }
        } ?: run {
            LogUtils.e("OptionsVM", "Cannot read $paramId — not connected to drone")
        }
        return null
    }

    /**
     * Set a parameter value on the autopilot.
     * Returns the PARAM_VALUE response if successful within timeout.
     */
    suspend fun setParameter(paramId: String, value: Float, timeoutMs: Long = 3000L): com.divpundir.mavlink.definitions.common.ParamValue? {
        repo?.let { repository ->
            try {
                LogUtils.d("RCCalVM", "📤 Setting parameter: $paramId = $value")

                val paramSet = com.divpundir.mavlink.definitions.common.ParamSet(
                    targetSystem = repository.fcuSystemId,
                    targetComponent = repository.fcuComponentId,
                    paramId = paramId,
                    paramValue = value,
                    paramType = com.divpundir.mavlink.definitions.common.MavParamType.REAL32.wrap()
                )

                // Wait for the PARAM_VALUE response confirming the set. The PARAM_SET is sent from
                // onSubscription so it goes out only AFTER this collector is attached: the flow has
                // no replay, so sending first raced the ack — a fast FC could answer before the
                // collector existed, the ack was dropped, and a successful write was reported as
                // failed, stalling every caller that gates on it for the full timeout.
                // (readParameter() collects before sending for the same reason.)
                // Note: paramId from drone may contain null-terminator chars, so we must clean before comparing
                return withTimeoutOrNull(timeoutMs) {
                    paramValue
                        .onSubscription {
                            repository.connection.trySendUnsignedV2(
                                repository.gcsSystemId,
                                repository.gcsComponentId,
                                paramSet
                            )
                        }
                        .filter { it.paramId.trim().replace("\u0000", "") == paramId }
                        .first()
                }
            } catch (e: Exception) {
                LogUtils.e("RCCalVM", "Failed to set parameter $paramId", e)
                return null
            }
        }
        return null
    }

    // ── Proximity-radar thresholds (hybrid vehicle-seeded / pilot-override) ──────

    private fun loadRadarThresholdsFromPrefs(): RadarThresholds {
        val context = GCSApplication.getInstance()
            ?: return RadarThresholds(
                UserSettingsManager.DEFAULT_RADAR_CAUTION_M,
                UserSettingsManager.DEFAULT_RADAR_CRITICAL_M
            )
        return RadarThresholds(
            cautionM = UserSettingsManager.loadRadarCautionMeters(context),
            criticalM = UserSettingsManager.loadRadarCriticalMeters(context)
        )
    }

    /** True once the pilot has explicitly overridden the radar thresholds locally. */
    fun isRadarThresholdsOverridden(): Boolean {
        val context = GCSApplication.getInstance() ?: return false
        return UserSettingsManager.isRadarThresholdsOverridden(context)
    }

    /** Persist a pilot override and publish it immediately. Blocks future vehicle re-seeding. */
    fun setRadarThresholds(cautionM: Float, criticalM: Float) {
        val context = GCSApplication.getInstance() ?: return
        UserSettingsManager.saveRadarThresholdsOverride(context, cautionM, criticalM)
        _radarThresholds.value = RadarThresholds(cautionM, criticalM)
    }

    /**
     * Clear the pilot override and re-seed from the connected vehicle (if any). Called by the
     * "Reset to vehicle defaults" action in SensorSettingsScreen.
     */
    fun resetRadarThresholdsToVehicle() {
        val context = GCSApplication.getInstance() ?: return
        UserSettingsManager.clearRadarThresholdsOverride(context)
        viewModelScope.launch { seedRadarThresholdsFromVehicle() }
    }

    /**
     * Read the vehicle's AVOID_DIST_MAX (caution) and AVOID_MARGIN (critical) and seed the local
     * thresholds — but only when the pilot has NOT set a local override. Both ArduPilot params are
     * in metres, so they map 1:1. Missing/timed-out reads are left at their current value.
     */
    private suspend fun seedRadarThresholdsFromVehicle() {
        val context = GCSApplication.getInstance() ?: return
        if (UserSettingsManager.isRadarThresholdsOverridden(context)) {
            LogUtils.d("SharedVM", "Radar thresholds overridden by pilot — skipping vehicle seed")
            return
        }
        val caution = readParameter("AVOID_DIST_MAX")
        val critical = readParameter("AVOID_MARGIN")
        if (caution == null && critical == null) {
            LogUtils.d("SharedVM", "No radar-threshold params returned from vehicle")
            return
        }
        val current = _radarThresholds.value
        val seeded = RadarThresholds(
            cautionM = caution?.takeIf { it > 0f } ?: current.cautionM,
            criticalM = critical?.takeIf { it > 0f } ?: current.criticalM
        )
        UserSettingsManager.saveRadarThresholdsFromVehicle(context, seeded.cautionM, seeded.criticalM)
        _radarThresholds.value = seeded
        LogUtils.d("SharedVM", "Seeded radar thresholds from vehicle: $seeded")
    }

    /**
     * Read the vehicle's spray configuration on connect and seed the local state.
     *
     * Before this existed [_sprayRate] was hardcoded to 100 at startup, so the slider always
     * claimed 100% no matter what the FC held — a pilot who set 40 last flight reconnected to a
     * UI that disagreed with the vehicle, and the first nudge of the slider wrote a value they
     * had not chosen.
     *
     * SPRAY_PUMP_MIN and SPRAY_ENABLE are read for display only (see the effective-output readout
     * in the spray panels): the floor and the master switch both decide what the pump actually
     * does, and neither was visible anywhere in the app. We do not write either one here.
     *
     * Missing / timed-out reads leave the corresponding value alone.
     */
    private suspend fun seedSprayConfigFromVehicle() {
        val rate = readParameter("SPRAY_PUMP_RATE")
        if (rate != null) {
            // Clamp into the slider's own range: an FC holding a rate outside 10..100 (a hand-set
            // param, or the ArduPilot default of 10) must still land on a position the slider can
            // represent, or the thumb and the number would disagree.
            _sprayRate.value = rate.coerceIn(10f, 100f)
            LogUtils.i("SprayControl", "🚿 Seeded spray rate from vehicle: SPRAY_PUMP_RATE=$rate → slider ${_sprayRate.value.toInt()}%")
        } else {
            LogUtils.w("SprayControl", "⚠ Could not read SPRAY_PUMP_RATE — slider keeps ${_sprayRate.value.toInt()}%")
        }

        _sprayPumpMin.value = readParameter("SPRAY_PUMP_MIN") ?: run {
            LogUtils.w("SprayControl", "⚠ Could not read SPRAY_PUMP_MIN")
            null
        }

        val enable = readParameter("SPRAY_ENABLE")
        _sprayEnableParam.value = enable?.let { it >= 0.5f }
        if (enable != null && enable < 0.5f) {
            LogUtils.w("SprayControl", "⚠ SPRAY_ENABLE=0 on the vehicle — the Sprayer library is off, so rate changes will have no effect")
        }

        // ── Manual-pump params (new firmware) ────────────────────────────────
        // This probe doubles as the confirmation that the param NAMES are right: the firmware
        // source is not in this repo, so a successful read here is the first hard evidence that
        // PARAM_SPRAY_PUMP_MODE / PARAM_SPRAY_PUMP_PCT match what AC_Sprayer actually defines.
        // A timeout means either old firmware or a name mismatch — indistinguishable over
        // MAVLink, which is why the message names both possibilities.
        val mode = readParameter(PARAM_SPRAY_PUMP_MODE)
        val pct = readParameter(PARAM_SPRAY_PUMP_PCT)

        if (mode == null && pct == null) {
            _sprayManualParamsSupported.value = false
            LogUtils.w("SprayControl",
                "⚠ Neither $PARAM_SPRAY_PUMP_MODE nor $PARAM_SPRAY_PUMP_PCT could be read — " +
                "either this firmware predates manual pump mode, or the param names in " +
                "SharedViewModel do not match the firmware. Manual mode disabled in the UI.")
        } else {
            _sprayManualParamsSupported.value = true
            if (mode != null) {
                _sprayManualMode.value = mode >= 0.5f
                LogUtils.i("SprayControl", "🚿 Seeded pump mode from vehicle: $PARAM_SPRAY_PUMP_MODE=$mode → ${if (_sprayManualMode.value) "MANUAL" else "AUTO"}")
            } else {
                LogUtils.w("SprayControl", "⚠ Could not read $PARAM_SPRAY_PUMP_MODE (but $PARAM_SPRAY_PUMP_PCT answered) — keeping ${if (_sprayManualMode.value) "MANUAL" else "AUTO"}")
            }
            if (pct != null) {
                _sprayManualPct.value = pct.coerceIn(SPRAY_PUMP_PCT_MIN, SPRAY_PUMP_PCT_MAX)
                LogUtils.i("SprayControl", "🚿 Seeded manual duty from vehicle: $PARAM_SPRAY_PUMP_PCT=$pct → ${_sprayManualPct.value.toInt()}%")
            } else {
                LogUtils.w("SprayControl", "⚠ Could not read $PARAM_SPRAY_PUMP_PCT — manual slider keeps ${_sprayManualPct.value.toInt()}%")
            }
        }

        LogUtils.d("SprayControl",
            "Spray config seeded: rate=${_sprayRate.value}, pumpMin=${_sprayPumpMin.value}, " +
            "enabled=${_sprayEnableParam.value}, manualMode=${_sprayManualMode.value}, " +
            "manualPct=${_sprayManualPct.value}, manualSupported=${_sprayManualParamsSupported.value}")
    }

    fun connect() {
        viewModelScope.launch {
            try {
                val provider: MavConnectionProvider? = when (_connectionType.value) {
                    ConnectionType.TCP -> {
                        val portInt = port.value.toIntOrNull()
                        if (portInt != null) {
                            TcpConnectionProvider(ipAddress.value, portInt)
                        } else {
                            LogUtils.e("SharedVM", "Invalid port number.")
                            null
                        }
                    }
                    ConnectionType.UDP -> {
                        val localPortInt = udpLocalPort.value.toIntOrNull()
                        if (localPortInt != null && localPortInt in 1..65535) {
                            // A blank host means pure listen mode (peer learned from first packet).
                            //
                            // A LOOPBACK host is now also forced to listen-only, whatever port is
                            // set. Five field tests in a row ran in SEEDED mode because a stale
                            // 127.0.0.1 sat in the box and nobody cleared it, and seeding loopback
                            // is never useful here: the RC's router already pushes to our port, so
                            // the only thing the seed achieves is transmitting at a loopback port
                            // that may have nothing bound to it. When nothing is listening there,
                            // the kernel answers our own datagrams with ICMP port-unreachable, and
                            // a pending ICMP error on the socket can cost us inbound datagrams —
                            // which is exactly the "packets out: 9, packets in: 0" we measured on a
                            // port that Scan had just proved was carrying 4 packets of telemetry.
                            //
                            // QGroundControl and Mission Planner never invent a loopback target
                            // either; they bind and learn the peer from the first datagram.
                            val raw = udpRemoteHost.value.trim()
                            val isLoopbackHost = raw.equals("localhost", ignoreCase = true) ||
                                raw == "::1" || raw == "[::1]" || raw.startsWith("127.")
                            val host: String? = raw.ifBlank { null }?.takeUnless { isLoopbackHost }
                            val hostPort: Int = udpRemotePort.value.toIntOrNull()
                                ?.takeIf { it in 1..65535 }
                                ?: localPortInt
                            UdpConnectionProvider(
                                localPortInt,
                                host,
                                hostPort,
                                GCSApplication.getInstance()?.applicationContext
                            )
                        } else {
                            LogUtils.e("SharedVM", "Invalid UDP local port.")
                            null
                        }
                    }
                    ConnectionType.BLUETOOTH -> {
                        selectedDevice.value?.device?.let {
                            BluetoothConnectionProvider(it)
                        } ?: run {
                            LogUtils.e("SharedVM", "No Bluetooth device selected.")
                            null
                        }
                    }
                    ConnectionType.USB -> {
                        val device = selectedUsbDevice.value?.device
                        val manager = usbManager
                        when {
                            device == null -> {
                                LogUtils.e("SharedVM", "No USB device selected.")
                                null
                            }
                            manager == null -> {
                                LogUtils.e("SharedVM", "USB manager unavailable; refresh devices first.")
                                null
                            }
                            !manager.hasPermission(device) -> {
                                LogUtils.e("SharedVM", "USB permission not granted for selected device.")
                                null
                            }
                            else -> UsbSerialConnectionProvider(manager, device, baudRate.value)
                        }
                    }
                }

                if (provider == null) {
                    LogUtils.e("SharedVM", "Failed to create connection provider.")
                    return@launch
                }

                // If there's an old repo, close its connection first
                try {
                    repo?.closeConnection()
                } catch (e: Exception) {
                    LogUtils.e("SharedVM", "Error closing old connection", e)
                }

                // 🔥 CRITICAL FIX: Clear ALL stale geofence state when establishing a new connection.
                // The ViewModel survives in memory across sessions (Activity not destroyed),
                // so old geofence polygon/waypoints from a previous FC/session can persist for days.
                // Without this, the app would upload the OLD geofence (from days ago) to a NEW FC.
                LogUtils.i("Geofence", "🧹 New connection: clearing stale geofence state from previous session")
                stopFenceStatusMonitoring()
                _geofenceEnabled.value = false
                _geofencePolygon.value = emptyList()
                _fenceConfiguration.value = null
                _homePosition.value = null
                resetGeofenceState()

                val newRepo = MavlinkTelemetryRepository(provider, this@SharedViewModel)
                repo = newRepo
                newRepo.start()
                DisconnectionRTLHandler.startMonitoring(_telemetryState, newRepo, viewModelScope)

                // 🔥 FIX: Cancel any previous connection collection jobs to prevent orphaned coroutines
                connectionJobs.forEach { it.cancel() }
                connectionJobs.clear()

            // Initialize video tracking manager
            trackingManager?.destroy()
            val newTrackingManager = TrackingManager(newRepo)
            trackingManager = newTrackingManager

            // Start tracking manager when FCU is detected
            connectionJobs += viewModelScope.launch {
                newRepo.state.collect { state ->
                    if (state.fcuDetected && state.connected) {
                        newTrackingManager.initialize()
                        return@collect // Only need to initialize once
                    }
                }
            }

            // Seed proximity-radar thresholds from the vehicle once the FCU is detected (unless the
            // pilot has set a local override). One-shot per connection.
            connectionJobs += viewModelScope.launch {
                newRepo.state.collect { state ->
                    if (state.fcuDetected && state.connected) {
                        seedRadarThresholdsFromVehicle()
                        // Same one-shot: read the spray config so the slider reflects the vehicle
                        // rather than its hardcoded startup value. Sequential (not a parallel
                        // launch) because readParameter drives a shared PARAM_VALUE flow and
                        // overlapping reads would race for each other's acks.
                        seedSprayConfigFromVehicle()
                        return@collect
                    }
                }
            }

            // Collect tracking state
            connectionJobs += viewModelScope.launch {
                newTrackingManager.cameraTrackingState.collect { trackingState ->
                    _cameraTrackingState.value = trackingState
                }
            }

            connectionJobs += viewModelScope.launch {
                newRepo.state.collect { repoState ->
                    // Preserve SharedViewModel-managed fields (pause state, mission active state) while updating from repository
                    _telemetryState.update { currentState ->
                        // DEBUG LOG: Track state synchronization
//                        LogUtils.i("DEBUG_STATE", "Before sync - repoLastAuto: ${repoState.lastAutoWaypoint}, currentLastAuto: ${currentState.lastAutoWaypoint}, repoCurrent: ${repoState.currentWaypoint}, currentCurrent: ${currentState.currentWaypoint}")

                        // IMPORTANT: Preserve isMissionActive if it's currently true (managed by UnifiedFlightTracker)
                        // This ensures manual missions aren't overwritten by TelemetryRepository's AUTO-only logic
                        // The UnifiedFlightTracker is the single source of truth for flight state
                        val preserveMissionActive = currentState.isMissionActive
                        val preserveMissionElapsedSec = if (preserveMissionActive) currentState.missionElapsedSec else repoState.missionElapsedSec
                        val preserveTotalDistanceMeters = if (preserveMissionActive) currentState.totalDistanceMeters else repoState.totalDistanceMeters
                        val preserveMissionCompleted = currentState.missionCompleted
                        // While a mission is running the VM is authoritative: a deliberate clear at
                        // flight start must NOT be resurrected by the repo's stale copy (the repo only
                        // maintains this on the AUTO path, so after a manual flight it holds a value
                        // from an older mission). Outside an active mission the repo may still fill in.
                        val preserveLastMissionElapsedSec = if (preserveMissionActive) {
                            currentState.lastMissionElapsedSec
                        } else {
                            currentState.lastMissionElapsedSec ?: repoState.lastMissionElapsedSec
                        }
                        val preserveMissionCompletedHandled = currentState.missionCompletedHandled

                        repoState.copy(
                            missionPaused = currentState.missionPaused,
                            pausedAtWaypoint = currentState.pausedAtWaypoint,
                            isMissionActive = preserveMissionActive || repoState.isMissionActive,
                            missionElapsedSec = preserveMissionElapsedSec,
                            totalDistanceMeters = preserveTotalDistanceMeters,
                            missionCompleted = preserveMissionCompleted || repoState.missionCompleted,
                            lastMissionElapsedSec = preserveLastMissionElapsedSec,
                            missionCompletedHandled = preserveMissionCompletedHandled
                        )
                    }
                }
            }

            connectionJobs += viewModelScope.launch {
                try {
                    newRepo.mavFrame
                        .map { it.message }
                        .filterIsInstance<Statustext>()
                        .collect {
                            val statusText = it.text
                            // Surface common calibration-related prompts, including accel/compass/barometer keywords
                            val lower = statusText.lowercase()
                            val keys = listOf(
                                // generic
                                "calib", "progress",
                                // accel prompts
                                "place", "position", "level", "nose", "left", "right", "back",
                                // barometer
                                "baro", "barometer", "pressure"
                            )
                            if (keys.any { key -> lower.contains(key) }) {
                                _calibrationStatus.value = statusText
                            }
                        }
                } catch (e: Exception) {
                    LogUtils.e("SharedVM", "Error collecting status text", e)
                }
            }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Connection failed with error: ${e.message}", e)
                // Clean up on failure - cancel collection jobs and close connection
                connectionJobs.forEach { it.cancel() }
                connectionJobs.clear()
                // 🔥 FIX: Only clean up repo if it was set during this connect() call.
                // The old working aerogcsclone code had NO try-catch here at all.
                // If repo.start() succeeded but a later step (TrackingManager, collect setup) threw,
                // we should NOT null out repo — the MAVLink connection is still alive.
                // Instead, just log the error and let the connection continue working.
                if (repo != null) {
                    LogUtils.w("SharedVM", "⚠️ connect() threw after repo was created - NOT nulling repo (connection may still be alive)")
                    LogUtils.w("SharedVM", "⚠️ Exception was: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // --- Mission State ---
    // Expose mission uploaded state as StateFlow so UI can observe it reliably
    private val _missionUploaded = MutableStateFlow(false)
    val missionUploaded: StateFlow<Boolean> = _missionUploaded.asStateFlow()
    var lastUploadedCount by mutableStateOf(0)

    // Store the full uploaded mission items list for command-type lookup
    // Used by tank-empty detection to identify mission-end sequences (DO_SPRAYER(0), LOITER, RTL, LAND)
    @Volatile
    var lastUploadedMissionItems: List<MissionItemInt> = emptyList()
        private set

    // MAV_CMD IDs for mission-end detection
    private val MAV_CMD_DO_SPRAYER_ID = 216u
    private val MAV_CMD_NAV_LOITER_UNLIM_ID = 17u
    private val MAV_CMD_NAV_RETURN_TO_LAUNCH_ID = 20u
    private val MAV_CMD_NAV_LAND_ID = 21u

    /**
     * Check if the given mission sequence number is in the "mission end" phase.
     * Returns true if:
     * - The item at [seq] is a terminal nav command (LOITER_UNLIM, RTL, LAND)
     * - The item at [seq] is DO_SPRAYER with param1=0 (sprayer OFF)
     * - seq is within the last 3 items of the stored mission
     * This is used to prevent false "Tank Empty" alerts at end-of-mission.
     */
    fun isMissionEndSequence(seq: Int): Boolean {
        val items = lastUploadedMissionItems
        if (items.isEmpty()) return false

        // Check if seq is within the last 3 items
        if (seq >= items.size - 3) return true

        // Check the command type of the item at this sequence
        val item = items.find { it.seq.toInt() == seq } ?: return false
        val cmdValue = item.command.value

        return when (cmdValue) {
            MAV_CMD_NAV_LOITER_UNLIM_ID -> true
            MAV_CMD_NAV_RETURN_TO_LAUNCH_ID -> true
            MAV_CMD_NAV_LAND_ID -> true
            MAV_CMD_DO_SPRAYER_ID -> item.param1 == 0f  // Only DO_SPRAYER(0) = sprayer OFF
            else -> false
        }
    }

    /**
     * Whether the mission has spray COMMANDED ON at the given (current) sequence.
     *
     * Missions embed DO_SPRAYER(1) after each line-start and DO_SPRAYER(0) after each
     * line-end, so spray is intentionally OFF while the drone flies the horizontal
     * connector between lines. Tank-empty detection must NOT fire during those
     * transitions (flow legitimately drops to ~0). ArduPilot executes DO_ commands the
     * instant they are reached and advances the reported "current" sequence to the next
     * NAV item, so we key off the most recent DO_SPRAYER with seq STRICTLY LESS than the
     * current sequence (i.e. already executed) rather than expecting current to land on
     * the DO_SPRAYER item itself.
     *
     * Returns true (spray assumed on / behavior unchanged) when the mission is unknown or
     * contains no DO_SPRAYER commands (e.g. uploaded from another GCS, or manual spray),
     * so this only ever suppresses tank-empty during a genuine commanded-off transition.
     */
    fun isSprayCommandedActiveAt(seq: Int): Boolean {
        val items = lastUploadedMissionItems
        if (items.isEmpty()) return true            // unknown mission → don't change behavior
        val lastSprayer = items
            .filter { it.command.value == MAV_CMD_DO_SPRAYER_ID && it.seq.toInt() < seq }
            .maxByOrNull { it.seq.toInt() }
            ?: return true                          // no sprayer cmd executed yet → assume on
        return lastSprayer.param1 == 1f
    }

    // --- Current Mission Names (for tracking which template/mission is active) ---
    private val _currentProjectName = MutableStateFlow("")
    val currentProjectName: StateFlow<String> = _currentProjectName.asStateFlow()

    private val _currentPlotName = MutableStateFlow("")
    val currentPlotName: StateFlow<String> = _currentPlotName.asStateFlow()

    // --- Active spray-mission area parameters (for acres accounting) ---
    // Effective swath width (m) used for sprayed-area accounting. For auto missions this is
    // the planned line spacing — adjacent lanes are exactly this far apart, so
    // lane_width × length gives non-overlapping coverage (no double counting). Manual flights
    // use DEFAULT_SWATH_METERS. Read synchronously by UnifiedFlightTracker / TelemetryRepository.
    @Volatile
    var currentSwathMeters: Double = DEFAULT_SWATH_METERS
        private set

    // Geodesic area of the active plot polygon in acres. null for manual flights / no plot,
    // in which case "Total acres" falls back to a swept-path estimate.
    @Volatile
    var currentFieldAreaAcres: Double? = null
        private set

    // Planned spray ground speed (m/s) for the active mission, recorded with the other mission
    // params. NOT used to scale the spray rate — the slider writes SPRAY_PUMP_RATE 1:1 (see
    // [applySprayRateToFc]); the FC's own speed-proportional pump model is what applies speed.
    @Volatile
    var currentNominalSpeedMs: Double = DEFAULT_NOMINAL_SPEED_MS
        private set

    /**
     * Record the active spray-mission's area parameters when a plan is created/loaded/uploaded.
     * @param swathMeters effective swath (auto mission line spacing). Ignored if <= 0.
     * @param fieldAreaAcres geodesic plot area in acres, or null if unknown (manual/no polygon).
     * @param nominalSpeedMs planned spray ground speed in m/s. Ignored if <= 0.
     */
    fun setCurrentSprayMissionParams(
        swathMeters: Double?,
        fieldAreaAcres: Double?,
        nominalSpeedMs: Double?
    ) {
        if (swathMeters != null && swathMeters > 0.0) currentSwathMeters = swathMeters
        currentFieldAreaAcres = fieldAreaAcres
        if (nominalSpeedMs != null && nominalSpeedMs > 0.0) currentNominalSpeedMs = nominalSpeedMs
        LogUtils.i("SharedVM", "Spray mission params set - swath=${currentSwathMeters}m, fieldAcres=${currentFieldAreaAcres}, nominalSpeed=${currentNominalSpeedMs}m/s")
    }

    /** Reset spray-mission area params to defaults (new mission / manual flight). */
    fun resetCurrentSprayMissionParams() {
        currentSwathMeters = DEFAULT_SWATH_METERS
        currentFieldAreaAcres = null
        currentNominalSpeedMs = DEFAULT_NOMINAL_SPEED_MS
    }

    // --- Mission Completion Dialog State ---
    private val _showMissionCompletionDialog = MutableStateFlow(false)
    val showMissionCompletionDialog: StateFlow<Boolean> = _showMissionCompletionDialog.asStateFlow()

    // Store mission completion data for the dialog
    data class MissionCompletionData(
        val totalTime: String = "",
        val totalAcres: String = "",
        val sprayedAcres: String = "",
        val consumedLitres: String = ""
    )

    private val _missionCompletionData = MutableStateFlow(MissionCompletionData())
    val missionCompletionData: StateFlow<MissionCompletionData> = _missionCompletionData.asStateFlow()

    /**
     * Set the current mission names (project and plot)
     * Called when a template is loaded or mission is created
     */
    fun setCurrentMissionNames(projectName: String, plotName: String) {
        _currentProjectName.value = projectName
        _currentPlotName.value = plotName
        LogUtils.i("SharedVM", "Current mission names set - Project: $projectName, Plot: $plotName")
    }

    /**
     * Show the mission completion dialog with the given data
     * WebSocket stays connected until user clicks OK
     */
    fun showMissionCompletionDialog(totalTime: String, totalAcres: String, sprayedAcres: String, consumedLitres: String) {
        _missionCompletionData.value = MissionCompletionData(totalTime, totalAcres, sprayedAcres, consumedLitres)
        _showMissionCompletionDialog.value = true
        LogUtils.i("SharedVM", "Mission completion dialog triggered - Time: $totalTime, Acres: $totalAcres, Sprayed: $sprayedAcres, Litres: $consumedLitres")
        // 🔌 WebSocket stays connected - will be disconnected when user clicks OK
    }

    /**
     * Dismiss the mission completion dialog
     */
    fun dismissMissionCompletionDialog() {
        _showMissionCompletionDialog.value = false
        LogUtils.i("SharedVM", "Mission completion dialog dismissed")
    }

    /**
     * Save the mission completion data with project, plot names, and crop type
     * Called when user clicks OK on the completion dialog
     * This is when mission summary is sent and WebSocket connection is closed
     */
    fun saveMissionCompletionData(projectName: String, plotName: String, cropType: String) {
        _currentProjectName.value = projectName
        _currentPlotName.value = plotName
        _currentCropType.value = cropType
        _showMissionCompletionDialog.value = false
        LogUtils.i("SharedVM", "Mission completion data saved - Project: $projectName, Plot: $plotName, CropType: $cropType")

        // 🔥 Send mission summary with all data including crop type
        try {
            val wsManager = WebSocketManager.getInstance()
            val currentState = _telemetryState.value
            val completionData = _missionCompletionData.value

            // Parse total acres from the completion data string (e.g., "0.13 acres")
            val totalAcres = completionData.totalAcres
                .replace(" acres", "")
                .replace(" acre", "")
                .toDoubleOrNull() ?: 0.0

            // Parse total time from completion data string (e.g., "00:02:25") to minutes
            val timeParts = completionData.totalTime.split(":")
            val flyingTimeMinutes = if (timeParts.size == 3) {
                val hours = timeParts[0].toIntOrNull() ?: 0
                val minutes = timeParts[1].toIntOrNull() ?: 0
                val seconds = timeParts[2].toIntOrNull() ?: 0
                hours * 60.0 + minutes + seconds / 60.0
            } else 0.0

            val totalSprayUsed = currentState.sprayTelemetry.consumedLiters?.toDouble() ?: 0.0

            // Parse sprayed acres from the completion data string (e.g., "0.13 acres")
            val totalSprayedAcres = completionData.sprayedAcres
                .replace(" acres", "")
                .replace(" acre", "")
                .toDoubleOrNull() ?: 0.0

            LogUtils.d("SharedVM", "🔥 DEBUG: completionData.sprayedAcres='${completionData.sprayedAcres}', parsed totalSprayedAcres=$totalSprayedAcres")

            // Only send a summary if a backend session was actually opened this flight (drone took
            // off). Otherwise this is a ground arm/disarm whose dialog was shown locally — sending
            // would enqueue an orphan summary that a later real mission would inherit on reconnect.
            if (wsManager.sessionOpenedForFlight) {
                wsManager.sendMissionSummary(
                    totalAcres = totalAcres,
                    totalSprayUsed = totalSprayUsed,
                    flyingTimeMinutes = flyingTimeMinutes,
                    averageSpeed = 0.0, // Average speed would need to be calculated
                    alertsCount = wsManager.missionAlertsCount,
                    status = "COMPLETED",
                    projectName = projectName,
                    plotName = plotName,
                    cropType = cropType,
                    totalSprayedAcres = totalSprayedAcres
                )
                LogUtils.i("SharedVM", "📤 Mission summary sent with cropType=$cropType")
            } else {
                LogUtils.i("SharedVM", "⏭️ Skipped mission summary — no backend session (drone never took off)")
            }
        } catch (e: Exception) {
            LogUtils.e("SharedVM", "❌ Failed to send mission summary: ${e.message}", e)
        }

        // 🔌 Disconnect WebSocket after sending summary
        try {
            WebSocketManager.getInstance().disconnect()
            LogUtils.i("SharedVM", "🔌 WebSocket disconnected - User clicked OK on mission completion dialog")
        } catch (e: Exception) {
            LogUtils.e("SharedVM", "❌ Failed to disconnect WebSocket: ${e.message}", e)
        }

        // The actual saving to database should be handled by TlogViewModel or MissionTemplateViewModel
    }

    // Current crop type for mission
    private val _currentCropType = MutableStateFlow("")
    val currentCropType: StateFlow<String> = _currentCropType.asStateFlow()

    private val _uploadedWaypoints = MutableStateFlow<List<LatLng>>(emptyList())
    val uploadedWaypoints: StateFlow<List<LatLng>> = _uploadedWaypoints.asStateFlow()

    private val _surveyPolygon = MutableStateFlow<List<LatLng>>(emptyList())
    val surveyPolygon: StateFlow<List<LatLng>> = _surveyPolygon.asStateFlow()

    private val _gridLines = MutableStateFlow<List<Pair<LatLng, LatLng>>>(emptyList())
    val gridLines: StateFlow<List<Pair<LatLng, LatLng>>> = _gridLines.asStateFlow()

    private val _gridWaypoints = MutableStateFlow<List<LatLng>>(emptyList())
    val gridWaypoints: StateFlow<List<LatLng>> = _gridWaypoints.asStateFlow()

    private val _planningWaypoints = MutableStateFlow<List<LatLng>>(emptyList())
    val planningWaypoints: StateFlow<List<LatLng>> = _planningWaypoints.asStateFlow()

    private val _fenceRadius = MutableStateFlow(1f)  // Default 1m as requested
    val fenceRadius: StateFlow<Float> = _fenceRadius.asStateFlow()

    // Track previous fence radius to calculate delta for scaling
    private var _previousFenceRadius: Float = 1f

    private val _geofenceEnabled = MutableStateFlow(false)
    val geofenceEnabled: StateFlow<Boolean> = _geofenceEnabled.asStateFlow()

    private val _geofencePolygon = MutableStateFlow<List<LatLng>>(emptyList())
    val geofencePolygon: StateFlow<List<LatLng>> = _geofencePolygon.asStateFlow()

    // Geofence upload control - prevent concurrent uploads and add debouncing
    private var fenceUploadJob: Job? = null
    private val fenceUploadMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingFenceUpload: List<LatLng>? = null
    private var lastFenceUploadTime = 0L
    private val FENCE_UPLOAD_DEBOUNCE_MS = 1500L  // Wait 1.5 seconds after last change before uploading

    // Obstacle zones - list of polygons representing no-fly zones
    private val _obstacles = MutableStateFlow<List<List<LatLng>>>(emptyList())
    val obstacles: StateFlow<List<List<LatLng>>> = _obstacles.asStateFlow()

    // Clearance the uploaded mission was planned with, carried over from PlanScreen so the
    // home screen can shade the same buffer ring the drone is actually flying around.
    private val _obstacleBoundary = MutableStateFlow(GridGenerator.MIN_OBSTACLE_BUFFER_M.toFloat())
    val obstacleBoundary: StateFlow<Float> = _obstacleBoundary.asStateFlow()

    // Trigger to clear the drone's drawn flight path in GcsMap (incremented each time clear is requested)
    private val _clearDronePathTrigger = MutableStateFlow(0)
    val clearDronePathTrigger: StateFlow<Int> = _clearDronePathTrigger.asStateFlow()

    /**
     * The flown path with its per-point spray status — the source of the red/green trail.
     *
     * Held HERE rather than in a `remember` inside GcsMap because composable-local state dies
     * whenever the map leaves composition. GcsMap is instantiated separately by MainPage and
     * PlanScreen, so any navigation between them — which is exactly what a pause/resume
     * involves — destroyed the whole trail and took every green sprayed line with it. The
     * pilot then lost the record of what had already been covered, which is the one thing
     * they need when deciding where to resume.
     *
     * The ViewModel outlives that navigation, so the trail now survives it.
     */
    private val _dronePathPoints = MutableStateFlow<List<DronePathPoint>>(emptyList())
    val dronePathPoints: StateFlow<List<DronePathPoint>> = _dronePathPoints.asStateFlow()

    /**
     * Append one position sample to the trail, if it differs from the last one.
     *
     * Called from GcsMap on each position / spray-status change. A point is recorded when the
     * position moved OR the spray status flipped — the latter is what creates the boundary
     * between a red segment and a green one, so it must never be skipped.
     *
     * Unbounded on purpose: the pilot needs the whole flown trail intact until they explicitly
     * hit "Clear Map" (see [clearDronePath]) — a length cap here silently truncated the oldest
     * points once a long spray mission passed it, which looked like sprayed lines vanishing
     * mid-flight.
     */
    fun recordDronePathPoint(position: LatLng, isSpraying: Boolean) {
        val current = _dronePathPoints.value
        val last = current.lastOrNull()
        if (last != null && last.position == position && last.isSpraying == isSpraying) return

        _dronePathPoints.value = current + DronePathPoint(position, isSpraying)
    }

    /** Drop the whole trail. Only for an explicit "clear map" — never on resume. */
    fun clearDronePath() {
        _dronePathPoints.value = emptyList()
    }

    // Store home position for geofence calculation
    private val _homePosition = MutableStateFlow<LatLng?>(null)


    // Geofence shape: true for square, false for polygon (default square for MainPage)
    private val _useSquareGeofence = MutableStateFlow(true)
    val useSquareGeofence: StateFlow<Boolean> = _useSquareGeofence.asStateFlow()

    fun setGeofenceShape(useSquare: Boolean) {
        _useSquareGeofence.value = useSquare
        updateGeofencePolygon()
    }

    fun setSurveyPolygon(polygon: List<LatLng>) {
        _surveyPolygon.value = polygon
        updateGeofencePolygon()
        updateSurveyArea()
    }
    fun setGridLines(lines: List<Pair<LatLng, LatLng>>) {
        _gridLines.value = lines
    }
    fun setGridWaypoints(waypoints: List<LatLng>) {
        _gridWaypoints.value = waypoints
        updateGeofencePolygon()
        // Grid waypoints may be derived from survey polygon - ensure survey area is recalculated
        updateSurveyArea()
    }

    /**
     * Set obstacle zones for display on the map
     */
    fun setObstacles(obstacleList: List<List<LatLng>>) {
        _obstacles.value = obstacleList
    }

    /**
     * Set the obstacle clearance the mission was planned with, so the home screen shades the
     * same buffer ring PlanScreen showed.
     */
    fun setObstacleBoundary(boundaryMeters: Float) {
        _obstacleBoundary.value = boundaryMeters
    }

    fun setPlanningWaypoints(waypoints: List<LatLng>) {
        _planningWaypoints.value = waypoints
        updateGeofencePolygon()
        updateSurveyArea()
    }

    fun setFenceRadius(radius: Float) {
        // Ensure minimum 5m radius
        val newRadius = radius.coerceAtLeast(5f)

        _fenceRadius.value = newRadius

        // 🔥 FIX: Regenerate geofence from current waypoints instead of scaling a potentially
        // stale polygon. But ONLY update the local polygon for display — use debounced upload
        // to avoid flooding the FC with upload attempts on every slider tick.
        if (_geofenceEnabled.value) {
            // Regenerate the polygon shape locally for immediate UI feedback
            regenerateGeofencePolygonLocally()
            // Schedule debounced upload to FC (will upload after user stops adjusting)
            if (_geofencePolygon.value.size >= 3) {
                scheduleGeofenceUpload(_geofencePolygon.value)
            }
        }

        // Update the previous radius tracker
        _previousFenceRadius = newRadius
    }

    /**
     * Regenerate geofence polygon locally (UI only, no FC upload).
     * Used during slider adjustments for immediate visual feedback.
     */
    private fun regenerateGeofencePolygonLocally() {
        if (!_geofenceEnabled.value) {
            _geofencePolygon.value = emptyList()
            return
        }

        val allWaypoints = mutableListOf<LatLng>()

        val homePos = _homePosition.value
        if (homePos != null) allWaypoints.add(homePos)

        if (_uploadedWaypoints.value.isNotEmpty()) {
            allWaypoints.addAll(_uploadedWaypoints.value)
        } else {
            allWaypoints.addAll(_planningWaypoints.value)
        }
        allWaypoints.addAll(_surveyPolygon.value)
        allWaypoints.addAll(_gridWaypoints.value)

        if (allWaypoints.isNotEmpty()) {
            val bufferDistance = _fenceRadius.value.toDouble().coerceAtLeast(7.0)
            val geofenceShape = if (_useSquareGeofence.value) {
                GeofenceUtils.generateSquareGeofence(allWaypoints, bufferDistance)
            } else {
                GeofenceUtils.generatePolygonBuffer(allWaypoints, bufferDistance)
            }
            if (geofenceShape.size >= 3) {
                _geofencePolygon.value = geofenceShape
            }
        }
    }

    fun setGeofenceEnabled(enabled: Boolean) {
        _geofenceEnabled.value = enabled
        if (enabled) {
            // Reset geofence state for fresh monitoring
            resetGeofenceState()

            // 🔥 CRITICAL FIX: Clear any stale geofence polygon and home position BEFORE
            // regenerating. This ensures we never re-use a geofence from a previous session
            // even if the ViewModel has been alive for days with old data.
            _geofencePolygon.value = emptyList()
            _fenceConfiguration.value = null
            _homePosition.value = null
            LogUtils.i("Geofence", "🧹 Cleared stale geofence data before enabling fresh geofence")

            // Capture current drone position as home position
            val droneLat = _telemetryState.value.latitude
            val droneLon = _telemetryState.value.longitude
            if (droneLat != null && droneLon != null) {
                _homePosition.value = LatLng(droneLat, droneLon)
                LogUtils.i("Geofence", "Home position captured: $droneLat, $droneLon")
            }

            // Generate geofence polygon from CURRENT waypoints and upload to FC
            // This will call uploadGeofenceToFC which uses the new Mission Planner approach
            updateGeofencePolygon()

            // Restart fence status monitoring with current repo
            if (repo != null) {
                startFenceStatusMonitoring()
            }

            LogUtils.i("Geofence", "✓ Geofence ENABLED - uploading to FC")
            addNotification(
                Notification(
                    message = "Geofence enabled - uploading to FC...",
                    type = NotificationType.INFO
                )
            )
        } else {
            // IMMEDIATELY reset ALL local geofence state FIRST
            // This prevents stale state from causing false warnings or arm blocks
            // even if the FC communication below fails
            stopFenceStatusMonitoring()
            resetGeofenceState()
            _geofencePolygon.value = emptyList()
            _fenceConfiguration.value = null
            _homePosition.value = null
            LogUtils.i("Geofence", "Geofence DISABLED - all local state reset")

            // Then attempt to disable/clear geofence on FC (best effort)
            viewModelScope.launch {
                try {
                    // Try clearing fence data from FC completely
                    val cleared = repo?.clearGeofenceFromFC() ?: false
                    if (cleared) {
                        LogUtils.i("Geofence", "✅ Geofence cleared from FC")
                    } else {
                        // Fallback 1: just disable the fence parameter
                        LogUtils.w("Geofence", "⚠️ clearGeofenceFromFC failed, trying enableFence(false)")
                        val disabled = repo?.enableFence(false) ?: false
                        if (disabled) {
                            LogUtils.i("Geofence", "✅ Geofence disabled on FC via FENCE_ENABLE=0")
                        } else {
                            LogUtils.e("Geofence", "❌ Failed to disable fence on FC - fence may remain active on FC!")
                            addNotification(
                                Notification(
                                    message = "⚠️ Could not disable fence on FC. Power-cycle FC to clear.",
                                    type = NotificationType.WARNING
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    LogUtils.e("Geofence", "❌ Exception disabling geofence on FC", e)
                    // Best-effort fallback: try enableFence(false) one more time
                    try {
                        repo?.enableFence(false)
                    } catch (_: Exception) {}
                }
            }

            addNotification(
                Notification(
                    message = "Geofence disabled",
                    type = NotificationType.INFO
                )
            )
        }
    }

    /**
     * Manually update the geofence polygon (for user adjustments via dragging)
     * Uses debounced upload to prevent rapid uploads during dragging.
     */
    fun updateGeofencePolygonManually(polygon: List<LatLng>) {
        if (_geofenceEnabled.value && polygon.size >= 3) {
            _geofencePolygon.value = polygon
            LogUtils.d("Geofence", "Geofence polygon manually updated with ${polygon.size} vertices")

            // Use debounced upload for manual adjustments (dragging)
            scheduleGeofenceUpload(polygon)
        }
    }

    private fun updateGeofencePolygon() {
        if (!_geofenceEnabled.value) {
            _geofencePolygon.value = emptyList()
            return
        }

        val allWaypoints = mutableListOf<LatLng>()

        // Count mission waypoints SEPARATELY from home position
        val missionWaypointCount = _uploadedWaypoints.value.size +
            _planningWaypoints.value.size +
            _surveyPolygon.value.size +
            _gridWaypoints.value.size

        // 🔥 Log all waypoint sources to help diagnose stale geofence issues
        LogUtils.i("Geofence", "📊 Waypoint sources: uploaded=${_uploadedWaypoints.value.size}, " +
            "planning=${_planningWaypoints.value.size}, survey=${_surveyPolygon.value.size}, " +
            "grid=${_gridWaypoints.value.size}, home=${if (_homePosition.value != null) "set" else "null"}, " +
            "total_mission_wps=$missionWaypointCount")

        // ALWAYS include home position (where drone was when geofence was enabled)
        val homePos = _homePosition.value
        if (homePos != null) {
            allWaypoints.add(homePos)
            LogUtils.d("Geofence", "Added home position to geofence: $homePos")
        }

        // DO NOT include current drone position - geofence should remain stationary
        // The drone should move within the fence, not the fence move with the drone

        // Add mission waypoints
        if (_uploadedWaypoints.value.isNotEmpty()) {
            allWaypoints.addAll(_uploadedWaypoints.value)
            LogUtils.d("Geofence", "Added ${_uploadedWaypoints.value.size} uploaded waypoints")
        } else {
            allWaypoints.addAll(_planningWaypoints.value)
            if (_planningWaypoints.value.isNotEmpty()) {
                LogUtils.d("Geofence", "Added ${_planningWaypoints.value.size} planning waypoints")
            }
        }
        allWaypoints.addAll(_surveyPolygon.value)
        if (_surveyPolygon.value.isNotEmpty()) {
            LogUtils.d("Geofence", "Added ${_surveyPolygon.value.size} survey polygon points")
        }
        allWaypoints.addAll(_gridWaypoints.value)
        if (_gridWaypoints.value.isNotEmpty()) {
            LogUtils.d("Geofence", "Added ${_gridWaypoints.value.size} grid waypoints")
        }

        if (allWaypoints.isNotEmpty()) {
            // Use default buffer distance with 7m minimum (increased from 5m for 2m extra safety)
            val bufferDistance = _fenceRadius.value.toDouble().coerceAtLeast(7.0)
            LogUtils.i("Geofence", "Generating ${if (_useSquareGeofence.value) "square" else "polygon"} geofence with ${allWaypoints.size} points, buffer distance: ${bufferDistance}m")

            val geofenceShape = if (_useSquareGeofence.value) {
                GeofenceUtils.generateSquareGeofence(allWaypoints, bufferDistance)
            } else {
                GeofenceUtils.generatePolygonBuffer(allWaypoints, bufferDistance)
            }

            if (geofenceShape.size >= 3) {
                _geofencePolygon.value = geofenceShape
                LogUtils.i("Geofence", "✓ Geofence ${if (_useSquareGeofence.value) "square" else "polygon"} generated successfully with ${geofenceShape.size} vertices")

                // 🔥 CRITICAL FIX: Only upload to FC if we have ACTUAL MISSION WAYPOINTS.
                // When there's only home position (0 mission waypoints), the generated fence
                // is a tiny square around the drone's current position — uploading this to the
                // FC would either fail or create a useless fence that the drone immediately breaches.
                // The FC upload will happen automatically when mission waypoints are set
                // (via setPlanningWaypoints/setSurveyPolygon/setGridWaypoints/uploadMission which
                // all call updateGeofencePolygon again).
                if (missionWaypointCount > 0) {
                    // 🔥 VALIDATION: Verify all source waypoints are inside the generated geofence
                    var allInside = true
                    for ((index, wp) in allWaypoints.withIndex()) {
                        val isInside = GeofenceUtils.isPointInPolygon(wp, geofenceShape)
                        val distToEdge = GeofenceUtils.distanceToPolygonEdge(wp, geofenceShape)
                        if (!isInside) {
                            LogUtils.e("Geofence", "❌ VALIDATION FAILED: Waypoint $index at ${wp.latitude}, ${wp.longitude} is OUTSIDE generated geofence!")
                            allInside = false
                        } else {
                            LogUtils.d("Geofence", "✓ Waypoint $index inside geofence, ${String.format("%.1f", distToEdge)}m from edge")
                        }
                    }

                    if (!allInside) {
                        LogUtils.e("Geofence", "⚠️ WARNING: Some waypoints are outside the geofence! Buffer may be too small.")
                        // Increase buffer and regenerate
                        val largerBuffer = bufferDistance + 5.0
                        LogUtils.i("Geofence", "Attempting to regenerate with larger buffer: ${largerBuffer}m")
                        val largerGeofence = if (_useSquareGeofence.value) {
                            GeofenceUtils.generateSquareGeofence(allWaypoints, largerBuffer)
                        } else {
                            GeofenceUtils.generatePolygonBuffer(allWaypoints, largerBuffer)
                        }
                        _geofencePolygon.value = largerGeofence
                        LogUtils.i("Geofence", "✓ Regenerated geofence with larger buffer")

                        // Upload regenerated geofence to FC immediately
                        if (largerGeofence.size >= 3) {
                            uploadGeofenceImmediately(largerGeofence)
                        }
                    } else {
                        // Upload geofence to FC immediately
                        uploadGeofenceImmediately(geofenceShape)
                    }
                } else {
                    LogUtils.i("Geofence", "📌 Geofence generated for display only (0 mission waypoints) — FC upload deferred until mission is uploaded")
                }
            } else {
                LogUtils.w("Geofence", "Failed to generate valid geofence")
                _geofencePolygon.value = emptyList()
            }
        } else {
            LogUtils.w("Geofence", "No waypoints available for geofence")
            _geofencePolygon.value = emptyList()
        }
    }

    /**
     * Validates that all points are inside or on the polygon boundary
     */
    private fun validatePolygonContainsPoints(polygon: List<LatLng>, points: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        // Check if all points are inside the polygon with a small tolerance
        for (point in points) {
            if (!GeofenceUtils.isPointInPolygon(point, polygon)) {
                LogUtils.w("SharedVM", "Point not in geofence: $point")
                return false
            }
        }
        return true
    }

    /**
     * Schedule a debounced geofence upload to Flight Controller.
     * This prevents rapid uploads when user is adjusting the slider or dragging points.
     * The actual upload happens after FENCE_UPLOAD_DEBOUNCE_MS of no changes.
     */
    private fun scheduleGeofenceUpload(polygon: List<LatLng>) {
        if (polygon.size < 3) {
            LogUtils.w("Geofence", "❌ Cannot upload geofence: insufficient points (${polygon.size})")
            return
        }

        // Store the pending polygon
        pendingFenceUpload = polygon

        // Cancel any existing debounce job
        fenceUploadJob?.cancel()

        // Start a new debounce job
        fenceUploadJob = viewModelScope.launch {
            LogUtils.d("Geofence", "⏳ Waiting ${FENCE_UPLOAD_DEBOUNCE_MS}ms before uploading fence...")
            delay(FENCE_UPLOAD_DEBOUNCE_MS)

            // After debounce period, upload the latest polygon
            pendingFenceUpload?.let { polygonToUpload ->
                uploadGeofenceToFCInternal(polygonToUpload)
            }
            pendingFenceUpload = null
        }
    }

    /**
     * Force immediate geofence upload (bypasses debounce).
     * Used when enabling geofence for the first time.
     */
    private fun uploadGeofenceImmediately(polygon: List<LatLng>) {
        if (polygon.size < 3) {
            LogUtils.w("Geofence", "❌ Cannot upload geofence: insufficient points (${polygon.size})")
            return
        }

        // Cancel any pending debounced upload
        fenceUploadJob?.cancel()
        pendingFenceUpload = null

        viewModelScope.launch {
            uploadGeofenceToFCInternal(polygon)
        }
    }

    /**
     * Internal function to upload geofence to Flight Controller.
     * Uses mutex to prevent concurrent uploads.
     */
    private suspend fun uploadGeofenceToFCInternal(polygon: List<LatLng>) {
        // 🔥 FIX: Check connection state BEFORE attempting upload
        // Prevents flooding FC with doomed upload attempts when not connected
        // NOTE: Use _telemetryState.value.connected directly instead of isConnected.value
        // because isConnected uses SharingStarted.WhileSubscribed(5000) which defaults to
        // false when no UI collector is active — causing uploads to be silently skipped.
        val currentState = _telemetryState.value
        if (repo == null) {
            LogUtils.w("Geofence", "⏭️ Skipping fence upload: not connected to FC (repo is null)")
            return
        }
        if (!currentState.fcuDetected) {
            LogUtils.w("Geofence", "⏭️ Skipping fence upload: FCU not yet detected")
            return
        }
        if (!currentState.connected) {
            LogUtils.w("Geofence", "⏭️ Skipping fence upload: connection not active (telemetryState.connected=false)")
            return
        }

        // Use mutex to prevent concurrent uploads
        if (!fenceUploadMutex.tryLock()) {
            LogUtils.w("Geofence", "⏳ Upload already in progress, skipping...")
            return
        }

        try {
            LogUtils.i("Geofence", "🔥 Starting geofence upload to FC: ${polygon.size} points")
            // Log actual polygon coordinates for debugging stale fence issues
            polygon.forEachIndexed { idx, pt ->
                LogUtils.d("Geofence", "  Fence vertex[$idx]: ${pt.latitude}, ${pt.longitude}")
            }

            // Use the new Mission Planner-style upload.
            // No action/margin here: FENCE_ACTION and FENCE_MARGIN are operator-owned and
            // the GCS never writes them (see configureFenceParameters).
            val config = FenceConfiguration(
                zones = listOf(FenceZone.Polygon(points = polygon, isInclusion = true)),
                // The FC's alt fence is biased slightly BELOW the pilot's ceiling, because
                // ArduPilot arrests the climb after detecting the breach and coasts past the
                // limit. See getFcAltitudeFenceMax().
                altitudeMax = getFcAltitudeFenceMax()
                    ?: (DEFAULT_MAX_ALTITUDE_M - FC_ALT_FENCE_SAFETY_OFFSET_M),
                // The range limit is enforced GCS-side (handleMaxRangeFailsafe), so the
                // upload does not touch FENCE_RADIUS or the FENCE_TYPE circle bit.
            )

            val uploadResult = repo?.uploadGeofence(config) ?: false

            if (uploadResult) {
                LogUtils.i("Geofence", "✅ Fence uploaded to FC successfully")
                _fenceConfiguration.value = config
                lastFenceUploadTime = System.currentTimeMillis()
                // NOTE: Removed geofence upload notification from notification panel
                // The upload progress is shown in the dedicated upload UI
            } else {
                LogUtils.e("Geofence", "❌ Failed to upload fence to FC")
                // NOTE: Removed geofence upload failure notification from notification panel
            }

        } catch (e: Exception) {
            LogUtils.e("Geofence", "❌ Geofence upload failed", e)
            // NOTE: Removed geofence upload error notification from notification panel
        } finally {
            fenceUploadMutex.unlock()
        }
    }

    /**
     * Legacy function name for compatibility - now uses debounced upload
     */
    private fun uploadGeofenceToFC(polygon: List<LatLng>) {
        scheduleGeofenceUpload(polygon)
    }

    // Spray control state
    private val _sprayEnabled = MutableStateFlow(false)
    val sprayEnabled: StateFlow<Boolean> = _sprayEnabled.asStateFlow()

    // Seeded from the vehicle's SPRAY_PUMP_RATE on connect (see seedSprayConfigFromVehicle), so
    // the slider shows what the FC is actually set to instead of always reading 100 on startup.
    // The 100f here is only the pre-connect placeholder.
    private val _sprayRate = MutableStateFlow(100f) // 10% to 100%
    val sprayRate: StateFlow<Float> = _sprayRate.asStateFlow()

    // Pump floor (SPRAY_PUMP_MIN) and sprayer master switch (SPRAY_ENABLE), read from the vehicle
    // on connect. Both are needed to show the pilot the pump output the FC will actually command:
    // AC_Sprayer floors its computed output at SPRAY_PUMP_MIN, and does nothing at all unless
    // SPRAY_ENABLE = 1. Null = not read yet / read timed out.
    private val _sprayPumpMin = MutableStateFlow<Float?>(null)
    val sprayPumpMin: StateFlow<Float?> = _sprayPumpMin.asStateFlow()

    private val _sprayEnableParam = MutableStateFlow<Boolean?>(null)
    val sprayEnableParam: StateFlow<Boolean?> = _sprayEnableParam.asStateFlow()

    // ── Pump mode: AUTO (speed-scaled) vs MANUAL (direct duty cycle) ───────────
    //
    // NOTE: this is NOT the `autoSpray` flag in PlanScreen. That one decides whether a planned
    // grid mission gets DO_SPRAYER items embedded at survey-line boundaries (see
    // GridMissionConverter.convertToMissionItems). This one decides how the FC computes pump
    // output once the pump is on. They are independent and must not be merged.
    //
    // AUTO  = the historical behaviour: SPRAY_PUMP_RATE is % pump per 1 m/s, so output scales
    //         with groundspeed (constant L/ha).
    // MANUAL = new firmware path: SPRAY_PUMP_PCT is a direct 0-100 duty cycle, independent of
    //         speed. Fly slower and the same ground gets more chemical — that is the point, for
    //         spot work and ground testing.
    private val _sprayManualMode = MutableStateFlow(false)
    val sprayManualMode: StateFlow<Boolean> = _sprayManualMode.asStateFlow()

    // The Manual-mode duty cycle, kept separate from _sprayRate so switching modes back and forth
    // does not destroy the other mode's setting. Seeded from the vehicle on connect.
    private val _sprayManualPct = MutableStateFlow(50f)
    val sprayManualPct: StateFlow<Float> = _sprayManualPct.asStateFlow()

    // Null until the connect-time probe runs; false means the FC rejected the reads, i.e. it is
    // running firmware without the manual-pump params. The UI uses this to disable the toggle
    // rather than let the pilot select a mode the vehicle cannot honour.
    private val _sprayManualParamsSupported = MutableStateFlow<Boolean?>(null)
    val sprayManualParamsSupported: StateFlow<Boolean?> = _sprayManualParamsSupported.asStateFlow()

    // Track spray state before pause for automatic restore on resume
    private var _sprayWasActiveBeforePause = false

    // ========== YAW HOLD STATE (Hold Nose Position feature) ==========
    // These control continuous yaw enforcement during AUTO mode
    private val _yawHoldEnabled = MutableStateFlow(false)
    val yawHoldEnabled: StateFlow<Boolean> = _yawHoldEnabled.asStateFlow()

    private val _lockedYaw = MutableStateFlow<Float?>(null)
    val lockedYaw: StateFlow<Float?> = _lockedYaw.asStateFlow()

    private var yawEnforcementJob: kotlinx.coroutines.Job? = null
    private val YAW_TOLERANCE = 5f  // degrees - only send correction if error exceeds this
    private val YAW_ENFORCEMENT_INTERVAL = 500L  // ms - how often to check/enforce yaw
    // ================================================================

    // --- Notification State ---
    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    /**
     * Event stream emitting each notification at the moment it is added. Unlike
     * [notifications] (a snapshot list for the UI panel), this is a one-shot event
     * source so observers — e.g. UnifiedFlightTracker — can persist warnings/errors
     * (tank empty, low voltage, etc.) to the local flight logs without diffing the list.
     */
    private val _notificationEvents = MutableSharedFlow<Notification>(extraBufferCapacity = 32)
    val notificationEvents: SharedFlow<Notification> = _notificationEvents.asSharedFlow()

    private val _isNotificationPanelVisible = MutableStateFlow(false)
    val isNotificationPanelVisible: StateFlow<Boolean> = _isNotificationPanelVisible.asStateFlow()

    // Spray status popup (temporary message that disappears after 2 seconds)
    private val _sprayStatusPopup = MutableStateFlow<String?>(null)
    val sprayStatusPopup: StateFlow<String?> = _sprayStatusPopup.asStateFlow()

    // Failsafe alert popup (top-left, temporary message that disappears after
    // FAILSAFE_POPUP_DURATION_MS). Shared by every failsafe trigger — Battery, Fence, Max
    // Range, Max Altitude, Tank Empty, RC, RTL/link-loss — so only one can be on screen at a
    // time; a new trigger restarts the window. Also dismissible early via [dismissFailsafePopup].
    private val FAILSAFE_POPUP_DURATION_MS = 6000L
    private val _failsafePopup = MutableStateFlow<String?>(null)
    val failsafePopup: StateFlow<String?> = _failsafePopup.asStateFlow()
    private var failsafePopupJob: Job? = null

    // Last failsafe reason and when it fired, so [handleRtlModeAnnouncement] can name the cause
    // of an RTL that follows within FAILSAFE_REASON_LINGER_MS instead of silently replacing it.
    private var lastFailsafePopupReason: String? = null
    private var lastFailsafePopupTime = 0L
    private val FAILSAFE_REASON_LINGER_MS = 15000L

    fun showFailsafePopup(message: String) {
        // Don't let an RTL announcement overwrite the reason it is meant to be attributed to.
        if (!message.startsWith("RTL Flight Mode")) {
            lastFailsafePopupReason = message
            lastFailsafePopupTime = System.currentTimeMillis()
        }
        failsafePopupJob?.cancel()
        failsafePopupJob = viewModelScope.launch {
            _failsafePopup.value = message
            delay(FAILSAFE_POPUP_DURATION_MS)
            _failsafePopup.value = null
        }
    }

    /** Pilot tapped the popup's close button — dismiss immediately instead of waiting out the timer. */
    fun dismissFailsafePopup() {
        failsafePopupJob?.cancel()
        _failsafePopup.value = null
    }

    // ── Vehicle service alert ─────────────────────────────────────────────────
    // Set to true when backend reports is_limit_reached for the connected drone
    private val _showServiceAlert = MutableStateFlow(false)
    val showServiceAlert: StateFlow<Boolean> = _showServiceAlert.asStateFlow()

    fun dismissServiceAlert() {
        _showServiceAlert.value = false
    }

    /**
     * Observes telemetryState.droneUid — the moment the drone is identified via
     * AUTOPILOT_VERSION / OpenDroneID (i.e. real drone connection), call the REST API
     * to check whether the vehicle has reached its max flight limit.
     * Uses distinctUntilChanged so the check fires once per unique drone UID.
     */
    fun setupServiceLimitCheck() {
        viewModelScope.launch {
            _telemetryState
                .map { it.droneUid }
                .distinctUntilChanged()
                .collect { droneUid ->
                    if (!droneUid.isNullOrBlank()) {
                        try {
                            LogUtils.i("ServiceLimit", "🔍 Drone identified ($droneUid) — checking service limit")
                            val result = ApiService.checkVehicleServiceLimit(droneUid)
                            if (result is com.example.kftgcs.api.ApiResponse.Success) {
                                val limitReached = result.data.vehicles.any { it.is_limit_reached }
                                if (limitReached) {
                                    _showServiceAlert.value = true
                                    LogUtils.w("ServiceLimit", "🔧 Vehicle $droneUid has reached max flight limit")
                                }
                            }
                        } catch (e: Exception) {
                            LogUtils.e("ServiceLimit", "Failed to check vehicle service limit", e)
                        }
                    }
                }
        }
    }

    fun addNotification(notification: Notification) {
        _notifications.value = listOf(notification) + _notifications.value
        _notificationEvents.tryEmit(notification)
    }

    fun toggleNotificationPanel() {
        _isNotificationPanelVisible.value = !_isNotificationPanelVisible.value
    }

    fun showSprayStatusPopup(message: String) {
        viewModelScope.launch {
            _sprayStatusPopup.value = message
            delay(2000) // Show for 2 seconds
            _sprayStatusPopup.value = null
        }
    }

    // ========== ADD RESUME HERE POPUP STATE ==========
    // Triggered when mode changes from AUTO to LOITER during a mission
    private val _showAddResumeHerePopup = MutableStateFlow(false)
    val showAddResumeHerePopup: StateFlow<Boolean> = _showAddResumeHerePopup.asStateFlow()

    // The waypoint number where the mode changed (resume point candidate)
    private val _resumePointWaypoint = MutableStateFlow<Int?>(null)
    val resumePointWaypoint: StateFlow<Int?> = _resumePointWaypoint.asStateFlow()

    // The location (LatLng) where the drone paused - for displaying "R" marker on map
    private val _resumePointLocation = MutableStateFlow<LatLng?>(null)
    val resumePointLocation: StateFlow<LatLng?> = _resumePointLocation.asStateFlow()

    // Track the previous mode to detect AUTO -> LOITER transition
    private var previousMode: String? = null

    // Flag to track if we have a stored resume mission ready to execute
    private val _resumeMissionReady = MutableStateFlow(false)
    val resumeMissionReady: StateFlow<Boolean> = _resumeMissionReady.asStateFlow()

    /**
     * True once an attempt to put the resume mission on the FC has failed.
     *
     * Separate from `!resumeMissionReady`, which is also the state before anything has been
     * attempted. This one means "we tried and the drone still holds the original mission",
     * which is the case a pilot must not walk into by flicking the mode switch. See
     * [reportResumePreparationFailed].
     */
    private val _resumePreparationFailed = MutableStateFlow(false)
    val resumePreparationFailed: StateFlow<Boolean> = _resumePreparationFailed.asStateFlow()

    /**
     * True while [processResumePoint] is still downloading, clearing and uploading the resumed
     * mission — seconds of work on a shared link.
     *
     * Without this, a pilot who flicks to AUTO mid-preparation fell into the "no resume point
     * loaded" branch of [onModeChangedToAuto], which reads as an outright failure when the resume
     * was in fact about to be ready.
     */
    private val _resumePreparationInProgress = MutableStateFlow(false)
    val resumePreparationInProgress: StateFlow<Boolean> = _resumePreparationInProgress.asStateFlow()

    /**
     * Called when mode changes from AUTO to LOITER or BRAKE (detected in TelemetryRepository)
     * This shows a popup asking user if they want to set resume point here
     * NOTE: Only works when user selected Automatic mode, not Manual mode
     */
    fun onModeChangedToLoiterFromAuto(waypointNumber: Int) {
        // Safety check: Don't show popup if user is in Manual mode
        if (!isPauseResumeEnabled()) {
            LogUtils.i("SharedVM", "=== MODE CHANGED: AUTO → LOITER/BRAKE === (IGNORED - user in MANUAL mode)")
            return
        }

        val currentMode = _telemetryState.value.mode ?: "Loiter"
        LogUtils.i("SharedVM", "=== MODE CHANGED: AUTO → $currentMode ===")
        LogUtils.i("SharedVM", "Waypoint at mode change: $waypointNumber")

        // The FC has not told us where the mission is, so there is no resume point to offer.
        // Say so instead of showing a popup that would prepare a resume from a guessed sequence —
        // the guess lands on the previous resume's transit waypoint and flies the wrong line.
        if (waypointNumber == MISSION_PROGRESS_UNKNOWN) {
            LogUtils.e("SharedVM", "⚠️ AUTO → $currentMode but the FC has reported no mission progress — cannot offer a resume point")
            _telemetryState.update { it.copy(missionPaused = true, pausedAtWaypoint = null) }
            reportResumePreparationFailed("the drone has not reported mission progress yet")
            return
        }

        _resumePointWaypoint.value = waypointNumber

        // Capture where the mission was actually interrupted. _pendingResumeLocation drives the
        // green "R" marker and is only promoted once the user confirms; _missionPauseLocation is
        // kept regardless, because EVERY resume path has to fly back to this exact spot before
        // carrying on - otherwise the drone rejoins at the far end of the line it was half way
        // along and the rest of that line never gets sprayed.
        val currentLat = _telemetryState.value.latitude
        val currentLon = _telemetryState.value.longitude
        if (currentLat != null && currentLon != null) {
            val pausedAt = LatLng(currentLat, currentLon)
            _pendingResumeLocation = pausedAt
            _missionPauseLocation = pausedAt
            LogUtils.i("SharedVM", "Pause location captured: $currentLat, $currentLon")
        }

        // Do NOT set resume location yet - wait for user confirmation
        // _resumePointLocation.value = ...

        // Show popup to ask user if they want to set resume point
        _showAddResumeHerePopup.value = true

        // Also set the paused state
        _telemetryState.update {
            it.copy(
                missionPaused = true,
                pausedAtWaypoint = waypointNumber
            )
        }

        addNotification(
            Notification(
                message = "Mode changed to $currentMode at waypoint $waypointNumber",
                type = NotificationType.INFO
            )
        )
    }

    // Temporary storage for pending resume location (before user confirms)
    private var _pendingResumeLocation: LatLng? = null

    // Where the drone was when the mission was interrupted. Unlike _resumePointLocation (which
    // only exists once the pilot confirms the "Set Resume Point" popup) this is always
    // recorded, so a resume never falls back to "fly straight to the next waypoint".
    private var _missionPauseLocation: LatLng? = null

    /**
     * The position a resume must fly back to before continuing the mission: the pilot's
     * confirmed resume point if there is one, otherwise the exact spot where the mission was
     * interrupted. Null only if no GPS fix was available at pause time.
     */
    private fun effectiveResumeLocation(): LatLng? =
        _resumePointLocation.value ?: _missionPauseLocation

    /**
     * Called when user confirms "OK" on the resume point popup
     * This sets the resume location marker and processes the resume mission
     */
    fun confirmSetResumePoint() {
        val waypointNumber = _resumePointWaypoint.value ?: return

        LogUtils.i("SharedVM", "User confirmed resume point at waypoint $waypointNumber")

        // Now set the resume location to show the "R" marker
        _pendingResumeLocation?.let {
            _resumePointLocation.value = it
            LogUtils.i("SharedVM", "Resume point marker set at: ${it.latitude}, ${it.longitude}")
        }

        // Hide the popup
        _showAddResumeHerePopup.value = false

        // Process the resume point in background
        processResumePoint(waypointNumber)
    }

    /**
     * Called when user cancels/dismisses the resume point popup
     * No marker is shown and no processing happens
     */
    fun cancelSetResumePoint() {
        LogUtils.i("SharedVM", "User cancelled resume point")

        // Clear pending location
        _pendingResumeLocation = null
        _resumePointWaypoint.value = null
        _resumePreparationFailed.value = false
        _resumeMissionReady.value = false

        // Hide the popup
        _showAddResumeHerePopup.value = false

        // Declining is a legitimate choice, but the consequence is not obvious: the FC still
        // holds the untouched mission, so a later switch to AUTO carries on from the FC's own
        // index rather than from here. Say so once, quietly.
        addNotification(
            Notification(
                message = "No resume point set — AUTO will continue the original mission, not from this position",
                type = NotificationType.INFO
            )
        )

        // Reset mission paused state since user declined to set resume point
        // This allows the mission to be cleared when navigating back and clicking Manual
        _telemetryState.update {
            it.copy(
                missionPaused = false,
                pausedAtWaypoint = null
            )
        }

        // Do NOT show resume marker - user declined
    }

    /**
     * What [prepareResumeMission] did. [failureReason] is written for a pilot, not a log.
     */
    private data class ResumePrepResult(
        val success: Boolean,
        val failureReason: String? = null,
        val itemCount: Int = 0,
        /** The index actually resumed from. Callers report it; -1 when preparation failed. */
        val resumeSeq: Int = -1
    )

    /**
     * Build the resumed mission and put it on the flight controller.
     *
     * THE one implementation. There used to be four — processResumePoint,
     * confirmAddResumeHere, resumeMissionFromManualPoint and resumeMissionComplete each carried
     * their own copy of read-mission, filter, resequence, validate, upload, set-index. They
     * drifted, because nothing kept them together: a fix went into whichever copy the bug had
     * been reported against, and the other three kept the old behaviour. That is the mechanism
     * behind "we fixed resume and it broke again" — resume was fixed, for one of the four
     * buttons, and the pilot pressed a different one.
     *
     * Concretely, before this was extracted, only processResumePoint had the cached-mission
     * fast path, only it retried a failed download, and only it refused to act on an unknown
     * mission index. The other three would happily build a resume against a mission they had
     * failed to read properly.
     *
     * Callers keep their own UI: popups, progress text, notifications, whether to engage AUTO
     * afterwards. What they must NOT keep is their own copy of the protocol sequence below.
     *
     * @param resumeSeq  mission index to resume from. [MISSION_PROGRESS_UNKNOWN] is rejected.
     * @param resumeLocation where the drone actually paused, inserted as the transit waypoint.
     * @param onProgress optional pilot-facing progress text.
     */
    private suspend fun prepareResumeMission(
        resumeSeq: Int,
        resumeLocation: LatLng?,
        onProgress: (String) -> Unit = {}
    ): ResumePrepResult {
        // Checked before the download too, so an unknown index costs nothing. selectResumeSeq
        // below re-checks, because the manual-point caller cannot know its index until the
        // mission is in hand.
        if (resumeSeq == MISSION_PROGRESS_UNKNOWN || resumeSeq < 0) {
            LogUtils.e("ResumeMission", "Refusing to resume: mission progress unknown (seq=$resumeSeq)")
            return ResumePrepResult(false, "the drone has not reported its mission progress yet")
        }
        return prepareResumeMission(resumeLocation, onProgress) { resumeSeq }
    }

    /**
     * As [prepareResumeMission], but the resume index is chosen FROM the mission once it has
     * been read back.
     *
     * Resuming from a point dropped on the map needs the mission in hand to work out which
     * segment the pin falls on. Passing a selector keeps that one caller on the same single
     * implementation instead of giving it a private copy of the download — which is exactly how
     * four copies of this sequence came to exist.
     */
    private suspend fun prepareResumeMission(
        resumeLocation: LatLng?,
        onProgress: (String) -> Unit = {},
        selectResumeSeq: (List<MissionItemInt>) -> Int
    ): ResumePrepResult {
        onProgress("Checking connection...")
        if (!_telemetryState.value.connected) {
            return ResumePrepResult(false, "not connected to the flight controller")
        }

        // ═══ Read the mission the FC is holding ═══
        //
        // Fast path: reuse the mission this app uploaded, when the FC's own item count agrees
        // with the cache. A full download costs a request and a reply per item and dominates
        // the time a resume takes; the count probe is one round trip.
        //
        // Trusted ONLY on an exact count match. Everything downstream reasons by sequence
        // number, so resuming against a mission the FC is not actually holding is precisely how
        // the drone ends up on the wrong line. The cache is cleared on disarm-clear, on Clear
        // Mission and on every failed upload, and is only ever written together with
        // lastUploadedCount, so a non-empty cache whose size matches the FC is the same mission.
        //
        // Any disagreement, any unanswered probe, any empty cache — fall through to the real
        // download. This is an optimisation, never a source of truth.
        onProgress("Retrieving mission from FC...")
        val cached = lastUploadedMissionItems
        var allWaypoints: List<MissionItemInt>? = null

        if (cached.isNotEmpty() && cached.size == lastUploadedCount) {
            val fcCount = repo?.getMissionCountForCacheCheck()
            if (fcCount == cached.size) {
                LogUtils.i("ResumeMission", "FC reports $fcCount items, matching the cached mission — skipping the download")
                allWaypoints = cached
            } else {
                LogUtils.i("ResumeMission", "Cached mission (${cached.size}) does not match the FC (${fcCount ?: "no answer"}) — downloading")
            }
        }

        if (allWaypoints == null) {
            // Retried: this is the most fragile step (a full mission download over a link also
            // carrying video and telemetry) and it is the one whose failure used to be silent.
            var downloaded = repo?.getAllWaypoints()
            if (downloaded.isNullOrEmpty()) {
                LogUtils.w("ResumeMission", "Mission download returned nothing — retrying once")
                delay(1000)
                downloaded = repo?.getAllWaypoints()
            }
            allWaypoints = downloaded
        }

        if (allWaypoints.isNullOrEmpty()) {
            return ResumePrepResult(false, "could not read the mission back from the drone")
        }
        LogUtils.i("ResumeMission", "Retrieved ${allWaypoints.size} waypoints from FC")

        // ═══ Which index are we resuming from? ═══
        //
        // A resume against an unknown index is worse than no resume at all: everything below
        // reasons by sequence number, so a bad seq produces a confident, WRONG mission and the
        // drone re-flies a line it has already sprayed, or skips one it has not. The FC reports
        // progress within a second or so of AUTO starting; a pilot told "not ready yet" can wait
        // for that. Guarded here so no entry point can skip the check.
        val resumeSeq = selectResumeSeq(allWaypoints)
        if (resumeSeq == MISSION_PROGRESS_UNKNOWN || resumeSeq < 0) {
            LogUtils.e("ResumeMission", "Refusing to resume: mission progress unknown (seq=$resumeSeq)")
            return ResumePrepResult(false, "the drone has not reported its mission progress yet")
        }

        // ═══ Cut the mission down to what is left, keeping the pause point ═══
        onProgress("Filtering waypoints from resume point...")
        val filtered = repo?.filterWaypointsForResume(
            allWaypoints,
            resumeSeq,
            resumeLatitude = resumeLocation?.latitude,
            resumeLongitude = resumeLocation?.longitude,
            restoreSpray = _sprayWasActiveBeforePause
        )
        if (filtered.isNullOrEmpty()) {
            return ResumePrepResult(false, "no waypoints left after the resume point")
        }
        LogUtils.i("ResumeMission", "Filtered to ${filtered.size} waypoints")

        onProgress("Resequencing waypoints...")
        val resequenced = repo?.resequenceWaypoints(filtered)
        if (resequenced.isNullOrEmpty()) {
            return ResumePrepResult(false, "could not renumber the resumed mission")
        }

        // The FC rejects a mission whose seqs are not 0..n-1, and it rejects it mid-transfer,
        // after the clear has already wiped what was there. Catch it here instead.
        val sequences = resequenced.map { it.seq.toInt() }
        if (sequences != resequenced.indices.toList()) {
            LogUtils.e("ResumeMission", "Invalid sequence numbers: $sequences")
            return ResumePrepResult(false, "the resumed mission was numbered wrongly")
        }
        LogUtils.i("ResumeMission", "Resequenced and validated: ${resequenced.size} waypoints")

        // ═══ Upload ═══
        //
        // Short settle first. getAllWaypoints closes its download with a MISSION_ACK, but give
        // any item the FC had already put on the wire time to drain before the upload's
        // MISSION_CLEAR_ALL starts waiting on an ack of its own.
        delay(300)
        onProgress("Uploading modified mission to FC...")
        val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
        if (!uploadSuccess) {
            return ResumePrepResult(false, "the drone rejected the resumed mission")
        }
        LogUtils.i("ResumeMission", "Modified mission uploaded to FC")

        // Read the count back. Advisory only — the upload's own ack is the contract — but a
        // mismatch here is the earliest visible sign that the FC did not commit what it acked.
        delay(500)
        val verifyCount = repo?.getMissionCount()
        if (verifyCount != null && verifyCount != resequenced.size) {
            LogUtils.w("ResumeMission", "FC reports $verifyCount waypoints but ${resequenced.size} were uploaded")
        }

        // ═══ Point the FC at the inserted transit waypoint ═══
        //
        // Not fatal. onModeChangedToAuto re-asserts the index through resumeMission() and
        // refuses to engage AUTO unless it is confirmed, so a drop here is recoverable. Worth a
        // warning though: it usually means the link is congested.
        onProgress("Setting current waypoint...")
        val setWpResult = repo?.setCurrentWaypoint(RESUME_TRANSIT_WAYPOINT_SEQ) ?: false
        if (!setWpResult) {
            LogUtils.w("ResumeMission", "FC did not confirm the mission index; will be re-asserted when AUTO is engaged")
        }

        // The FC now holds this mission, so the cache must describe it — including for the next
        // resume's fast path, which compares against exactly these two fields.
        _missionUploaded.value = true
        _missionLoadedOnFc.value = true
        lastUploadedCount = resequenced.size
        lastUploadedMissionItems = resequenced.toList()

        return ResumePrepResult(true, itemCount = resequenced.size, resumeSeq = resumeSeq)
    }

    /**
     * Process the resume point - retrieves and uploads modified mission
     * This runs in the background after user confirms
     *
     * ═══ WHY EVERY FAILURE HERE IS ANNOUNCED ═══
     *
     * This used to log-and-return on each failure path with nothing shown to the pilot, and
     * that silence is what produced the "drone resumes from the start or the end of the line"
     * report. The chain is:
     *
     *   1. Any step fails - most often [getAllWaypoints], a full mission download that has to
     *      complete inside 10s with a 2s budget per item, on the same link the video is on.
     *   2. [_resumeMissionReady] stays false and the FC keeps the ORIGINAL mission.
     *   3. Nothing tells the pilot. The "R" marker is already on the map, so as far as they
     *      can see the resume point took.
     *   4. They flick the transmitter to AUTO. [onModeChangedToAuto] sees no resume mission
     *      and does nothing, so ArduPilot simply carries on with its own stored mission -
     *      flying to whatever index it was on (the END of the line it was half way along) or,
     *      if MIS_RESTART is set, starting the whole grid again from the FIRST waypoint.
     *
     * So the drone is not resuming from the wrong place. It is not resuming at all, and the
     * FC's own behaviour is what the pilot is seeing. The fix is to make that state
     * impossible to miss: retry the flaky step, and on final failure say plainly that the
     * resume point did NOT take and the mission on the drone is unchanged.
     */
    private fun processResumePoint(waypointNumber: Int) {
        viewModelScope.launch {
            val resumeLocation = effectiveResumeLocation()
            LogUtils.i("SharedVM", "=== AUTO PROCESSING RESUME POINT (BACKGROUND) ===")
            LogUtils.i("SharedVM", "Resume waypoint: $waypointNumber, location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")

            _resumePreparationFailed.value = false
            _resumePreparationInProgress.value = true

            try {
                val result = prepareResumeMission(waypointNumber, resumeLocation)
                if (!result.success) {
                    reportResumePreparationFailed(result.failureReason ?: "unexpected error")
                    return@launch
                }

                _resumeMissionReady.value = true
                _resumePreparationFailed.value = false

                LogUtils.i("SharedVM", "Resume mission ready (${result.itemCount} items)")

                // Confirm it, for the same reason the failures are announced: the pilot has
                // to be able to tell the two states apart before they touch the mode switch.
                addNotification(
                    Notification(
                        message = "✅ Resume point set — switch to AUTO to fly back to it and carry on",
                        type = NotificationType.SUCCESS
                    )
                )
                ttsManager?.speak("Resume point ready")

            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to auto-process resume point", e)
                reportResumePreparationFailed(e.message ?: "unexpected error")
            } finally {
                // Every exit above is a return@launch, so this is the only place the flag can be
                // reliably cleared.
                _resumePreparationInProgress.value = false
            }
        }
    }

    /**
     * The resume mission could NOT be put on the flight controller.
     *
     * Loud on purpose. The dangerous state is not the failure itself - it is a pilot who
     * believes the resume point took, flicks to AUTO, and gets the flight controller's own
     * idea of where the mission is. Popup, notification and voice all fire, the "R" marker is
     * pulled back off the map, and the mission stays marked paused so it can be retried.
     */
    private fun reportResumePreparationFailed(reason: String) {
        LogUtils.e("SharedVM", "❌ Resume point NOT set: $reason")

        _resumeMissionReady.value = false
        _resumePreparationFailed.value = true
        // The marker is a promise the FC cannot keep, so take it back down.
        _resumePointLocation.value = null

        addNotification(
            Notification(
                message = "⛔ Resume point NOT set — $reason. The drone still holds the ORIGINAL mission: " +
                    "do not switch to AUTO, it will not carry on from here.",
                type = NotificationType.ERROR
            )
        )
        ttsManager?.speak("Resume point failed. Do not switch to auto.")
    }

    /**
     * Put the aircraft into AUTO so the resumed mission starts flying.
     *
     * Cuts the pump first. The transit leg back to the resume point must be dry — the uploaded
     * mission turns spray back on with its own DO_SPRAYER item when the drone arrives, so
     * leaving it running here double-doses everything between here and there.
     *
     * Retried three times: a mode change can be refused while the FC is still digesting the
     * mission upload that immediately precedes it.
     */
    private suspend fun engageAutoForResume(onProgress: (String) -> Unit = {}): Boolean {
        ensureSprayerOffForTransit()

        onProgress("Switching to AUTO mode...")
        repeat(3) { attempt ->
            if (repo?.changeMode(MavMode.AUTO) == true) {
                LogUtils.i("ResumeMission", "Switched to AUTO (attempt ${attempt + 1})")
                return true
            }
            LogUtils.w("ResumeMission", "AUTO mode attempt ${attempt + 1}/3 failed")
            if (attempt < 2) delay(2000)
        }
        LogUtils.e("ResumeMission", "Failed to switch to AUTO after 3 attempts; mode is ${_telemetryState.value.mode}")
        return false
    }

    /**
     * Drop the paused-mission state, now that a resume is actually flying.
     *
     * Only called once AUTO is confirmed. Clearing it earlier would leave a pilot whose mode
     * change failed looking at a GCS that says the mission is running when it is not.
     */
    private fun markMissionResumed(resumeSeq: Int) {
        _telemetryState.update {
            it.copy(missionPaused = false, pausedAtWaypoint = null)
        }
        _pendingResumeLocation = null
        _missionPauseLocation = null

        try {
            WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
            WebSocketManager.getInstance().sendMissionEvent(
                eventType = "MISSION_RESUMED",
                eventStatus = "INFO",
                description = "Mission resumed"
            )
        } catch (e: Exception) {
            LogUtils.e("ResumeMission", "Failed to send RESUMED status", e)
        }

        // Spray restore is handled by the uploaded mission's DO_SPRAYER items (see
        // filterWaypointsForResume) — off for the transit leg, back on at the resume waypoint.
        // Do NOT fire an immediate spray-on here.
        if (_sprayWasActiveBeforePause) {
            LogUtils.i("ResumeMission", "Spray resumes at waypoint $resumeSeq via the mission's DO_SPRAYER item (off during transit)")
            _sprayWasActiveBeforePause = false
        }

        addNotification(
            Notification(
                message = "Mission resumed from waypoint $resumeSeq",
                type = NotificationType.SUCCESS
            )
        )
        ttsManager?.announceMissionResumed()
    }

    /**
     * Called when user confirms "Add Resume Here" in the popup
     * This stores the resume point and prepares the mission for resume
     */
    fun confirmAddResumeHere(onProgress: (String) -> Unit = {}, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val resumeWaypoint = _resumePointWaypoint.value ?: run {
                LogUtils.e("SharedVM", "No resume waypoint stored!")
                onResult(false, "No resume waypoint stored")
                return@launch
            }

            val resumeLocation = effectiveResumeLocation()
            LogUtils.i("ResumeMission", "=== CONFIRM ADD RESUME HERE ===")
            LogUtils.i("ResumeMission", "Resume waypoint: $resumeWaypoint, location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")

            _showAddResumeHerePopup.value = false

            try {
                val result = prepareResumeMission(resumeWaypoint, resumeLocation, onProgress)
                if (!result.success) {
                    onResult(false, result.failureReason)
                    return@launch
                }

                if (!engageAutoForResume(onProgress)) {
                    onResult(false, "Failed to switch to AUTO. Stuck in: ${_telemetryState.value.mode}")
                    return@launch
                }

                onProgress("Mission resumed!")
                markMissionResumed(resumeWaypoint)

                LogUtils.i("ResumeMission", "Resume mission complete (${result.itemCount} items)")
                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ResumeMission", "Resume mission failed", e)
                addNotification(Notification("Resume mission failed: ${e.message}", NotificationType.ERROR))
                onResult(false, e.message)
            }
        }
    }

    /**
     * Resume mission from a manually placed point on the map.
     *
     * Strategy: Find the mission segment (WPi → WPi+1) that the resume point lies closest to,
     * then use WPi+1 (the END of that segment) as the resumeWaypointSeq so that the drone:
     *   1. Flies to the EXACT placed lat/lng first (inserted by filterWaypointsForResume)
     *   2. Continues with WPi+1 onwards — never backtracks to the segment start
     *
     * @param lat Latitude of the manually placed resume point
     * @param lng Longitude of the manually placed resume point
     * @param onProgress Callback for progress messages
     * @param onResult Callback for final result (success, errorMessage)
     */
    fun resumeMissionFromManualPoint(
        lat: Double,
        lng: Double,
        onProgress: (String) -> Unit = {},
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            // Same reason as processResumePoint: this takes seconds, and a pilot who engages AUTO
            // meanwhile must be told "still uploading", not "no resume point loaded".
            _resumePreparationInProgress.value = true
            try {
                val result = prepareResumeMission(
                    resumeLocation = LatLng(lat, lng),
                    onProgress = onProgress
                ) { allWaypoints ->
                    // Find the mission segment (WPi → WPi+1) the dropped pin lies closest to,
                    // and resume from the segment END. The drone then flies to the exact placed
                    // point first (filterWaypointsForResume inserts it) and carries on from
                    // WPi+1 — it never backtracks to the start of the segment it was already
                    // part-way along.
                    val navWaypoints = allWaypoints
                        .filter { it.seq.toInt() > 0 && it.x != 0 && it.y != 0 }
                        .sortedBy { it.seq.toInt() }

                    if (navWaypoints.size >= 2) {
                        var bestSegEndSeq = navWaypoints.last().seq.toInt()
                        var bestDist = Double.MAX_VALUE

                        for (i in 0 until navWaypoints.size - 1) {
                            val aLat = navWaypoints[i].x / 1e7
                            val aLng = navWaypoints[i].y / 1e7
                            val bLat = navWaypoints[i + 1].x / 1e7
                            val bLng = navWaypoints[i + 1].y / 1e7

                            val dist = pointToSegmentDistanceSq(lat, lng, aLat, aLng, bLat, bLng)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestSegEndSeq = navWaypoints[i + 1].seq.toInt()
                            }
                        }
                        bestSegEndSeq
                    } else {
                        // A pin dropped against a mission with no flyable legs has nothing to
                        // resume from. MISSION_PROGRESS_UNKNOWN makes prepareResumeMission say
                        // so; the old code defaulted to 1, which silently sent the drone to the
                        // start of the mission.
                        navWaypoints.firstOrNull()?.seq?.toInt() ?: MISSION_PROGRESS_UNKNOWN
                    }
                }

                if (!result.success) {
                    onResult(false, result.failureReason)
                    return@launch
                }

                LogUtils.i("ManualResume", "Resuming from segment end seq=${result.resumeSeq}: fly to the exact point, then continue from WP${result.resumeSeq}")

                _resumeMissionReady.value = true
                _resumePreparationFailed.value = false

                onProgress("Done!")
                addNotification(Notification("Resume point set — mission ready to resume", NotificationType.SUCCESS))
                LogUtils.i("ManualResume", "Manual resume mission ready (${result.itemCount} items)")
                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ManualResume", "Failed to process manual resume point", e)
                onResult(false, e.message)
            } finally {
                // Every exit above is a return@launch, so this is the only reliable clear.
                _resumePreparationInProgress.value = false
            }
        }
    }

    /**
     * Called when user dismisses the "Add Resume Here" popup without confirming
     */
    fun dismissAddResumeHerePopup() {
        _showAddResumeHerePopup.value = false
        _resumePointWaypoint.value = null
        LogUtils.i("SharedVM", "Add Resume Here popup dismissed")
    }

    /**
     * Called when mode changes to AUTO and we have a resume mission ready
     * This starts the mission from the resume point
     */
    fun onModeChangedToAuto() {
        if (_resumeMissionReady.value) {
            LogUtils.i("SharedVM", "=== MODE CHANGED TO AUTO - STARTING RESUME MISSION ===")

            viewModelScope.launch {
                // Small delay to ensure FC is ready
                delay(300)

                // Pump off before the drone starts flying back to the resume point. The
                // resume mission's DO_SPRAYER(1) turns it on again once it gets there.
                ensureSprayerOffForTransit()

                // resumeMission(), NOT startMission(): the latter sends MAV_CMD_MISSION_START
                // with param1 = 0, which resets the FC's mission index to the first item and
                // threw away the DO_SET_MISSION_CURRENT(1) that processResumePoint had just
                // set — the drone then re-flew the first two or three waypoints of the
                // resumed mission. The resumed mission always begins at the inserted transit
                // waypoint, which resequenceWaypoints puts at index 1.
                val startSuccess = repo?.resumeMission(RESUME_TRANSIT_WAYPOINT_SEQ) ?: false

                if (startSuccess) {
                    LogUtils.i("SharedVM", "✅ Resume mission started successfully")
                    _resumeMissionReady.value = false
                    _resumePreparationFailed.value = false
                    // Clear the resume point location (remove "R" marker from map)
                    _resumePointLocation.value = null
                    _resumePointWaypoint.value = null
                    _pendingResumeLocation = null
                    _missionPauseLocation = null
                    _telemetryState.update {
                        it.copy(
                            missionPaused = false,
                            pausedAtWaypoint = null
                        )
                    }

                    // ✅ Spray restore is handled by the resume mission that was already
                    // uploaded (processResumePoint / resumeMissionFromManualPoint), which
                    // brackets the resume waypoint with DO_SPRAYER(0)/DO_SPRAYER(1). Turning
                    // spray on here would spray the whole transit leg to that waypoint.
                    if (_sprayWasActiveBeforePause) {
                        LogUtils.i("SharedVM", "💧 Spray will resume at the resume point via the mission's DO_SPRAYER item (off during transit)")
                        _sprayWasActiveBeforePause = false
                    }

                    addNotification(
                        Notification(
                            message = "Mission resumed from stored point",
                            type = NotificationType.SUCCESS
                        )
                    )
                    ttsManager?.announceMissionResumed()
                } else {
                    // The pilot is ALREADY in AUTO at this point — that mode change is what
                    // called us. So a failure here is not "nothing happened": the FC is flying
                    // its stored mission from whatever index it holds, which is exactly the
                    // wrong-waypoint case this fix exists to prevent. Say so plainly.
                    //
                    // _resumeMissionReady is deliberately left TRUE: the resumed mission is
                    // still the one on the FC, so dropping back out of AUTO and back in will
                    // retry the index. Clearing it would silently downgrade the next attempt
                    // to the "no resume point loaded" branch below.
                    LogUtils.e("SharedVM", "❌ Resume failed — FC did not confirm the resume index while already in AUTO")
                    addNotification(
                        Notification(
                            message = "⛔ Resume did not take — the drone is flying from the wrong point. " +
                                "Switch out of AUTO now, then back to AUTO to retry.",
                            type = NotificationType.ERROR
                        )
                    )
                    ttsManager?.speak("Resume failed. Switch out of auto.")
                }
            }
        } else if (_resumePreparationInProgress.value) {
            // ═══ AUTO WHILE THE RESUME IS STILL BEING UPLOADED ═══
            //
            // Distinct from the branch below: nothing has failed, the resumed mission just is not
            // on the FC yet. The vehicle is nevertheless in AUTO now and ArduPilot is flying its
            // OWN stored mission, so the pilot still needs telling — but told the truth, which is
            // "wait", not "it failed".
            //
            // As above, we do not fight the pilot for the mode switch.
            LogUtils.w("SharedVM", "⚠️ AUTO entered while the resume mission is still uploading")
            addNotification(
                Notification(
                    message = "⚠️ Resume point is still uploading. The drone is flying its original mission — " +
                        "switch out of AUTO, wait for \"Resume point ready\", then go back to AUTO.",
                    type = NotificationType.ERROR
                )
            )
            ttsManager?.speak("Resume point still uploading. Switch out of auto and wait.")
        } else if (_telemetryState.value.missionPaused) {
            // ═══ AUTO WITH A PAUSED MISSION AND NOTHING PREPARED ═══
            //
            // This is the branch that produced "the drone resumes from the start or the end
            // of the line". Nothing here commands anything, which reads as safe — but the
            // vehicle is now in AUTO, and ArduPilot does not need us: it picks its own
            // stored mission back up at whatever index it holds. That is the far end of the
            // line it was half way along, or, with MIS_RESTART set, waypoint 1 of the whole
            // grid. Either way it is not where the pilot paused, and until now they got no
            // warning that the GCS had bowed out.
            //
            // We deliberately do NOT try to take the mode back. Wrestling a pilot for the
            // flight mode is worse than letting them fly; what they need is to KNOW, now,
            // that this is the FC's mission and not their resume.
            LogUtils.e(
                "SharedVM",
                "⚠️ AUTO entered with a PAUSED mission but no resume mission prepared " +
                    "(preparationFailed=${_resumePreparationFailed.value}) — the FC is flying its OWN stored mission"
            )
            addNotification(
                Notification(
                    message = "⚠️ No resume point is loaded. AUTO is flying the drone's original mission, " +
                        "not continuing from where you paused.",
                    type = NotificationType.ERROR
                )
            )
            ttsManager?.speak("Warning. No resume point loaded. Flying the original mission.")
        }
    }

    /**
     * Update the previous mode tracking (called from TelemetryRepository)
     */
    fun updatePreviousMode(mode: String?) {
        previousMode = mode
    }

    /**
     * Get the previous mode for transition detection
     */
    fun getPreviousMode(): String? = previousMode

    // --- MAVLink Actions ---

    fun arm() {
        viewModelScope.launch {
            repo?.arm()
        }
    }


    /**
     * Send a calibration command to the vehicle.
     * This method is used for accelerometer calibration and other calibration procedures.
     */
    suspend fun sendCalibrationCommand(
        command: MavCmd,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f
    ) {
        repo?.sendCommand(
            command = command,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            param5 = param5,
            param6 = param6,
            param7 = param7
        )
    }

    /**
     * Send a raw calibration command using command ID (for ArduPilot-specific commands).
     */
    suspend fun sendCalibrationCommandRaw(
        commandId: UInt,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f
    ) {
        repo?.sendCommandRaw(
            commandId = commandId,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            param5 = param5,
            param6 = param6,
            param7 = param7
        )
    }

    /**
     * Send COMMAND_ACK back to autopilot (for ArduPilot conversational calibration protocol).
     * This is used during IMU calibration where GCS sends ACK to autopilot to confirm position.
     */
    suspend fun sendCommandAck(
        commandId: UInt,
        result: MavResult,
        progress: UByte = 0u,
        resultParam2: Int = 0
    ) {
        repo?.sendCommandAck(
            commandId = commandId,
            result = result,
            progress = progress,
            resultParam2 = resultParam2
        )
    }

    /**
     * Validate mission structure before upload
     * Checks for NAV_TAKEOFF, proper sequence numbers, valid coordinates, etc.
     * Returns Pair(isValid, errorMessage)
     */
    private fun validateMissionStructure(missionItems: List<MissionItemInt>): Pair<Boolean, String?> {
        if (missionItems.isEmpty()) {
            return Pair(false, "Mission is empty")
        }

        // Check sequence numbers are sequential starting from 0
        val sequences = missionItems.map { it.seq.toInt() }.sorted()
        val expectedSequences = (0 until missionItems.size).toList()

        if (sequences != expectedSequences) {
            // Enhanced logging for debugging
            LogUtils.e("MissionValidation", "❌ Sequence validation FAILED")
            LogUtils.e("MissionValidation", "Expected sequences: $expectedSequences")
            LogUtils.e("MissionValidation", "Actual sequences: $sequences")
            LogUtils.e("MissionValidation", "Missing sequences: ${expectedSequences.minus(sequences.toSet())}")
            LogUtils.e("MissionValidation", "Extra sequences: ${sequences.toSet().minus(expectedSequences.toSet())}")

            // Log each mission item for debugging
            missionItems.forEach { item ->
                LogUtils.e("MissionValidation", "Item: seq=${item.seq} cmd=${item.command.value} current=${item.current}")
            }

            return Pair(false, "Invalid sequence numbers - Expected: $expectedSequences, Got: $sequences")
        }

        // Find NAV_TAKEOFF command
        val hasTakeoff = missionItems.any { it.command.value == MavCmdId.NAV_TAKEOFF }
        if (!hasTakeoff) {
            LogUtils.w("MissionValidation", "⚠️ Mission does not contain NAV_TAKEOFF command!")
            addNotification(
                Notification(
                    "WARNING: Mission missing NAV_TAKEOFF. AUTO mode may fail with 'Missing Takeoff Cmd'",
                    NotificationType.WARNING
                )
            )
            // Don't fail validation, just warn - some missions might work without it
        }

        // Check that NAV_TAKEOFF is early in mission (ideally seq 1 after HOME)
        val takeoffSeq = missionItems.find { it.command.value == MavCmdId.NAV_TAKEOFF }?.seq?.toInt()
        if (takeoffSeq != null && takeoffSeq > 2) {
            LogUtils.w("MissionValidation", "⚠️ NAV_TAKEOFF at seq=$takeoffSeq (expected seq=1)")
            addNotification(
                Notification(
                    "WARNING: NAV_TAKEOFF should be at sequence 1 (after HOME)",
                    NotificationType.WARNING
                )
            )
        }

        // Validate coordinates and altitudes for NAV commands
        missionItems.forEach { item ->
            val cmdId = item.command.value
            // NAV commands: WAYPOINT, LOITER, RETURN_TO_LAUNCH, LAND, TAKEOFF
            if (cmdId in listOf(
                    MavCmdId.NAV_WAYPOINT,
                    MavCmdId.NAV_LOITER_UNLIM,
                    MavCmdId.NAV_RETURN_TO_LAUNCH,
                    MavCmdId.NAV_LAND,
                    MavCmdId.NAV_TAKEOFF
                )) {
                val lat = item.x / 1e7
                val lon = item.y / 1e7

                // Skip HOME waypoint (seq=0) coordinate check as it can be (0,0)
                if (item.seq.toInt() != 0) {
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        return Pair(false, "Invalid coordinates at seq=${item.seq}: lat=$lat, lon=$lon")
                    }
                    if (lat == 0.0 && lon == 0.0) {
                        LogUtils.w("MissionValidation", "⚠️ Waypoint at seq=${item.seq} has coordinates (0,0)")
                    }
                }

                if (item.z < AltitudeLimits.MIN_ALTITUDE || item.z > AltitudeLimits.MAX_ALTITUDE) {
                    return Pair(false, "Invalid altitude at seq=${item.seq}: ${item.z}m (valid range: ${AltitudeLimits.MIN_ALTITUDE}-${AltitudeLimits.MAX_ALTITUDE}m)")
                }
            }
        }

        LogUtils.i("MissionValidation", "✅ Mission structure validation passed")
        return Pair(true, null)
    }

    fun uploadMission(missionItems: List<MissionItemInt>, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                LogUtils.i("MissionUpload", "═══ VM: Starting mission upload (${missionItems.size} items) ═══")

                if (repo == null) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    LogUtils.e("MissionUpload", "VM: No repository available")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    LogUtils.e("MissionUpload", "VM: FCU not detected")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                // Validate mission structure before uploading
                LogUtils.i("MissionUpload", "VM: Validating mission structure...")
                val (isValid, errorMessage) = validateMissionStructure(missionItems)
                if (!isValid) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    LogUtils.e("MissionUpload", "VM: Mission validation failed - $errorMessage")
                    addNotification(
                        Notification("Mission validation failed: $errorMessage", NotificationType.ERROR)
                    )
                    onResult(false, "Mission validation failed: $errorMessage")
                    return@launch
                }
                LogUtils.i("MissionUpload", "VM: Mission structure validation passed")

                // Show progress: Uploading
                _missionUploadProgress.value = MissionUploadProgress(
                    stage = "Uploading",
                    currentItem = 0,
                    totalItems = missionItems.size,
                    message = "Uploading ${missionItems.size} waypoints..."
                )
                LogUtils.d("MissionUpload", "VM: Progress UI updated - Uploading")

                val success = repo?.uploadMissionWithAck(
                    missionItems = missionItems,
                    onProgress = { currentItem, totalItems ->
                        // Update progress UI on main thread
                        _missionUploadProgress.value = MissionUploadProgress(
                            stage = "Uploading",
                            currentItem = currentItem,
                            totalItems = totalItems,
                            message = "Uploading waypoint $currentItem of $totalItems..."
                        )
                    }
                ) ?: false

                _missionUploaded.value = success
                if (success) {
                    LogUtils.i("MissionUpload", "VM: Upload successful, processing waypoints...")
                    lastUploadedCount = missionItems.size
                    lastUploadedMissionItems = missionItems.toList()
                    val waypoints = missionItems.filter { item ->
                        item.command.value != 20u && !(item.x == 0 && item.y == 0)
                    }.map { item ->
                        LatLng(item.x / 1E7, item.y / 1E7)
                    }
                    _uploadedWaypoints.value = waypoints
                    updateGeofencePolygon()

                    // Calculate mission area
                    if (_surveyPolygon.value.size >= 3) {
                        val areaMeters = GridUtils.calculatePolygonArea(_surveyPolygon.value)
                        val formatted = GridUtils.calculateAndFormatPolygonArea(_surveyPolygon.value)
                        _missionAreaSqMeters.value = areaMeters
                        _missionAreaFormatted.value = formatted
                        LogUtils.d("MissionUpload", "VM: Mission area (survey polygon): $formatted")
                    } else if (waypoints.size >= 3) {
                        val formatted = GridUtils.calculateAndFormatPolygonArea(waypoints)
                        val areaMeters = GridUtils.calculatePolygonArea(waypoints)
                        _missionAreaSqMeters.value = areaMeters
                        _missionAreaFormatted.value = formatted
                        LogUtils.d("MissionUpload", "VM: Mission area (waypoints): $formatted")
                    } else {
                        _missionAreaSqMeters.value = 0.0
                        _missionAreaFormatted.value = "0 acres"
                    }

                    // Success notification
                    _missionUploadProgress.value = MissionUploadProgress(
                        stage = "Complete",
                        currentItem = missionItems.size,
                        totalItems = missionItems.size,
                        message = "Mission uploaded successfully!"
                    )
                    delay(1500)
                    _missionUploadProgress.value = null

                    LogUtils.i("MissionUpload", "VM: ✅ Upload complete - ${missionItems.size} items")
                    onResult(true, null)
                } else {
                    LogUtils.e("MissionUpload", "VM: ❌ Upload failed")
                    lastUploadedCount = 0
                    _uploadedWaypoints.value = emptyList()
                    _missionAreaSqMeters.value = 0.0
                    _missionAreaFormatted.value = "0 acres"
                    _missionUploaded.value = false

                    // Error notification
                    _missionUploadProgress.value = MissionUploadProgress(
                        stage = "Failed",
                        currentItem = 0,
                        totalItems = missionItems.size,
                        message = "Upload failed"
                    )
                    delay(2000)
                    _missionUploadProgress.value = null

                    onResult(false, "Mission upload failed")
                }
            } catch (e: Exception) {
                _missionUploaded.value = false
                lastUploadedCount = 0
                _uploadedWaypoints.value = emptyList()

                _missionUploadProgress.value = MissionUploadProgress(
                    stage = "Error",
                    currentItem = 0,
                    totalItems = missionItems.size,
                    message = "Error: ${e.message}"
                )
                delay(2000)
                _missionUploadProgress.value = null

                LogUtils.e("MissionUpload", "VM: ❌ Upload exception: ${e.message}", e)
                onResult(false, e.message)
            }
        }
    }

    fun startMission(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _telemetryState.value = _telemetryState.value.copy(isMissionActive = false, missionCompleted = false, missionCompletedHandled = false, missionElapsedSec = null)
            // A fresh mission start invalidates any pause point left over from a previous one.
            _missionPauseLocation = null
            _pendingResumeLocation = null
            try {
                LogUtils.i("SharedVM", "Starting mission start sequence...")

                if (repo == null) {
                    LogUtils.w("SharedVM", "No repo available, cannot start mission")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    LogUtils.w("SharedVM", "FCU not detected, cannot start mission")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                if (!_missionUploaded.value || lastUploadedCount == 0) {
                    LogUtils.w("SharedVM", "No mission uploaded or acknowledged, cannot start")
                    onResult(false, "No mission uploaded. Please upload a mission first.")
                    return@launch
                }
                LogUtils.i("SharedVM", "✓ Mission upload acknowledged (${lastUploadedCount} items)")

                if (!_telemetryState.value.armable) {
                    LogUtils.w("SharedVM", "Vehicle not armable, cannot start mission")
                    onResult(false, "Vehicle not armable. Check sensors and GPS.")
                    return@launch
                }

                val sats = _telemetryState.value.sats ?: 0
                if (sats < 6) {
                    LogUtils.w("SharedVM", "Insufficient GPS satellites ($sats), minimum 6 required")
                    onResult(false, "Insufficient GPS satellites ($sats). Need at least 6 for mission.")
                    return@launch
                }

                val currentMode = _telemetryState.value.mode
                val isInArmableMode = currentMode?.equals("Stabilize", ignoreCase = true) == true ||
                        currentMode?.equals("Loiter", ignoreCase = true) == true

                if (!isInArmableMode) {
                    LogUtils.i("SharedVM", "Current mode '$currentMode' not suitable for arming, switching to Stabilize")
                    repo?.changeMode(MavMode.STABILIZE)
                    val modeTimeout = 5000L
                    val modeStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - modeStart < modeTimeout) {
                        if (_telemetryState.value.mode?.equals("Stabilize", ignoreCase = true) == true) {
                            LogUtils.i("SharedVM", "✓ Successfully switched to Stabilize mode")
                            break
                        }
                        delay(500)
                    }
                    if (!(_telemetryState.value.mode?.equals("Stabilize", ignoreCase = true) == true)) {
                        LogUtils.w("SharedVM", "Failed to switch to Stabilize mode within timeout")
                        onResult(false, "Failed to switch to suitable mode for arming. Current mode: ${_telemetryState.value.mode}")
                        return@launch
                    }
                } else {
                    LogUtils.i("SharedVM", "✓ Already in suitable mode for arming: $currentMode")
                }

                if (!_telemetryState.value.armed) {
                    LogUtils.i("SharedVM", "Vehicle not armed - attempting to arm")
                    repo?.arm()
                    val armTimeout = 10000L
                    val armStart = System.currentTimeMillis()
                    while (!_telemetryState.value.armed && System.currentTimeMillis() - armStart < armTimeout) {
                        delay(500)
                    }
                    if (!_telemetryState.value.armed) {
                        LogUtils.w("SharedVM", "Vehicle did not arm within timeout")
                        onResult(false, "Vehicle failed to arm. Check pre-arm conditions.")
                        return@launch
                    }
                    LogUtils.i("SharedVM", "✓ Vehicle armed successfully")
                } else {
                    LogUtils.i("SharedVM", "✓ Vehicle already armed")
                }

                if (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true) {
                    LogUtils.i("SharedVM", "Switching vehicle mode to AUTO")
                    repo?.changeMode(MavMode.AUTO)
                    val autoModeTimeout = 8000L
                    val autoModeStart = System.currentTimeMillis()
                    while (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true &&
                        System.currentTimeMillis() - autoModeStart < autoModeTimeout) {
                        delay(500)
                    }
                    if (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true) {
                        LogUtils.w("SharedVM", "Vehicle did not switch to AUTO mode within timeout")
                        onResult(false, "Failed to switch to AUTO mode. Current mode: ${_telemetryState.value.mode}")
                        return@launch
                    }
                    LogUtils.i("SharedVM", "✓ Vehicle mode is now AUTO")
                } else {
                    LogUtils.i("SharedVM", "✓ Vehicle already in AUTO mode")
                }

                delay(1000)

                // Limit takeoff climb rate to 1 m/s (WPNAV_SPEED_UP is in cm/s)
                LogUtils.i("SharedVM", "Setting WPNAV_SPEED_UP to 100 cm/s (1 m/s) for safe takeoff")
                val speedUpResult = setParameter("WPNAV_SPEED_UP", 100f)
                if (speedUpResult != null) {
                    LogUtils.i("SharedVM", "✓ WPNAV_SPEED_UP set to 100 cm/s (1 m/s)")
                } else {
                    LogUtils.w("SharedVM", "⚠️ Failed to set WPNAV_SPEED_UP, takeoff may use default climb rate")
                }

                LogUtils.i("SharedVM", "Sending start mission command")
                val result = repo?.startMission() ?: false
                if (result) {
                    LogUtils.i("SharedVM", "✓ Mission start acknowledged by FCU")

                    // 🔥 Connect WebSocket when mission starts
                    try {
                        val wsManager = WebSocketManager.getInstance()
                        if (!wsManager.isConnected) {
                            LogUtils.i("SharedVM", "🔌 Opening WebSocket connection for mission...")

                            // 🔥 CRITICAL: Get latest pilotId and adminId from SessionManager
                            // This ensures values are up-to-date if user logged in after MainActivity loaded
                            GCSApplication.getInstance()?.let { app ->
                                val pilotId = com.example.kftgcs.api.SessionManager.getPilotId(app)
                                val adminId = com.example.kftgcs.api.SessionManager.getAdminId(app)
                                val superAdminId = com.example.kftgcs.api.SessionManager.getSuperAdminId(app)
                                wsManager.pilotId = pilotId
                                wsManager.adminId = adminId
                                wsManager.superAdminId = superAdminId
                                LogUtils.i("SharedVM", "📋 Updated WebSocket credentials: pilotId=$pilotId, adminId=$adminId, superAdminId=$superAdminId")

                                // Warn if pilot is not logged in
                                if (pilotId <= 0) {
                                    LogUtils.e("SharedVM", "⚠️ WARNING: pilotId=$pilotId - User may not be logged in! Telemetry will not be saved.")
                                }
                            }

                            // 🔥 Set plot name before connecting
                            wsManager.selectedPlotName = _currentPlotName.value
                            LogUtils.i("SharedVM", "📋 Plot name set for WebSocket: ${_currentPlotName.value}")

                            // 🔥 Set flight mode (Automatic or Manual)
                            wsManager.selectedFlightMode = _userSelectedFlightMode.value.name
                            LogUtils.i("SharedVM", "📋 Flight mode set for WebSocket: ${_userSelectedFlightMode.value.name}")

                            // 🔥 Set mission type (Grid or Waypoint)
                            wsManager.selectedMissionType = _selectedMissionType.value.name
                            LogUtils.i("SharedVM", "📋 Mission type set for WebSocket: ${_selectedMissionType.value.name}")

                            // 🔥 Set grid setup source (KML_IMPORT, MAP_DRAW, DRONE_POSITION, RC_CONTROL)
                            wsManager.gridSetupSource = _gridSetupSource.value.name
                            LogUtils.i("SharedVM", "📋 Grid setup source set for WebSocket: ${_gridSetupSource.value.name}")

                            // Backend session is NOT opened here. Connecting the moment the user
                            // presses Start created phantom missions when the drone armed but never
                            // took off. UnifiedFlightTracker.openBackendSession() opens the session
                            // (and sends MISSION_STARTED) once the drone actually takes off. The
                            // credentials/plot/mode set above are re-applied there at open time.
                        }
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to prepare WebSocket credentials", e)
                    }

                    // Start yaw enforcement if yaw hold is enabled
                    if (_yawHoldEnabled.value && _lockedYaw.value != null) {
                        LogUtils.i("SharedVM", "🧭 Starting yaw enforcement for locked yaw: ${_lockedYaw.value}°")
                        startYawEnforcement()
                    }

                    onResult(true, null)
                } else {
                    LogUtils.e("SharedVM", "Mission start failed or not acknowledged")
                    onResult(false, "Mission start failed. Check vehicle status and try again.")
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to start mission", e)
                onResult(false, e.message)
            }
        }
    }

    fun readMissionFromFcu() {
        viewModelScope.launch {
            if (repo == null) {
                LogUtils.w("SharedVM", "No repo available, cannot request mission readback")
                return@launch
            }
            try {
                repo?.requestMissionAndLog()
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Exception during mission readback", e)
            }
        }
    }

    fun pauseMission(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val currentWp = _telemetryState.value.currentWaypoint
                val lastAutoWp = _telemetryState.value.lastAutoWaypoint
                // The item the drone is flying TOWARDS. Resuming from an already-reached item
                // sends the drone back to the start of the line it was half way along.
                // MISSION_PROGRESS_UNKNOWN means the FC has not reported progress; fall back to
                // the raw telemetry for display rather than storing the sentinel as a waypoint.
                // The real resume sequence is decided in onModeChangedToLoiterFromAuto, which
                // refuses outright in that case.
                val targetSeq = repo?.currentMissionTargetSeq()
                val waypointToStore = targetSeq?.takeIf { it != MISSION_PROGRESS_UNKNOWN }
                    ?: (if (lastAutoWp > 0) lastAutoWp else currentWp)

                // Record where the mission is being interrupted, so the resume flies back to
                // this exact spot even if the pilot dismisses the "Set Resume Point" popup.
                val pauseLat = _telemetryState.value.latitude
                val pauseLon = _telemetryState.value.longitude
                if (pauseLat != null && pauseLon != null) {
                    _missionPauseLocation = LatLng(pauseLat, pauseLon)
                }

                // Save spray state before pause for automatic restore on resume
                // Check both app state and telemetry (RC7 or flow rate indicates active spraying)
                val sprayTelemetry = _telemetryState.value.sprayTelemetry
                _sprayWasActiveBeforePause = _sprayEnabled.value || sprayTelemetry.sprayEnabled || (sprayTelemetry.flowRateLiterPerMin ?: 0f) > 0f
                LogUtils.i("SharedVM", "💧 Spray state before pause: $_sprayWasActiveBeforePause (app=${_sprayEnabled.value}, RC7=${sprayTelemetry.sprayEnabled}, flow=${sprayTelemetry.flowRateLiterPerMin})")

                // DEBUG LOGS
                LogUtils.i("SharedVM", "=== PAUSE MISSION ===")
                LogUtils.i("SharedVM", "lastAutoWaypoint: $lastAutoWp")
                LogUtils.i("SharedVM", "currentWaypoint: $currentWp")
                LogUtils.i("SharedVM", "waypointToStore (will be pausedAtWaypoint): $waypointToStore")
                LogUtils.i("DEBUG_PAUSE", "Pausing - lastAutoWp: $lastAutoWp, currentWp: $currentWp, storing: $waypointToStore")

                // Switch to LOITER to hold position
                // NOTE: The mode change will be detected by TelemetryRepository which will
                // trigger onModeChangedToLoiterFromAuto() to show the "Add Resume Here" popup
                val result = repo?.changeMode(MavMode.LOITER) ?: false

                if (result) {
                    // Don't set missionPaused here - let the mode change detection handle it
                    // The popup will be shown by onModeChangedToLoiterFromAuto()
                    LogUtils.i("SharedVM", "LOITER mode change command sent. Waiting for mode change detection...")

                    // ✅ Send mission status PAUSED to backend (crash-safe)
                    try {
                        WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_PAUSED)
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MISSION_PAUSED",
                            eventStatus = "INFO",
                            description = "Mission paused"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to send PAUSED status", e)
                    }

                    // Announce via TTS
                    ttsManager?.announceMissionPaused(waypointToStore ?: 0)
                    onResult(true, null)
                } else {
                    onResult(false, "Failed to pause mission")
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to pause mission", e)
                onResult(false, e.message)
            }
        }
    }

    /**
     * Complete Resume Mission Implementation with progress tracking
     * This is called from UI with user-specified waypoint and progress callbacks
     *
     * @param resumeWaypointNumber The waypoint number to resume from (user can modify)
     * @param resetHomeCoords Whether to reset home coordinates (for copters) - currently unused
     * @param onProgress Callback for progress updates
     * @param onResult Callback for final result
     */
    fun resumeMissionComplete(
        resumeWaypointNumber: Int,
        resetHomeCoords: Boolean = false,
        onProgress: (String) -> Unit = {},
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val resumeLocation = effectiveResumeLocation()
                LogUtils.i("ResumeMission", "Starting Resume Mission at waypoint $resumeWaypointNumber, location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")

                val result = prepareResumeMission(resumeWaypointNumber, resumeLocation, onProgress)
                if (!result.success) {
                    onResult(false, result.failureReason)
                    return@launch
                }

                if (!engageAutoForResume(onProgress)) {
                    onResult(false, "Failed to switch to AUTO. Stuck in: ${_telemetryState.value.mode}")
                    return@launch
                }

                onProgress("Mission resumed!")
                markMissionResumed(resumeWaypointNumber)

                LogUtils.i("ResumeMission", "Resume Mission Complete (${result.itemCount} items)")
                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ResumeMission", "Resume mission failed", e)
                addNotification(Notification("Resume mission failed: ${e.message}", NotificationType.ERROR))
                onResult(false, e.message)
            }
        }
    }

    /**
     * Simple resume mission (for backward compatibility)
     * Resumes from the paused waypoint or current waypoint
     */
    fun resumeMission(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val pausedWaypoint = _telemetryState.value.pausedAtWaypoint

                if (pausedWaypoint == null) {
                    // No paused waypoint, just switch to AUTO
                    LogUtils.i("SharedVM", "Resuming mission from current position")
                    val result = repo?.changeMode(MavMode.AUTO) ?: false

                    if (result) {
                        _telemetryState.update { it.copy(missionPaused = false) }

                        // ✅ Restore spray if it was active before pause.
                        // No transit leg in this branch: the FC keeps its existing mission index,
                        // so the drone carries on along the same line it was interrupted on and
                        // spray should come straight back.
                        if (_sprayWasActiveBeforePause) {
                            LogUtils.i("SharedVM", "💧 Restoring spray — mission continues from the current index, no transit leg")
                            delay(500) // Small delay to ensure mode change is complete
                            setSprayEnabled(true)
                            _sprayWasActiveBeforePause = false
                        }

                        // ✅ Send mission status RESUMED to backend (crash-safe)
                        try {
                            WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                            WebSocketManager.getInstance().sendMissionEvent(
                                eventType = "MISSION_RESUMED",
                                eventStatus = "INFO",
                                description = "Mission resumed from current position"
                            )
                        } catch (e: Exception) {
                            LogUtils.e("SharedVM", "Failed to send RESUMED status", e)
                        }

                        addNotification(Notification("Mission resumed", NotificationType.INFO))
                        ttsManager?.announceMissionResumed()
                        onResult(true, null)
                    } else {
                        onResult(false, "Failed to resume mission")
                    }
                    return@launch
                }

                // Resume from specific waypoint
                LogUtils.i("SharedVM", "Resuming mission from waypoint: $pausedWaypoint")

                // Set current waypoint in FCU
                val setWaypointSuccess = repo?.setCurrentWaypoint(pausedWaypoint) ?: false

                if (!setWaypointSuccess) {
                    LogUtils.w("SharedVM", "Failed to set waypoint, continuing anyway")
                }

                delay(500)

                // Pump off for the leg back to $pausedWaypoint; restoreSprayOnArrival below
                // turns it on again once the drone actually gets there.
                ensureSprayerOffForTransit()

                // Switch to AUTO mode
                val result = repo?.changeMode(MavMode.AUTO) ?: false

                if (result) {
                    _telemetryState.update {
                        it.copy(
                            missionPaused = false,
                            pausedAtWaypoint = null
                        )
                    }

                    // ✅ Restore spray only once the drone has actually REACHED the waypoint.
                    // This path jumps the FC's mission index without re-uploading a mission, so
                    // there is no DO_SPRAYER item to gate on — but the drone still has to fly
                    // from wherever it is to $pausedWaypoint, and that leg must stay dry.
                    if (_sprayWasActiveBeforePause) {
                        LogUtils.i("SharedVM", "💧 Spray will restore on arrival at waypoint $pausedWaypoint (off during transit)")
                        restoreSprayOnArrival(pausedWaypoint)
                        _sprayWasActiveBeforePause = false
                    }

                    // ✅ Send mission status RESUMED to backend (crash-safe)
                    try {
                        WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MISSION_RESUMED",
                            eventStatus = "INFO",
                            description = "Mission resumed from waypoint $pausedWaypoint"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to send RESUMED status", e)
                    }

                    addNotification(
                        Notification(
                            message = "Mission resumed from waypoint $pausedWaypoint",
                            type = NotificationType.SUCCESS
                        )
                    )
                    ttsManager?.announceMissionResumed()
                    onResult(true, null)
                } else {
                    onResult(false, "Failed to resume mission")
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to resume mission", e)
                onResult(false, e.message)
            }
        }
    }

    /**
     * Update current waypoint from telemetry repository
     */
    fun updateCurrentWaypoint(waypoint: Int) {
        _telemetryState.update { it.copy(currentWaypoint = waypoint) }
    }

    /**
     * Update level sensor calibration values (empty and full voltage thresholds)
     * This updates the app-side calculation for tank level percentage
     */
    fun updateLevelSensorCalibration(emptyVoltageMv: Int, fullVoltageMv: Int) {
        LogUtils.i("LevelSensorCal", "Updating calibration: empty=$emptyVoltageMv mV, full=$fullVoltageMv mV")
        _telemetryState.update { currentState ->
            currentState.copy(
                sprayTelemetry = currentState.sprayTelemetry.copy(
                    levelSensorEmptyMv = emptyVoltageMv,
                    levelSensorFullMv = fullVoltageMv
                )
            )
        }
        LogUtils.i("LevelSensorCal", "Calibration updated successfully")
    }

    // Spray control configuration
    // ArduPilot Sprayer library integration:
    // - SERVO9_FUNCTION = 22 (SprayerPump) - ArduPilot's Sprayer library controls the pump
    // - Uses MAV_CMD_DO_SPRAYER (216) to enable/disable spraying
    // - Uses SPRAY_PUMP_RATE parameter to control the application rate
    //
    // The slider writes SPRAY_PUMP_RATE 1:1 — slider 80% → SPRAY_PUMP_RATE 80. The value in the
    // app and the value on the FC are always the same number, so a param refresh (or a manual
    // param write) agrees with the slider instead of contradicting it.
    //
    // Know what that number means on the FC: ArduPilot defines SPRAY_PUMP_RATE as the pump output
    // percentage PER 1 m/s of ground speed — AC_Sprayer computes pump output% = ground_speed_ms ×
    // SPRAY_PUMP_RATE, floored at SPRAY_PUMP_MIN and capped at 100%. So the slider is NOT the pump
    // output percentage in flight: at 4 m/s any value above ~25 already commands full pump, which
    // makes the top of the slider range flat. Spray density is tuned with the slider and the
    // mission's ground speed together.
    //
    // We deliberately do NOT touch SPRAY_PUMP_MIN: it is the pilot's pump floor, set in Spraying
    // Configuration, and nothing here needs it moved.
    private val MAV_CMD_DO_SPRAYER = 216u

    // Valid clamp for the SPRAY_PUMP_RATE parameter we push to the FC (its documented range).
    private val SPRAY_PUMP_RATE_MIN = 0f
    private val SPRAY_PUMP_RATE_MAX = 100f

    // ── Firmware param IDs for the manual-pump feature ────────────────────────
    //
    // These live in exactly one place because they are UNVERIFIED against the firmware from this
    // repo: the ArduPilot/AC_Sprayer source is not checked in here and there is no firmware
    // submodule, so the names below could not be confirmed against the var_info[] table that
    // actually defines them. If the firmware spells either differently, change it HERE and
    // nowhere else.
    //
    // Both fit MAVLink's 16-character param-ID limit (15 and 14 chars), so they are transmissible
    // as written. Note ArduPilot declares group members with a short suffix under a group prefix
    // (e.g. "PUMP_PCT" inside the SPRAY_ group) — the full name is what goes on the wire, and the
    // full name is what these constants must hold.
    //
    // A wrong name here fails SILENTLY in the worst way: setParameter() returns null, the resend
    // also returns null, and the pilot sees a slider that does nothing. seedSprayConfigFromVehicle
    // probes both on connect and sets _sprayManualParamsSupported so that failure is visible.
    private val PARAM_SPRAY_PUMP_MODE = "SPRAY_PUMP_MODE"
    private val PARAM_SPRAY_PUMP_PCT = "SPRAY_PUMP_PCT"

    // Manual duty cycle range. Unlike the rate slider's 10-100, manual allows a true 0 = pump off.
    private val SPRAY_PUMP_PCT_MIN = 0f
    private val SPRAY_PUMP_PCT_MAX = 100f

    // Serializes rate writes so two overlapping applies can't reach the FC out of order.
    private val sprayRateWriteMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * The pump output percentage the FC will actually command right now, mirroring AC_Sprayer's
     * own arithmetic: `output% = groundspeed_m/s × SPRAY_PUMP_RATE`, floored at SPRAY_PUMP_MIN and
     * capped at 100.
     *
     * This exists because the slider alone is misleading in flight. SPRAY_PUMP_RATE is pump % per
     * 1 m/s, not an absolute percentage, so at 4 m/s every slider position from 25 up already
     * commands a saturated pump — the pilot moves the slider across most of its travel and nothing
     * changes, which reads as "the app isn't setting the rate". Surfacing the computed number next
     * to the slider makes that visible instead of invisible.
     *
     * Emits null when groundspeed is unknown (no VFR_HUD yet), so the UI can say so rather than
     * show a confident 0.
     */
    val sprayEffectiveOutputPct: StateFlow<Float?> =
        combine(
            _telemetryState, _sprayRate, _sprayPumpMin, _sprayManualMode, _sprayManualPct
        ) { telemetry, rate, pumpMin, manual, manualPct ->
            if (manual) {
                // MANUAL: SPRAY_PUMP_PCT is the duty cycle directly — no groundspeed term, so
                // this is known even sitting on the ground with no VFR_HUD. The pump floor still
                // applies (AC_Sprayer clamps the same way in both modes), except at a commanded
                // 0, which means "off" rather than "as slow as the floor allows".
                if (manualPct <= 0f) 0f
                else manualPct.coerceAtLeast(pumpMin ?: 0f).coerceIn(0f, 100f)
            } else {
                // AUTO: output scales with groundspeed, so it is unknowable until VFR_HUD arrives.
                val speedMs = telemetry.groundspeed ?: return@combine null
                (speedMs * rate).coerceAtLeast(pumpMin ?: 0f).coerceIn(0f, 100f)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Push the current slider rate to the FC as SPRAY_PUMP_RATE (1:1 — see the block comment
     * above). Safe to call whether or not the sprayer is currently enabled: SPRAY_PUMP_RATE takes
     * effect immediately for an ongoing mission's DO_SPRAYER, which is what makes mid-mission
     * slider changes work.
     */
    private suspend fun applySprayRateToFc() {
        if (repo == null) {
            LogUtils.e("SprayControl", "✗ Cannot set spray rate - not connected to drone")
            return
        }

        sprayRateWriteMutex.lock()
        try {
            // Read the slider AND the mode HERE, not at call time: a write that queued behind the
            // mutex must push the value the pilot ended up on, in the mode they ended up in — not
            // whatever was current when it was queued.
            val manual = _sprayManualMode.value
            val paramName = if (manual) PARAM_SPRAY_PUMP_PCT else "SPRAY_PUMP_RATE"
            val value = if (manual) {
                _sprayManualPct.value.coerceIn(SPRAY_PUMP_PCT_MIN, SPRAY_PUMP_PCT_MAX)
            } else {
                _sprayRate.value.coerceIn(SPRAY_PUMP_RATE_MIN, SPRAY_PUMP_RATE_MAX)
            }

            // PARAM_SET is fire-and-forget and the link can drop it, so an unconfirmed write
            // gets one resend rather than silently leaving the FC on the old value.
            var ack = setParameter(paramName, value)
            if (ack == null) {
                LogUtils.w("SprayControl", "⚠ $paramName=$value unconfirmed — resending")
                ack = setParameter(paramName, value)
            }

            val units = if (manual) "% duty" else "% pump per 1 m/s"
            LogUtils.i("SprayControl",
                "🚿 ${if (manual) "MANUAL" else "AUTO"} ${value.toInt()} ($units) → $paramName=$value " +
                (if (ack != null) "(confirmed)" else "(no confirmation)"))

            // In manual mode an unconfirmed write is worth surfacing: the most likely cause is
            // firmware that does not define SPRAY_PUMP_PCT at all, which otherwise looks exactly
            // like a slider that quietly does nothing.
            if (manual && ack == null) {
                _sprayManualParamsSupported.value = false
                LogUtils.e("SprayControl",
                    "✗ $paramName not acknowledged twice — does this firmware define it? " +
                    "Manual pump mode will not work until the param name matches the firmware.")
            }
        } catch (e: Exception) {
            LogUtils.e("SprayControl", "✗ Failed to set spray rate: ${e.message}", e)
        } finally {
            sprayRateWriteMutex.unlock()
        }
    }

    /**
     * Control spray system using ArduPilot's Sprayer library.
     *
     * SERVO9_FUNCTION = 22 (SprayerPump) means the Sprayer library owns the pump servo output,
     * so the rate is set via the SPRAY_PUMP_RATE parameter (mapped in [applySprayRateToFc]) and
     * on/off via MAV_CMD_DO_SPRAYER (216).
     *
     * @param enable true to enable spray at current rate, false to disable
     */
    fun controlSpray(enable: Boolean) {
        viewModelScope.launch {
            repo?.let { repository ->
                try {
                    // Step 1: Send DO_SPRAYER to enable/disable — the pilot's on/off must never
                    // queue behind a parameter write (which waits on acks and can retry).
                    // MAV_CMD_DO_SPRAYER (216): param1 = 1 (enable) or 0 (disable)
                    repository.sendCommandRaw(
                        commandId = MAV_CMD_DO_SPRAYER,
                        param1 = if (enable) 1f else 0f
                    )
                    LogUtils.i("SprayControl", "✓ DO_SPRAYER command sent: ${if (enable) "ENABLE" else "DISABLE"}")

                    // Step 2: Push the mapped SPRAY_PUMP_RATE (and ensure SPRAY_PUMP_MIN=0).
                    // Safe to do after the enable: AC_Sprayer recomputes pump output from
                    // SPRAY_PUMP_RATE on every update, so a rate landing a moment later still
                    // applies to the spray that just started.
                    applySprayRateToFc()
                } catch (e: Exception) {
                    LogUtils.e("SprayControl", "✗ Failed to send spray command: ${e.message}", e)
                }
            } ?: run {
                LogUtils.e("SprayControl", "✗ Cannot control spray - not connected to drone")
            }
        }
    }

    fun setSprayEnabled(enabled: Boolean) {
        _sprayEnabled.value = enabled
        controlSpray(enabled)
        LogUtils.i("SprayControl", "Spray ${if (enabled) "ENABLED" else "DISABLED"} at rate: ${_sprayRate.value.toInt()}%")

        // Show notification
        addNotification(
            Notification(
                message = if (enabled) "Spray enabled at ${_sprayRate.value.toInt()}%" else "Spray disabled",
                type = if (enabled) NotificationType.SUCCESS else NotificationType.INFO
            )
        )
    }

    /**
     * Force the pump off before a resumed mission starts flying back to its resume point.
     *
     * Pausing already sends DO_SPRAYER(0) via [disableSprayOnModeChange], but the pilot can
     * turn spray back on by hand while parked. Without this the drone would spray the whole
     * transit leg. Unlike [disableSprayOnModeChange] this is silent — no "spray disabled"
     * notification, because from the pilot's point of view nothing was disabled.
     */
    private suspend fun ensureSprayerOffForTransit() {
        _sprayEnabled.value = false
        repo?.let { repository ->
            try {
                repository.sendCommandRaw(commandId = MAV_CMD_DO_SPRAYER, param1 = 0f)
                LogUtils.i("SprayControl", "🚿 DO_SPRAYER(0) sent — pump off for the resume transit leg")
            } catch (e: Exception) {
                LogUtils.e("SprayControl", "✗ Failed to send DO_SPRAYER(0) before resume", e)
            }
        }
    }

    /** Pending "turn spray back on once the drone arrives" watcher. */
    private var sprayRestoreJob: Job? = null

    /** Give up waiting for arrival after this long, rather than arming the pump indefinitely. */
    private val SPRAY_RESTORE_TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * Turn the sprayer back on only once the drone has actually reached [targetWaypoint].
     *
     * On resume the drone first has to fly from wherever the pilot left it back to the
     * mission, and that transit leg must stay dry — it has either already been sprayed or
     * was never part of the mission. Resume paths that re-upload a mission get this for free
     * from the DO_SPRAYER items in [MavlinkTelemetryRepository.filterWaypointsForResume];
     * this helper covers the path that only jumps the FC's mission index.
     *
     * Arrival is detected as currentWaypoint STRICTLY GREATER than [targetWaypoint], not
     * equal to it: MISSION_CURRENT reports the waypoint the FC is flying TO, and
     * setCurrentWaypoint has just pointed it at [targetWaypoint] — so `==` would be true
     * immediately, while the drone is still at the far end of the transit leg. The index
     * only advances past [targetWaypoint] once it has actually been reached.
     *
     * Aborts silently if the drone leaves AUTO or disarms before arriving — the pilot has
     * taken over, and a delayed pump start would be a nasty surprise.
     */
    private fun restoreSprayOnArrival(targetWaypoint: Int) {
        sprayRestoreJob?.cancel()
        sprayRestoreJob = viewModelScope.launch {
            val outcome = withTimeoutOrNull(SPRAY_RESTORE_TIMEOUT_MS) {
                _telemetryState.first { st ->
                    !st.armed ||
                        !st.mode.equals("Auto", ignoreCase = true) ||
                        (st.currentWaypoint ?: -1) > targetWaypoint
                }
            }

            when {
                outcome == null ->
                    LogUtils.w("SprayControl", "💧 Spray restore timed out — never reached WP$targetWaypoint, leaving pump off")

                outcome.armed && outcome.mode.equals("Auto", ignoreCase = true) -> {
                    LogUtils.i("SprayControl", "💧 Reached WP$targetWaypoint (now heading to ${outcome.currentWaypoint}) — restoring spray")
                    setSprayEnabled(true)
                }

                else ->
                    LogUtils.i("SprayControl", "💧 Spray restore abandoned — left AUTO before reaching WP$targetWaypoint (mode=${outcome.mode}, armed=${outcome.armed})")
            }
        }
    }

    /**
     * Disable spray when mode changes from Auto to another mode
     * This ensures spray is turned off when pilot takes manual control or mission is paused/aborted.
     * Always sends DO_SPRAYER(0) to FC regardless of app state, because mission-embedded
     * DO_SPRAYER commands may have turned sprayer on without updating app state.
     */
    fun disableSprayOnModeChange() {
        LogUtils.i("SprayControl", "🚿 Disabling spray due to mode change from Auto")

        // Drop any pending arrival-gated spray restore — leaving AUTO cancels the resume.
        sprayRestoreJob?.cancel()
        sprayRestoreJob = null

        // Reset AUTO mode spray detection to prevent false "Tank Empty" alerts
        repo?.resetAutoModeSprayDetection()

        // Always send DO_SPRAYER(0) to FC to ensure sprayer is OFF
        // This is critical because mission-embedded DO_SPRAYER commands work independently of app state
        viewModelScope.launch {
            repo?.let { repository ->
                try {
                    repository.sendCommandRaw(
                        commandId = MAV_CMD_DO_SPRAYER,
                        param1 = 0f  // 0 = Disable sprayer
                    )
                    LogUtils.i("SprayControl", "✓ DO_SPRAYER(0) sent to FC - sprayer disabled")
                } catch (e: Exception) {
                    LogUtils.e("SprayControl", "✗ Failed to send DO_SPRAYER disable: ${e.message}", e)
                }
            }
        }

        // Also update app state if it was enabled
        if (_sprayEnabled.value) {
            _sprayEnabled.value = false
            addNotification(Notification("Spray disabled - Mode changed from Auto", NotificationType.INFO))
            showSprayStatusPopup("Spray Disabled (Mode Change)")
        }
    }

    // Debounce job for spray rate changes
    private var sprayRateDebounceJob: kotlinx.coroutines.Job? = null
    private val SPRAY_RATE_DEBOUNCE_MS = 150L // 150ms debounce for smoother slider interaction

    fun setSprayRate(rate: Float) {
        val newRate = rate.coerceIn(10f, 100f)
        _sprayRate.value = newRate
        scheduleSprayValueWrite("Rate set to ${newRate.toInt()}%")
    }

    /**
     * Switch the FC between AUTO (speed-scaled SPRAY_PUMP_RATE) and MANUAL (direct
     * SPRAY_PUMP_PCT duty cycle).
     *
     * Writes [PARAM_SPRAY_PUMP_MODE] first, then immediately pushes the newly-active mode's
     * value. Without that second push the FC would sit in the new mode holding whatever the
     * other mode last wrote — e.g. switching to Manual would leave SPRAY_PUMP_PCT at a stale
     * value while the UI showed the pilot's current one.
     *
     * The mode write is NOT debounced: it is a deliberate discrete action, not a drag.
     */
    fun setSprayManualMode(manual: Boolean) {
        if (_sprayManualMode.value == manual) return
        _sprayManualMode.value = manual

        if (repo == null) {
            LogUtils.d("SprayControl", "Pump mode set to ${if (manual) "MANUAL" else "AUTO"} (not connected; will apply when connected)")
            return
        }

        viewModelScope.launch {
            val modeValue = if (manual) 1f else 0f
            var ack = setParameter(PARAM_SPRAY_PUMP_MODE, modeValue)
            if (ack == null) {
                LogUtils.w("SprayControl", "⚠ $PARAM_SPRAY_PUMP_MODE=$modeValue unconfirmed — resending")
                ack = setParameter(PARAM_SPRAY_PUMP_MODE, modeValue)
            }

            if (ack == null) {
                // Most likely this firmware has no manual-pump support at all. Say so loudly and
                // mark it unsupported so the UI can stop offering a mode the vehicle ignores.
                _sprayManualParamsSupported.value = false
                LogUtils.e("SprayControl",
                    "✗ $PARAM_SPRAY_PUMP_MODE not acknowledged — firmware may not define it. " +
                    "Pump mode on the FC is unchanged.")
            } else {
                _sprayManualParamsSupported.value = true
                LogUtils.i("SprayControl",
                    "🚿 Pump mode → ${if (manual) "MANUAL (SPRAY_PUMP_PCT duty)" else "AUTO (SPRAY_PUMP_RATE × speed)"} (confirmed)")
            }

            // Push the active mode's value regardless of the mode ack: if the mode write did land
            // but its ack was dropped, the FC is in the new mode and still needs the right value.
            applySprayRateToFc()
        }
    }

    /**
     * Manual-mode duty cycle, 0-100. Shares [sprayRateDebounceJob] with [setSprayRate] on purpose:
     * only one of the two sliders is on screen at a time, so one debounce channel is correct and
     * a mode switch mid-drag cannot leave two competing writes in flight.
     */
    fun setSprayManualPct(pct: Float) {
        val newPct = pct.coerceIn(SPRAY_PUMP_PCT_MIN, SPRAY_PUMP_PCT_MAX)
        _sprayManualPct.value = newPct
        scheduleSprayValueWrite("Manual pump set to ${newPct.toInt()}%")
    }

    /**
     * Debounced push of whichever value the current mode owns.
     *
     * [applySprayRateToFc] re-reads both the mode and the value under its mutex, so this only has
     * to decide WHEN to write, never WHAT.
     */
    private fun scheduleSprayValueWrite(disconnectedLog: String) {
        // Debounce the actual command send to avoid flooding FC when slider moves rapidly.
        // Always push to the FC (regardless of RC7 / flight mode): the parameter takes effect
        // immediately for the ongoing mission's DO_SPRAYER, so this is what makes changing the
        // slider mid-mission actually change the spray. We do NOT toggle DO_SPRAYER
        // here — enabling/disabling the pump stays with controlSpray()/RC7/the mission.
        // Only the debounce DELAY is cancellable. Once a write has started it finishes in its
        // own job: cancelling a param write that is already in flight is what let a rate change
        // vanish, leaving the FC on the last value that got through while the slider showed the
        // new one. applySprayRateToFc() re-reads the slider under a mutex, so the write that
        // wins is always the pilot's latest value.
        sprayRateDebounceJob?.cancel()
        sprayRateDebounceJob = viewModelScope.launch {
            delay(SPRAY_RATE_DEBOUNCE_MS)
            if (repo != null) {
                viewModelScope.launch { applySprayRateToFc() }
            } else {
                LogUtils.d("SprayControl", "$disconnectedLog (not connected; will apply when connected)")
            }
        }
    }

    // ========== YAW HOLD FUNCTIONS (Hold Nose Position feature) ==========

    /**
     * Enable yaw hold and capture current yaw as the locked target.
     * Call this when starting AUTO mission with "Hold Nose Position" enabled.
     */
    fun enableYawHold() {
        val currentYaw = _telemetryState.value.heading
        if (currentYaw != null) {
            // Normalize yaw to 0-360
            val normalizedYaw = if (currentYaw < 0) currentYaw + 360 else currentYaw
            _lockedYaw.value = normalizedYaw
            _yawHoldEnabled.value = true
            LogUtils.i("YawHold", "🧭 Yaw hold ENABLED - locked to ${normalizedYaw}°")
            addNotification(Notification("Yaw locked at ${normalizedYaw.toInt()}°", NotificationType.INFO))
        } else {
            LogUtils.w("YawHold", "⚠️ Cannot enable yaw hold - no heading data available")
        }
    }

    /**
     * Disable yaw hold and stop continuous enforcement.
     * Call this when exiting AUTO mode or completing mission.
     */
    fun disableYawHold() {
        if (_yawHoldEnabled.value) {
            _yawHoldEnabled.value = false
            _lockedYaw.value = null
            yawEnforcementJob?.cancel()
            yawEnforcementJob = null
            LogUtils.i("YawHold", "🧭 Yaw hold DISABLED")
        }
    }

    /**
     * Start continuous yaw enforcement loop.
     * This should be called when entering AUTO mode with yaw hold enabled.
     */
    fun startYawEnforcement() {
        if (!_yawHoldEnabled.value || _lockedYaw.value == null) {
            LogUtils.w("YawHold", "Cannot start yaw enforcement - yaw hold not enabled or no locked yaw")
            return
        }

        // Cancel any existing enforcement job
        yawEnforcementJob?.cancel()

        yawEnforcementJob = viewModelScope.launch {
            LogUtils.i("YawHold", "🔄 Starting continuous yaw enforcement at ${_lockedYaw.value}°")

            while (currentCoroutineContext().isActive && _yawHoldEnabled.value) {
                val currentMode = _telemetryState.value.mode
                val targetYaw = _lockedYaw.value ?: break

                // Only enforce yaw in AUTO mode
                if (currentMode?.equals("Auto", ignoreCase = true) == true) {
                    val currentYaw = _telemetryState.value.heading ?: continue

                    // Calculate yaw error (handle wrap-around)
                    var yawError = targetYaw - currentYaw
                    if (yawError > 180) yawError -= 360
                    if (yawError < -180) yawError += 360

                    // Only send correction if error exceeds tolerance
                    if (kotlin.math.abs(yawError) > YAW_TOLERANCE) {
                        LogUtils.d("YawHold", "📐 Yaw error: ${yawError}° - sending correction to ${targetYaw}°")
                        sendYawCommand(targetYaw)
                    }
                } else {
                    // Not in AUTO mode - stop enforcement
                    LogUtils.i("YawHold", "Mode is $currentMode (not AUTO) - stopping yaw enforcement")
                    break
                }

                delay(YAW_ENFORCEMENT_INTERVAL)
            }

            LogUtils.i("YawHold", "🛑 Yaw enforcement loop ended")
        }
    }

    /**
     * Send a yaw command to the drone using MAV_CMD_CONDITION_YAW.
     * This tells the drone to rotate to the specified absolute yaw angle.
     */
    private suspend fun sendYawCommand(targetYaw: Float) {
        try {
            repo?.sendCommand(
                MavCmd.CONDITION_YAW,
                param1 = targetYaw,  // Target yaw angle (degrees, 0-360)
                param2 = 30f,        // Yaw speed deg/s
                param3 = 0f,         // Direction: 0 = shortest path
                param4 = 0f          // 0 = absolute angle
            )
        } catch (e: Exception) {
            LogUtils.e("YawHold", "Failed to send yaw command: ${e.message}")
        }
    }

    /**
     * Set WP_YAW_BEHAVIOR parameter on the autopilot.
     * 0 = Never change yaw (what we want for hold nose position)
     * 1 = Face next waypoint (default)
     * 2 = Face direction of travel
     */
    suspend fun setWpYawBehavior(value: Int) {
        try {
            setParameter("WP_YAW_BEHAVIOR", value.toFloat())
            LogUtils.i("YawHold", "✓ WP_YAW_BEHAVIOR set to $value")
        } catch (e: Exception) {
            LogUtils.e("YawHold", "Failed to set WP_YAW_BEHAVIOR: ${e.message}")
        }
    }

    // =================================================================
    /**
     * Update flow sensor calibration factor (BATT2_AMP_PERVLT parameter)
     * This will be sent to the autopilot to update the flow sensor calibration
     */
    fun updateFlowSensorCalibration(calibrationFactor: Float) {
        LogUtils.i("FlowSensorCal", "Updating flow calibration factor: $calibrationFactor")

        // Update local state
        _telemetryState.update { currentState ->
            currentState.copy(
                sprayTelemetry = currentState.sprayTelemetry.copy(
                    batt2AmpPerVolt = calibrationFactor
                )
            )
        }

        // Send parameter to autopilot (BATT2_AMP_PERVLT)
        viewModelScope.launch {
            try {
                // Set BATT2_AMP_PERVLT parameter
                setParameter("BATT2_AMP_PERVLT", calibrationFactor)
                LogUtils.i("FlowSensorCal", "✓ Calibration factor sent to autopilot")
            } catch (e: Exception) {
                LogUtils.e("FlowSensorCal", "Error sending calibration factor to autopilot", e)
            }
        }

        LogUtils.i("FlowSensorCal", "Flow sensor calibration updated successfully")
    }

    /**
     * Setup callback for emergency RTL triggered by app crash
     */
    private fun setupEmergencyRTLCallback() {
        GCSApplication.onTriggerEmergencyRTL = {
            try {
                // Use runBlocking to ensure RTL command is sent before app dies
                kotlinx.coroutines.runBlocking {
                    triggerEmergencyRTL()
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Error in emergency RTL callback", e)
            }
        }

        // Surface a failsafe popup + notification when DisconnectionRTLHandler auto-triggers
        // RTL after a mid-flight link loss, matching Battery/Fence/Max Range/Tank Empty.
        DisconnectionRTLHandler.onRtlTriggered = {
            LogUtils.w("SharedVM", "⛔ LINK LOSS: connection lost mid-flight — RTL triggered automatically")
            addNotification(
                Notification(
                    message = "⛔ Communication Link Loss, Drone Disconnected — RTL activated automatically",
                    type = NotificationType.ERROR
                )
            )
            showFailsafePopup("Communication Link Loss, Drone Disconnected")
            ttsManager?.speak("Communication Link Loss, Drone Disconnected")
        }
    }

    /**
     * Trigger emergency RTL - called when app crashes during flight
     * This sends the RTL command synchronously to ensure it's sent before app termination
     */
    suspend fun triggerEmergencyRTL() {
        LogUtils.w("SharedVM", "🚨 TRIGGERING EMERGENCY RTL 🚨")
        try {
            repo?.let { repository ->
                // Send RTL mode change command (mode 6 = RTL for ArduPilot)
                repository.changeMode(6u)
                LogUtils.i("SharedVM", "✓ Emergency RTL command sent to drone")
            } ?: run {
                LogUtils.e("SharedVM", "❌ Cannot send RTL - repository is null")
            }
        } catch (e: Exception) {
            LogUtils.e("SharedVM", "❌ Failed to send emergency RTL command", e)
        }
    }

    // Expose FCU system and component IDs for mission building
    fun getFcuSystemId(): UByte = repo?.fcuSystemId ?: 0u
    fun getFcuComponentId(): UByte = repo?.fcuComponentId ?: 0u



    suspend fun cancelConnection() {
        // 🔥 FIX: Cancel ALL collection coroutines FIRST, before closing connection.
        // This prevents orphaned coroutines from updating _telemetryState with stale data
        // after repo is set to null, which caused the app to think it was connected
        // (had lat/lon in telemetryState) when repo was actually null.
        connectionJobs.forEach { it.cancel() }
        connectionJobs.clear()

        repo?.let {
            try {
                it.closeConnection()
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Error closing connection", e)
            }
        }
        DisconnectionRTLHandler.stopMonitoring()
        repo = null

        // 🔥 FIX: Clear geofence state on disconnect to prevent stale fence data
        // from persisting into the next connection (possibly a different FC).
        LogUtils.i("Geofence", "🧹 Disconnect: clearing geofence state")
        stopFenceStatusMonitoring()
        _geofenceEnabled.value = false
        _geofencePolygon.value = emptyList()
        _fenceConfiguration.value = null
        _homePosition.value = null
        resetGeofenceState()

        // Preserve mission pause state when disconnecting (e.g., for battery change)
        // Only reset connection-related fields, NOT mission pause state
        val currentState = _telemetryState.value
        val wasPaused = currentState.missionPaused
        val pausedAtWp = currentState.pausedAtWaypoint

        if (wasPaused) {
            LogUtils.i("SharedVM", "Preserving mission pause state during disconnect (pausedAtWaypoint=$pausedAtWp)")
            // Reset to default but keep mission pause state
            _telemetryState.value = TelemetryState(
                missionPaused = true,
                pausedAtWaypoint = pausedAtWp
            )
        } else {
            _telemetryState.value = TelemetryState()
        }
    }

    // --- Split Plan Management ---
    private val _splitPlanActive = MutableStateFlow(false)
    val splitPlanActive: StateFlow<Boolean> = _splitPlanActive.asStateFlow()

    private val _isSplitPlanActive = MutableStateFlow(false)
    val isSplitPlanActive: StateFlow<Boolean> = _isSplitPlanActive.asStateFlow()

    private val _resumeWaypointIndex = MutableStateFlow<Int?>(null)
    val resumeWaypointIndex: StateFlow<Int?> = _resumeWaypointIndex.asStateFlow()

    private val _splitPlanWaypointLat = MutableStateFlow<Double?>(null)
    val splitPlanWaypointLat: StateFlow<Double?> = _splitPlanWaypointLat.asStateFlow()

    private val _splitPlanWaypointLon = MutableStateFlow<Double?>(null)
    val splitPlanWaypointLon: StateFlow<Double?> = _splitPlanWaypointLon.asStateFlow()

    /**
     * Toggle split plan mode - show confirmation dialog
     */
    fun toggleSplitPlan() {
        if (_splitPlanActive.value) {
            // If already in split plan mode, resume from split point
            resumeFromSplitPlan { success, error ->
                if (success) {
                    addNotification(
                        Notification(
                            message = "Resuming mission from split point",
                            type = NotificationType.SUCCESS
                        )
                    )
                } else {
                    addNotification(
                        Notification(
                            message = "Failed to resume: ${error ?: "Unknown error"}",
                            type = NotificationType.ERROR
                        )
                    )
                }
            }
        } else {
            // Not in split plan mode - initiate split
            LogUtils.i("SharedVM", "Split plan toggle initiated")
            // The dialog will be shown in the UI (MainPage), we just need to trigger it
            // by setting a mutable state - but that's handled in the composable
        }
    }

    /**
     * Confirm split plan action - called when user clicks Yes in dialog
     */
    fun confirmSplitPlan() {
        splitPlan { success, error ->
            if (success) {
                LogUtils.i("SharedVM", "✓ Split plan confirmed and initiated")
            } else {
                LogUtils.e("SharedVM", "✗ Split plan failed: $error")
            }
        }
    }

    /**
     * Initiate split plan: send RTL command to drone and wait for it to land
     * Once landed, it will be disarmed and the user can resume from the split waypoint
     */
    fun splitPlan(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                LogUtils.i("SharedVM", "Initiating split plan...")

                if (repo == null) {
                    LogUtils.w("SharedVM", "No repo available, cannot split plan")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    LogUtils.w("SharedVM", "FCU not detected, cannot split plan")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                // Store current position as resume waypoint
                val currentLat = _telemetryState.value.latitude
                val currentLon = _telemetryState.value.longitude

                if (currentLat == null || currentLon == null) {
                    LogUtils.w("SharedVM", "Current position not available, cannot split plan")
                    onResult(false, "Current position not available")
                    return@launch
                }

                _splitPlanWaypointLat.value = currentLat
                _splitPlanWaypointLon.value = currentLon

                LogUtils.i("SharedVM", "✓ Stored split waypoint at Lat: $currentLat, Lon: $currentLon")

                // Switch to RTL mode
                LogUtils.i("SharedVM", "Switching to RTL mode...")
                val rtlSuccess = repo?.changeMode(MavMode.RTL) ?: false

                if (!rtlSuccess) {
                    LogUtils.e("SharedVM", "Failed to switch to RTL mode")
                    onResult(false, "Failed to switch to RTL mode")
                    return@launch
                }

                LogUtils.i("SharedVM", "✓ RTL mode activated")
                addNotification(
                    Notification(
                        message = "Plan split initiated - returning to launch point",
                        type = NotificationType.INFO
                    )
                )

                // Wait for drone to land (altitude becomes 0 or very low)
                LogUtils.i("SharedVM", "Waiting for drone to land...")
                val landTimeout = 300000L // 5 minutes timeout
                val landStart = System.currentTimeMillis()

                while (System.currentTimeMillis() - landStart < landTimeout) {
                    val altitude = _telemetryState.value.altitudeRelative ?: 0f
                    if (altitude <= 0.5f) {
                        LogUtils.i("SharedVM", "✓ Drone has landed (altitude: $altitude)")
                        break
                    }
                    delay(500)
                }

                // Disarm the drone
                LogUtils.i("SharedVM", "Disarming drone...")
                repo?.disarm()
                delay(1000)

                if (!_telemetryState.value.armed) {
                    LogUtils.i("SharedVM", "✓ Drone disarmed successfully")
                } else {
                    LogUtils.w("SharedVM", "Drone may not be fully disarmed yet")
                }

                // Mark split plan as active
                _splitPlanActive.value = true
                _isSplitPlanActive.value = true

                addNotification(
                    Notification(
                        message = "Plan split complete - drone disarmed. Click 'Start' to resume from split point",
                        type = NotificationType.SUCCESS
                    )
                )

                onResult(true, null)
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to split plan", e)
                onResult(false, e.message)
            }
        }
    }

    /**
     * Resume mission from the split waypoint
     * This will start the mission from where the drone came down
     */
    fun resumeFromSplitPlan(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                LogUtils.i("SharedVM", "Resuming from split plan...")

                if (repo == null) {
                    LogUtils.w("SharedVM", "No repo available, cannot resume from split")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_splitPlanActive.value) {
                    LogUtils.w("SharedVM", "No active split plan to resume")
                    onResult(false, "No split plan active")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    LogUtils.w("SharedVM", "FCU not detected, cannot resume from split")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                if (!_missionUploaded.value || lastUploadedCount == 0) {
                    LogUtils.w("SharedVM", "No mission uploaded, cannot resume")
                    onResult(false, "No mission uploaded")
                    return@launch
                }

                if (!_telemetryState.value.armable) {
                    LogUtils.w("SharedVM", "Vehicle not armable")
                    onResult(false, "Vehicle not armable. Check sensors and GPS.")
                    return@launch
                }

                val sats = _telemetryState.value.sats ?: 0
                if (sats < 6) {
                    LogUtils.w("SharedVM", "Insufficient GPS satellites ($sats)")
                    onResult(false, "Insufficient GPS satellites ($sats). Need at least 6.")
                    return@launch
                }

                // Arm the vehicle
                LogUtils.i("SharedVM", "Arming vehicle for split plan resume...")
                repo?.arm()
                delay(500)

                if (!_telemetryState.value.armed) {
                    LogUtils.w("SharedVM", "Failed to arm vehicle")
                    onResult(false, "Failed to arm vehicle")
                    return@launch
                }

                LogUtils.i("SharedVM", "✓ Vehicle armed successfully")

                // Send mission start command
                LogUtils.i("SharedVM", "Sending mission start command...")
                repo?.sendMissionStartCommand()
                delay(500)

                // Switch to AUTO mode
                LogUtils.i("SharedVM", "Switching to AUTO mode...")
                val autoSuccess = repo?.changeMode(MavMode.AUTO) ?: false

                if (!autoSuccess) {
                    LogUtils.e("SharedVM", "Failed to switch to AUTO mode")
                    onResult(false, "Failed to switch to AUTO mode")
                    return@launch
                }

                LogUtils.i("SharedVM", "✓ Mission resumed from split point")
                addNotification(
                    Notification(
                        message = "Mission resumed from split waypoint",
                        type = NotificationType.SUCCESS
                    )
                )

                // Clear split plan active flag after successful resume
                _splitPlanActive.value = false
                _isSplitPlanActive.value = false

                onResult(true, null)
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to resume from split plan", e)
                onResult(false, e.message)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // GEOFENCE MANAGEMENT (ArduPilot Native System - Mission Planner Style)
    // FC handles all fence enforcement at 400Hz. GCS only uploads and monitors.
    // ════════════════════════════════════════════════════════════════

    companion object {
        // Default fence radius - Distance between waypoints and geofence boundary
        private const val DEFAULT_FENCE_RADIUS_METERS = 17.0f

        // Mission index the inserted "fly back to where you paused" waypoint always lands on.
        // filterWaypointsForResume emits HOME first and the transit waypoint second, and
        // resequenceWaypoints renumbers from 0 — so the transit waypoint is index 1. Every
        // resume path must point the FC here, or it rejoins the mission at the wrong place.
        private const val RESUME_TRANSIT_WAYPOINT_SEQ = 1

        // ARMING_CHECK safety check (MVP — hardcoded target account list).
        // ARMING_CHECK=0 lets the FC arm despite failing PreArm checks; a
        // fielded drone crashed because of this. Correct value is 4390.
        private val ARMING_CHECK_TARGET_EMAILS = setOf(
            "pavamantesting@gmail.com"
        )
        private const val ARMING_CHECK_REQUIRED_VALUE = 4390f

        // Fallback effective spray swath (m) used for area accounting when no auto-mission
        // line spacing is available (e.g. manual flights). Approximate boom/spray width.
        const val DEFAULT_SWATH_METERS = 5.0
        // Fallback nominal spray ground speed (m/s) when no mission speed is planned.
        const val DEFAULT_NOMINAL_SPEED_MS = 5.0

        // Battery failsafe voltage defaults, used until the pilot saves their own values
        // in Options. These are the 12S fleet figures; see the pack-aware fallback at
        // [defaultWarnVoltage] / [defaultCritVoltage] for why a known-6S pack overrides them.
        const val DEFAULT_LOW_VOLT_1 = 43.0f   // low voltage level 1 (warning) → BATT_LOW_VOLT
        const val DEFAULT_LOW_VOLT_2 = 42.0f   // low voltage level 2 (critical) → BATT_CRT_VOLT

        // Altitude ceiling failsafe default (metres AGL), mirrored to the FC's FENCE_ALT_MAX.
        // 120 m is the DGCA / most-jurisdictions legal ceiling for this class of drone and
        // matches the value the geofence upload has always used.
        const val DEFAULT_MAX_ALTITUDE_M = 120.0f

        // NOTE: the max range from home is no longer a GCS constant. It is the FC's
        // FENCE_RADIUS parameter, owned by the operator, and the GCS only reads it.
        //
        // It was previously a hardcoded 300m GCS-side distance check, justified by the
        // belief that "the FC only has one active fence slot" already taken by the mission
        // polygon. That was wrong: FENCE_TYPE is a bitmask and ArduPilot enforces the
        // altitude ceiling, the home cylinder and the inclusion polygon simultaneously
        // (FENCE_TYPE=7). The cylinder is a separate fence from any circle in the
        // inclusion/exclusion list, so arming it costs the polygon nothing. Enforcement now
        // lives on the FC at 400Hz, and both the radius and the breach action follow the
        // operator's parameters rather than GCS defaults.
    }

    // ═══ Fence parameters as configured on the vehicle ═══
    // Read from the FC on connect; the GCS never writes ACTION or MARGIN. These back the
    // breach announcement, the pre-arm summary and the map's range circle, so that what the
    // pilot is told always matches what the flight controller will actually do.

    private val _fenceAction = MutableStateFlow<FenceAction?>(null)
    val fenceAction: StateFlow<FenceAction?> = _fenceAction.asStateFlow()

    /** FENCE_MARGIN (m) — how far from the fence the FC tries to stay. Operator-owned. */
    private val _fenceMargin = MutableStateFlow<Float?>(null)
    val fenceMargin: StateFlow<Float?> = _fenceMargin.asStateFlow()

    /**
     * FENCE_RADIUS (m) — the home-centred cylinder radius.
     * NOTE: distinct from [_fenceRadius], which is the polygon *buffer* slider.
     */
    private val _fenceRadiusMeters = MutableStateFlow<Float?>(null)
    val fenceRadiusMeters: StateFlow<Float?> = _fenceRadiusMeters.asStateFlow()

    /** FENCE_TYPE bitmask as read from the FC. */
    private val _fenceTypeBits = MutableStateFlow<Int?>(null)
    val fenceTypeBits: StateFlow<Int?> = _fenceTypeBits.asStateFlow()

    /**
     * True when the GCS is enforcing a max-range limit, i.e. we have a usable FENCE_RADIUS.
     *
     * Enforcement is ours (see [handleMaxRangeFailsafe]), so this tracks whether we know the
     * radius rather than whether the FC has its own cylinder bit set — the map ring must show
     * the boundary that is actually being enforced, not one the FC may be ignoring.
     *
     * Eagerly started, NOT WhileSubscribed: a lazily-shared flow reports `false` whenever no
     * UI happens to be collecting.
     */
    val rangeFenceArmed: StateFlow<Boolean> = _fenceRadiusMeters
        .map { it != null && it > 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Current fence configuration uploaded to FC
    private val _fenceConfiguration = MutableStateFlow<FenceConfiguration?>(null)
    val fenceConfiguration: StateFlow<FenceConfiguration?> = _fenceConfiguration.asStateFlow()

    // Internal fence status fallback when repo is not connected
    private val _localFenceStatus = MutableStateFlow(FenceStatus())

    // Fence status from FC - returns repo's fenceStatus if available, otherwise local fallback
    val fenceStatus: StateFlow<FenceStatus>
        get() = repo?.fenceStatus ?: _localFenceStatus

    // Geofence warning state - for UI indication
    private val _geofenceWarningTriggered = MutableStateFlow(false)
    val geofenceWarningTriggered: StateFlow<Boolean> = _geofenceWarningTriggered.asStateFlow()

    // Geofence violation detected - mirrors fenceStatus.breached for backward compatibility
    private val _geofenceViolationDetected = MutableStateFlow(false)
    val geofenceViolationDetected: StateFlow<Boolean> = _geofenceViolationDetected.asStateFlow()

    // Track if geofence is currently triggering a mode change
    // Used to prevent resume popup from showing when FC switches mode due to breach
    @Volatile
    private var geofenceTriggeringModeChange = false

    // Expose geofence triggering state for TelemetryRepository
    val isGeofenceTriggeringModeChange: Boolean
        get() = geofenceTriggeringModeChange

    // ARMING_CHECK safety-check dialog state (see companion object for target emails/value)
    private val _armingCheckState = MutableStateFlow(ArmingCheckState.HIDDEN)
    val armingCheckState: StateFlow<ArmingCheckState> = _armingCheckState.asStateFlow()

    // ═══ Pre-arm failsafe acknowledgement ═══
    // Shown on every connection; the pilot must press OK before the drone will arm.
    // Non-null means the popup is up. Reset on disconnect so each flight session
    // re-confirms the settings that were actually pushed to this vehicle.
    private val _preflightFailsafeSummary = MutableStateFlow<PreflightFailsafeSummary?>(null)
    val preflightFailsafeSummary: StateFlow<PreflightFailsafeSummary?> = _preflightFailsafeSummary.asStateFlow()

    @Volatile
    private var preflightAcknowledged = false

    init {
        // Monitor connection status and announce via TTS
        // Also start fence status monitoring when connected
        viewModelScope.launch {
            isConnected.collect { connected ->
                ttsManager?.announceConnectionStatus(connected)
                LogUtils.d("SharedVM", "Connection status changed: ${if (connected) "Connected" else "Disconnected"}")

                if (connected && repo != null) {
                    // Start fence monitoring when connected (repo will be available)
                    startFenceStatusMonitoring()
                    // Auto-sync failsafe options to drone on connect
                    syncFailsafeOptionsOnConnect()
                    // Check ARMING_CHECK safety param for targeted accounts
                    checkArmingCheckOnConnect()
                    // Make the pilot acknowledge the failsafe settings before arming
                    showPreflightFailsafeSummaryOnConnect()
                } else if (!connected) {
                    // Stop fence monitoring on disconnect to prevent stale state
                    stopFenceStatusMonitoring()
                    // Reset all geofence warning/violation states so stale fence
                    // data from previous session doesn't block arming on reconnect
                    _geofenceViolationDetected.value = false
                    _geofenceWarningTriggered.value = false
                    geofenceTriggeringModeChange = false
                    _localFenceStatus.value = FenceStatus()

                    // Drop the cached FENCE_* parameters. They describe the vehicle we just
                    // lost, and the next connection may be a different airframe entirely —
                    // reporting the previous drone's fence action in the pre-arm popup, or
                    // drawing its range ring, would be worse than reporting nothing.
                    _fenceAction.value = null
                    _fenceMargin.value = null
                    _fenceRadiusMeters.value = null
                    _fenceTypeBits.value = null
                    LogUtils.i("Geofence", "Connection lost - fence monitoring stopped, fence state and cached FENCE_* params reset")

                    // Require a fresh pre-arm acknowledgement on the next connection
                    preflightAcknowledged = false
                    _preflightFailsafeSummary.value = null
                }
            }
        }
    }

    // Job reference for fence status monitoring - allows cancellation on reconnect/disable
    private var fenceMonitoringJob: Job? = null

    /**
     * Sync failsafe configuration with the drone immediately after connection.
     *
     * Voltage thresholds are READ from the FC and cached — never written here. The only
     * thing this pushes is the altitude ceiling (when the pilot has explicitly set one) and
     * BATT_FS_LOW_ACT / BATT_FS_CRT_ACT = 0, which is a hard invariant.
     */
    private fun syncFailsafeOptionsOnConnect() {
        viewModelScope.launch {
            try {
                val context = GCSApplication.getInstance() ?: return@launch
                val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)

                LogUtils.i("OptionsSync", "Syncing failsafe options with drone on connect...")

                // Small delay to let the connection and the parameter link stabilize before
                // the first reads.
                delay(2000)

                // Do not interleave parameter traffic with a geofence upload.
                //
                // The fence upload is a chain of PARAM_SET writes, each waiting on a
                // PARAM_VALUE ack matched by name, and configureFenceParameters() aborts the
                // WHOLE fence on the first unconfirmed write. Our reads here emit their own
                // PARAM_REQUEST_READ / PARAM_VALUE traffic on the same 2s timescale as the
                // fence upload debounce, which is enough to make an ack look missing and
                // leave the polygon fence uploaded but not enforcing. Wait our turn.
                fenceUploadMutex.withLock {
                    LogUtils.d("OptionsSync", "Parameter link clear of fence upload — proceeding")
                }

                // ═══ Altitude ceiling (FENCE_ALT_MAX): THE FC IS THE SOURCE OF TRUTH ═══
                // Read, never written here — whatever FENCE_ALT_MAX says is the ceiling the
                // GCS enforces. Same principle as the voltage thresholds below: connecting
                // to a vehicle must not change what that vehicle is configured to do. The
                // pilot's Options value reaches the FC only when they press Update.
                adoptAltitudeCeilingFromFc(prefs)

                // ═══ RTL_ALT, kept under the ceiling ═══
                // Must run AFTER the ceiling sync: on a first connect that seeds the ceiling
                // from the FC, the prefs value this reads is only correct once that has run.
                syncRtlAltOnConnect(prefs)

                // ═══ Fence parameters (FENCE_ACTION / MARGIN / RADIUS / TYPE) ═══
                // Reads what the operator has actually configured, and arms the
                // home-centred range cylinder. Runs here so it shares the same
                // post-mutex, link-settled window as the ceiling sync.
                syncFenceParametersOnConnect()

                // ═══ Hand the altitude ceiling to the FC ═══
                // Must run AFTER syncFenceParametersOnConnect (which caches FENCE_TYPE) and
                // AFTER adoptAltitudeCeilingFromFc (which reads FENCE_ALT_MAX), so the fence
                // goes live already pointing at the limit the vehicle is configured with.
                armFcAltitudeFence()

                // ═══ Voltage thresholds: THE FC IS THE SOURCE OF TRUTH ═══
                // On connect the GCS only READS BATT_LOW_VOLT / BATT_CRT_VOLT and caches
                // them. It never writes them here, on any flavor.
                //
                // The old behaviour derived thresholds from the live pack voltage and pushed
                // them down, which meant simply connecting could rewrite the vehicle's
                // configured critical voltage — including overwriting values a pilot had
                // deliberately set in Mission Planner or in Options on an earlier session.
                // Deriving a threshold from a *measured* voltage is also circular: the same
                // partial-cell-sum that fires a false failsafe would have silently rewritten
                // the parameter it was being judged against.
                //
                // The ONLY path that writes these parameters is the pilot pressing Update in
                // the Options tab (OptionsViewModel.updateParameters). The failsafe *actions*
                // are still forced off below — the GCS owns the critical action.
                val readFailures = readFailsafeVoltagesFromFc(prefs)

                val failures = mutableListOf<String>()
                failures.addAll(readFailures)
                failures.addAll(disableFcBatteryFailsafeActions())

                if (failures.isEmpty()) {
                    LogUtils.i("OptionsSync", "All failsafe options synced to drone ✓")
                } else {
                    LogUtils.w("OptionsSync", "Failed to sync: ${failures.joinToString()}")
                }
            } catch (e: Exception) {
                LogUtils.e("OptionsSync", "Error syncing failsafe options on connect", e)
                // The invariant must hold even when the sync above threw partway through —
                // otherwise an exception in the threshold writes leaves the FC free to act.
                try {
                    val recoveryFailures = disableFcBatteryFailsafeActions()
                    if (recoveryFailures.isEmpty()) {
                        LogUtils.i("OptionsSync", "✓ FC battery actions forced to 0 after sync error")
                    } else {
                        LogUtils.e("OptionsSync", "✗ Could not force FC battery actions to 0 after sync error: ${recoveryFailures.joinToString()}")
                    }
                } catch (inner: Exception) {
                    LogUtils.e("OptionsSync", "Error forcing FC battery actions to 0 after sync error", inner)
                }
            }
        }
    }

    /**
     * Force both of the FC's own battery failsafe actions to 0 (None).
     *
     * Level 1 is alert-only by design. Level 2 (critical) is handled by the GCS via
     * [handleBatteryVoltageFailsafe]; letting the FC ALSO act caused a dual failsafe where
     * the FC's RTL took priority over the geofence and flew straight through it.
     *
     * Returns the names of the parameters that failed to write.
     */
    private suspend fun disableFcBatteryFailsafeActions(): List<String> {
        val failures = mutableListOf<String>()

        if (setParameter("BATT_FS_LOW_ACT", 0.0f) != null) {
            LogUtils.i("OptionsSync", "✓ BATT_FS_LOW_ACT = 0 (alert only)")
        } else {
            failures.add("BATT_FS_LOW_ACT")
            LogUtils.e("OptionsSync", "✗ Failed to set BATT_FS_LOW_ACT")
        }

        if (setParameter("BATT_FS_CRT_ACT", 0.0f) != null) {
            LogUtils.i("OptionsSync", "✓ BATT_FS_CRT_ACT = 0 (None — GCS handles critical action)")
        } else {
            failures.add("BATT_FS_CRT_ACT")
            LogUtils.e("OptionsSync", "✗ Failed to set BATT_FS_CRT_ACT")
        }

        return failures
    }

    /**
     * Pull BATT_LOW_VOLT / BATT_CRT_VOLT off the flight controller and cache them so the
     * GCS-side monitoring watches exactly the thresholds the vehicle is configured with.
     * Nothing is pushed down — this runs on every flavor, on every connect.
     *
     * A reading is accepted whenever the FC actually gave us one. Notably this does NOT
     * second-guess the value against the live pack voltage any more: doing so meant a
     * momentarily low or mis-summed pack reading discarded a perfectly valid configured
     * parameter and silently substituted a GCS default, which is precisely the "the app
     * rewrote my critical voltage" behaviour. The FC's parameter is the truth; if it is
     * wrong for the pack, the pilot corrects it in Options.
     *
     * Returns the names of the parameters that could not be read.
     */
    private suspend fun readFailsafeVoltagesFromFc(prefs: android.content.SharedPreferences): List<String> {
        val failures = mutableListOf<String>()

        // An unreadable parameter clears the cached key rather than leaving it: the previous
        // value could have come from a different vehicle on an earlier connect, and the
        // cell-count-aware default is a safer thing to fall back on than a stale one.
        fun accept(name: String, key: String, value: Float?) {
            when {
                value == null || value <= 0f -> {
                    failures.add(name)
                    prefs.edit().remove(key).apply()
                    LogUtils.w("OptionsSync", "✗ Could not read $name from FC — falling back to the default threshold")
                }
                else -> {
                    prefs.edit().putFloat(key, value).apply()
                    LogUtils.i("OptionsSync", "✓ Read $name = ${value}V from FC")
                }
            }
        }

        accept("BATT_LOW_VOLT", "low_volt_level_1", readParameter("BATT_LOW_VOLT"))
        delay(100) // small gap between param requests
        accept("BATT_CRT_VOLT", "low_volt_level_2", readParameter("BATT_CRT_VOLT"))

        return failures
    }

    /**
     * Adopt the altitude ceiling FROM the flight controller's FENCE_ALT_MAX on connect.
     *
     * THE FC IS THE SOURCE OF TRUTH for the ceiling, exactly as it already is for
     * BATT_LOW_VOLT / BATT_CRT_VOLT (see [readFailsafeVoltagesFromFc]). Whatever
     * FENCE_ALT_MAX reads back is the ceiling the GCS enforces, displays, and derives
     * RTL_ALT from.
     *
     * This used to PUSH instead: if a "max_altitude" pref existed it was written down to the
     * FC on every connect, and the FC was only read on a first-ever connect. That meant a
     * ceiling set once on one drone followed the tablet onto the next drone and silently
     * overwrote its configured limit. Reading instead means connecting to a vehicle never
     * changes what that vehicle is configured to do.
     *
     * The pilot's Options value is still authoritative when they press Update — that path
     * goes through [pushAltitudeCeilingToFc], which is the ONLY writer of FENCE_ALT_MAX.
     *
     * NOTE: deliberately does NOT touch FENCE_ENABLE or FENCE_TYPE; [armFcAltitudeFence]
     * owns those.
     */
    private suspend fun adoptAltitudeCeilingFromFc(prefs: android.content.SharedPreferences) {
        try {
            if (!prefs.getBoolean("max_altitude_enabled", true)) {
                LogUtils.i("OptionsSync", "Altitude ceiling failsafe disabled — skipping FENCE_ALT_MAX read")
                return
            }

            val fcValue = readParameter("FENCE_ALT_MAX", timeoutMs = 4000L)
            if (fcValue != null && fcValue > 0f) {
                // FENCE_ALT_MAX carries the safety offset (see getFcAltitudeFenceMax), so add
                // it back to recover the ceiling the pilot actually means. Without this the
                // displayed/enforced ceiling would sit a metre low, and would creep down again
                // on every reconnect.
                val ceiling = fcValue + FC_ALT_FENCE_SAFETY_OFFSET_M
                prefs.edit().putFloat("max_altitude", ceiling).apply()
                LogUtils.i("OptionsSync", "✓ Altitude ceiling adopted from FC: FENCE_ALT_MAX = ${fcValue}m → ceiling ${ceiling}m")
            } else {
                // Leave whatever is cached rather than inventing a ceiling: a stale pref from
                // this same airframe is a better guess than a default, and the GCS layers need
                // *some* limit to enforce. Said out loud — the pilot cannot see this otherwise.
                val fallback = prefs.getFloat("max_altitude", DEFAULT_MAX_ALTITUDE_M)
                LogUtils.e("OptionsSync", "✗ Could not read FENCE_ALT_MAX — enforcing the cached ceiling ${fallback}m instead")
                addNotification(
                    Notification(
                        message = "⚠️ Could not read the altitude ceiling (FENCE_ALT_MAX) from the drone — using ${String.format(Locale.US, "%.0f", fallback)} m",
                        type = NotificationType.WARNING
                    )
                )
            }
        } catch (e: Exception) {
            LogUtils.e("OptionsSync", "Error adopting the altitude ceiling from the FC", e)
        }
    }

    /**
     * Write the pilot's altitude ceiling DOWN to the FC's FENCE_ALT_MAX.
     *
     * The only writer of FENCE_ALT_MAX, reached from Options → Update via
     * [applyAltitudeCeilingToFc]. Connect-time sync reads instead — see
     * [adoptAltitudeCeilingFromFc] for why.
     */
    private suspend fun pushAltitudeCeilingToFc(prefs: android.content.SharedPreferences) {
        try {
            if (!prefs.getBoolean("max_altitude_enabled", true)) {
                LogUtils.i("OptionsSync", "Altitude ceiling failsafe disabled — not writing FENCE_ALT_MAX")
                return
            }

            val ceiling = prefs.getFloat("max_altitude", DEFAULT_MAX_ALTITUDE_M)
            if (ceiling <= 0f) return
            // Biased below the pilot's ceiling so ArduPilot's climb-arrest overshoot lands
            // under it rather than over. See getFcAltitudeFenceMax().
            val fcLimit = (ceiling - FC_ALT_FENCE_SAFETY_OFFSET_M).coerceAtLeast(1f)
            if (setParameter("FENCE_ALT_MAX", fcLimit) != null) {
                LogUtils.i("OptionsSync", "✓ FENCE_ALT_MAX = $fcLimit m (ceiling ${ceiling}m less ${FC_ALT_FENCE_SAFETY_OFFSET_M}m overshoot allowance)")
            } else {
                LogUtils.e("OptionsSync", "✗ Failed to set FENCE_ALT_MAX")
                addNotification(
                    Notification(
                        message = "⚠️ Could not write the altitude ceiling to the drone",
                        type = NotificationType.WARNING
                    )
                )
            }
        } catch (e: Exception) {
            LogUtils.e("OptionsSync", "Error pushing the altitude ceiling to the FC", e)
        }
    }

    /**
     * Keep the FC's RTL_ALT below the altitude ceiling.
     *
     * ArduCopter's RTL begins with a climb stage: below RTL_ALT it climbs UP to RTL_ALT
     * before returning home. With the stock RTL_ALT (1500 cm) on a vehicle whose ceiling is
     * lower, an RTL triggered AT the ceiling — including the one our own altitude failsafe
     * commands — drives the drone straight through it. That is the overshoot this sync
     * exists to remove; [handleAltitudeFailsafe] brakes first as the in-flight backstop for
     * vehicles this write could not reach.
     *
     * Units: RTL_ALT is CENTIMETRES. RTL_ALT=0 means "return at the current altitude", which
     * is why the written value is floored rather than allowed to reach zero.
     *
     * Only ever lowered, never raised: an operator who deliberately set a conservative
     * RTL_ALT keeps it, on the same read-modify-write principle as FENCE_RADIUS.
     */
    private suspend fun syncRtlAltOnConnect(prefs: android.content.SharedPreferences) {
        try {
            if (!prefs.getBoolean("max_altitude_enabled", true)) {
                LogUtils.i("OptionsSync", "Altitude ceiling failsafe disabled — skipping RTL_ALT sync")
                return
            }

            val ceiling = prefs.getFloat("max_altitude", DEFAULT_MAX_ALTITUDE_M)
            if (ceiling <= 0f) return

            // The ceiling is AGL, and so is RTL_ALT — both measured from home.
            val desiredM = (ceiling - RTL_ALT_BELOW_CEILING_M).coerceAtLeast(RTL_ALT_MIN_M)

            if (desiredM >= ceiling) {
                // Only reachable with a ceiling at or under the floor, where no RTL altitude
                // can be both legal and sane. Say so — RTL is not a safe breach action here.
                LogUtils.e("OptionsSync", "✗ Ceiling ${ceiling}m is too low for a safe RTL_ALT (floor ${RTL_ALT_MIN_M}m) — RTL will breach it")
                addNotification(
                    Notification(
                        message = "⚠️ Altitude ceiling ${String.format(Locale.US, "%.0f", ceiling)}m is too low for RTL. Set the breach action to Loiter or Land.",
                        type = NotificationType.WARNING
                    )
                )
                return
            }

            val currentCm = readParameter("RTL_ALT", timeoutMs = 4000L)
            if (currentCm == null) {
                LogUtils.w("OptionsSync", "Could not read RTL_ALT — cannot confirm RTL stays under the ${ceiling}m ceiling")
                return
            }

            val currentM = currentCm / 100f
            if (currentM <= desiredM) {
                LogUtils.i("OptionsSync", "RTL_ALT = ${currentM}m already clears the ${ceiling}m ceiling — left as configured")
                return
            }

            val desiredCm = desiredM * 100f
            if (setParameter("RTL_ALT", desiredCm) != null) {
                LogUtils.i("OptionsSync", "✓ RTL_ALT lowered ${currentM}m → ${desiredM}m (${RTL_ALT_BELOW_CEILING_M}m under the ${ceiling}m ceiling)")
                // Said out loud, not just logged. An operator who set RTL_ALT deliberately in
                // the parameter list and then finds the drone returning lower has every
                // reason to conclude "the RTL altitude isn't being taken" — which is exactly
                // the report this came from. It IS being overridden, on purpose, and the
                // override is only visible if we say so.
                addNotification(
                    Notification(
                        message = "RTL altitude lowered to ${String.format(Locale.US, "%.0f", desiredM)}m " +
                            "so an RTL stays under the ${String.format(Locale.US, "%.0f", ceiling)}m ceiling " +
                            "(was ${String.format(Locale.US, "%.0f", currentM)}m)",
                        type = NotificationType.INFO
                    )
                )
            } else {
                LogUtils.e("OptionsSync", "✗ Failed to set RTL_ALT — RTL may climb through the ${ceiling}m ceiling")
                addNotification(
                    Notification(
                        message = "⚠️ Could not lower RTL_ALT below the altitude ceiling — an RTL may exceed it",
                        type = NotificationType.WARNING
                    )
                )
            }
        } catch (e: Exception) {
            LogUtils.e("OptionsSync", "Error syncing RTL_ALT", e)
        }
    }

    /**
     * Read the vehicle's fence configuration, and arm the home-centred range cylinder.
     *
     * FENCE_ACTION and FENCE_MARGIN are READ ONLY — never written, on any path. DGCA
     * requires the drone to act on the parameters actually set on it, so whatever the
     * operator configured is what happens on a breach; the GCS's job is to report it
     * faithfully (breach announcement, pre-arm summary) rather than to impose a default.
     * Previously every fence upload stamped FENCE_ACTION=4 and FENCE_MARGIN=3 onto the FC,
     * silently reverting the operator's settings.
     *
     * FENCE_RADIUS / FENCE_TYPE are different: the 300m range limit is a product requirement,
     * so we arm it here. Both writes are conservative — the radius is only written when the
     * FC's value is missing or LARGER than our limit (never widening a stricter setting an
     * operator chose), and FENCE_TYPE is read-modify-written so the circle bit is ORed in
     * without disturbing the altitude or polygon bits.
     */
    private suspend fun syncFenceParametersOnConnect() {
        try {
            val repository = repo ?: return

            // --- Operator-owned: read and cache only ---
            val action = repository.readFenceParameter("FENCE_ACTION")
            _fenceAction.value = FenceAction.fromParam(action)
            if (action != null && _fenceAction.value == null) {
                LogUtils.w("FenceSync", "FENCE_ACTION=$action is not a recognised ArduPilot action")
            }
            LogUtils.i("FenceSync", "FENCE_ACTION = $action (${_fenceAction.value?.pilotLabel ?: "unknown"}) — operator-owned, not modified")

            // The altitude limit's escalation reads FENCE_ACTION, so check here — while the
            // value is fresh and the vehicle is still on the ground — that it matches what
            // the pilot selected in Options.
            warnIfLimitActionMismatch()

            _fenceMargin.value = repository.readFenceParameter("FENCE_MARGIN")
            LogUtils.i("FenceSync", "FENCE_MARGIN = ${_fenceMargin.value} m — operator-owned, not modified")

            // --- Range cylinder radius: operator-owned, read only ---
            // The GCS used to clamp this to MAX_RANGE_METERS, which silently reverted any
            // radius a pilot deliberately configured. Same DGCA reasoning as FENCE_ACTION:
            // the drone flies the limit that is actually set on it, and the GCS reports it.
            _fenceRadiusMeters.value = repository.readFenceParameter("FENCE_RADIUS")
            LogUtils.i("FenceSync", "FENCE_RADIUS = ${_fenceRadiusMeters.value} m — operator-owned, not modified")

            // FENCE_TYPE: read only, for display.
            //
            // The GCS no longer arms the FC's home-centred cylinder. Doing so proved
            // unreliable: AC_Fence::update() rebuilds its live _enabled_fences mask only when
            // FENCE_ENABLE *changes value*, so a FENCE_TYPE written to an already-enabled
            // fence updated the parameter — and everything the pilot could see — while the
            // circle fence was never actually evaluated. The drone flew past 1000m in Loiter
            // with no breach. Rather than depend on a 0→1 bounce landing correctly on every
            // vehicle, the range limit is enforced GCS-side by handleMaxRangeFailsafe, the
            // same way the altitude ceiling is. The limit and the action still come from the
            // vehicle's own FENCE_RADIUS / FENCE_ACTION.
            _fenceTypeBits.value = repository.readFenceParameter("FENCE_TYPE")?.toInt()
            LogUtils.i("FenceSync", "FENCE_TYPE = ${_fenceTypeBits.value} — operator-owned, not modified")

            // The GCS enforces the range limit, so what matters is that we know the radius,
            // not that the FC has its cylinder armed. Say so plainly if we could not read it:
            // without FENCE_RADIUS there is no limit to enforce and the pilot must know.
            if (_fenceRadiusMeters.value == null || _fenceRadiusMeters.value!! <= 0f) {
                LogUtils.e("FenceSync", "Max range NOT enforced: FENCE_RADIUS unreadable or zero")
                addNotification(
                    Notification(
                        message = "⚠️ Max range not enforced — could not read FENCE_RADIUS from the drone",
                        type = NotificationType.WARNING
                    )
                )
            } else {
                LogUtils.i("FenceSync", "Max range enforced by GCS at ${_fenceRadiusMeters.value}m (FENCE_RADIUS), action ${_fenceAction.value?.pilotLabel ?: "RTL (default)"}")
            }

            // --- Clear any polygon left on the FC from a previous session ---
            //
            // The FC reports fence health for ALL fence types through a single SYS_STATUS
            // bit, so we cannot tell a cylinder breach from a polygon breach. Previously the
            // breach path was ignored entirely unless the GCS polygon toggle was on, which
            // masked stale polygons; now that the cylinder is always armed that gate is
            // always open, and a leftover polygon reports a breach the moment the drone
            // powers up outside it — announced as "FC activated RTL" while the drone sits
            // well inside the range fence.
            //
            // If the GCS is not flying a polygon this session, the FC must not be holding
            // one either. FENCE_TOTAL is the vertex count; >0 with our toggle off is stale.
            if (!_geofenceEnabled.value) {
                val fenceTotal = repository.readFenceParameter("FENCE_TOTAL")?.toInt() ?: 0
                if (fenceTotal > 0) {
                    LogUtils.w("FenceSync", "Stale polygon on FC (FENCE_TOTAL=$fenceTotal) with geofence off — clearing")
                    // Items only — must NOT disable the fence, or the range cylinder goes with it.
                    if (repository.clearFenceItemsOnly()) {
                        LogUtils.i("FenceSync", "✓ Stale polygon cleared")
                    } else {
                        LogUtils.e("FenceSync", "✗ Could not clear stale polygon — breach reports may be spurious")
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e("FenceSync", "Error syncing fence parameters", e)
        }
    }

    /**
     * Warn, on the ground, when "Action at Limit" and the vehicle's FENCE_ACTION disagree.
     *
     * FENCE_ACTION is what actually runs at the limit ([runAltitudeLimitAction]); the Options
     * dropdown only decides WHETHER to escalate. So a pilot who selects RTL on a vehicle
     * whose FENCE_ACTION is Brake gets a braked hold, not a return home — and connect time is
     * the only cheap place to discover that. Finding out mid-flight, or in front of someone
     * being shown the behaviour, is the failure this exists to prevent.
     *
     * Reports only; it never writes FENCE_ACTION. That parameter stays the operator's.
     */
    private fun warnIfLimitActionMismatch() {
        val context = GCSApplication.getInstance() ?: return
        if (!isAltitudeFailsafeEnabled(context)) return

        val selected = getMaxAltitudeAction(context)
        // Hover asks for no escalation at all, so nothing can disagree with it.
        if (selected.equals("HOVER", ignoreCase = true)) return

        val fenceAction = _fenceAction.value
        if (fenceAction == null) {
            // Nothing to compare against. The dropdown becomes the fallback, which IS the
            // documented behaviour — say so rather than implying the two agree.
            LogUtils.w("FenceSync", "'Action at Limit' is $selected but FENCE_ACTION could not be read — the dropdown will be used as the fallback")
            return
        }

        val agrees = when (selected.uppercase(Locale.US)) {
            "RTL" -> fenceAction == FenceAction.RTL ||
                fenceAction == FenceAction.SMART_RTL ||
                fenceAction == FenceAction.SMART_RTL_LAND
            "LAND" -> fenceAction == FenceAction.ALWAYS_LAND
            else -> true
        }

        if (agrees) {
            LogUtils.i("FenceSync", "'Action at Limit' $selected agrees with FENCE_ACTION=${fenceAction.pilotLabel}")
            return
        }

        LogUtils.w("FenceSync", "⚠️ 'Action at Limit' is $selected but the drone's FENCE_ACTION is ${fenceAction.pilotLabel} — the drone will ${fenceAction.pilotLabel} at the limit")
        addNotification(
            Notification(
                message = "⚠️ Action at Limit is set to $selected, but this drone's FENCE_ACTION is " +
                    "${fenceAction.pilotLabel}. At the altitude limit it will ${fenceAction.pilotLabel}, " +
                    "not $selected. Change FENCE_ACTION on the drone to match.",
                type = NotificationType.WARNING
            )
        )
    }

    /**
     * Hand enforcement of the altitude ceiling to the flight controller.
     *
     * Until now the ceiling was enforced GCS-side only ([handleAltitudeWall] /
     * [handleAltitudeFailsafe]) and FENCE_ALT_MAX was written as a "second layer" that was
     * never actually armed: the GCS only ever set FENCE_ENABLE=1 inside the geofence-polygon
     * upload, so on any flight without a polygon the FC ignored FENCE_ALT_MAX completely.
     * That left a ~5Hz link as the sole thing holding the limit, which is how a 48 m ceiling
     * produced a 52 m peak - the GCS could not see and react fast enough.
     *
     * The FC evaluates its fences at 400Hz with no link in the path, so it is the only layer
     * that can actually hold the line. The GCS wall stays in front of it as the predictive
     * layer (it stops the climb BEFORE the limit rather than recovering after), and the FC
     * fence sits underneath, biased [FC_ALT_FENCE_SAFETY_OFFSET_M] lower still.
     *
     * Three things this is careful about:
     *
     *  1. THE POLYGON BIT. Enabling a fence whose FENCE_TYPE claims an inclusion polygon
     *     while the FC holds no polygon can be refused by ArduPilot's fence pre-arm check.
     *     So when FENCE_TOTAL says there is no polygon loaded, bit 2 is cleared. The geofence
     *     upload ORs it straight back in (configureFenceParameters) when a real fence is sent.
     *  2. THE ENABLE BOUNCE. AC_Fence rebuilds its live _enabled_fences mask only when
     *     FENCE_ENABLE *changes value*. A FENCE_TYPE written to an already-enabled fence
     *     updates the parameter - and everything the pilot can see - while the new bit is
     *     never evaluated. So a type change on an enabled fence is followed by a 1-0-1 bounce.
     *  3. ARMED VEHICLES. Never reconfigure a fence on an aircraft that is flying. If this
     *     runs on a reconnect mid-flight it reports and leaves the fence exactly as it is.
     *
     * FENCE_ACTION and FENCE_MARGIN remain untouched, as everywhere else: what the FC does on
     * the breach is still the operator's parameter, which is what DGCA requires.
     */
    private suspend fun armFcAltitudeFence() {
        val repository = repo ?: return
        try {
            val context = GCSApplication.getInstance() ?: return

            if (!isAltitudeFailsafeEnabled(context)) {
                // Deliberately does NOT set FENCE_ENABLE=0. Switching a safety fence off is
                // not something a GCS settings toggle should do behind the pilot's back; the
                // toggle governs the GCS layers, and the FC keeps whatever it was configured
                // with. Said out loud in the log so the behaviour is not a surprise.
                LogUtils.i("FenceSync", "Altitude ceiling failsafe disabled - leaving FENCE_ENABLE/FENCE_TYPE as configured on the FC")
                return
            }

            val fcLimit = getFcAltitudeFenceMax()
            if (fcLimit == null) {
                LogUtils.w("FenceSync", "No altitude ceiling configured - not arming the FC altitude fence")
                return
            }

            if (_telemetryState.value.armed) {
                LogUtils.w("FenceSync", "Vehicle is ARMED - not reconfiguring the fence in flight (FENCE_TYPE=${_fenceTypeBits.value})")
                return
            }

            // FENCE_TYPE was just read by syncFenceParametersOnConnect; re-read only if that
            // failed. Acting on a guess here would either disable a fence the operator wanted
            // or enable one against a bitmask we invented.
            val currentType = _fenceTypeBits.value
                ?: repository.readFenceParameter("FENCE_TYPE")?.toInt()
            if (currentType == null) {
                LogUtils.e("FenceSync", "✗ Could not read FENCE_TYPE - altitude ceiling stays GCS-only")
                addNotification(
                    Notification(
                        message = "⚠️ Could not read FENCE_TYPE - the drone is not enforcing the altitude ceiling itself",
                        type = NotificationType.WARNING
                    )
                )
                return
            }

            val currentEnable = repository.readFenceParameter("FENCE_ENABLE")?.toInt()
            if (currentEnable == null) {
                LogUtils.e("FenceSync", "✗ Could not read FENCE_ENABLE - altitude ceiling stays GCS-only")
                addNotification(
                    Notification(
                        message = "⚠️ Could not read FENCE_ENABLE - the drone is not enforcing the altitude ceiling itself",
                        type = NotificationType.WARNING
                    )
                )
                return
            }

            // FENCE_TOTAL is the stored polygon vertex count - the same signal
            // syncFenceParametersOnConnect uses to spot a stale polygon.
            val fenceTotal = repository.readFenceParameter("FENCE_TOTAL")?.toInt() ?: 0
            val polygonLoaded = fenceTotal > 0 || _geofenceEnabled.value

            var desiredType = currentType or FENCE_TYPE_ALT_MAX
            if (!polygonLoaded) {
                desiredType = desiredType and FENCE_TYPE_POLYGON.inv()
            }
            // Same reasoning one bit over: an armed home cylinder with no radius behind it is
            // a fence ArduPilot cannot evaluate, and a pre-arm refusal the pilot would have to
            // debug in the field. The circle bit is only ever left set, never added here — if
            // the operator has a radius configured, arming the cylinder is their call and the
            // GCS just stops undoing it.
            val radius = _fenceRadiusMeters.value
            if (radius == null || radius <= 0f) {
                if (desiredType and FENCE_TYPE_CIRCLE != 0) {
                    LogUtils.w("FenceSync", "FENCE_RADIUS is ${radius ?: "unreadable"} — clearing the circle bit so the fence can still arm")
                }
                desiredType = desiredType and FENCE_TYPE_CIRCLE.inv()
            }

            val typeChanged = desiredType != currentType
            if (typeChanged) {
                if (!repository.setFenceParameter("FENCE_TYPE", desiredType.toFloat())) {
                    LogUtils.e("FenceSync", "✗ Failed to write FENCE_TYPE=$desiredType - altitude ceiling stays GCS-only")
                    addNotification(
                        Notification(
                            message = "⚠️ Could not set FENCE_TYPE - the drone is not enforcing the altitude ceiling itself",
                            type = NotificationType.WARNING
                        )
                    )
                    return
                }
                _fenceTypeBits.value = desiredType
                val polyNote = if (!polygonLoaded) ", polygon bit off: no polygon loaded" else ""
                LogUtils.i("FenceSync", "✓ FENCE_TYPE $currentType -> $desiredType (alt bit on$polyNote)")
                delay(200)
            }

            // See (2) above: a type change only becomes live across an enable transition.
            if (currentEnable != 0 && typeChanged) {
                LogUtils.i("FenceSync", "Bouncing FENCE_ENABLE so the new FENCE_TYPE is actually evaluated")
                repository.setFenceParameter("FENCE_ENABLE", 0f)
                delay(300)
            }

            if (currentEnable != 1 || typeChanged) {
                if (!repository.setFenceParameter("FENCE_ENABLE", 1f)) {
                    LogUtils.e("FenceSync", "✗ Failed to write FENCE_ENABLE=1 - altitude ceiling stays GCS-only")
                    addNotification(
                        Notification(
                            message = "⚠️ Could not enable the drone's fence - the altitude ceiling is enforced by the tablet only",
                            type = NotificationType.WARNING
                        )
                    )
                    return
                }
                delay(200)
            }

            // Read back rather than trust the ack: this is the parameter the whole ceiling
            // now rests on, and "we sent it" is not the same as "it took".
            val readBack = repository.readFenceParameter("FENCE_ENABLE")?.toInt()
            if (readBack == 1) {
                LogUtils.i("FenceSync", "✓ FC altitude fence ARMED: FENCE_ENABLE=1, FENCE_TYPE=$desiredType, FENCE_ALT_MAX=${fcLimit}m")
            } else {
                LogUtils.e("FenceSync", "✗ FENCE_ENABLE reads back as $readBack - the FC is NOT enforcing the altitude ceiling")
                addNotification(
                    Notification(
                        message = "⚠️ The drone did not accept the fence - the altitude ceiling is enforced by the tablet only",
                        type = NotificationType.WARNING
                    )
                )
            }
        } catch (e: Exception) {
            LogUtils.e("FenceSync", "Error arming the FC altitude fence", e)
        }
    }

    /**
     * Push the pilot's altitude ceiling to the FC right now, outside the connect sequence.
     *
     * Called when the ceiling is changed in Options. That path used to write FENCE_ALT_MAX and
     * nothing else, which left RTL_ALT stranded at whatever the connect-time sync had agreed
     * with the OLD ceiling: drop the ceiling from 120 m to 48 m mid-session and RTL_ALT stayed
     * at 110 m, so the breach action - an RTL - began by climbing 60 m through the very limit
     * it was recovering from. That is the "RTL alt isn't being taken as per set params"
     * report. All three writes now move together.
     */
    suspend fun applyAltitudeCeilingToFc() {
        val context = GCSApplication.getInstance() ?: return
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        try {
            pushAltitudeCeilingToFc(prefs)
            syncRtlAltOnConnect(prefs)
            armFcAltitudeFence()
        } catch (e: Exception) {
            LogUtils.e("OptionsSync", "Failed to apply the altitude ceiling to the FC", e)
        }
    }

    /**
     * ARMING_CHECK=0 lets the FC arm despite failing PreArm checks (a fielded
     * drone crashed because of this). For targeted accounts only, check on every
     * connection whether ARMING_CHECK is still 0 and surface a fix dialog if so.
     * Re-runs on every reconnect, so the prompt keeps appearing until fixed.
     */
    private fun checkArmingCheckOnConnect() {
        viewModelScope.launch {
            try {
                val context = GCSApplication.getInstance() ?: return@launch
                val email = com.example.kftgcs.api.SessionManager.getEmail(context)?.trim()
                if (email == null || ARMING_CHECK_TARGET_EMAILS.none { it.equals(email, ignoreCase = true) }) {
                    return@launch
                }

                delay(2000) // let connection/param link stabilize (mirrors syncFailsafeOptionsOnConnect)

                val value = readParameter("ARMING_CHECK", timeoutMs = 5000L)
                _armingCheckState.value =
                    if (value == 0f) ArmingCheckState.PROMPT_WRITE
                    else ArmingCheckState.HIDDEN
            } catch (e: Exception) {
                LogUtils.e("ArmingSafety", "Failed to check ARMING_CHECK", e)
            }
        }
    }

    /** Writes ARMING_CHECK to the required safety value, retrying on timeout. */
    fun fixArmingCheck() {
        _armingCheckState.value = ArmingCheckState.WRITING
        viewModelScope.launch {
            var ack: com.divpundir.mavlink.definitions.common.ParamValue? = null
            for (attempt in 1..3) {
                ack = setParameter("ARMING_CHECK", ARMING_CHECK_REQUIRED_VALUE, timeoutMs = 5000L)
                if (ack != null) break
                if (attempt < 3) delay(500)
            }
            _armingCheckState.value =
                if (ack != null && ack.paramValue == ARMING_CHECK_REQUIRED_VALUE) ArmingCheckState.PROMPT_REBOOT
                else ArmingCheckState.WRITE_FAILED
        }
    }

    fun skipArmingCheckWarning() {
        _armingCheckState.value = ArmingCheckState.HIDDEN
    }

    fun confirmArmingCheckReboot() {
        viewModelScope.launch { rebootAutopilot() }
        _armingCheckState.value = ArmingCheckState.HIDDEN
    }

    fun dismissArmingCheckRebootPrompt() {
        _armingCheckState.value = ArmingCheckState.HIDDEN
    }

    // ════════════════════════════════════════════════════════════════
    // PRE-ARM FAILSAFE ACKNOWLEDGEMENT
    // ════════════════════════════════════════════════════════════════

    /**
     * Raise the pre-arm summary popup once the drone is connected.
     *
     * Delayed past [syncFailsafeOptionsOnConnect] (2 s) so the values shown are the ones
     * that were actually pushed to this vehicle, and so the first BATTERY_STATUS frames
     * have landed — without a live pack voltage an unconfigured threshold would be
     * reported as the 12S default even on a 6S drone.
     */
    private fun showPreflightFailsafeSummaryOnConnect() {
        viewModelScope.launch {
            delay(3000)
            val context = GCSApplication.getInstance() ?: return@launch
            if (preflightAcknowledged) return@launch
            _preflightFailsafeSummary.value = buildPreflightFailsafeSummary(context)
            LogUtils.i("PreArm", "Showing pre-arm failsafe acknowledgement: ${_preflightFailsafeSummary.value}")
        }
    }

    private fun buildPreflightFailsafeSummary(context: Context) = PreflightFailsafeSummary(
        lowVoltLevel1 = getLowVoltLevel1(context),
        criticalVoltage = getLowVoltLevel2(context),
        tankEmptyAction = describeTankEmptyAction(context),
        batteryFailsafeAction = getLowVoltLevel2Action(context),
        // Straight from the vehicle's FENCE_* parameters (see syncFenceParametersOnConnect).
        // "Unknown" rather than a plausible-looking default: a pilot must not acknowledge a
        // fence action we merely assumed.
        fenceAction = _fenceAction.value?.pilotLabel ?: "Unknown",
        fenceRadius = _fenceRadiusMeters.value
            ?.let { String.format(Locale.US, "%.0f m", it) } ?: "Unknown",
        fenceMargin = _fenceMargin.value
            ?.let { String.format(Locale.US, "%.1f m", it) } ?: "Unknown"
    )

    /**
     * Arming gate: true while the pilot still owes an acknowledgement of the failsafe
     * summary. Called from [TelemetryRepository.arm]. Also re-raises the popup if the
     * arm attempt beat the post-connect delay, so the block is always actionable.
     */
    fun isPreflightAcknowledgementPending(): Boolean {
        if (preflightAcknowledged) return false
        if (_preflightFailsafeSummary.value == null) {
            GCSApplication.getInstance()?.let {
                _preflightFailsafeSummary.value = buildPreflightFailsafeSummary(it)
            }
        }
        return true
    }

    /** Pilot pressed OK on the pre-arm popup — arming is unblocked for this connection. */
    fun acknowledgePreflightFailsafeSummary() {
        preflightAcknowledged = true
        _preflightFailsafeSummary.value = null
        LogUtils.i("PreArm", "Pre-arm failsafe summary acknowledged — arming unblocked")
    }

    /**
     * Monitor fence status from flight controller.
     * FC handles all breach detection and enforcement at 400Hz.
     * GCS just monitors and notifies user.
     */
    private fun startFenceStatusMonitoring() {
        // Cancel any previous monitoring job (e.g. from old repo on reconnect)
        stopFenceStatusMonitoring()

        fenceMonitoringJob = viewModelScope.launch {
            // Collect fence status updates from repository
            repo?.fenceStatus?.collect { status ->
                // GUARD: Only process fence status if a fence we armed is actually active.
                // Prevents stale FC fence data from causing false warnings like
                // "approaching polygon fence" when the geofence is off — but the
                // home-centred range cylinder is armed independently of that toggle, so a
                // 300m breach must still get through with the polygon disabled.
                // Only the polygon is FC-enforced now — the range limit and the altitude
                // ceiling are handled GCS-side — so this gate is back to the polygon toggle.
                if (!_geofenceEnabled.value) {
                    // Geofence is off in GCS - ensure clean state
                    if (_geofenceViolationDetected.value || _geofenceWarningTriggered.value) {
                        _geofenceViolationDetected.value = false
                        _geofenceWarningTriggered.value = false
                        geofenceTriggeringModeChange = false
                    }
                    return@collect
                }

                // Update backward-compatible violation state
                _geofenceViolationDetected.value = status.breached

                if (status.breached) {
                    // Just notify - FC is handling everything
                    _geofenceWarningTriggered.value = true
                    geofenceTriggeringModeChange = true

                    notifyFenceBreach("SYS_STATUS")

                    // Reset the triggering flag after the FC has had time to act. Launched
                    // separately rather than delayed inline: this is a StateFlow collector, and
                    // blocking it for 2s lets a short breach→clear→breach sequence be conflated
                    // away, which is exactly how a real breach ended up with no popup at all.
                    viewModelScope.launch {
                        delay(2000)
                        geofenceTriggeringModeChange = false
                    }
                } else if (_geofenceWarningTriggered.value) {
                    // Breach cleared
                    LogUtils.i("Geofence", "✓ Fence breach cleared - drone back in safe zone")
                    _geofenceWarningTriggered.value = false
                    // Clear the de-dupe window too. It exists to collapse the SYS_STATUS and
                    // STATUSTEXT reports of the SAME breach into one alert; once the drone is
                    // back inside, the next breach is a genuinely new event and must alert
                    // immediately even if it happens within FENCE_BREACH_DEDUPE_MS of the last.
                    lastFenceBreachAlertTime = 0L
                    addNotification(Notification(
                        message = "✓ Geofence clear - drone back in safe zone",
                        type = NotificationType.INFO
                    ))
                }
            }
        }
    }

    // Fence-breach alert de-dup. The breach reaches us on two independent paths and either one
    // can be the only one that fires: the SYS_STATUS geofence bit (bit 20, fence sensor
    // enabled + unhealthy) and the FC's own "Fence breach" STATUSTEXT. Whichever arrives first raises the alert; the
    // other is swallowed for FENCE_BREACH_DEDUPE_MS so the pilot gets one popup, not two.
    private var lastFenceBreachAlertTime = 0L
    private val FENCE_BREACH_DEDUPE_MS = 5000L

    /**
     * Single entry point for the geofence-breach alert (notification + popup + TTS), matching
     * what Battery / Max Range / Max Altitude / RC / Tank Empty already do.
     *
     * Previously this lived only inside the SYS_STATUS collector, so a breach the FC reported
     * via STATUSTEXT but never reflected in the SYS_STATUS fence health bit produced no popup
     * at all. Both paths now land here.
     */
    /**
     * @param fenceName which boundary was crossed ("Range", "Polygon", "Max Altitude"...),
     *   when the FC told us. The SYS_STATUS geofence bit is a single flag for every fence type, so that
     *   path cannot know; only the STATUSTEXT path can name it. Null means "a fence".
     */
    @JvmOverloads
    fun notifyFenceBreach(source: String, fenceName: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastFenceBreachAlertTime < FENCE_BREACH_DEDUPE_MS) {
            LogUtils.d("Geofence", "Fence breach from $source suppressed — already alerted ${now - lastFenceBreachAlertTime}ms ago")
            return
        }
        lastFenceBreachAlertTime = now

        // Every pilot-facing channel names the action the FC is ACTUALLY configured to take,
        // not a GCS assumption. The popup is what a pilot looks at mid-flight, so it has to
        // answer "what is the drone about to do" — "Fence Breached" alone did not.
        // When FENCE_ACTION could not be read we say only that the fence was breached,
        // rather than naming a behaviour we cannot vouch for.
        val action = _fenceAction.value
        val label = fenceName?.let { "$it fence" } ?: "Geofence"
        LogUtils.w("Geofence", "⚠️ $label breach detected via $source - FC handling with ${getCurrentFenceAction()}")
        addNotification(Notification(
            message = "⚠️ $label breach! FC activated ${getCurrentFenceAction()}",
            type = NotificationType.WARNING
        ))
        showFailsafePopup(
            if (action != null) "$label Breached — ${action.pilotLabel}" else "$label Breached"
        )
        // Callers include the STATUSTEXT collector, which runs off the main thread; viewModelScope
        // is Main.immediate, so this keeps TTS on the same thread the old call site used.
        val spoken = when (action) {
            null -> "Fence breached"
            FenceAction.REPORT_ONLY -> "Fence breached"
            FenceAction.RTL, FenceAction.SMART_RTL, FenceAction.SMART_RTL_LAND ->
                "Fence breached. Returning to launch."
            FenceAction.ALWAYS_LAND -> "Fence breached. Landing."
            FenceAction.BRAKE -> "Fence breached. Holding position."
        }
        viewModelScope.launch { speak(spoken) }
    }

    /**
     * Stop fence status monitoring and reset all fence-related state.
     * Called on disconnect, reconnect, or when geofence is disabled.
     */
    private fun stopFenceStatusMonitoring() {
        fenceMonitoringJob?.cancel()
        fenceMonitoringJob = null
    }

    /**
     * The fence action the FC will actually take on breach, as a pilot-facing string.
     *
     * Sourced from [_fenceAction], which is read from the vehicle's FENCE_ACTION parameter
     * on connect. It used to read the locally-built FenceConfiguration instead, which was
     * populated only by a fence upload in this same session — so a real breach before any
     * upload announced the useless "Safety Mode", and after an upload it always announced
     * the hardcoded BRAKE regardless of what the FC was actually set to.
     *
     * Falls back to "Safety Mode" only when the parameter genuinely could not be read.
     */
    private fun getCurrentFenceAction(): String =
        _fenceAction.value?.pilotLabel ?: "Safety Mode"

    /**
     * Upload geofence to flight controller.
     * This replaces the old GCS-based fence enforcement.
     * FC will enforce the fence autonomously at 400Hz.
     *
     * @param outerBoundary Inclusion polygon - drone must stay inside
     * @param innerBoundary Optional exclusion polygon - drone must stay outside (creates "donut" shape)
     * @param exclusionZones Additional exclusion polygons (obstacles, buildings, etc.)
     * @param returnPoint Where drone goes if fence is breached (defaults to first point if null)
     * @param altitudeMax Maximum altitude in meters AGL
     * @param altitudeMin Minimum altitude in meters AGL
     *
     * Note: what the FC does on breach is NOT a parameter here. FENCE_ACTION and
     * FENCE_MARGIN belong to the operator and are never written by the GCS.
     */
    fun uploadGeofence(
        outerBoundary: List<LatLng>,
        innerBoundary: List<LatLng>? = null,
        exclusionZones: List<List<LatLng>> = emptyList(),
        returnPoint: LatLng? = null,
        altitudeMax: Float? = null,
        altitudeMin: Float? = null
    ) {
        viewModelScope.launch {
            try {
                LogUtils.i("Geofence", "Preparing geofence upload...")

                // Build fence zones
                val zones = mutableListOf<FenceZone>()

                // Outer boundary (inclusion - drone must stay inside)
                if (outerBoundary.size >= 3) {
                    zones.add(FenceZone.Polygon(
                        points = outerBoundary,
                        isInclusion = true
                    ))
                } else {
                    LogUtils.e("Geofence", "Outer boundary must have at least 3 points")
                    addNotification(Notification(
                        message = "❌ Geofence needs at least 3 boundary points",
                        type = NotificationType.ERROR
                    ))
                    return@launch
                }

                // Inner boundary (exclusion - drone must stay outside)
                // This creates a "donut" - outer inclusion + inner exclusion
                if (innerBoundary != null && innerBoundary.size >= 3) {
                    zones.add(FenceZone.Polygon(
                        points = innerBoundary,
                        isInclusion = false  // Exclusion zone
                    ))
                }

                // Additional exclusion zones (obstacles, buildings, etc.)
                exclusionZones.forEach { zone ->
                    if (zone.size >= 3) {
                        zones.add(FenceZone.Polygon(
                            points = zone,
                            isInclusion = false
                        ))
                    }
                }

                // Return point (where to go if breached)
                val actualReturnPoint = returnPoint ?: outerBoundary.firstOrNull()
                if (actualReturnPoint != null) {
                    zones.add(FenceZone.ReturnPoint(actualReturnPoint))
                }

                // Create configuration
                val config = FenceConfiguration(
                    zones = zones,
                    altitudeMin = altitudeMin,
                    altitudeMax = altitudeMax
                )

                // Upload to FC
                val success = repo?.uploadGeofence(config) ?: false

                if (success) {
                    _fenceConfiguration.value = config
                    _geofenceEnabled.value = true

                    // Also update the UI polygon for display
                    _geofencePolygon.value = outerBoundary

                    // NOTE: Removed geofence upload notification from notification panel
                    speak("Geofence enabled")

                    LogUtils.i("Geofence", "✅ Geofence uploaded successfully:")
                    LogUtils.i("Geofence", "  - Zones: ${zones.size}")
                    // Action and margin are the FC's own operator-set parameters, not values
                    // we passed in — log what the vehicle is actually configured with.
                    LogUtils.i("Geofence", "  - Action: ${getCurrentFenceAction()} (FENCE_ACTION, operator-set)")
                    LogUtils.i("Geofence", "  - Margin: ${_fenceMargin.value ?: "unknown"}m (FENCE_MARGIN, operator-set)")
                    LogUtils.i("Geofence", "  - Alt Max: ${altitudeMax ?: "none"}m")
                } else {
                    // NOTE: Removed geofence upload failure notification from notification panel
                    speak("Geofence upload failed")
                }

            } catch (e: Exception) {
                LogUtils.e("Geofence", "Error uploading geofence: ${e.message}")
                // NOTE: Removed geofence upload error notification from notification panel
            }
        }
    }

    /**
     * Upload circular geofence (inclusion or exclusion)
     */
    fun uploadCircularGeofence(
        center: LatLng,
        radiusMeters: Float,
        isInclusion: Boolean = true,
        altitudeMax: Float? = null
    ) {
        viewModelScope.launch {
            try {
                LogUtils.i("Geofence", "Uploading circular geofence: radius=${radiusMeters}m")

                val zones = listOf(
                    FenceZone.Circle(
                        center = center,
                        radiusMeters = radiusMeters,
                        isInclusion = isInclusion
                    ),
                    FenceZone.ReturnPoint(center)
                )

                val config = FenceConfiguration(
                    zones = zones,
                    altitudeMax = altitudeMax
                )

                val success = repo?.uploadGeofence(config) ?: false

                if (success) {
                    _fenceConfiguration.value = config
                    _geofenceEnabled.value = true
                    addNotification(Notification(
                        message = "✅ Circular geofence enabled (${radiusMeters}m radius)",
                        type = NotificationType.SUCCESS
                    ))
                    speak("Circular geofence enabled")
                } else {
                    addNotification(Notification(
                        message = "❌ Failed to upload circular geofence",
                        type = NotificationType.ERROR
                    ))
                }

            } catch (e: Exception) {
                LogUtils.e("Geofence", "Error uploading circular geofence: ${e.message}")
            }
        }
    }

    /**
     * Download current geofence from flight controller
     */
    fun downloadGeofence() {
        viewModelScope.launch {
            try {
                val zones = repo?.downloadGeofence() ?: emptyList()

                if (zones.isNotEmpty()) {
                    addNotification(
                        Notification(
                            message = "✅ Downloaded ${zones.size} fence zones",
                            type = NotificationType.SUCCESS
                        )
                    )

                    // Extract polygon points for UI display
                    val polygonZone = zones.filterIsInstance<FenceZone.Polygon>()
                        .firstOrNull { it.isInclusion }
                    if (polygonZone != null) {
                        _geofencePolygon.value = polygonZone.points
                        _geofenceEnabled.value = true
                    }

                    LogUtils.i("Geofence", "Downloaded ${zones.size} fence zones from FC")
                } else {
                    addNotification(
                        Notification(
                            message = "⚠️ No geofence configured on FC",
                            type = NotificationType.WARNING
                        )
                    )
                }

            } catch (e: Exception) {
                LogUtils.e("Geofence", "Error downloading geofence: ${e.message}")
            }
        }
    }

    /**
     * Enable/disable geofence on FC
     */
    fun setFenceEnabled(enabled: Boolean) {
        if (!enabled) {
            // Reset local state immediately when disabling
            stopFenceStatusMonitoring()
            resetGeofenceState()
        }

        viewModelScope.launch {
            val success = repo?.enableFence(enabled) ?: false

            if (success) {
                _geofenceEnabled.value = enabled
                val message = if (enabled) "✅ Geofence enabled" else "⚠️ Geofence disabled"
                addNotification(Notification(message, NotificationType.INFO))
                speak(if (enabled) "Geofence enabled" else "Geofence disabled")
                LogUtils.i("Geofence", message)

                // Restart monitoring if enabling, or ensure stopped if disabling
                if (enabled && repo != null) {
                    startFenceStatusMonitoring()
                }
            } else {
                addNotification(
                    Notification(
                        message = "❌ Failed to ${if (enabled) "enable" else "disable"} geofence",
                        type = NotificationType.ERROR
                    )
                )
            }
        }
    }

    /**
     * Clear geofence from FC and UI
     */
    fun clearGeofenceFromFC() {
        // Immediately reset all local state to prevent stale warnings
        stopFenceStatusMonitoring()
        _fenceConfiguration.value = null
        _geofenceEnabled.value = false
        _geofencePolygon.value = emptyList()
        _homePosition.value = null
        resetGeofenceState()

        viewModelScope.launch {
            try {
                val success = repo?.clearGeofenceFromFC() ?: false

                if (success) {
                    addNotification(Notification(
                        message = "✅ Geofence cleared from FC",
                        type = NotificationType.SUCCESS
                    ))
                    speak("Geofence cleared")
                    LogUtils.i("Geofence", "✅ Geofence cleared from FC and UI")
                } else {
                    // Fallback: try to at least disable the fence parameter
                    repo?.enableFence(false)
                    addNotification(Notification(
                        message = "⚠️ Geofence disabled (clear may be incomplete)",
                        type = NotificationType.WARNING
                    ))
                }

            } catch (e: Exception) {
                LogUtils.e("Geofence", "Error clearing geofence: ${e.message}")
                try { repo?.enableFence(false) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Reset geofence state - call this when starting a new mission or disabling geofence.
     * Clears all warning/violation flags AND internal fence status to prevent
     * stale state from blocking arming or showing false warnings.
     */
    fun resetGeofenceState() {
        _geofenceViolationDetected.value = false
        _geofenceWarningTriggered.value = false
        geofenceTriggeringModeChange = false
        lastFenceBreachAlertTime = 0L
        _localFenceStatus.value = FenceStatus()
        // Cancel any pending fence uploads
        fenceUploadJob?.cancel()
        fenceUploadJob = null
        pendingFenceUpload = null
        // Also reset fence status in repo if available
        repo?.stopFenceMonitoring()
        LogUtils.i("Geofence", "Geofence state fully reset (warnings, violations, local fence status, pending uploads)")
    }

    override fun onCleared() {
        super.onCleared()
        stopFenceStatusMonitoring()
        ttsManager?.shutdown()
        LogUtils.d("SharedVM", "ViewModel cleared, TTS shutdown")
    }

    /**
     * Returns the squared minimum distance from point P=(pLat,pLng) to the segment A→B.
     * Uses simple flat-earth approximation (adequate for short drone mission segments).
     * Squared distance avoids sqrt — only used for comparison so absolute value doesn't matter.
     */
    private fun pointToSegmentDistanceSq(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double {
        val abLat = bLat - aLat
        val abLng = bLng - aLng
        val abLenSq = abLat * abLat + abLng * abLng

        if (abLenSq == 0.0) {
            // Segment is a point — return distance to that point
            val dlat = pLat - aLat
            val dlng = pLng - aLng
            return dlat * dlat + dlng * dlng
        }

        // Project P onto AB, clamped to [0,1]
        val t = ((pLat - aLat) * abLat + (pLng - aLng) * abLng) / abLenSq
        val tClamped = t.coerceIn(0.0, 1.0)

        val closestLat = aLat + tClamped * abLat
        val closestLng = aLng + tClamped * abLng

        val dlat = pLat - closestLat
        val dlng = pLng - closestLng
        return dlat * dlat + dlng * dlng
    }
}
