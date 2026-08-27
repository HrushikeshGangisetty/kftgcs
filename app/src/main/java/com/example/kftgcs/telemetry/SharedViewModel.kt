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
import com.example.kftgcs.grid.GridUtils
import com.example.kftgcs.videotracking.CameraTrackingState
import com.example.kftgcs.videotracking.TrackingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import com.example.kftgcs.api.ApiService

enum class ConnectionType {
    TCP, BLUETOOTH, USB
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
    val batteryFailsafeAction: String
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

    // Altitude ceiling failsafe tracking (FENCE_ALT_MAX)
    private var altitudeLimitActionTriggered = false // One-shot per arm cycle, like the voltage action
    private var lastAltitudeWarnTime = 0L
    private var lastAltitudeLimitTime = 0L
    private val ALTITUDE_WARN_INTERVAL_MS = 4000L
    private val ALTITUDE_LIMIT_INTERVAL_MS = 5000L
    /** Start warning this far below the ceiling so the pilot can level off before hitting it. */
    private val ALTITUDE_WARN_MARGIN_M = 10f
    /**
     * Smallest buffer below the ceiling where the action fires. Applies when the drone is
     * barely climbing; a faster climb gets the projected stopping distance below instead.
     */
    private val ALTITUDE_MIN_ACTION_MARGIN_M = 4f
    /** Upper clamp so a bogus climb rate or a stale fix can't consume the whole envelope. */
    private val ALTITUDE_MAX_ACTION_MARGIN_M = 25f
    /**
     * Seconds of latency budgeted between crossing the trigger point and the mode change
     * biting: DO_SET_MODE round trip + the FC's own mode-entry delay. The age of the
     * position fix is added on top of this at evaluation time, not folded into it.
     */
    private val ALTITUDE_LATENCY_S = 0.5f
    /** Conservative vertical deceleration (ArduCopter PILOT_ACCEL_Z ≈ 2.5 m/s²). */
    private val ALTITUDE_DECEL_MPS2 = 2.5f

    /**
     * A position fix older than this tells us nothing usable about where the drone is now,
     * so the altitude/range failsafes decline to act on it and warn the pilot instead.
     * Acting on a multi-second-old fix is how a failsafe fires late or in the wrong place.
     */
    private val POSITION_STALE_HARD_MS = 3000L
    private val POSITION_STALE_WARN_INTERVAL_MS = 5000L
    private var lastPositionStaleWarnTime = 0L

    // Max Range failsafe tracking (fixed 300m circular fence, GCS-side only)
    private var maxRangeActionTriggered = false // One-shot per arm cycle, like the other failsafes
    private var lastMaxRangeWarnTime = 0L
    private var lastMaxRangeLimitTime = 0L
    private val MAX_RANGE_WARN_INTERVAL_MS = 4000L
    private val MAX_RANGE_LIMIT_INTERVAL_MS = 5000L
    /** Warn this far inside the RTL trigger point so the pilot can turn back before it fires. */
    private val MAX_RANGE_WARN_LEAD_M = 25f
    /**
     * Smallest internal buffer inside [MAX_RANGE_METERS] where RTL fires — the "10 m internal
     * fence". Applies when the drone is barely moving; anything faster gets the speed-based
     * stopping distance below instead.
     */
    private val MAX_RANGE_MIN_ACTION_MARGIN_M = 10f
    /** Upper clamp so a bogus groundspeed reading can't shrink the usable range to nothing. */
    private val MAX_RANGE_MAX_ACTION_MARGIN_M = 60f
    /**
     * Seconds of latency budgeted between crossing the trigger point and RTL actually biting:
     * telemetry frame age + DO_SET_MODE round trip + the FC's own mode-entry delay.
     */
    private val MAX_RANGE_LATENCY_S = 1.0f
    /**
     * Conservative horizontal deceleration used to project the drone's stopping distance.
     * ArduCopter brakes at roughly ATC_ACCEL_*_MAX / WPNAV_ACCEL (≈2.5 m/s² on this airframe).
     */
    private val MAX_RANGE_DECEL_MPS2 = 2.5f

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
     * When a GCS failsafe caused the RTL its reason is still appended, so replacing the
     * "Max Range" / "Battery Failsafe" popup a second earlier does not hide *why* it happened.
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
     * Altitude ceiling failsafe, backed by the FENCE_ALT_MAX parameter.
     *
     * Why the GCS enforces this rather than leaving it to the FC: the flight controller
     * only acts on FENCE_ALT_MAX when FENCE_ENABLE is on AND bit 0 (altitude) is set in
     * FENCE_TYPE. On a drone flying without a geofence uploaded neither is guaranteed, so
     * the ceiling would silently do nothing. This mirrors how the GCS already owns the
     * critical-battery action (see [handleBatteryVoltageFailsafe]) instead of letting the
     * FC race it.
     *
     * Warning zone: within [ALTITUDE_WARN_MARGIN_M] of the ceiling → TTS + notification only.
     * At/above the ceiling: the configured action (HOVER→BRAKE / RTL / LAND) fires ONCE per
     * arm cycle. One-shot for the same reason the battery action is: a pilot who deliberately
     * takes back control must not be fought on every telemetry frame. TTS keeps repeating.
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
        // in the gap. Both the hard gate and the margin below depend on this.
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

        val actionMargin = altitudeActionMargin(state.climbRate, positionAgeMs)
        val actionThreshold = ceiling - actionMargin

