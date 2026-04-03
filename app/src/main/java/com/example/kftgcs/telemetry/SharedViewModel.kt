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
import com.example.kftgcs.GCSApplication
import com.example.kftgcs.Telemetry.TelemetryState
//import com.example.aerogcsclone.Telemetry.connections.BluetoothConnectionProvider
//import com.example.aerogcsclone.Telemetry.connections.MavConnectionProvider
import com.example.kftgcs.telemetry.connections.BluetoothConnectionProvider
import com.example.kftgcs.telemetry.connections.MavConnectionProvider
import com.example.kftgcs.telemetry.connections.TcpConnectionProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.example.kftgcs.grid.GridUtils
import com.example.kftgcs.videotracking.CameraTrackingState
import com.example.kftgcs.videotracking.TrackingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

enum class ConnectionType {
    TCP, BLUETOOTH
}

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

class SharedViewModel : ViewModel() {

    // TextToSpeech manager for voice announcements
    private var ttsManager: TextToSpeechManager? = null

    // Telemetry state - must be declared before init block that uses it
    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    // Battery failsafe tracking
    private var lastVoltageAlertLevel1Time = 0L
    private var lastVoltageAlertLevel2Time = 0L  // Track last Level 2 alert time for repeated warnings
    private var voltageAlertLevel2Triggered = false
    private var lastLevel2ModeAtTrigger: String? = null  // Track mode when Level 2 was triggered
    private val VOLTAGE_ALERT_INTERVAL_MS = 3000L // Alert every 5 seconds for Level 1
    private val VOLTAGE_CRITICAL_INTERVAL_MS = 5000L // Re-alert every 10 seconds for Level 2 if still critical

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
        viewModelScope.launch {
            _telemetryState.collect { state ->
                // Only monitor when connected and armed (in flight)
                if (state.connected && state.armed && state.voltage != null) {
                    handleBatteryVoltageFailsafe(state.voltage)
                } else if (!state.armed) {
                    // Reset tracking when disarmed
                    voltageAlertLevel2Triggered = false
                    lastVoltageAlertLevel1Time = 0L
                    lastVoltageAlertLevel2Time = 0L
                    lastLevel2ModeAtTrigger = null
                }
            }
        }
    }

    /**
     * Handle battery voltage failsafe monitoring.
     * Level 1: Alert only (TTS + notification every 5 seconds)
     * Level 2: Action (BRAKE/RTL/LAND) + TTS, repeated alerts every 10 seconds if still critical
     */
    private fun handleBatteryVoltageFailsafe(voltage: Float) {
        val context = GCSApplication.getInstance() ?: return

        val level1Threshold = getLowVoltLevel1(context)
        val level2Threshold = getLowVoltLevel2(context)
        val level2Action = getLowVoltLevel2Action(context)
        val currentMode = _telemetryState.value.mode

        val now = System.currentTimeMillis()

        // Level 2 (critical) - takes priority
        if (voltage <= level2Threshold) {
            // Check if we should trigger/re-trigger the action:
            // 1. Never triggered before (!voltageAlertLevel2Triggered)
            // 2. Mode changed since last trigger (pilot overrode, so try again)
            // 3. Enough time passed for a repeat alert
            val shouldTriggerAction = !voltageAlertLevel2Triggered || 
                (lastLevel2ModeAtTrigger != null && currentMode != lastLevel2ModeAtTrigger)
            
            val shouldAlert = !voltageAlertLevel2Triggered || 
                (now - lastVoltageAlertLevel2Time >= VOLTAGE_CRITICAL_INTERVAL_MS)

            if (shouldTriggerAction) {
                voltageAlertLevel2Triggered = true
                lastVoltageAlertLevel2Time = now
                lastLevel2ModeAtTrigger = currentMode
                
                LogUtils.i("BatteryFailsafe", "⚠️ CRITICAL: Battery voltage ${voltage}V <= ${level2Threshold}V - Triggering $level2Action")

                // TTS alert
                ttsManager?.speak("Critical! Battery voltage ${String.format(Locale.US, "%.1f", voltage)} volts. Activating $level2Action mode.")

                // Add notification
                addNotification(
                    Notification(
                        message = "⚠️ CRITICAL BATTERY: ${String.format(Locale.US, "%.1f", voltage)}V - Activating $level2Action",
                        type = NotificationType.ERROR
                    )
                )

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

                    val result = repo?.changeMode(targetMode) ?: false
                    if (result) {
                        LogUtils.i("BatteryFailsafe", "✅ $targetModeName mode activated for battery failsafe")
                    } else {
                        LogUtils.e("BatteryFailsafe", "❌ Failed to activate $targetModeName mode for battery failsafe")
                    }

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
            } else if (shouldAlert) {
                // Just repeat the TTS warning without re-triggering mode change
                lastVoltageAlertLevel2Time = now
                LogUtils.i("BatteryFailsafe", "⚠️ CRITICAL (repeat): Battery voltage ${voltage}V still below ${level2Threshold}V")
                ttsManager?.speak("Critical! Battery voltage ${String.format(Locale.US, "%.1f", voltage)} volts.")
            }
        }
        // Level 1 (warning) - alert only, every 5 seconds
        else if (voltage <= level1Threshold && voltage > level2Threshold) {
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
        // Voltage recovered above level 2 - reset trigger
        else if (voltage > level2Threshold + 0.5f) {
            // Only reset if voltage is well above threshold to avoid oscillation
            if (voltageAlertLevel2Triggered) {
                LogUtils.i("BatteryFailsafe", "Battery voltage recovered: ${voltage}V")
                voltageAlertLevel2Triggered = false
                lastLevel2ModeAtTrigger = null
            }
        }
    }

    /**
     * Called automatically when isMissionActive transitions from false to true.
     * This ensures WebSocket telemetry logging starts regardless of how the mission was initiated.
     */
    private fun onMissionBecameActive() {
        LogUtils.i("SharedVM", "🚀 Mission became active - checking WebSocket connection")
        viewModelScope.launch {
            try {
                val wsManager = WebSocketManager.getInstance()
                if (!wsManager.isConnected) {
                    LogUtils.i("SharedVM", "🔌 Auto-connecting WebSocket for active mission...")

                    // 🔥 CRITICAL: Get latest pilotId and adminId from SessionManager
                    GCSApplication.getInstance()?.let { app ->
                        val pilotId = com.example.kftgcs.api.SessionManager.getPilotId(app)
                        val adminId = com.example.kftgcs.api.SessionManager.getAdminId(app)
                        val superAdminId = com.example.kftgcs.api.SessionManager.getSuperAdminId(app)
                        wsManager.pilotId = pilotId
                        wsManager.adminId = adminId
                        wsManager.superAdminId = superAdminId
                        LogUtils.i("SharedVM", "📋 Auto-connect: Updated WebSocket credentials: pilotId=$pilotId, adminId=$adminId, superAdminId=$superAdminId")

                        if (pilotId <= 0) {
                            LogUtils.e("SharedVM", "⚠️ WARNING: pilotId=$pilotId - User may not be logged in! Telemetry will not be saved.")
                        }
                    }

                    // 🔥 Set plot name before connecting
                    wsManager.selectedPlotName = _currentPlotName.value
                    LogUtils.i("SharedVM", "📋 Auto-connect: Plot name set: ${_currentPlotName.value}")

                    // 🔥 Set flight mode (Automatic or Manual)
                    wsManager.selectedFlightMode = _userSelectedFlightMode.value.name
                    LogUtils.i("SharedVM", "📋 Auto-connect: Flight mode set: ${_userSelectedFlightMode.value.name}")

                    // 🔥 Set mission type (Grid or Waypoint)
                    wsManager.selectedMissionType = _selectedMissionType.value.name
                    LogUtils.i("SharedVM", "📋 Auto-connect: Mission type set: ${_selectedMissionType.value.name}")

                    // 🔥 Set grid setup source
                    wsManager.gridSetupSource = _gridSetupSource.value.name
                    LogUtils.i("SharedVM", "📋 Auto-connect: Grid setup source set: ${_gridSetupSource.value.name}")

                    wsManager.connect()

                    // Wait for WebSocket to connect
                    var waitTime = 0
                    while (!wsManager.isConnected && waitTime < 5000) {
                        delay(100)
                        waitTime += 100
                    }

                    if (wsManager.isConnected) {
                        delay(500) // Give time for session_ack and mission_created
                        LogUtils.i("SharedVM", "✅ Auto-connect: WebSocket ready after ${waitTime}ms")
                    } else {
                        LogUtils.w("SharedVM", "⚠️ Auto-connect: WebSocket failed to connect within timeout")
                    }

                    // Send mission started status unconditionally — sendMissionStatus
                    // handles offline enqueue if socket/missionId not ready yet.
                    wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_STARTED)
                    wsManager.sendMissionEvent(
                        eventType = "MISSION_STARTED",
                        eventStatus = "INFO",
                        description = "Mission started (auto-detected via mode change)"
                    )
                    LogUtils.i("SharedVM", "✅ Auto-connect: Mission status STARTED sent/queued (connected=${wsManager.isConnected}, missionId=${wsManager.missionId})")
                } else {
                    LogUtils.i("SharedVM", "✅ WebSocket already connected - no action needed")
                }
            } catch (e: Exception) {
                LogUtils.e("SharedVM", "❌ Auto-connect: Failed to connect WebSocket", e)
            }
        }
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

        // Clear geofence when clearing mission
        clearGeofence()
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

                // Get the user's selected tank empty action from settings
                val context = GCSApplication.getInstance()
                val tankEmptyAction = if (context != null) {
                    getTankEmptyAction(context)
                } else {
                    "HOVER" // Default fallback
                }

                LogUtils.i("SharedVM", "=== TANK EMPTY DETECTED ===")
                LogUtils.i("SharedVM", "Current flight mode: $currentMode")
                LogUtils.i("SharedVM", "User configured action: $tankEmptyAction")
                LogUtils.i("SharedVM", "Current waypoint: $currentWp, Last AUTO waypoint: $lastAutoWp")

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

                // Change mode to user's selected action
                val result = repo?.changeMode(targetMode) ?: false

                if (result) {
                    LogUtils.i("SharedVM", "$modeName mode command sent successfully")
                    
                    // TTS announcement for tank empty
                    ttsManager?.speak("Tank empty! Switching to $modeName mode.")

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

    /**
     * Get the user's tank empty action setting from SharedPreferences
     */
    private fun getTankEmptyAction(context: Context): String {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getString("tank_empty_action", "HOVER") ?: "HOVER"
    }

    /**
     * Get the user's low voltage level 1 threshold from SharedPreferences
     */
    private fun getLowVoltLevel1(context: Context): Float {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getFloat("low_volt_level_1", 22.2f)
    }

    /**
     * Get the user's low voltage level 2 threshold from SharedPreferences
     */
    private fun getLowVoltLevel2(context: Context): Float {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getFloat("low_volt_level_2", 21.0f)
    }

    /**
     * Get the user's low voltage level 2 action from SharedPreferences
     */
    private fun getLowVoltLevel2Action(context: Context): String {
        val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)
        return prefs.getString("low_volt_level_2_action", "HOVER") ?: "HOVER"
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

    // Expose PARAM_VALUE flow for parameter reading
    val paramValue: SharedFlow<com.divpundir.mavlink.definitions.common.ParamValue>
        get() = repo?.paramValue ?: MutableSharedFlow()

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
        // Calculate sprayed acres from sprayed distance
        // Formula: (sprayed_distance_m * spray_width_m) / 4046.86 (sq meters per acre)
        val sprayWidthMeters = 5.0f  // Default spray width
        val sprayedAreaSqMeters = sprayedDistanceMeters * sprayWidthMeters
        val sprayedAcres = sprayedAreaSqMeters / 4046.86f

        _telemetryState.value = _telemetryState.value.copy(
            isMissionActive = isActive,
            missionElapsedSec = if (isActive) elapsedSeconds else null,
            totalDistanceMeters = if (isActive || completed) distanceMeters else null,
            totalSprayedDistanceMeters = if (isActive || completed) sprayedDistanceMeters else null,
            totalSprayedAcres = if (isActive || completed) sprayedAcres else null,
            missionCompleted = completed,
            lastMissionElapsedSec = if (completed) elapsedSeconds else _telemetryState.value.lastMissionElapsedSec
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

                repository.connection.trySendUnsignedV2(
                    repository.gcsSystemId,
                    repository.gcsComponentId,
                    paramSet
                )

                // Wait for PARAM_VALUE response confirming the set
                return withTimeoutOrNull(timeoutMs) {
                    paramValue
                        .filter { it.paramId == paramId }
                        .first()
                }
            } catch (e: Exception) {
                LogUtils.e("RCCalVM", "Failed to set parameter $paramId", e)
                return null
            }
        }
        return null
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
                        val preserveLastMissionElapsedSec = currentState.lastMissionElapsedSec ?: repoState.lastMissionElapsedSec
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

    // --- Current Mission Names (for tracking which template/mission is active) ---
    private val _currentProjectName = MutableStateFlow("")
    val currentProjectName: StateFlow<String> = _currentProjectName.asStateFlow()

    private val _currentPlotName = MutableStateFlow("")
    val currentPlotName: StateFlow<String> = _currentPlotName.asStateFlow()

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
            val batteryEnd = currentState.batteryPercent ?: 0

            // Parse sprayed acres from the completion data string (e.g., "0.13 acres")
            val totalSprayedAcres = completionData.sprayedAcres
                .replace(" acres", "")
                .replace(" acre", "")
                .toDoubleOrNull() ?: 0.0

            LogUtils.d("SharedVM", "🔥 DEBUG: completionData.sprayedAcres='${completionData.sprayedAcres}', parsed totalSprayedAcres=$totalSprayedAcres")

            wsManager.sendMissionSummary(
                totalAcres = totalAcres,
                totalSprayUsed = totalSprayUsed,
                flyingTimeMinutes = flyingTimeMinutes,
                averageSpeed = 0.0, // Average speed would need to be calculated
                batteryStart = wsManager.missionBatteryStart,
                batteryEnd = batteryEnd,
                alertsCount = wsManager.missionAlertsCount,
                status = "COMPLETED",
                projectName = projectName,
                plotName = plotName,
                cropType = cropType,
                totalSprayedAcres = totalSprayedAcres
            )
            LogUtils.i("SharedVM", "📤 Mission summary sent with cropType=$cropType")
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
                altitudeMax = 120f,  // Default max altitude
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

    private val _isNotificationPanelVisible = MutableStateFlow(false)
    val isNotificationPanelVisible: StateFlow<Boolean> = _isNotificationPanelVisible.asStateFlow()

    // Spray status popup (temporary message that disappears after 2 seconds)
    private val _sprayStatusPopup = MutableStateFlow<String?>(null)
    val sprayStatusPopup: StateFlow<String?> = _sprayStatusPopup.asStateFlow()

    fun addNotification(notification: Notification) {
        _notifications.value = listOf(notification) + _notifications.value
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
     * Called when mode changes from AUTO to LOITER (detected in TelemetryRepository)
     * This shows a popup asking user if they want to set resume point here
     * NOTE: Only works when user selected Automatic mode, not Manual mode
     */
    fun onModeChangedToLoiterFromAuto(waypointNumber: Int) {
        // Safety check: Don't show popup if user is in Manual mode
        if (!isPauseResumeEnabled()) {
            LogUtils.i("SharedVM", "=== MODE CHANGED: AUTO → LOITER === (IGNORED - user in MANUAL mode)")
            return
        }

        LogUtils.i("SharedVM", "=== MODE CHANGED: AUTO → LOITER ===")
        LogUtils.i("SharedVM", "Waypoint at mode change: $waypointNumber")

        _resumePointWaypoint.value = waypointNumber

        // Capture current drone location temporarily (will only be shown if user confirms)
        val currentLat = _telemetryState.value.latitude
        val currentLon = _telemetryState.value.longitude
        if (currentLat != null && currentLon != null) {
            _pendingResumeLocation = LatLng(currentLat, currentLon)
            LogUtils.i("SharedVM", "Pending resume point location captured: $currentLat, $currentLon")
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
                message = "Mode changed to Loiter at waypoint $waypointNumber",
                type = NotificationType.INFO
            )
        )
    }

    // Temporary storage for pending resume location (before user confirms)
    private var _pendingResumeLocation: LatLng? = null

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
            val resumeLocation = _resumePointLocation.value
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
            val resumeLocation = _resumePointLocation.value

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
                LogUtils.i("SharedVM", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // ✅ Restore spray if it was active before pause
                if (_sprayWasActiveBeforePause) {
                    LogUtils.i("SharedVM", "💧 Restoring spray after resume from waypoint $resumeWaypoint (was active before pause)")
                    delay(500) // Small delay to ensure mode change is complete
                    setSprayEnabled(true)
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

                // Send mission start command
                val startSuccess = repo?.startMission() ?: false

                if (startSuccess) {
                    LogUtils.i("SharedVM", "✅ Resume mission started successfully")
                    _resumeMissionReady.value = false
                    // Clear the resume point location (remove "R" marker from map)
                    _resumePointLocation.value = null
                    _resumePointWaypoint.value = null
                    _telemetryState.update {
                        it.copy(
                            missionPaused = false,
                            pausedAtWaypoint = null
                        )
                    }

                    // ✅ Restore spray if it was active before pause
                    if (_sprayWasActiveBeforePause) {
                        LogUtils.i("SharedVM", "💧 Restoring spray after AUTO mode change (was active before pause)")
                        delay(500) // Small delay to ensure mode change is complete
                        setSprayEnabled(true)
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

                            wsManager.connect()

                            // 🔥 Wait for WebSocket to connect and receive session_ack before sending status
                            // This prevents the "socket not ready" error
                            var waitTime = 0
                            while (!wsManager.isConnected && waitTime < 5000) {
                                delay(100)
                                waitTime += 100
                            }
                            // Additional wait for session_ack and mission_created
                            if (wsManager.isConnected) {
                                delay(500) // Give time for session_ack and mission_created
                                LogUtils.i("SharedVM", "✅ WebSocket ready after ${waitTime}ms")
                            }
                        }
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to connect WebSocket", e)
                    }

                    // ✅ Send mission status STARTED to backend (crash-safe)
                    // sendMissionStatus/sendMissionEvent handle offline enqueue
                    // internally if the socket is down or missionId not yet received.
                    try {
                        val wsManager = WebSocketManager.getInstance()
                        wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_STARTED)
                        wsManager.sendMissionEvent(
                            eventType = "MISSION_STARTED",
                            eventStatus = "INFO",
                            description = "Mission started successfully"
                        )
                        LogUtils.i("SharedVM", "✅ Mission status STARTED sent/queued (connected=${wsManager.isConnected}, missionId=${wsManager.missionId})")
                    } catch (e: Exception) {
                        LogUtils.e("SharedVM", "Failed to send STARTED status", e)
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
                val waypointToStore = if (lastAutoWp > 0) lastAutoWp else currentWp

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
                val resumeLocation = _resumePointLocation.value

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
                LogUtils.i("ResumeMission", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // ✅ Restore spray if it was active before pause
                if (_sprayWasActiveBeforePause) {
                    LogUtils.i("ResumeMission", "💧 Restoring spray after resume from waypoint $resumeWaypointNumber (was active before pause)")
                    delay(500) // Small delay to ensure mode change is complete
                    setSprayEnabled(true)
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

                        // ✅ Restore spray if it was active before pause
                        if (_sprayWasActiveBeforePause) {
                            LogUtils.i("SharedVM", "💧 Restoring spray after resume (was active before pause)")
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

                // Switch to AUTO mode
                val result = repo?.changeMode(MavMode.AUTO) ?: false

                if (result) {
                    _telemetryState.update {
                        it.copy(
                            missionPaused = false,
                            pausedAtWaypoint = null
                        )
                    }

                    // ✅ Restore spray if it was active before pause
                    if (_sprayWasActiveBeforePause) {
                        LogUtils.i("SharedVM", "💧 Restoring spray after resume from waypoint $pausedWaypoint (was active before pause)")
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
    // - Uses SPRAY_PUMP_RATE parameter (0-100%) to control pump duty cycle
    // - PWM range is controlled by SERVO9_MIN (1051) and SERVO9_MAX (1951) on the FC
    private val MAV_CMD_DO_SPRAYER = 216u

    /**
     * Control spray system using ArduPilot's Sprayer library.
     *
     * Since SERVO9_FUNCTION = 22 (SprayerPump), the ArduPilot Sprayer library owns the servo output.
     * Direct DO_SET_SERVO commands won't work because the library overrides them.
     *
     * This implementation uses:
     * 1. SPRAY_PUMP_RATE parameter (0-100%) - Controls the maximum pump duty cycle
     * 2. MAV_CMD_DO_SPRAYER (216) - Enables/disables the sprayer
     *
     * The Sprayer library then calculates actual PWM output based on:
     * - SPRAY_PUMP_RATE: The maximum pump rate percentage
     * - Ground speed (when SPRAY_SPEED_MIN > 0)
     * - Target coverage rate
     *
     * Hardware notes (Hobbywing 5L Pump):
     * - PWM 1051 µs = minimum throttle (0%)
     * - PWM 1951 µs = maximum throttle (100% = 5 L/min)
     * - Linear interpolation between min and max
     *
     * @param enable true to enable spray at current rate, false to disable
     */
    fun controlSpray(enable: Boolean) {
        viewModelScope.launch {
            val rate = _sprayRate.value.coerceIn(10f, 100f)
            // PWM calculation: 1051 (10%) to 1951 (100%)
            // PWM = 1051 + (rate/100) * 900
            val expectedPwm = (1051 + (rate / 100f * 900f)).toInt()

            LogUtils.i("SprayControl", "═══════════════════════════════════════")
            LogUtils.i("SprayControl", "🚿 SPRAY COMMAND (Sprayer Library Mode)")
            LogUtils.i("SprayControl", "   State: ${if (enable) "ON" else "OFF"}")
            LogUtils.i("SprayControl", "   SPRAY_PUMP_RATE: ${rate.toInt()}%")
            LogUtils.i("SprayControl", "   Expected PWM: $expectedPwm µs (range 1051-1951)")
            LogUtils.i("SprayControl", "   Method: MAV_CMD_DO_SPRAYER + SPRAY_PUMP_RATE param")
            LogUtils.i("SprayControl", "═══════════════════════════════════════")

            repo?.let { repository ->
                try {
                    // Step 1: Set the SPRAY_PUMP_RATE parameter to control duty cycle
                    // This parameter controls the maximum pump output (0-100%)
                    val paramResult = setParameter("SPRAY_PUMP_RATE", rate)
                    if (paramResult != null) {
                        LogUtils.i("SprayControl", "✓ SPRAY_PUMP_RATE set to ${rate.toInt()}%")
                    } else {
                        LogUtils.w("SprayControl", "⚠ SPRAY_PUMP_RATE set (no confirmation received)")
                    }

                    // Step 2: Send DO_SPRAYER command to enable/disable
                    // MAV_CMD_DO_SPRAYER (216): param1 = 1 (enable) or 0 (disable)
                    repository.sendCommandRaw(
                        commandId = MAV_CMD_DO_SPRAYER,
                        param1 = if (enable) 1f else 0f
                    )
                    LogUtils.i("SprayControl", "✓ DO_SPRAYER command sent: ${if (enable) "ENABLE" else "DISABLE"}")
                    LogUtils.i("SprayControl", "✓ Command sent successfully")
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
     * Disable spray when mode changes from Auto to another mode
     * This ensures spray is turned off when pilot takes manual control or mission is paused/aborted.
     * Always sends DO_SPRAYER(0) to FC regardless of app state, because mission-embedded
     * DO_SPRAYER commands may have turned sprayer on without updating app state.
     */
    fun disableSprayOnModeChange() {
        LogUtils.i("SprayControl", "🚿 Disabling spray due to mode change from Auto")

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

        // Debounce the actual command send to avoid flooding FC when slider moves rapidly
        sprayRateDebounceJob?.cancel()
        sprayRateDebounceJob = viewModelScope.launch {
            delay(SPRAY_RATE_DEBOUNCE_MS)
            // Check if RC7 is enabled (spray enabled from RC transmitter)
            val rc7Enabled = _telemetryState.value.sprayTelemetry.sprayEnabled
            if (rc7Enabled) {
                LogUtils.i("SprayControl", "🚿 Rate changed to ${newRate.toInt()}% - updating SPRAY_PUMP_RATE parameter (RC7 enabled)")
                controlSpray(true) // Re-send with new rate
            } else {
                LogUtils.d("SprayControl", "Rate set to ${newRate.toInt()}% (RC7 disabled, SPRAY_PUMP_RATE will be set when RC7 enabled)")
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
                }
            }
        }
    }

    // Job reference for fence status monitoring - allows cancellation on reconnect/disable
    private var fenceMonitoringJob: Job? = null

    /**
     * Auto-sync saved failsafe options to the drone immediately after connection.
     * Reads settings from SharedPreferences and pushes BATT voltage/action parameters via MAVLink.
     */
    private fun syncFailsafeOptionsOnConnect() {
        viewModelScope.launch {
            try {
                val context = GCSApplication.getInstance() ?: return@launch
                val prefs = context.getSharedPreferences("failsafe_options", Context.MODE_PRIVATE)

                val lowVoltLevel1 = prefs.getFloat("low_volt_level_1", 22.2f)
                val lowVoltLevel2 = prefs.getFloat("low_volt_level_2", 21.0f)
                val lowVoltLevel2Action = prefs.getString("low_volt_level_2_action", "HOVER") ?: "HOVER"

                LogUtils.i("OptionsSync", "Auto-syncing failsafe options to drone on connect...")

                // Small delay to let the connection stabilize
                delay(2000)

                val failures = mutableListOf<String>()

                // BATT_LOW_VOLT ← lowVoltLevel1
                val r1 = setParameter("BATT_LOW_VOLT", lowVoltLevel1)
                if (r1 != null) {
                    LogUtils.i("OptionsSync", "✓ BATT_LOW_VOLT = $lowVoltLevel1")
                } else {
                    failures.add("BATT_LOW_VOLT")
                    LogUtils.e("OptionsSync", "✗ Failed to set BATT_LOW_VOLT")
                }

                // BATT_CRT_VOLT ← lowVoltLevel2
                val r2 = setParameter("BATT_CRT_VOLT", lowVoltLevel2)
                if (r2 != null) {
                    LogUtils.i("OptionsSync", "✓ BATT_CRT_VOLT = $lowVoltLevel2")
                } else {
                    failures.add("BATT_CRT_VOLT")
                    LogUtils.e("OptionsSync", "✗ Failed to set BATT_CRT_VOLT")
                }

                // BATT_FS_LOW_ACT ← Level 1 action is alert only, set to 0 (None)
                val r3 = setParameter("BATT_FS_LOW_ACT", 0.0f)
                if (r3 != null) {
                    LogUtils.i("OptionsSync", "✓ BATT_FS_LOW_ACT = 0 (alert only)")
                } else {
                    failures.add("BATT_FS_LOW_ACT")
                    LogUtils.e("OptionsSync", "✗ Failed to set BATT_FS_LOW_ACT")
                }

                // BATT_FS_CRT_ACT ← lowVoltLevel2Action
                val crtActValue = when (lowVoltLevel2Action) {
                    "LAND" -> 1.0f
                    "RTL" -> 2.0f
                    "HOVER", "LOITER" -> 0.0f
                    else -> 0.0f
                }
                val r4 = setParameter("BATT_FS_CRT_ACT", crtActValue)
                if (r4 != null) {
                    LogUtils.i("OptionsSync", "✓ BATT_FS_CRT_ACT = $crtActValue ($lowVoltLevel2Action)")
                } else {
                    failures.add("BATT_FS_CRT_ACT")
                    LogUtils.e("OptionsSync", "✗ Failed to set BATT_FS_CRT_ACT")
                }

                if (failures.isEmpty()) {
                    LogUtils.i("OptionsSync", "All failsafe options synced to drone ✓")
                } else {
                    LogUtils.w("OptionsSync", "Failed to sync: ${failures.joinToString()}")
                }
            } catch (e: Exception) {
                LogUtils.e("OptionsSync", "Error syncing failsafe options on connect", e)
            }
        }
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
                    LogUtils.w("Geofence", "⚠️ Fence breach detected - FC handling with ${getCurrentFenceAction()}")
                    _geofenceWarningTriggered.value = true
                    geofenceTriggeringModeChange = true

                    addNotification(Notification(
                        message = "⚠️ Geofence breach! FC activated ${getCurrentFenceAction()}",
                        type = NotificationType.WARNING
                    ))
                    speak("Geofence breach")

                    // Reset geofence triggering flag after FC handles it
                    delay(2000)
                    geofenceTriggeringModeChange = false
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
}