        if (altitude >= actionThreshold) {

            // ═══ PRIORITY GUARDS: never cancel a higher-priority recovery ═══
            // Same reasoning as the voltage failsafe's geofence guard: while the FC is
            // pulling the drone back inside a breached fence, or while the critical-battery
            // action is bringing it home, issuing our own DO_SET_MODE here would override
            // that recovery and strand the drone. Suppress the action WITHOUT consuming the
            // one-shot, and keep warning the pilot.
            // NOTE: tests voltageCriticalActive (the LIVE condition), not the
            // voltageAlertLevel2Triggered one-shot latch. The latch never clears until
            // disarm, so testing it meant a single voltage trigger — including a spurious
            // one — disabled the altitude ceiling for the whole remaining flight.
            val deferReason = when {
                _geofenceEnabled.value && _geofenceViolationDetected.value -> "geofence recovery in progress"
                voltageCriticalActive -> "critical-battery action in progress"
                else -> null
            }
            if (!altitudeLimitActionTriggered && deferReason != null) {
                if (now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS) {
                    lastAltitudeLimitTime = now
                    LogUtils.w("AltitudeFailsafe", "⏸️ Altitude ${altitude}m over action threshold ${actionThreshold}m (ceiling ${ceiling}m) but $deferReason — deferring $action (one-shot NOT consumed)")
                    ttsManager?.speak("Above altitude limit.")
                }
                return
            }

            if (!altitudeLimitActionTriggered) {
                // ═══ FIRST TRIGGER: stop the climb (one-shot per arm cycle) ═══
                altitudeLimitActionTriggered = true
                lastAltitudeLimitTime = now

                LogUtils.i("AltitudeFailsafe", "⛔ ALTITUDE LIMIT: ${altitude}m >= action threshold ${actionThreshold}m (FENCE_ALT_MAX ${ceiling}m, margin ${String.format(Locale.US, "%.1f", actionMargin)}m from climb=${state.climbRate}m/s age=${positionAgeMs}ms) — triggering $action (one-shot), mode=${_telemetryState.value.mode}")

                ttsManager?.speak("Approaching altitude limit. Activating $action.")
                addNotification(
                    Notification(
                        message = "⛔ ALTITUDE LIMIT: ${String.format(Locale.US, "%.0f", altitude)}m ≥ ${String.format(Locale.US, "%.0f", actionThreshold)}m (${String.format(Locale.US, "%.0f", actionMargin)}m margin below ${String.format(Locale.US, "%.0f", ceiling)}m ceiling) — activating $action",
                        type = NotificationType.ERROR
                    )
                )
                showFailsafePopup("Max Altitude")

                viewModelScope.launch {
                    // BRAKE holds both position and altitude, so it is the right "stop
                    // climbing" action; RTL/LAND are offered for pilots who want the
                    // drone brought down instead.
                    val targetMode = when (action.uppercase()) {
                        "RTL" -> MavMode.RTL
                        "LAND" -> MavMode.LAND
                        else -> MavMode.BRAKE
                    }
                    val targetModeName = when (targetMode) {
                        MavMode.RTL -> "RTL"
                        MavMode.LAND -> "LAND"
                        else -> "BRAKE"
                    }

                    executeFailsafeModeChange("AltitudeFailsafe", targetMode, targetModeName)

                    try {
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "ALTITUDE_LIMIT",
                            eventStatus = "CRITICAL",
                            description = "Altitude ${String.format(Locale.US, "%.1f", altitude)}m reached ceiling ${String.format(Locale.US, "%.1f", ceiling)}m - $targetModeName activated"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("AltitudeFailsafe", "Failed to send altitude limit event", e)
                    }
                }
            } else if (now - lastAltitudeLimitTime >= ALTITUDE_LIMIT_INTERVAL_MS) {
                // ═══ REPEAT: TTS only, action already taken this arm cycle ═══
                lastAltitudeLimitTime = now
                LogUtils.i("AltitudeFailsafe", "⛔ Still above ceiling: ${altitude}m >= ${ceiling}m (action already taken this arm cycle)")
                ttsManager?.speak("Above altitude limit. ${altitude.toInt()} meters.")
            }
        }
        // Approaching the ceiling — alert only, so the pilot can level off themselves.
        else if (altitude >= ceiling - ALTITUDE_WARN_MARGIN_M) {
            if (now - lastAltitudeWarnTime >= ALTITUDE_WARN_INTERVAL_MS) {
                lastAltitudeWarnTime = now
                LogUtils.i("AltitudeFailsafe", "⚠️ Approaching altitude limit: ${altitude}m of ${ceiling}m")
                ttsManager?.speak("Approaching altitude limit. ${altitude.toInt()} meters.")
                addNotification(
                    Notification(
                        message = "⚠️ Approaching altitude limit: ${String.format(Locale.US, "%.0f", altitude)}m of ${String.format(Locale.US, "%.0f", ceiling)}m",
                        type = NotificationType.WARNING
                    )
                )
            }
        }
        // NOTE: like the voltage failsafe, altitudeLimitActionTriggered only resets on DISARM,
        // so descending back below the ceiling does not re-arm the action mid-flight.
    }

    /**
     * Max Range failsafe — a fixed [MAX_RANGE_METERS] (300 m) circular fence centred on home,
     * enforced entirely on the GCS side.
     *
     * Deliberately NOT uploaded to the FC as a fence zone: the FC only enforces one active
     * fence at a time, and that slot already holds the mission's rectangular polygon fence
     * (see uploadGeofence). Adding a circular zone there would replace it instead of layering
     * on top of it. Watching the haversine distance from HOME_POSITION here, exactly the way
     * [handleAltitudeFailsafe] watches FENCE_ALT_MAX independently of the polygon fence, keeps
     * the two fences fully independent — a Max Range breach never touches the rectangular
     * fence's state, and vice versa.
     *
     * The RTL trigger sits an internal buffer inside the radius (see [maxRangeActionMargin]) so
     * the drone's momentum never carries it past the limit; [MAX_RANGE_WARN_LEAD_M] before that
     * is a TTS + notification warning zone so the pilot can turn back first.
     *
     * At/beyond the trigger point: RTL fires ONCE per arm cycle (the failsafe explicitly always
     * uses RTL, unlike the configurable altitude/battery actions). One-shot for the same reason
     * as the other failsafes — a pilot who deliberately takes back control must not be fought on
     * every telemetry frame.
     */
    private fun handleMaxRangeFailsafe(state: TelemetryState) {
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
                LogUtils.w("MaxRangeFailsafe", "⚠️ Position fix ${positionAgeMs}ms stale — max range failsafe cannot evaluate (dist=${distance}m)")
                ttsManager?.speak("Telemetry delayed")
            }
            return
        }

        val margin = maxRangeActionMargin(state.groundspeed, positionAgeMs)
        val actionThreshold = MAX_RANGE_METERS - margin
        val warnThreshold = actionThreshold - MAX_RANGE_WARN_LEAD_M

        if (distance >= actionThreshold) {

            // ═══ PRIORITY GUARD: never cancel a rectangular-fence recovery in progress ═══
            // Same reasoning as the altitude ceiling's geofence guard — while the FC is
            // pulling the drone back inside a breached polygon fence, issuing our own RTL
            // here would override that recovery. Suppress WITHOUT consuming the one-shot.
            if (!maxRangeActionTriggered && _geofenceEnabled.value && _geofenceViolationDetected.value) {
                if (now - lastMaxRangeLimitTime >= MAX_RANGE_LIMIT_INTERVAL_MS) {
                    lastMaxRangeLimitTime = now
                    LogUtils.w("MaxRangeFailsafe", "⏸️ Range ${distance}m over action threshold ${actionThreshold}m (limit ${MAX_RANGE_METERS}m, margin ${margin}m) but geofence recovery in progress — deferring RTL (one-shot NOT consumed)")
                    ttsManager?.speak("Max range exceeded. Returning inside the fence first.")
                }
                return
            }

            if (!maxRangeActionTriggered) {
                // ═══ FIRST TRIGGER: RTL (one-shot per arm cycle) ═══
                maxRangeActionTriggered = true
                lastMaxRangeLimitTime = now

                LogUtils.i("MaxRangeFailsafe", "⛔ MAX RANGE: ${distance}m >= action threshold ${actionThreshold}m (limit ${MAX_RANGE_METERS}m, ${margin}m margin at ${state.groundspeed}m/s) — triggering RTL (one-shot), mode=${state.mode}")

                ttsManager?.speak("Approaching max range. Returning to launch.")
                addNotification(
                    Notification(
                        message = "⛔ MAX RANGE: ${String.format(Locale.US, "%.0f", distance)}m ≥ ${String.format(Locale.US, "%.0f", actionThreshold)}m (${String.format(Locale.US, "%.0f", margin)}m margin below ${String.format(Locale.US, "%.0f", MAX_RANGE_METERS)}m limit) — activating RTL",
                        type = NotificationType.ERROR
                    )
                )
                showFailsafePopup("Max Range")

                viewModelScope.launch {
                    executeFailsafeModeChange("MaxRangeFailsafe", MavMode.RTL, "RTL")

                    try {
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MAX_RANGE",
                            eventStatus = "CRITICAL",
                            description = "Range ${String.format(Locale.US, "%.1f", distance)}m reached limit ${String.format(Locale.US, "%.1f", MAX_RANGE_METERS)}m - RTL activated"
                        )
                    } catch (e: Exception) {
                        LogUtils.e("MaxRangeFailsafe", "Failed to send max range event", e)
                    }
                }
            } else if (now - lastMaxRangeLimitTime >= MAX_RANGE_LIMIT_INTERVAL_MS) {
                // ═══ REPEAT: TTS only, action already taken this arm cycle ═══
                lastMaxRangeLimitTime = now
                LogUtils.i("MaxRangeFailsafe", "⛔ Still beyond max range: ${distance}m >= ${MAX_RANGE_METERS}m (action already taken this arm cycle)")
                ttsManager?.speak("Beyond max range. ${distance.toInt()} meters.")
            }
        }
        // Approaching the trigger point — alert only, so the pilot can turn back themselves.
        else if (distance >= warnThreshold) {
            if (now - lastMaxRangeWarnTime >= MAX_RANGE_WARN_INTERVAL_MS) {
                lastMaxRangeWarnTime = now
                LogUtils.i("MaxRangeFailsafe", "⚠️ Approaching max range: ${distance}m of ${MAX_RANGE_METERS}m (RTL at ${actionThreshold}m)")
                ttsManager?.speak("Approaching max range. ${distance.toInt()} meters.")
                addNotification(
                    Notification(
                        message = "⚠️ Approaching max range: ${String.format(Locale.US, "%.0f", distance)}m of ${String.format(Locale.US, "%.0f", MAX_RANGE_METERS)}m",
                        type = NotificationType.WARNING
                    )
                )
            }
        }
        // NOTE: like the other failsafes, maxRangeActionTriggered only resets on DISARM, so
        // flying back inside the radius does not re-arm the action mid-flight.
    }

    /**
     * How far inside [MAX_RANGE_METERS] the RTL trigger sits, in metres.
     *
     * A fixed buffer does not work: RTL is not instantaneous, so the drone keeps flying outward
     * for the command latency and then for its braking distance. With the old flat 4 m buffer a
     * drone cruising at 8 m/s crossed the 300 m limit by ~10 m before it turned around. The
     * margin therefore tracks speed:
     *
     *     margin = v · [MAX_RANGE_LATENCY_S] + v² / (2 · [MAX_RANGE_DECEL_MPS2])
     *
     * clamped to [[MAX_RANGE_MIN_ACTION_MARGIN_M], [MAX_RANGE_MAX_ACTION_MARGIN_M]]. At 8 m/s
     * that is 8 + 12.8 ≈ 21 m, so RTL fires around 279 m and the ~14 m of real-world overshoot
     * lands well inside 300 m. Hovering or drifting slowly falls back to the 10 m floor.
     *
     * groundspeed is used rather than airspeed because the fence is a ground-frame distance from
     * home; a null/garbage reading degrades to the 10 m floor rather than disabling the failsafe.
     *
     * positionAgeMs adds the distance already flown since the fix we are judging was measured.
     * [MAX_RANGE_LATENCY_S] was implicitly absorbing some of that staleness at a fixed 1 s;
     * making the age explicit means the margin grows when the link actually degrades instead
     * of silently under-budgeting.
     */
    private fun maxRangeActionMargin(groundspeed: Float?, positionAgeMs: Long = 0L): Float {
        val v = groundspeed?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val ageS = positionAgeMs.coerceAtLeast(0L) / 1000f
        val stoppingDistance = v * (ageS + MAX_RANGE_LATENCY_S) + (v * v) / (2f * MAX_RANGE_DECEL_MPS2)
        return stoppingDistance.coerceIn(MAX_RANGE_MIN_ACTION_MARGIN_M, MAX_RANGE_MAX_ACTION_MARGIN_M)
    }

    /**
     * How far below the ceiling the altitude action fires, in metres.
     *
     * The old flat 4 m buffer assumed the altitude being judged was current. It usually was —
     * until a telemetry link carrying live rangefinder traffic saturated and GLOBAL_POSITION_INT
     * degraded from 10 Hz to 1-2 Hz. The drone then climbed for up to a second past the reading
     * the failsafe was still looking at, and sailed ~1 m over the ceiling before BRAKE bit.
     *
     * Three terms, mirroring [maxRangeActionMargin] but with the fix age made explicit:
     *
     *     margin = v · (age + [ALTITUDE_LATENCY_S]) + v² / (2 · [ALTITUDE_DECEL_MPS2])
     *
     * clamped to [[ALTITUDE_MIN_ACTION_MARGIN_M], [ALTITUDE_MAX_ACTION_MARGIN_M]]. At 2 m/s with
     * a fresh fix that is 2·0.5 + 0.8 = 1.8 m → the 4 m floor, i.e. unchanged from before. At
     * 5 m/s with a 0.8 s-stale fix it is 5·1.3 + 5 = 11.5 m, which is what actually prevents the
     * overshoot. Only a positive climb rate counts: descending toward the ceiling is not a risk.
     */
    private fun altitudeActionMargin(climbRate: Float?, positionAgeMs: Long): Float {
        val v = climbRate?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val ageS = positionAgeMs.coerceAtLeast(0L) / 1000f
        val travel = v * (ageS + ALTITUDE_LATENCY_S) + (v * v) / (2f * ALTITUDE_DECEL_MPS2)
        return travel.coerceIn(ALTITUDE_MIN_ACTION_MARGIN_M, ALTITUDE_MAX_ACTION_MARGIN_M)
    }

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
        // Signal GcsMap to clear the local drone path trail
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

    // Trigger to clear the drone's drawn flight path in GcsMap (incremented each time clear is requested)
    private val _clearDronePathTrigger = MutableStateFlow(0)
    val clearDronePathTrigger: StateFlow<Int> = _clearDronePathTrigger.asStateFlow()

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

            // Use the new Mission Planner-style upload
            val config = FenceConfiguration(
                zones = listOf(FenceZone.Polygon(points = polygon, isInclusion = true)),
                // The FC's alt fence is biased slightly BELOW the pilot's ceiling, because
                // ArduPilot arrests the climb after detecting the breach and coasts past the
                // limit. See getFcAltitudeFenceMax().
                altitudeMax = getFcAltitudeFenceMax()
                    ?: (DEFAULT_MAX_ALTITUDE_M - FC_ALT_FENCE_SAFETY_OFFSET_M),
                action = FenceAction.BRAKE,  // Recommended for spray drones
                margin = 3.0f
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

    private val _sprayRate = MutableStateFlow(100f) // 10% to 100%
    val sprayRate: StateFlow<Float> = _sprayRate.asStateFlow()

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

        // Hide the popup
        _showAddResumeHerePopup.value = false

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
     * Process the resume point - retrieves and uploads modified mission
     * This runs in the background after user confirms
     */
    private fun processResumePoint(waypointNumber: Int) {
        viewModelScope.launch {
            LogUtils.i("SharedVM", "═══════════════════════════════════════")
            LogUtils.i("SharedVM", "=== AUTO PROCESSING RESUME POINT (BACKGROUND) ===")
            LogUtils.i("SharedVM", "Resume waypoint: $waypointNumber")

            // Get the resume location (where drone was paused)
            val resumeLocation = effectiveResumeLocation()
            LogUtils.i("SharedVM", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
            LogUtils.i("SharedVM", "═══════════════════════════════════════")

            try {
                // Step 1: Check connection
                if (!_telemetryState.value.connected) {
                    LogUtils.e("SharedVM", "Not connected to FC - skipping auto resume processing")
                    return@launch
                }

                // Step 2: Get current mission from FC (silent - no progress updates)
                LogUtils.i("SharedVM", "Retrieving mission from FC (background)...")
                val allWaypoints = repo?.getAllWaypoints()
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    LogUtils.e("SharedVM", "Failed to retrieve mission from FC")
                    return@launch
                }

                LogUtils.i("SharedVM", "Retrieved ${allWaypoints.size} waypoints from FC")

                // Step 3: Filter waypoints from resume point, inserting resume location as first WP
                LogUtils.i("SharedVM", "Filtering waypoints from resume point (background)...")
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    waypointNumber,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude,
                    restoreSpray = _sprayWasActiveBeforePause
                )
                if (filtered == null || filtered.isEmpty()) {
                    LogUtils.e("SharedVM", "Filtering resulted in empty mission")
                    return@launch
                }

                LogUtils.i("SharedVM", "Filtered to ${filtered.size} waypoints")

                // Step 4: Resequence waypoints
                LogUtils.i("SharedVM", "Resequencing waypoints (background)...")
                val resequenced = repo?.resequenceWaypoints(filtered)
                if (resequenced == null || resequenced.isEmpty()) {
                    LogUtils.e("SharedVM", "Resequencing failed")
                    return@launch
                }

                LogUtils.i("SharedVM", "Resequenced to ${resequenced.size} waypoints")

                // Step 5: Validate sequence numbers
                val sequences = resequenced.map { it.seq.toInt() }
                val expectedSequences = (0 until resequenced.size).toList()
                if (sequences != expectedSequences) {
                    LogUtils.e("SharedVM", "❌ Invalid sequence numbers!")
                    return@launch
                }
                LogUtils.i("SharedVM", "✅ Sequence validation passed")

                // Step 6: Upload modified mission to FC (silent)
                LogUtils.i("SharedVM", "Uploading modified mission to FC (background)...")
                val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
                if (!uploadSuccess) {
                    LogUtils.e("SharedVM", "❌ Mission upload failed")
                    return@launch
                }

                LogUtils.i("SharedVM", "✅ Modified mission uploaded to FC")

                delay(500)

                // Step 7: Set current waypoint to 1
                val setWpResult = repo?.setCurrentWaypoint(1) ?: false
                if (setWpResult) {
                    LogUtils.i("SharedVM", "✅ Current waypoint set to 1")
                } else {
                    LogUtils.w("SharedVM", "⚠️ Failed to set current waypoint, continuing anyway")
                }

                // Mark that resume mission is ready
                _resumeMissionReady.value = true
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                lastUploadedMissionItems = resequenced.toList()

                LogUtils.i("SharedVM", "═══════════════════════════════════════")
                LogUtils.i("SharedVM", "✅ Resume mission ready (background processing complete)")
                LogUtils.i("SharedVM", "═══════════════════════════════════════")

            } catch (e: Exception) {
                LogUtils.e("SharedVM", "Failed to auto-process resume point", e)
            }
        }
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

            // Get the resume location (where drone was paused)
            val resumeLocation = effectiveResumeLocation()

            LogUtils.i("ResumeMission", "═══════════════════════════════════════")
            LogUtils.i("ResumeMission", "=== CONFIRM ADD RESUME HERE ===")
            LogUtils.i("ResumeMission", "Resume waypoint: $resumeWaypoint")
            LogUtils.i("ResumeMission", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
            LogUtils.i("ResumeMission", "═══════════════════════════════════════")

            _showAddResumeHerePopup.value = false

            try {
                // Step 1: Check connection
                onProgress("Checking connection...")
                if (!_telemetryState.value.connected) {
                    LogUtils.e("SharedVM", "Not connected to FC")
                    onResult(false, "Not connected to flight controller")
                    return@launch
                }

                onProgress("Retrieving mission from FC...")

                // Step 2: Get current mission from FC
                val allWaypoints = repo?.getAllWaypoints()
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    LogUtils.e("SharedVM", "Failed to retrieve mission from FC")
                    onResult(false, "Failed to retrieve mission from flight controller")
                    return@launch
                }

                LogUtils.i("SharedVM", "Retrieved ${allWaypoints.size} waypoints from FC")

                // Log original mission
                LogUtils.i("SharedVM", "--- Original Mission ---")
                allWaypoints.forEach { wp ->
                    val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
                    LogUtils.i("SharedVM", "  seq=${wp.seq}: $cmdName frame=${wp.frame.value} current=${wp.current}")
                }

                onProgress("Filtering waypoints from resume point...")

                // Step 3: Filter waypoints from resume point, inserting resume location as first WP
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    resumeWaypoint,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude,
                    restoreSpray = _sprayWasActiveBeforePause
                )
                if (filtered == null || filtered.isEmpty()) {
                    LogUtils.e("SharedVM", "Filtering resulted in empty mission")
                    onResult(false, "No waypoints after resume point")
                    return@launch
                }

                LogUtils.i("SharedVM", "Filtered to ${filtered.size} waypoints")

                onProgress("Resequencing waypoints...")

                // Step 4: Resequence waypoints
                val resequenced = repo?.resequenceWaypoints(filtered)
                if (resequenced == null || resequenced.isEmpty()) {
                    LogUtils.e("SharedVM", "Resequencing failed")
                    onResult(false, "Failed to resequence waypoints")
                    return@launch
                }

                LogUtils.i("SharedVM", "Resequenced to ${resequenced.size} waypoints")

                // Log final mission structure
                LogUtils.i("SharedVM", "--- Final Resume Mission ---")
                resequenced.forEach { wp ->
                    val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
                    LogUtils.i("SharedVM", "  seq=${wp.seq}: $cmdName frame=${wp.frame.value} alt=${wp.z}m target=${wp.targetSystem}:${wp.targetComponent}")
                }

                // Step 5: Validate sequence numbers (skip TAKEOFF validation for resume)
                onProgress("Validating mission...")
                val sequences = resequenced.map { it.seq.toInt() }
                val expectedSequences = (0 until resequenced.size).toList()
                if (sequences != expectedSequences) {
                    LogUtils.e("SharedVM", "❌ Invalid sequence numbers!")
                    LogUtils.e("SharedVM", "Expected: $expectedSequences, Got: $sequences")
                    onResult(false, "Invalid mission sequence")
                    return@launch
                }
                LogUtils.i("SharedVM", "✅ Sequence validation passed")

                onProgress("Uploading modified mission to FC...")

                // Step 6: Upload modified mission to FC
                val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
                if (uploadSuccess) {
                    LogUtils.i("SharedVM", "✅ Modified mission uploaded to FC")

                    // Verify by reading back the mission count
                    // Delay allows FC to fully commit mission to storage before verification
                    delay(1000)
                    val verifyCount = repo?.getMissionCount() ?: 0
                    if (verifyCount != resequenced.size) {
                        LogUtils.e("SharedVM", "⚠️ WARNING: FC reports $verifyCount waypoints but we uploaded ${resequenced.size}")
                    } else {
                        LogUtils.i("SharedVM", "✅ FC confirms $verifyCount waypoints stored")
                    }
                } else {
                    LogUtils.e("SharedVM", "❌ Mission upload FAILED - FC rejected mission")
                    onResult(false, "Mission upload failed - flight controller rejected mission")
                    return@launch
                }

                // Step 7: Set Current Waypoint to start execution
                onProgress("Setting current waypoint...")
                LogUtils.i("SharedVM", "Setting current waypoint to 1 (start from first mission item after HOME)")
                val setWaypointSuccess = repo?.setCurrentWaypoint(1) ?: false

                if (!setWaypointSuccess) {
                    LogUtils.w("SharedVM", "Failed to set current waypoint, continuing anyway")
                }

                delay(500)

                // Make sure the pump is off before the drone starts flying back to the resume
                // point. The mission's DO_SPRAYER(1) turns it on again on arrival.
                ensureSprayerOffForTransit()

                // Step 8: Switch to AUTO Mode
                onProgress("Switching to AUTO mode...")
                val currentMode = _telemetryState.value.mode
                LogUtils.i("SharedVM", "Current mode: $currentMode")

                var autoSuccess = false
                var retryCount = 0
                val maxRetries = 3

                while (!autoSuccess && retryCount < maxRetries) {
                    val attempt = retryCount + 1
                    LogUtils.i("SharedVM", "Attempt $attempt/$maxRetries: Sending AUTO mode command...")

                    autoSuccess = repo?.changeMode(MavMode.AUTO) ?: false

                    LogUtils.i("SharedVM", "Attempt $attempt result: ${if (autoSuccess) "SUCCESS" else "FAILED"}")

                    if (!autoSuccess) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            LogUtils.w("SharedVM", "Waiting 2 seconds before retry...")
                            delay(2000)
                        }
                    }
                }

                if (!autoSuccess) {
                    val finalMode = _telemetryState.value.mode
                    LogUtils.e("SharedVM", "❌ Failed to switch to AUTO after $maxRetries attempts")
                    LogUtils.e("SharedVM", "Final mode: $finalMode")
                    onResult(false, "Failed to switch to AUTO. Stuck in: $finalMode")
                    return@launch
                }

                LogUtils.i("SharedVM", "✅ Successfully switched to AUTO mode")

                // Complete: Update state
                onProgress("Mission resumed!")
                _telemetryState.update {
                    it.copy(
                        missionPaused = false,
                        pausedAtWaypoint = null
                    )
                }
                _pendingResumeLocation = null
                _missionPauseLocation = null

                // ✅ Send mission status RESUMED to backend (crash-safe)
                try {
                    WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                    WebSocketManager.getInstance().sendMissionEvent(
                        eventType = "MISSION_RESUMED",
                        eventStatus = "INFO",
                        description = "Mission resumed"
                    )
                } catch (e: Exception) {
                    LogUtils.e("SharedVM", "Failed to send RESUMED status", e)
                }

                // Mark mission as uploaded
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                lastUploadedMissionItems = resequenced.toList()
                LogUtils.i("SharedVM", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // ✅ Spray restore is handled by the uploaded mission's DO_SPRAYER items
                // (see filterWaypointsForResume) — off for the transit leg, back on when the
                // drone reaches the resume waypoint. Do NOT fire an immediate spray-on here.
                if (_sprayWasActiveBeforePause) {
                    LogUtils.i("SharedVM", "💧 Spray will resume at waypoint $resumeWaypoint via the mission's DO_SPRAYER item (off during transit)")
                    _sprayWasActiveBeforePause = false
                }

                // Complete
                addNotification(
                    Notification(
                        message = "Mission resumed from waypoint $resumeWaypoint",
                        type = NotificationType.SUCCESS
                    )
                )
                ttsManager?.announceMissionResumed()

                LogUtils.i("ResumeMission", "═══════════════════════════════════════")
                LogUtils.i("ResumeMission", "✅ Resume Mission Complete!")
                LogUtils.i("ResumeMission", "═══════════════════════════════════════")

                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ResumeMission", "❌ Resume mission failed", e)
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
            try {
                onProgress("Step 1/6: Pre-flight checks...")
                if (!_telemetryState.value.connected) {
                    onResult(false, "Not connected to flight controller")
                    return@launch
                }

                onProgress("Step 2/6: Retrieving mission from flight controller...")
                val allWaypoints = repo?.getAllWaypoints()
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    onResult(false, "Failed to retrieve mission from flight controller")
                    return@launch
                }
                LogUtils.i("ManualResume", "Retrieved ${allWaypoints.size} waypoints from FC")

                // Build an ordered list of NAV waypoints (have real lat/lng coords, seq > 0)
                onProgress("Step 3/6: Locating resume segment in mission...")
                val navWaypoints = allWaypoints
                    .filter { it.seq.toInt() > 0 && it.x != 0 && it.y != 0 }
                    .sortedBy { it.seq.toInt() }

                // Find which segment (navWaypoints[i] → navWaypoints[i+1]) is closest to the
                // dropped pin. Use the perpendicular distance from point to line segment.
                // The segment END (navWaypoints[i+1]) becomes the resumeWaypointSeq so the
                // drone flies: exact resume point → segment end → rest of mission (no backtrack).
                val resumeWaypointSeq: Int
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
                    resumeWaypointSeq = bestSegEndSeq
                } else {
                    resumeWaypointSeq = navWaypoints.firstOrNull()?.seq?.toInt() ?: 1
                }

                LogUtils.i("ManualResume", "Resume segment end seq=$resumeWaypointSeq → drone will fly to exact point then continue from WP$resumeWaypointSeq")

                onProgress("Step 4/6: Filtering waypoints for resume...")
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    resumeWaypointSeq,
                    resumeLatitude = lat,
                    resumeLongitude = lng,
                    restoreSpray = _sprayWasActiveBeforePause
                )
                if (filtered == null || filtered.isEmpty()) {
                    onResult(false, "Mission filtering failed - no waypoints to resume")
                    return@launch
                }

                val resequenced = repo?.resequenceWaypoints(filtered)
                if (resequenced == null || resequenced.isEmpty()) {
                    onResult(false, "Mission resequencing failed")
                    return@launch
                }
                LogUtils.i("ManualResume", "Resequenced to ${resequenced.size} waypoints")

                onProgress("Step 5/6: Uploading modified mission to flight controller...")
                val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
                if (!uploadSuccess) {
                    LogUtils.e("ManualResume", "❌ Mission upload FAILED")
                    onResult(false, "Mission upload failed - flight controller rejected mission")
                    return@launch
                }
                LogUtils.i("ManualResume", "✅ Manual resume mission uploaded to FC")

                delay(500)
                repo?.setCurrentWaypoint(1)

                _resumeMissionReady.value = true
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                lastUploadedMissionItems = resequenced.toList()

                onProgress("Step 6/6: Done!")
                addNotification(Notification("Resume point set — mission ready to resume", NotificationType.SUCCESS))
                LogUtils.i("ManualResume", "✅ Manual resume mission ready")
                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ManualResume", "Failed to process manual resume point", e)
                onResult(false, e.message)
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

                // Send mission start command
                val startSuccess = repo?.startMission() ?: false

                if (startSuccess) {
                    LogUtils.i("SharedVM", "✅ Resume mission started successfully")
                    _resumeMissionReady.value = false
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
                    LogUtils.e("SharedVM", "Failed to start resume mission")
                    addNotification(
                        Notification(
                            message = "Failed to start resume mission",
                            type = NotificationType.ERROR
                        )
                    )
                }
            }
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
                val waypointToStore = repo?.currentMissionTargetSeq()
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
                // Get the resume location (where drone was paused)
                val resumeLocation = effectiveResumeLocation()

                LogUtils.i("ResumeMission", "═══════════════════════════════════════")
                LogUtils.i("ResumeMission", "Starting Resume Mission")
                LogUtils.i("ResumeMission", "Resume at waypoint: $resumeWaypointNumber")
                LogUtils.i("ResumeMission", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
                LogUtils.i("ResumeMission", "═══════════════════════════════════════")

                // Step 1: Pre-flight Checks
                onProgress("Step 1/8: Pre-flight checks...")
                if (!_telemetryState.value.connected) {
                    onResult(false, "Not connected to flight controller")
                    return@launch
                }

                // Step 2: Retrieve Current Mission from FC
                onProgress("Step 2/8: Retrieving mission from flight controller...")
                LogUtils.i("ResumeMission", "Retrieving current mission from FC...")
                val allWaypoints = repo?.getAllWaypoints()

                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    LogUtils.e("ResumeMission", "❌ Failed to retrieve mission from FC")
                    onResult(false, "Failed to retrieve mission from flight controller")
                    return@launch
                }

                // Log original mission structure
                LogUtils.i("ResumeMission", "════════════════════════════════")
                LogUtils.i("ResumeMission", "Original mission count: ${allWaypoints.size}")
                LogUtils.i("ResumeMission", "Resume from waypoint: $resumeWaypointNumber")
                allWaypoints.forEach { wp ->
                    LogUtils.i("ResumeMission", "  Original: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }

                // Step 3: Filter Waypoints for Resume, inserting resume location as first WP
                onProgress("Step 3/8: Filtering waypoints...")
                LogUtils.i("ResumeMission", "Filtering waypoints for resume from waypoint $resumeWaypointNumber...")
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    resumeWaypointNumber,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude,
                    restoreSpray = _sprayWasActiveBeforePause
                )

                if (filtered == null || filtered.isEmpty()) {
                    LogUtils.e("ResumeMission", "❌ Filtering resulted in empty mission")
                    onResult(false, "Mission filtering failed - no waypoints to resume")
                    return@launch
                }

                LogUtils.i("ResumeMission", "────────────────────────────────")
                LogUtils.i("ResumeMission", "Filtered mission count: ${filtered.size}")
                filtered.forEach { wp ->
                    LogUtils.i("ResumeMission", "  Filtered: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }

                // Step 4: Resequence Waypoints
                onProgress("Step 4/8: Resequencing waypoints...")
                LogUtils.i("ResumeMission", "Resequencing waypoints...")
                val resequenced = repo?.resequenceWaypoints(filtered)

                if (resequenced == null || resequenced.isEmpty()) {
                    LogUtils.e("ResumeMission", "❌ Resequencing resulted in empty mission")
                    onResult(false, "Mission resequencing failed")
                    return@launch
                }

                LogUtils.i("ResumeMission", "────────────────────────────────")
                LogUtils.i("ResumeMission", "Resequenced mission count: ${resequenced.size}")
                resequenced.forEach { wp ->
                    LogUtils.i("ResumeMission", "  Resequenced: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }
                LogUtils.i("ResumeMission", "════════════════════════════════")

                // Step 5: Validate Mission Structure
                onProgress("Step 5/8: Validating mission...")
                val (isValid, validationError) = validateMissionStructure(resequenced)
                if (!isValid) {
                    LogUtils.e("ResumeMission", "❌ Mission validation failed: $validationError")
                    onResult(false, "Mission validation failed: $validationError")
                    return@launch
                }
                LogUtils.i("ResumeMission", "✅ Mission validation passed")

                // Step 6: Upload Modified Mission to FC
                onProgress("Step 6/8: Uploading mission to flight controller...")
                LogUtils.i("ResumeMission", "Uploading ${resequenced.size} waypoints to FC...")
                val success = repo?.uploadMissionWithAck(resequenced) ?: false

                if (success) {
                    LogUtils.i("ResumeMission", "✅ Mission upload confirmed by FC")

                    // Verify by reading back the mission count
                    // Delay allows FC to fully commit mission to storage before verification
                    delay(1000)
                    val verifyCount = repo?.getMissionCount() ?: 0
                    if (verifyCount != resequenced.size) {
                        LogUtils.e("ResumeMission", "⚠️ WARNING: FC reports $verifyCount waypoints but we uploaded ${resequenced.size}")
                    } else {
                        LogUtils.i("ResumeMission", "✅ FC confirms $verifyCount waypoints stored")
                    }
                } else {
                    LogUtils.e("ResumeMission", "❌ Mission upload FAILED - FC rejected mission")
                    onResult(false, "Mission upload failed - flight controller rejected mission")
                    return@launch
                }

                // Step 7: Set Current Waypoint to start execution
                onProgress("Step 7/8: Setting current waypoint...")
                LogUtils.i("ResumeMission", "Setting current waypoint to 1 (start from first mission item after HOME)")
                val setWaypointSuccess = repo?.setCurrentWaypoint(1) ?: false

                if (!setWaypointSuccess) {
                    LogUtils.w("ResumeMission", "Failed to set current waypoint, continuing anyway")
                }

                delay(500)

                // Make sure the pump is off before the drone starts flying back to the resume
                // point. The mission's DO_SPRAYER(1) turns it on again on arrival.
                ensureSprayerOffForTransit()

                // Step 8: Switch to AUTO Mode
                onProgress("Step 8/8: Switching to AUTO mode...")
                val currentMode = _telemetryState.value.mode
                LogUtils.i("ResumeMission", "Current mode: $currentMode")

                var autoSuccess = false
                var retryCount = 0
                val maxRetries = 3

                while (!autoSuccess && retryCount < maxRetries) {
                    val attempt = retryCount + 1
                    LogUtils.i("ResumeMission", "Attempt $attempt/$maxRetries: Sending AUTO mode command...")

                    autoSuccess = repo?.changeMode(MavMode.AUTO) ?: false

                    LogUtils.i("ResumeMission", "Attempt $attempt result: ${if (autoSuccess) "SUCCESS" else "FAILED"}")

                    if (!autoSuccess) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            LogUtils.w("ResumeMission", "Waiting 2 seconds before retry...")
                            delay(2000)
                        }
                    }
                }

                if (!autoSuccess) {
                    val finalMode = _telemetryState.value.mode
                    LogUtils.e("ResumeMission", "❌ Failed to switch to AUTO after $maxRetries attempts")
                    LogUtils.e("ResumeMission", "Final mode: $finalMode")
                    onResult(false, "Failed to switch to AUTO. Stuck in: $finalMode")
                    return@launch
                }

                LogUtils.i("ResumeMission", "✅ Successfully switched to AUTO mode")

                // Complete: Update state
                onProgress("Mission resumed!")
                _telemetryState.update {
                    it.copy(
                        missionPaused = false,
                        pausedAtWaypoint = null
                    )
                }
                _pendingResumeLocation = null
                _missionPauseLocation = null

                // ✅ Send mission status RESUMED to backend (crash-safe)
                try {
                    WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                    WebSocketManager.getInstance().sendMissionEvent(
                        eventType = "MISSION_RESUMED",
                        eventStatus = "INFO",
                        description = "Mission resumed"
                    )
                } catch (e: Exception) {
                    LogUtils.e("SharedVM", "Failed to send RESUMED status", e)
                }

                // Mark mission as uploaded
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                lastUploadedMissionItems = resequenced.toList()
                LogUtils.i("ResumeMission", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // ✅ Spray restore is handled BY THE MISSION, not from here.
                // filterWaypointsForResume already bracketed the resume waypoint with
                // DO_SPRAYER(0) → resume WP → DO_SPRAYER(1), so the FC turns the pump back
                // on only once the drone has actually arrived at the resume point. Firing
                // setSprayEnabled(true) here as well is what made it spray the whole transit
                // leg from wherever the pilot left the drone back to that point.
                if (_sprayWasActiveBeforePause) {
                    LogUtils.i("ResumeMission", "💧 Spray will resume at waypoint $resumeWaypointNumber via the mission's DO_SPRAYER item (off during transit)")
                    _sprayWasActiveBeforePause = false
                }

                // Complete
                addNotification(
                    Notification(
                        message = "Mission resumed from waypoint $resumeWaypointNumber - Switch to AUTO to resume",
                        type = NotificationType.SUCCESS
                    )
                )
                ttsManager?.announceMissionResumed()

                LogUtils.i("ResumeMission", "═══════════════════════════════════════")
                LogUtils.i("ResumeMission", "✅ Resume Mission Complete!")
                LogUtils.i("ResumeMission", "═══════════════════════════════════════")

                onResult(true, null)

            } catch (e: Exception) {
                LogUtils.e("ResumeMission", "❌ Resume mission failed", e)
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

    // Serializes rate writes so two overlapping applies can't reach the FC out of order.
    private val sprayRateWriteMutex = kotlinx.coroutines.sync.Mutex()

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
            // Read the slider HERE, not at call time: a write that queued behind the mutex must
            // push the rate the pilot ended up on, not the one current when it was queued.
            val pumpRate = _sprayRate.value.coerceIn(SPRAY_PUMP_RATE_MIN, SPRAY_PUMP_RATE_MAX)

            // PARAM_SET is fire-and-forget and the link can drop it, so an unconfirmed write
            // gets one resend rather than silently leaving the FC on the old rate.
            var rateAck = setParameter("SPRAY_PUMP_RATE", pumpRate)
            if (rateAck == null) {
                LogUtils.w("SprayControl", "⚠ SPRAY_PUMP_RATE=$pumpRate unconfirmed — resending")
                rateAck = setParameter("SPRAY_PUMP_RATE", pumpRate)
            }
            LogUtils.i("SprayControl",
                "🚿 Spray rate ${pumpRate.toInt()}% → SPRAY_PUMP_RATE=$pumpRate " +
                (if (rateAck != null) "(confirmed)" else "(no confirmation)"))
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

        // Debounce the actual command send to avoid flooding FC when slider moves rapidly.
        // Always push SPRAY_PUMP_RATE to the FC (regardless of RC7 / flight mode): the parameter
        // takes effect immediately for the ongoing mission's DO_SPRAYER, so this is what makes
        // changing the slider mid-mission actually change the spray. We do NOT toggle DO_SPRAYER
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
                LogUtils.d("SprayControl", "Rate set to ${newRate.toInt()}% (not connected; will apply when connected)")
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

        // Max Range failsafe: fixed circular fence, radius from home (metres). This is a
        // GCS-side-only check — deliberately NOT uploaded to the FC as a FENCE_RADIUS/Circle
        // zone, because the FC only has one active fence slot and that slot already holds the
        // mission's rectangular polygon fence (see uploadGeofence). Reusing it here would
        // silently replace the polygon fence instead of layering on top of it. Monitoring the
        // distance from home on the GCS side, the same way the altitude ceiling failsafe
        // monitors FENCE_ALT_MAX, keeps the two fences fully independent.
        const val MAX_RANGE_METERS = 300.0f
    }

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
                    LogUtils.i("Geofence", "Connection lost - fence monitoring stopped, fence state reset")

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

                // ═══ Altitude ceiling (FENCE_ALT_MAX) ═══
                // Hybrid, like the radar thresholds: if the pilot has explicitly set a
                // ceiling we push it to the FC so both layers agree; if they haven't, we
                // seed the local setting from whatever the FC already has instead of
                // overwriting a value someone configured in Mission Planner.
                syncAltitudeCeilingOnConnect(prefs)

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
     * Keep the GCS altitude ceiling and the FC's FENCE_ALT_MAX in agreement on connect.
     *
     * The GCS is the primary enforcer (see [handleAltitudeFailsafe]) because the FC's
     * altitude fence is inert unless FENCE_ENABLE is on, but FENCE_ALT_MAX is still worth
     * keeping correct: the moment a geofence *is* enabled the FC becomes a second layer,
     * and it must not be guarding some stale ceiling from a previous configuration.
     *
     * NOTE: this deliberately does NOT touch FENCE_ENABLE or FENCE_TYPE. Those are owned
     * by the geofence upload flow, and forcing them on here would change fence/pre-arm
     * behaviour for drones flying without a geofence.
     */
    private suspend fun syncAltitudeCeilingOnConnect(prefs: android.content.SharedPreferences) {
        try {
            if (!prefs.getBoolean("max_altitude_enabled", true)) {
                LogUtils.i("OptionsSync", "Altitude ceiling failsafe disabled — skipping FENCE_ALT_MAX sync")
                return
            }

            if (prefs.contains("max_altitude")) {
                val ceiling = prefs.getFloat("max_altitude", DEFAULT_MAX_ALTITUDE_M)
                if (ceiling <= 0f) return
                // Biased below the pilot's ceiling so ArduPilot's climb-arrest overshoot
                // lands under it rather than over. See getFcAltitudeFenceMax().
                val fcLimit = (ceiling - FC_ALT_FENCE_SAFETY_OFFSET_M).coerceAtLeast(1f)
                if (setParameter("FENCE_ALT_MAX", fcLimit) != null) {
                    LogUtils.i("OptionsSync", "✓ FENCE_ALT_MAX = $fcLimit m (ceiling ${ceiling}m less ${FC_ALT_FENCE_SAFETY_OFFSET_M}m overshoot allowance)")
                } else {
                    LogUtils.e("OptionsSync", "✗ Failed to set FENCE_ALT_MAX")
                }
            } else {
                // No explicit pilot setting yet — adopt the FC's value so the GCS ceiling
                // matches what the vehicle was already configured with.
                val fcValue = readParameter("FENCE_ALT_MAX", timeoutMs = 4000L)
                if (fcValue != null && fcValue > 0f) {
                    // FENCE_ALT_MAX carries the safety offset (see getFcAltitudeFenceMax), so
                    // add it back to recover the ceiling the pilot actually means. Without
                    // this the displayed/enforced ceiling would sit a metre low, and would
                    // creep down again on every reconnect that re-seeds from the FC.
                    val ceiling = fcValue + FC_ALT_FENCE_SAFETY_OFFSET_M
                    prefs.edit().putFloat("max_altitude", ceiling).apply()
                    LogUtils.i("OptionsSync", "✓ Seeded altitude ceiling from FC: FENCE_ALT_MAX = ${fcValue}m → ceiling ${ceiling}m")
                } else {
                    LogUtils.w("OptionsSync", "Could not read FENCE_ALT_MAX — using default ${DEFAULT_MAX_ALTITUDE_M}m")
                }
            }
        } catch (e: Exception) {
            LogUtils.e("OptionsSync", "Error syncing altitude ceiling", e)
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
        batteryFailsafeAction = getLowVoltLevel2Action(context)
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
                // GUARD: Only process fence status if GCS geofence is enabled.
                // Prevents stale FC fence data from causing false warnings
                // like "approaching polygon fence" when geofence is off.
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
                    addNotification(Notification(
                        message = "✓ Geofence clear - drone back in safe zone",
                        type = NotificationType.INFO
                    ))
                }
            }
        }
    }

    // Fence-breach alert de-dup. The breach reaches us on two independent paths and either one
    // can be the only one that fires: SYS_STATUS bit 8 (fence sensor enabled + unhealthy) and
    // the FC's own "Fence breach" STATUSTEXT. Whichever arrives first raises the alert; the
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
    fun notifyFenceBreach(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastFenceBreachAlertTime < FENCE_BREACH_DEDUPE_MS) {
            LogUtils.d("Geofence", "Fence breach from $source suppressed — already alerted ${now - lastFenceBreachAlertTime}ms ago")
            return
        }
        lastFenceBreachAlertTime = now

        LogUtils.w("Geofence", "⚠️ Fence breach detected via $source - FC handling with ${getCurrentFenceAction()}")
        addNotification(Notification(
            message = "⚠️ Geofence breach! FC activated ${getCurrentFenceAction()}",
            type = NotificationType.WARNING
        ))
        showFailsafePopup("Fence Breached")
        // Callers include the STATUSTEXT collector, which runs off the main thread; viewModelScope
        // is Main.immediate, so this keeps TTS on the same thread the old call site used.
        viewModelScope.launch { speak("Fence Breached") }
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
     * Get the current fence action string for display
     */
    private fun getCurrentFenceAction(): String {
        return when (_fenceConfiguration.value?.action) {
            FenceAction.BRAKE -> "BRAKE"
            FenceAction.RTL -> "RTL"
            FenceAction.HOLD -> "LOITER"
            FenceAction.SMART_RTL -> "SMART RTL"
            FenceAction.GUIDED -> "GUIDED"
            FenceAction.REPORT_ONLY -> "REPORT"
            else -> "Safety Mode"
        }
    }

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
     * @param action What FC does on breach (BRAKE recommended for spray drones)
     * @param margin Safety margin in meters
     */
    fun uploadGeofence(
        outerBoundary: List<LatLng>,
        innerBoundary: List<LatLng>? = null,
        exclusionZones: List<List<LatLng>> = emptyList(),
        returnPoint: LatLng? = null,
        altitudeMax: Float? = null,
        altitudeMin: Float? = null,
        action: FenceAction = FenceAction.BRAKE,
        margin: Float = 3.0f
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
                    altitudeMax = altitudeMax,
                    action = action,
                    margin = margin
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
                    LogUtils.i("Geofence", "  - Action: $action")
                    LogUtils.i("Geofence", "  - Margin: ${margin}m")
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
        altitudeMax: Float? = null,
        action: FenceAction = FenceAction.BRAKE,
        margin: Float = 3.0f
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
                    altitudeMax = altitudeMax,
                    action = action,
                    margin = margin
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
