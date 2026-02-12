@file:Suppress("unused")
package com.example.aerogcsclone.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divpundir.mavlink.adapters.coroutines.trySendUnsignedV2
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavFrame
import com.divpundir.mavlink.definitions.common.MavMissionType
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.Statustext
import com.example.aerogcsclone.GCSApplication
import com.example.aerogcsclone.Telemetry.TelemetryState
//import com.example.aerogcsclone.Telemetry.connections.BluetoothConnectionProvider
//import com.example.aerogcsclone.Telemetry.connections.MavConnectionProvider
import com.example.aerogcsclone.telemetry.connections.BluetoothConnectionProvider
import com.example.aerogcsclone.telemetry.connections.MavConnectionProvider
import com.example.aerogcsclone.telemetry.connections.TcpConnectionProvider
import com.example.aerogcsclone.utils.GeofenceUtils
import com.example.aerogcsclone.utils.TextToSpeechManager
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.example.aerogcsclone.grid.GridUtils
import kotlinx.coroutines.withTimeoutOrNull

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
    }

    /**
     * Called automatically when isMissionActive transitions from false to true.
     * This ensures WebSocket telemetry logging starts regardless of how the mission was initiated.
     */
    private fun onMissionBecameActive() {
        Log.i("SharedVM", "🚀 Mission became active - checking WebSocket connection")
        viewModelScope.launch {
            try {
                val wsManager = WebSocketManager.getInstance()
                if (!wsManager.isConnected) {
                    Log.i("SharedVM", "🔌 Auto-connecting WebSocket for active mission...")

                    // 🔥 CRITICAL: Get latest pilotId and adminId from SessionManager
                    GCSApplication.getInstance()?.let { app ->
                        val pilotId = com.example.aerogcsclone.api.SessionManager.getPilotId(app)
                        val adminId = com.example.aerogcsclone.api.SessionManager.getAdminId(app)
                        wsManager.pilotId = pilotId
                        wsManager.adminId = adminId
                        Log.i("SharedVM", "📋 Auto-connect: Updated WebSocket credentials: pilotId=$pilotId, adminId=$adminId")

                        if (pilotId <= 0) {
                            Log.e("SharedVM", "⚠️ WARNING: pilotId=$pilotId - User may not be logged in! Telemetry will not be saved.")
                        }
                    }

                    // 🔥 Set plot name before connecting
                    wsManager.selectedPlotName = _currentPlotName.value
                    Log.i("SharedVM", "📋 Auto-connect: Plot name set: ${_currentPlotName.value}")

                    // 🔥 Set flight mode (Automatic or Manual)
                    wsManager.selectedFlightMode = _userSelectedFlightMode.value.name
                    Log.i("SharedVM", "📋 Auto-connect: Flight mode set: ${_userSelectedFlightMode.value.name}")

                    // 🔥 Set mission type (Grid or Waypoint)
                    wsManager.selectedMissionType = _selectedMissionType.value.name
                    Log.i("SharedVM", "📋 Auto-connect: Mission type set: ${_selectedMissionType.value.name}")

                    // 🔥 Set grid setup source
                    wsManager.gridSetupSource = _gridSetupSource.value.name
                    Log.i("SharedVM", "📋 Auto-connect: Grid setup source set: ${_gridSetupSource.value.name}")

                    wsManager.connect()

                    // Wait for WebSocket to connect
                    var waitTime = 0
                    while (!wsManager.isConnected && waitTime < 5000) {
                        delay(100)
                        waitTime += 100
                    }

                    if (wsManager.isConnected) {
                        delay(500) // Give time for session_ack and mission_created
                        Log.i("SharedVM", "✅ Auto-connect: WebSocket ready after ${waitTime}ms")

                        // Send mission started status
                        if (wsManager.missionId != null) {
                            wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_STARTED)
                            wsManager.sendMissionEvent(
                                eventType = "MISSION_STARTED",
                                eventStatus = "INFO",
                                description = "Mission started (auto-detected via mode change)"
                            )
                            Log.i("SharedVM", "✅ Auto-connect: Mission status STARTED sent to backend")
                        }
                    } else {
                        Log.w("SharedVM", "⚠️ Auto-connect: WebSocket failed to connect within timeout")
                    }
                } else {
                    Log.i("SharedVM", "✅ WebSocket already connected - no action needed")
                }
            } catch (e: Exception) {
                Log.e("SharedVM", "❌ Auto-connect: Failed to connect WebSocket", e)
            }
        }
    }

    /**
     * Clear all mission-related waypoints and polygons from the map.
     * Called when mission is completed or when user navigates to select a new mode.
     */
    fun clearMissionFromMap() {
        Log.i("SharedVM", "Clearing mission data from map (including geofence)")
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
     * Clear the geofence polygon and disable geofence monitoring.
     * Called when navigating away from mission or when user disables geofence.
     * 🔥 CRITICAL FIX: Now sends FENCE_ENABLE=0 to FC to properly disable geofence
     */
    fun clearGeofence() {
        Log.i("SharedVM", "🔥 Clearing geofence from UI and FC")

        // 🔥 STEP 1: Disable geofence on the Flight Controller
        viewModelScope.launch {
            try {
                Log.i("Geofence", "📤 Sending FENCE_ENABLE=0 to Flight Controller")
                val result = setParameter("FENCE_ENABLE", 0f, timeoutMs = 5000L)
                if (result != null) {
                    Log.i("Geofence", "✅ FENCE_ENABLE=0 confirmed by FC")
                    addNotification(
                        Notification(
                            message = "Geofence disabled on Flight Controller",
                            type = NotificationType.INFO
                        )
                    )
                } else {
                    Log.e("Geofence", "⚠️ No response from FC for FENCE_ENABLE=0")
                    addNotification(
                        Notification(
                            message = "Geofence disable command sent (no confirmation)",
                            type = NotificationType.WARNING
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("Geofence", "❌ Failed to disable geofence on FC", e)
                addNotification(
                    Notification(
                        message = "Failed to disable geofence on FC: ${e.message}",
                        type = NotificationType.ERROR
                    )
                )
            }
        }

        // 🔥 STEP 2: Clear UI state
        _geofenceEnabled.value = false
        _geofencePolygon.value = emptyList()
        _homePosition.value = null

        // 🔥 STEP 3: Reset geofence warning/violation states
        resetGeofenceState()

        Log.i("SharedVM", "✅ Geofence cleared from UI and FC")
    }

    // Initialize TTS with context
    fun initializeTextToSpeech(context: Context) {
        if (ttsManager == null) {
            ttsManager = TextToSpeechManager(context)
            Log.d("SharedVM", "TextToSpeech initialized")
        }
    }

    // Set the language for TTS
    fun setLanguage(languageCode: String) {
        ttsManager?.setLanguage(languageCode)
        // Also update the app-wide language for UI strings
        com.example.aerogcsclone.utils.AppStrings.setLanguage(languageCode)
        Log.d("SharedVM", "Language set to: $languageCode")
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
        Log.i("SharedVM", "📋 Mission type set to: ${type.name}")
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
        Log.i("SharedVM", "📋 Grid setup source set to: ${source.name}")
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
        Log.i("SharedVM", "User selected AUTOMATIC mode - pause/resume ENABLED")
        ttsManager?.announceSelectedAutomatic()
    }

    fun announceSelectedManual() {
        _userSelectedFlightMode.value = UserFlightMode.MANUAL
        Log.i("SharedVM", "User selected MANUAL mode - pause/resume DISABLED")
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
     * Handle tank empty detection during AUTO mission.
     * This will:
     * 1. Change mode to LOITER to hold position
     * 2. The mode change detection (AUTO → LOITER) will automatically trigger the resume popup
     *
     * Called from TelemetryRepository when tank empty is detected during AUTO mode.
     */
    fun handleTankEmptyInAutoMode() {
        viewModelScope.launch {
            try {
                val currentMode = _telemetryState.value.mode
                val currentWp = _telemetryState.value.currentWaypoint
                val lastAutoWp = _telemetryState.value.lastAutoWaypoint

                // Only proceed if we're in AUTO mode
                if (currentMode?.equals("Auto", ignoreCase = true) != true) {
                    Log.d("SharedVM", "Tank empty detected but not in AUTO mode ($currentMode) - skipping LOITER transition")
                    return@launch
                }

                Log.i("SharedVM", "=== TANK EMPTY IN AUTO MODE ===")
                Log.i("SharedVM", "Switching to LOITER mode for tank refill")
                Log.i("SharedVM", "Current waypoint: $currentWp, Last AUTO waypoint: $lastAutoWp")

                // Change mode to LOITER - this will trigger the resume popup via mode change detection
                // (AUTO → LOITER transition is detected in TelemetryRepository and triggers onModeChangedToLoiterFromAuto)
                val result = repo?.changeMode(MavMode.LOITER) ?: false

                if (result) {
                    Log.i("SharedVM", "LOITER mode command sent successfully - resume popup will be triggered by mode change detection")

                    // Send mission status to backend
                    try {
                        WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_PAUSED)
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "TANK_EMPTY_PAUSE",
                            eventStatus = "WARNING",
                            description = "Mission paused due to tank empty"
                        )
                    } catch (e: Exception) {
                        Log.e("SharedVM", "Failed to send tank empty pause status", e)
                    }
                } else {
                    Log.e("SharedVM", "Failed to send LOITER mode command for tank empty")
                    addNotification(
                        Notification(
                            message = "Failed to switch to LOITER mode for tank refill",
                            type = NotificationType.ERROR
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("SharedVM", "Error handling tank empty in AUTO mode", e)
            }
        }
    }

    fun speak(text: String) {
        ttsManager?.speak(text)
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

    private val _ipAddress = mutableStateOf("65.0.76.31")
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
                Log.d("SharedVM", "Refreshed ${pairedBtDevices.size} paired Bluetooth devices")
            } catch (se: SecurityException) {
                Log.e("SharedVM", "Bluetooth permission missing: ${se.message}")
            }
        } else {
            Log.e("SharedVM", "Bluetooth adapter not available")
        }
    }

    fun onDeviceSelected(device: PairedDevice) {
        _selectedDevice.value = device
    }

    // --- Telemetry & Repository ---
    private var repo: MavlinkTelemetryRepository? = null

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
            Log.i("SharedVM", "Mission completed via updateFlightState - keeping map lines visible")
        }
    }

    /**
     * Mark the mission completed popup as handled to prevent it from showing again
     * This should be called after the popup is shown or skipped
     */
    fun markMissionCompletedHandled() {
        _telemetryState.value = _telemetryState.value.copy(missionCompletedHandled = true)
        Log.i("SharedVM", "Mission completed handled - popup won't show again for this mission")
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
        Log.i("SharedVM", "Mission completed state reset")
    }

    // --- Calibration helpers ---
    /**
     * Request MAG_CAL_PROGRESS and MAG_CAL_REPORT messages from the autopilot.
     * This is needed because these messages are not sent by default.
     */
    suspend fun requestMagCalMessages(hz: Float = 10f) {
        Log.d("CompassCalVM", "========== REQUESTING MAG CAL MESSAGE STREAMING ==========")
        Log.d("CompassCalVM", "Requesting MAG_CAL_PROGRESS (191) at $hz Hz")
        Log.d("CompassCalVM", "Requesting MAG_CAL_REPORT (192) at $hz Hz")
        Log.d("CompassCalVM", "Interval: ${if (hz > 0f) (1_000_000f / hz).toInt() else 0} microseconds")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 191f, // MAG_CAL_PROGRESS message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        Log.d("CompassCalVM", "✓ MAG_CAL_PROGRESS message interval command sent")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 192f, // MAG_CAL_REPORT message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        Log.d("CompassCalVM", "✓ MAG_CAL_REPORT message interval command sent")
        Log.d("CompassCalVM", "========================================================")
    }

    /**
     * Stop MAG_CAL_PROGRESS and MAG_CAL_REPORT message streaming.
     * Sets the message interval to 0 (disabled).
     */
    suspend fun stopMagCalMessages() {
        Log.d("CompassCalVM", "========== STOPPING MAG CAL MESSAGE STREAMING ==========")
        Log.d("CompassCalVM", "Disabling MAG_CAL_PROGRESS (191) streaming")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 191f, // MAG_CAL_PROGRESS message ID
            param2 = 0f // 0 = disable streaming
        )
        Log.d("CompassCalVM", "✓ MAG_CAL_PROGRESS streaming disabled")

        Log.d("CompassCalVM", "Disabling MAG_CAL_REPORT (192) streaming")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 192f, // MAG_CAL_REPORT message ID
            param2 = 0f // 0 = disable streaming
        )
        Log.d("CompassCalVM", "✓ MAG_CAL_REPORT streaming disabled")
        Log.d("CompassCalVM", "========================================================")
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
            Log.e("SharedVM", "Error while awaiting COMMAND_ACK for $commandId", e)
            null
        }
    }

    /**
     * Request RC_CHANNELS messages from the autopilot at specified rate.
     * Message ID 65 for RC_CHANNELS.
     */
    suspend fun requestRCChannels(hz: Float = 10f) {
        Log.d("RCCalVM", "========== REQUESTING RC_CHANNELS MESSAGE STREAMING ==========")
        Log.d("RCCalVM", "Requesting RC_CHANNELS (65) at $hz Hz")
        Log.d("RCCalVM", "Interval: ${if (hz > 0f) (1_000_000f / hz).toInt() else 0} microseconds")

        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 65f, // RC_CHANNELS message ID
            param2 = if (hz <= 0f) 0f else (1_000_000f / hz) // interval in microseconds
        )
        Log.d("RCCalVM", "✓ RC_CHANNELS message interval command sent")
        Log.d("RCCalVM", "==============================================================")
    }

    /**
     * Stop RC_CHANNELS message streaming.
     */
    suspend fun stopRCChannels() {
        Log.d("RCCalVM", "========== STOPPING RC_CHANNELS MESSAGE STREAMING ==========")
        repo?.sendCommand(
            MavCmd.SET_MESSAGE_INTERVAL,
            param1 = 65f, // RC_CHANNELS message ID
            param2 = 0f // 0 = disable streaming
        )
        Log.d("RCCalVM", "✓ RC_CHANNELS streaming disabled")
        Log.d("RCCalVM", "=============================================================")
    }

    /**
     * Reboot the autopilot using MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN.
     *
     * Command: MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN (246)
     * Param1: 1 = Reboot autopilot
     * Param2-7: 0 (reserved)
     */
    suspend fun rebootAutopilot() {
        Log.d("Calibration", "========== SENDING REBOOT COMMAND ==========")
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
            Log.d("Calibration", "✓ Reboot command sent successfully")
        } catch (e: Exception) {
            Log.e("Calibration", "❌ Failed to send reboot command", e)
        }
        Log.d("Calibration", "============================================")
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
                Log.d("RCCalVM", "📤 Sent PARAM_REQUEST_READ for: $paramId")
            } catch (e: Exception) {
                Log.e("RCCalVM", "Failed to request parameter $paramId", e)
            }
        }
    }

    /**
     * Set a parameter value on the autopilot.
     * Returns the PARAM_VALUE response if successful within timeout.
     */
    suspend fun setParameter(paramId: String, value: Float, timeoutMs: Long = 3000L): com.divpundir.mavlink.definitions.common.ParamValue? {
        repo?.let { repository ->
            try {
                Log.d("RCCalVM", "📤 Setting parameter: $paramId = $value")

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
                Log.e("RCCalVM", "Failed to set parameter $paramId", e)
                return null
            }
        }
        return null
    }

    fun connect() {
        viewModelScope.launch {
            val provider: MavConnectionProvider? = when (_connectionType.value) {
                ConnectionType.TCP -> {
                    val portInt = port.value.toIntOrNull()
                    if (portInt != null) {
                        TcpConnectionProvider(ipAddress.value, portInt)
                    } else {
                        Log.e("SharedVM", "Invalid port number.")
                        null
                    }
                }
                ConnectionType.BLUETOOTH -> {
                    selectedDevice.value?.device?.let {
                        BluetoothConnectionProvider(it)
                    } ?: run {
                        Log.e("SharedVM", "No Bluetooth device selected.")
                        null
                    }
                }
            }

            if (provider == null) {
                Log.e("SharedVM", "Failed to create connection provider.")
                return@launch
            }

            // If there's an old repo, close its connection first
            repo?.closeConnection()

            val newRepo = MavlinkTelemetryRepository(provider, this@SharedViewModel)
            repo = newRepo
            newRepo.start()
            DisconnectionRTLHandler.startMonitoring(_telemetryState, newRepo, viewModelScope)

            viewModelScope.launch {
                newRepo.state.collect { repoState ->
                    // Preserve SharedViewModel-managed fields (pause state, mission active state) while updating from repository
                    _telemetryState.update { currentState ->
                        // DEBUG LOG: Track state synchronization
                        Log.i("DEBUG_STATE", "Before sync - repoLastAuto: ${repoState.lastAutoWaypoint}, currentLastAuto: ${currentState.lastAutoWaypoint}, repoCurrent: ${repoState.currentWaypoint}, currentCurrent: ${currentState.currentWaypoint}")

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

            viewModelScope.launch {
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
        Log.i("SharedVM", "Current mission names set - Project: $projectName, Plot: $plotName")
    }

    /**
     * Show the mission completion dialog with the given data
     * WebSocket stays connected until user clicks OK
     */
    fun showMissionCompletionDialog(totalTime: String, totalAcres: String, sprayedAcres: String, consumedLitres: String) {
        _missionCompletionData.value = MissionCompletionData(totalTime, totalAcres, sprayedAcres, consumedLitres)
        _showMissionCompletionDialog.value = true
        Log.i("SharedVM", "Mission completion dialog triggered - Time: $totalTime, Acres: $totalAcres, Sprayed: $sprayedAcres, Litres: $consumedLitres")
        // 🔌 WebSocket stays connected - will be disconnected when user clicks OK
    }

    /**
     * Dismiss the mission completion dialog
     */
    fun dismissMissionCompletionDialog() {
        _showMissionCompletionDialog.value = false
        Log.i("SharedVM", "Mission completion dialog dismissed")
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
        Log.i("SharedVM", "Mission completion data saved - Project: $projectName, Plot: $plotName, CropType: $cropType")

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
                cropType = cropType
            )
            Log.i("SharedVM", "📤 Mission summary sent with cropType=$cropType")
        } catch (e: Exception) {
            Log.e("SharedVM", "❌ Failed to send mission summary: ${e.message}", e)
        }

        // 🔌 Disconnect WebSocket after sending summary
        try {
            WebSocketManager.getInstance().disconnect()
            Log.i("SharedVM", "🔌 WebSocket disconnected - User clicked OK on mission completion dialog")
        } catch (e: Exception) {
            Log.e("SharedVM", "❌ Failed to disconnect WebSocket: ${e.message}", e)
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

    // Spray control state
    private val _sprayEnabled = MutableStateFlow(false)
    val sprayEnabled: StateFlow<Boolean> = _sprayEnabled.asStateFlow()

    private val _sprayRate = MutableStateFlow(100f) // 10% to 100%
    val sprayRate: StateFlow<Float> = _sprayRate.asStateFlow()

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
            Log.i("SharedVM", "=== MODE CHANGED: AUTO → LOITER === (IGNORED - user in MANUAL mode)")
            return
        }

        Log.i("SharedVM", "=== MODE CHANGED: AUTO → LOITER ===")
        Log.i("SharedVM", "Waypoint at mode change: $waypointNumber")

        _resumePointWaypoint.value = waypointNumber

        // Capture current drone location temporarily (will only be shown if user confirms)
        val currentLat = _telemetryState.value.latitude
        val currentLon = _telemetryState.value.longitude
        if (currentLat != null && currentLon != null) {
            _pendingResumeLocation = LatLng(currentLat, currentLon)
            Log.i("SharedVM", "Pending resume point location captured: $currentLat, $currentLon")
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

        Log.i("SharedVM", "User confirmed resume point at waypoint $waypointNumber")

        // Now set the resume location to show the "R" marker
        _pendingResumeLocation?.let {
            _resumePointLocation.value = it
            Log.i("SharedVM", "Resume point marker set at: ${it.latitude}, ${it.longitude}")
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
        Log.i("SharedVM", "User cancelled resume point")

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
            Log.i("SharedVM", "═══════════════════════════════════════")
            Log.i("SharedVM", "=== AUTO PROCESSING RESUME POINT (BACKGROUND) ===")
            Log.i("SharedVM", "Resume waypoint: $waypointNumber")

            // Get the resume location (where drone was paused)
            val resumeLocation = _resumePointLocation.value
            Log.i("SharedVM", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
            Log.i("SharedVM", "═══════════════════════════════════════")

            try {
                // Step 1: Check connection
                if (!_telemetryState.value.connected) {
                    Log.e("SharedVM", "Not connected to FC - skipping auto resume processing")
                    return@launch
                }

                // Step 2: Get current mission from FC (silent - no progress updates)
                Log.i("SharedVM", "Retrieving mission from FC (background)...")
                val allWaypoints = repo?.getAllWaypoints()
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    Log.e("SharedVM", "Failed to retrieve mission from FC")
                    return@launch
                }

                Log.i("SharedVM", "Retrieved ${allWaypoints.size} waypoints from FC")

                // Step 3: Filter waypoints from resume point, inserting resume location as first WP
                Log.i("SharedVM", "Filtering waypoints from resume point (background)...")
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    waypointNumber,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude
                )
                if (filtered == null || filtered.isEmpty()) {
                    Log.e("SharedVM", "Filtering resulted in empty mission")
                    return@launch
                }

                Log.i("SharedVM", "Filtered to ${filtered.size} waypoints")

                // Step 4: Resequence waypoints
                Log.i("SharedVM", "Resequencing waypoints (background)...")
                val resequenced = repo?.resequenceWaypoints(filtered)
                if (resequenced == null || resequenced.isEmpty()) {
                    Log.e("SharedVM", "Resequencing failed")
                    return@launch
                }

                Log.i("SharedVM", "Resequenced to ${resequenced.size} waypoints")

                // Step 5: Validate sequence numbers
                val sequences = resequenced.map { it.seq.toInt() }
                val expectedSequences = (0 until resequenced.size).toList()
                if (sequences != expectedSequences) {
                    Log.e("SharedVM", "❌ Invalid sequence numbers!")
                    return@launch
                }
                Log.i("SharedVM", "✅ Sequence validation passed")

                // Step 6: Upload modified mission to FC (silent)
                Log.i("SharedVM", "Uploading modified mission to FC (background)...")
                val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
                if (!uploadSuccess) {
                    Log.e("SharedVM", "❌ Mission upload failed")
                    return@launch
                }

                Log.i("SharedVM", "✅ Modified mission uploaded to FC")

                delay(500)

                // Step 7: Set current waypoint to 1
                val setWpResult = repo?.setCurrentWaypoint(1) ?: false
                if (setWpResult) {
                    Log.i("SharedVM", "✅ Current waypoint set to 1")
                } else {
                    Log.w("SharedVM", "⚠️ Failed to set current waypoint, continuing anyway")
                }

                // Mark that resume mission is ready
                _resumeMissionReady.value = true
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size

                Log.i("SharedVM", "═══════════════════════════════════════")
                Log.i("SharedVM", "✅ Resume mission ready (background processing complete)")
                Log.i("SharedVM", "═══════════════════════════════════════")

            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to auto-process resume point", e)
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
                Log.e("SharedVM", "No resume waypoint stored!")
                onResult(false, "No resume waypoint stored")
                return@launch
            }

            // Get the resume location (where drone was paused)
            val resumeLocation = _resumePointLocation.value

            Log.i("SharedVM", "═══════════════════════════════════════")
            Log.i("SharedVM", "=== CONFIRM ADD RESUME HERE ===")
            Log.i("SharedVM", "Resume waypoint: $resumeWaypoint")
            Log.i("SharedVM", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
            Log.i("SharedVM", "═══════════════════════════════════════")

            _showAddResumeHerePopup.value = false

            try {
                // Step 1: Check connection
                onProgress("Checking connection...")
                if (!_telemetryState.value.connected) {
                    Log.e("SharedVM", "Not connected to FC")
                    onResult(false, "Not connected to flight controller")
                    return@launch
                }

                onProgress("Retrieving mission from FC...")

                // Step 2: Get current mission from FC
                val allWaypoints = repo?.getAllWaypoints()
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    Log.e("SharedVM", "Failed to retrieve mission from FC")
                    onResult(false, "Failed to retrieve mission from flight controller")
                    return@launch
                }

                Log.i("SharedVM", "Retrieved ${allWaypoints.size} waypoints from FC")

                // Log original mission
                Log.i("SharedVM", "--- Original Mission ---")
                allWaypoints.forEach { wp ->
                    val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
                    Log.i("SharedVM", "  seq=${wp.seq}: $cmdName frame=${wp.frame.value} current=${wp.current}")
                }

                onProgress("Filtering waypoints from resume point...")

                // Step 3: Filter waypoints from resume point, inserting resume location as first WP
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    resumeWaypoint,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude
                )
                if (filtered == null || filtered.isEmpty()) {
                    Log.e("SharedVM", "Filtering resulted in empty mission")
                    onResult(false, "No waypoints after resume point")
                    return@launch
                }

                Log.i("SharedVM", "Filtered to ${filtered.size} waypoints")

                onProgress("Resequencing waypoints...")

                // Step 4: Resequence waypoints
                val resequenced = repo?.resequenceWaypoints(filtered)
                if (resequenced == null || resequenced.isEmpty()) {
                    Log.e("SharedVM", "Resequencing failed")
                    onResult(false, "Failed to resequence waypoints")
                    return@launch
                }

                Log.i("SharedVM", "Resequenced to ${resequenced.size} waypoints")

                // Log final mission structure
                Log.i("SharedVM", "--- Final Resume Mission ---")
                resequenced.forEach { wp ->
                    val cmdName = wp.command.entry?.name ?: "CMD_${wp.command.value}"
                    Log.i("SharedVM", "  seq=${wp.seq}: $cmdName frame=${wp.frame.value} alt=${wp.z}m target=${wp.targetSystem}:${wp.targetComponent}")
                }

                // Step 5: Validate sequence numbers (skip TAKEOFF validation for resume)
                onProgress("Validating mission...")
                val sequences = resequenced.map { it.seq.toInt() }
                val expectedSequences = (0 until resequenced.size).toList()
                if (sequences != expectedSequences) {
                    Log.e("SharedVM", "❌ Invalid sequence numbers!")
                    Log.e("SharedVM", "Expected: $expectedSequences, Got: $sequences")
                    onResult(false, "Invalid mission sequence")
                    return@launch
                }
                Log.i("SharedVM", "✅ Sequence validation passed")

                onProgress("Uploading modified mission to FC...")

                // Step 6: Upload modified mission to FC
                val uploadSuccess = repo?.uploadMissionWithAck(resequenced) ?: false
                if (!uploadSuccess) {
                    Log.e("SharedVM", "❌ Mission upload failed")
                    onResult(false, "Failed to upload mission to FC")
                    return@launch
                }

                Log.i("SharedVM", "✅ Modified mission uploaded to FC")

                delay(500)

                onProgress("Setting start waypoint...")

                // Step 7: Set current waypoint to 1 (first item after HOME in resequenced mission)
                val setWpResult = repo?.setCurrentWaypoint(1) ?: false
                if (setWpResult) {
                    Log.i("SharedVM", "✅ Current waypoint set to 1")
                } else {
                    Log.w("SharedVM", "⚠️ Failed to set current waypoint, continuing anyway")
                }

                // Mark that resume mission is ready
                _resumeMissionReady.value = true
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size

                Log.i("SharedVM", "═══════════════════════════════════════")
                Log.i("SharedVM", "✅ Resume mission ready - waiting for AUTO mode")
                Log.i("SharedVM", "═══════════════════════════════════════")

                addNotification(
                    Notification(
                        message = "Resume point set at waypoint $resumeWaypoint - Switch to AUTO to resume",
                        type = NotificationType.SUCCESS
                    )
                )

                onResult(true, null)

            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to prepare resume mission", e)
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
        Log.i("SharedVM", "Add Resume Here popup dismissed")
    }

    /**
     * Called when mode changes to AUTO and we have a resume mission ready
     * This starts the mission from the resume point
     */
    fun onModeChangedToAuto() {
        if (_resumeMissionReady.value) {
            Log.i("SharedVM", "=== MODE CHANGED TO AUTO - STARTING RESUME MISSION ===")

            viewModelScope.launch {
                // Small delay to ensure FC is ready
                delay(300)

                // Send mission start command
                val startSuccess = repo?.startMission() ?: false

                if (startSuccess) {
                    Log.i("SharedVM", "✅ Resume mission started successfully")
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
                    addNotification(
                        Notification(
                            message = "Mission resumed from stored point",
                            type = NotificationType.SUCCESS
                        )
                    )
                    ttsManager?.announceMissionResumed()
                } else {
                    Log.e("SharedVM", "Failed to start resume mission")
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

    fun setSurveyPolygon(polygon: List<LatLng>) {
        _surveyPolygon.value = polygon
        updateGeofencePolygon()
        updateSurveyArea()
    }
    fun setGridLines(lines: List<Pair<LatLng, LatLng>>) { _gridLines.value = lines }
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
        val oldRadius = _fenceRadius.value

        // Calculate the delta (change in buffer distance)
        val deltaMeters = (newRadius - oldRadius).toDouble()

        _fenceRadius.value = newRadius

        // If geofence is enabled and we have an existing polygon, scale it
        if (_geofenceEnabled.value && _geofencePolygon.value.size >= 3 && deltaMeters != 0.0) {
            // Scale the existing polygon by the delta
            val scaledPolygon = GeofenceUtils.scalePolygon(_geofencePolygon.value, deltaMeters)
            if (scaledPolygon.size >= 3) {
                _geofencePolygon.value = scaledPolygon
                Log.i("Geofence", "Geofence polygon scaled by ${deltaMeters}m (new buffer: ${newRadius}m)")
            }
        } else {
            // No existing polygon, generate a new one
            updateGeofencePolygon()
        }

        // Update the previous radius tracker
        _previousFenceRadius = newRadius
    }

    fun setGeofenceEnabled(enabled: Boolean) {
        _geofenceEnabled.value = enabled
        if (enabled) {
            // Reset geofence state for fresh monitoring
            resetGeofenceState()

            // Capture current drone position as home position if not set
            val droneLat = _telemetryState.value.latitude
            val droneLon = _telemetryState.value.longitude
            if (_homePosition.value == null && droneLat != null && droneLon != null) {
                _homePosition.value = LatLng(droneLat, droneLon)
                Log.i("Geofence", "Home position captured: $droneLat, $droneLon")
            }
            updateGeofencePolygon()

            // 🔥 CRITICAL FIX: Send FENCE_ENABLE=1 to Flight Controller
            viewModelScope.launch {
                try {
                    Log.i("Geofence", "📤 Sending FENCE_ENABLE=1 to Flight Controller")
                    val result = setParameter("FENCE_ENABLE", 1f, timeoutMs = 5000L)
                    if (result != null) {
                        Log.i("Geofence", "✅ FENCE_ENABLE=1 confirmed by FC")
                        addNotification(
                            Notification(
                                message = "Geofence enabled on Flight Controller",
                                type = NotificationType.INFO
                            )
                        )
                    } else {
                        Log.e("Geofence", "⚠️ No response from FC for FENCE_ENABLE=1")
                        addNotification(
                            Notification(
                                message = "Geofence enable command sent (no confirmation)",
                                type = NotificationType.WARNING
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("Geofence", "❌ Failed to enable geofence on FC", e)
                    addNotification(
                        Notification(
                            message = "Failed to enable geofence on FC: ${e.message}",
                            type = NotificationType.ERROR
                        )
                    )
                }
            }

            Log.i("Geofence", "✓ Geofence ENABLED - monitoring active")
            addNotification(
                Notification(
                    message = "Geofence enabled - monitoring active",
                    type = NotificationType.INFO
                )
            )
        } else {
            // 🔥 FIX: CRITICAL - Disable geofence on FC and reset ALL state
            viewModelScope.launch {
                try {
                    Log.i("Geofence", "📤 Sending FENCE_ENABLE=0 to Flight Controller")
                    val result = setParameter("FENCE_ENABLE", 0f, timeoutMs = 5000L)
                    if (result != null) {
                        Log.i("Geofence", "✅ FENCE_ENABLE=0 confirmed by FC")
                    } else {
                        Log.e("Geofence", "⚠️ No response from FC for FENCE_ENABLE=0")
                    }
                } catch (e: Exception) {
                    Log.e("Geofence", "❌ Failed to disable geofence on FC", e)
                }
            }

            // Reset ALL geofence state
            resetGeofenceState()
            _geofencePolygon.value = emptyList()
            _homePosition.value = null
            Log.i("Geofence", "Geofence DISABLED - all state reset")
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
     */
    fun updateGeofencePolygonManually(polygon: List<LatLng>) {
        if (_geofenceEnabled.value && polygon.size >= 3) {
            _geofencePolygon.value = polygon
            Log.i("Geofence", "Geofence polygon manually updated with ${polygon.size} vertices")

            // 🔥 Upload manually updated geofence to FC
            viewModelScope.launch {
                uploadGeofenceToFC(polygon)
            }
        }
    }

    private fun updateGeofencePolygon() {
        if (!_geofenceEnabled.value) {
            _geofencePolygon.value = emptyList()
            return
        }

        val allWaypoints = mutableListOf<LatLng>()

        // ALWAYS include home position (where drone was when geofence was enabled)
        val homePos = _homePosition.value
        if (homePos != null) {
            allWaypoints.add(homePos)
            Log.d("Geofence", "Added home position to geofence: $homePos")
        }
        
        // DO NOT include current drone position - geofence should remain stationary
        // The drone should move within the fence, not the fence move with the drone

        // Add mission waypoints
        if (_uploadedWaypoints.value.isNotEmpty()) {
            allWaypoints.addAll(_uploadedWaypoints.value)
            Log.d("Geofence", "Added ${_uploadedWaypoints.value.size} uploaded waypoints")
        } else {
            allWaypoints.addAll(_planningWaypoints.value)
            if (_planningWaypoints.value.isNotEmpty()) {
                Log.d("Geofence", "Added ${_planningWaypoints.value.size} planning waypoints")
            }
        }
        allWaypoints.addAll(_surveyPolygon.value)
        if (_surveyPolygon.value.isNotEmpty()) {
            Log.d("Geofence", "Added ${_surveyPolygon.value.size} survey polygon points")
        }
        allWaypoints.addAll(_gridWaypoints.value)
        if (_gridWaypoints.value.isNotEmpty()) {
            Log.d("Geofence", "Added ${_gridWaypoints.value.size} grid waypoints")
        }

        if (allWaypoints.isNotEmpty()) {
            // Use default 5m buffer distance
            val bufferDistance = _fenceRadius.value.toDouble().coerceAtLeast(5.0)
            Log.i("Geofence", "Generating ${if (_useSquareGeofence.value) "square" else "polygon"} geofence with ${allWaypoints.size} points, buffer distance: ${bufferDistance}m")

            val geofenceShape = if (_useSquareGeofence.value) {
                GeofenceUtils.generateSquareGeofence(allWaypoints, bufferDistance)
            } else {
                GeofenceUtils.generatePolygonBuffer(allWaypoints, bufferDistance)
            }

            if (geofenceShape.size >= 3) {
                _geofencePolygon.value = geofenceShape
                Log.i("Geofence", "✓ Geofence ${if (_useSquareGeofence.value) "square" else "polygon"} generated successfully with ${geofenceShape.size} vertices")

                // 🔥 VALIDATION: Verify all source waypoints are inside the generated geofence
                var allInside = true
                for ((index, wp) in allWaypoints.withIndex()) {
                    val isInside = GeofenceUtils.isPointInPolygon(wp, geofenceShape)
                    val distToEdge = GeofenceUtils.distanceToPolygonEdge(wp, geofenceShape)
                    if (!isInside) {
                        Log.e("Geofence", "❌ VALIDATION FAILED: Waypoint $index at ${wp.latitude}, ${wp.longitude} is OUTSIDE generated geofence!")
                        allInside = false
                    } else {
                        Log.d("Geofence", "✓ Waypoint $index inside geofence, ${String.format("%.1f", distToEdge)}m from edge")
                    }
                }

                if (!allInside) {
                    Log.e("Geofence", "⚠️ WARNING: Some waypoints are outside the geofence! Buffer may be too small.")
                    // Increase buffer and regenerate
                    val largerBuffer = bufferDistance + 5.0
                    Log.i("Geofence", "Attempting to regenerate with larger buffer: ${largerBuffer}m")
                    val largerGeofence = if (_useSquareGeofence.value) {
                        GeofenceUtils.generateSquareGeofence(allWaypoints, largerBuffer)
                    } else {
                        GeofenceUtils.generatePolygonBuffer(allWaypoints, largerBuffer)
                    }
                    _geofencePolygon.value = largerGeofence
                    Log.i("Geofence", "✓ Regenerated geofence with larger buffer")

                    // 🔥 Upload regenerated geofence to FC
                    if (largerGeofence.size >= 3) {
                        viewModelScope.launch {
                            uploadGeofenceToFC(largerGeofence)
                        }
                    }
                } else {
                    // 🔥 Upload original geofence to FC
                    viewModelScope.launch {
                        uploadGeofenceToFC(geofenceShape)
                    }
                }
            } else {
                Log.w("Geofence", "Failed to generate valid geofence")
                _geofencePolygon.value = emptyList()
            }
        } else {
            Log.w("Geofence", "No waypoints available for geofence")
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
                Log.w("SharedVM", "Point not in geofence: $point")
                return false
            }
        }
        return true
    }

    /**
     * 🔥 CRITICAL FIX: Upload geofence points to Flight Controller
     * This function properly clears old fence and uploads new fence points
     */
    private suspend fun uploadGeofenceToFC(polygon: List<LatLng>) {
        if (polygon.size < 3) {
            Log.w("Geofence", "❌ Cannot upload geofence: insufficient points (${polygon.size})")
            return
        }

        try {
            Log.i("Geofence", "🔥 Starting geofence upload to FC: ${polygon.size} points")

            // STEP 1: Clear existing fence by setting FENCE_TOTAL to 0
            Log.i("Geofence", "📤 Clearing existing fence points (FENCE_TOTAL=0)")
            val clearResult = setParameter("FENCE_TOTAL", 0f, timeoutMs = 5000L)
            if (clearResult != null) {
                Log.i("Geofence", "✅ Existing fence cleared successfully")
            } else {
                Log.w("Geofence", "⚠️ FENCE_TOTAL=0 sent but no confirmation")
            }

            // STEP 2: Upload fence points as mission items with fence mission type
            Log.i("Geofence", "📤 Uploading ${polygon.size} fence points to FC")
            val fenceItems = mutableListOf<MissionItemInt>()

            polygon.forEachIndexed { index, point ->
                val missionItem = MissionItemInt(
                    targetSystem = 1u,
                    targetComponent = 1u,
                    seq = index.toUShort(),
                    frame = MavEnumValue.of(MavFrame.GLOBAL),
                    command = MavEnumValue.of(MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION),
                    current = if (index == 0) 1u else 0u,
                    autocontinue = 1u,
                    param1 = polygon.size.toFloat(), // Total vertices in the first point
                    param2 = 0f,
                    param3 = 0f,
                    param4 = 0f,
                    x = (point.latitude * 1e7).toInt(),
                    y = (point.longitude * 1e7).toInt(),
                    z = 0f,
                    missionType = MavEnumValue.of(MavMissionType.FENCE)
                )
                fenceItems.add(missionItem)
            }

            // Upload fence items using the existing mission upload infrastructure
            val uploadResult = repo?.uploadFenceItems(fenceItems) ?: false
            if (uploadResult) {
                Log.i("Geofence", "✅ Fence points uploaded successfully")

                // STEP 3: Set FENCE_TOTAL to the number of points
                Log.i("Geofence", "📤 Setting FENCE_TOTAL=${polygon.size}")
                val totalResult = setParameter("FENCE_TOTAL", polygon.size.toFloat(), timeoutMs = 5000L)
                if (totalResult != null) {
                    Log.i("Geofence", "✅ FENCE_TOTAL=${polygon.size} confirmed")
                } else {
                    Log.w("Geofence", "⚠️ FENCE_TOTAL=${polygon.size} sent but no confirmation")
                }

                addNotification(
                    Notification(
                        message = "✅ Geofence uploaded to FC (${polygon.size} points)",
                        type = NotificationType.SUCCESS
                    )
                )
            } else {
                Log.e("Geofence", "❌ Failed to upload fence points to FC")
                addNotification(
                    Notification(
                        message = "❌ Failed to upload geofence to FC",
                        type = NotificationType.ERROR
                    )
                )
            }

        } catch (e: Exception) {
            Log.e("Geofence", "❌ Geofence upload failed", e)
            addNotification(
                Notification(
                    message = "❌ Geofence upload error: ${e.message}",
                    type = NotificationType.ERROR
                )
            )
        }
    }

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
            Log.e("MissionValidation", "❌ Sequence validation FAILED")
            Log.e("MissionValidation", "Expected sequences: $expectedSequences")
            Log.e("MissionValidation", "Actual sequences: $sequences")
            Log.e("MissionValidation", "Missing sequences: ${expectedSequences.minus(sequences.toSet())}")
            Log.e("MissionValidation", "Extra sequences: ${sequences.toSet().minus(expectedSequences.toSet())}")
            
            // Log each mission item for debugging
            missionItems.forEach { item ->
                Log.e("MissionValidation", "Item: seq=${item.seq} cmd=${item.command.value} current=${item.current}")
            }
            
            return Pair(false, "Invalid sequence numbers - Expected: $expectedSequences, Got: $sequences")
        }

        // Find NAV_TAKEOFF command
        val hasTakeoff = missionItems.any { it.command.value == MavCmdId.NAV_TAKEOFF }
        if (!hasTakeoff) {
            Log.w("MissionValidation", "⚠️ Mission does not contain NAV_TAKEOFF command!")
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
            Log.w("MissionValidation", "⚠️ NAV_TAKEOFF at seq=$takeoffSeq (expected seq=1)")
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
                        Log.w("MissionValidation", "⚠️ Waypoint at seq=${item.seq} has coordinates (0,0)")
                    }
                }
                
                if (item.z < AltitudeLimits.MIN_ALTITUDE || item.z > AltitudeLimits.MAX_ALTITUDE) {
                    return Pair(false, "Invalid altitude at seq=${item.seq}: ${item.z}m (valid range: ${AltitudeLimits.MIN_ALTITUDE}-${AltitudeLimits.MAX_ALTITUDE}m)")
                }
            }
        }

        Log.i("MissionValidation", "✅ Mission structure validation passed")
        return Pair(true, null)
    }

    fun uploadMission(missionItems: List<MissionItemInt>, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                Log.i("MissionUpload", "═══ VM: Starting mission upload (${missionItems.size} items) ═══")

                if (repo == null) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    Log.e("MissionUpload", "VM: No repository available")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    Log.e("MissionUpload", "VM: FCU not detected")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                // Validate mission structure before uploading
                Log.i("MissionUpload", "VM: Validating mission structure...")
                val (isValid, errorMessage) = validateMissionStructure(missionItems)
                if (!isValid) {
                    _missionUploaded.value = false
                    lastUploadedCount = 0
                    Log.e("MissionUpload", "VM: Mission validation failed - $errorMessage")
                    addNotification(
                        Notification("Mission validation failed: $errorMessage", NotificationType.ERROR)
                    )
                    onResult(false, "Mission validation failed: $errorMessage")
                    return@launch
                }
                Log.i("MissionUpload", "VM: Mission structure validation passed")

                // Show progress: Uploading
                _missionUploadProgress.value = MissionUploadProgress(
                    stage = "Uploading",
                    currentItem = 0,
                    totalItems = missionItems.size,
                    message = "Uploading ${missionItems.size} waypoints..."
                )
                Log.d("MissionUpload", "VM: Progress UI updated - Uploading")

                val success = repo?.uploadMissionWithAck(missionItems) ?: false

                _missionUploaded.value = success
                if (success) {
                    Log.i("MissionUpload", "VM: Upload successful, processing waypoints...")
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
                        Log.d("MissionUpload", "VM: Mission area (survey polygon): $formatted")
                    } else if (waypoints.size >= 3) {
                        val formatted = GridUtils.calculateAndFormatPolygonArea(waypoints)
                        val areaMeters = GridUtils.calculatePolygonArea(waypoints)
                        _missionAreaSqMeters.value = areaMeters
                        _missionAreaFormatted.value = formatted
                        Log.d("MissionUpload", "VM: Mission area (waypoints): $formatted")
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

                    Log.i("MissionUpload", "VM: ✅ Upload complete - ${missionItems.size} items")
                    onResult(true, null)
                } else {
                    Log.e("MissionUpload", "VM: ❌ Upload failed")
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

                Log.e("MissionUpload", "VM: ❌ Upload exception: ${e.message}", e)
                onResult(false, e.message)
            }
        }
    }

    fun startMission(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _telemetryState.value = _telemetryState.value.copy(isMissionActive = false, missionCompleted = false, missionCompletedHandled = false, missionElapsedSec = null)
            try {
                Log.i("SharedVM", "Starting mission start sequence...")

                if (repo == null) {
                    Log.w("SharedVM", "No repo available, cannot start mission")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    Log.w("SharedVM", "FCU not detected, cannot start mission")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                if (!_missionUploaded.value || lastUploadedCount == 0) {
                    Log.w("SharedVM", "No mission uploaded or acknowledged, cannot start")
                    onResult(false, "No mission uploaded. Please upload a mission first.")
                    return@launch
                }
                Log.i("SharedVM", "✓ Mission upload acknowledged (${lastUploadedCount} items)")

                if (!_telemetryState.value.armable) {
                    Log.w("SharedVM", "Vehicle not armable, cannot start mission")
                    onResult(false, "Vehicle not armable. Check sensors and GPS.")
                    return@launch
                }

                val sats = _telemetryState.value.sats ?: 0
                if (sats < 6) {
                    Log.w("SharedVM", "Insufficient GPS satellites ($sats), minimum 6 required")
                    onResult(false, "Insufficient GPS satellites ($sats). Need at least 6 for mission.")
                    return@launch
                }

                val currentMode = _telemetryState.value.mode
                val isInArmableMode = currentMode?.equals("Stabilize", ignoreCase = true) == true ||
                        currentMode?.equals("Loiter", ignoreCase = true) == true

                if (!isInArmableMode) {
                    Log.i("SharedVM", "Current mode '$currentMode' not suitable for arming, switching to Stabilize")
                    repo?.changeMode(MavMode.STABILIZE)
                    val modeTimeout = 5000L
                    val modeStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - modeStart < modeTimeout) {
                        if (_telemetryState.value.mode?.equals("Stabilize", ignoreCase = true) == true) {
                            Log.i("SharedVM", "✓ Successfully switched to Stabilize mode")
                            break
                        }
                        delay(500)
                    }
                    if (!(_telemetryState.value.mode?.equals("Stabilize", ignoreCase = true) == true)) {
                        Log.w("SharedVM", "Failed to switch to Stabilize mode within timeout")
                        onResult(false, "Failed to switch to suitable mode for arming. Current mode: ${_telemetryState.value.mode}")
                        return@launch
                    }
                } else {
                    Log.i("SharedVM", "✓ Already in suitable mode for arming: $currentMode")
                }

                if (!_telemetryState.value.armed) {
                    Log.i("SharedVM", "Vehicle not armed - attempting to arm")
                    repo?.arm()
                    val armTimeout = 10000L
                    val armStart = System.currentTimeMillis()
                    while (!_telemetryState.value.armed && System.currentTimeMillis() - armStart < armTimeout) {
                        delay(500)
                    }
                    if (!_telemetryState.value.armed) {
                        Log.w("SharedVM", "Vehicle did not arm within timeout")
                        onResult(false, "Vehicle failed to arm. Check pre-arm conditions.")
                        return@launch
                    }
                    Log.i("SharedVM", "✓ Vehicle armed successfully")
                } else {
                    Log.i("SharedVM", "✓ Vehicle already armed")
                }

                if (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true) {
                    Log.i("SharedVM", "Switching vehicle mode to AUTO")
                    repo?.changeMode(MavMode.AUTO)
                    val autoModeTimeout = 8000L
                    val autoModeStart = System.currentTimeMillis()
                    while (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true &&
                        System.currentTimeMillis() - autoModeStart < autoModeTimeout) {
                        delay(500)
                    }
                    if (_telemetryState.value.mode?.contains("Auto", ignoreCase = true) != true) {
                        Log.w("SharedVM", "Vehicle did not switch to AUTO mode within timeout")
                        onResult(false, "Failed to switch to AUTO mode. Current mode: ${_telemetryState.value.mode}")
                        return@launch
                    }
                    Log.i("SharedVM", "✓ Vehicle mode is now AUTO")
                } else {
                    Log.i("SharedVM", "✓ Vehicle already in AUTO mode")
                }

                delay(1000)

                Log.i("SharedVM", "Sending start mission command")
                val result = repo?.startMission() ?: false
                if (result) {
                    Log.i("SharedVM", "✓ Mission start acknowledged by FCU")

                    // 🔥 Connect WebSocket when mission starts
                    try {
                        val wsManager = WebSocketManager.getInstance()
                        if (!wsManager.isConnected) {
                            Log.i("SharedVM", "🔌 Opening WebSocket connection for mission...")

                            // 🔥 CRITICAL: Get latest pilotId and adminId from SessionManager
                            // This ensures values are up-to-date if user logged in after MainActivity loaded
                            GCSApplication.getInstance()?.let { app ->
                                val pilotId = com.example.aerogcsclone.api.SessionManager.getPilotId(app)
                                val adminId = com.example.aerogcsclone.api.SessionManager.getAdminId(app)
                                wsManager.pilotId = pilotId
                                wsManager.adminId = adminId
                                Log.i("SharedVM", "📋 Updated WebSocket credentials: pilotId=$pilotId, adminId=$adminId")

                                // Warn if pilot is not logged in
                                if (pilotId <= 0) {
                                    Log.e("SharedVM", "⚠️ WARNING: pilotId=$pilotId - User may not be logged in! Telemetry will not be saved.")
                                }
                            }

                            // 🔥 Set plot name before connecting
                            wsManager.selectedPlotName = _currentPlotName.value
                            Log.i("SharedVM", "📋 Plot name set for WebSocket: ${_currentPlotName.value}")

                            // 🔥 Set flight mode (Automatic or Manual)
                            wsManager.selectedFlightMode = _userSelectedFlightMode.value.name
                            Log.i("SharedVM", "📋 Flight mode set for WebSocket: ${_userSelectedFlightMode.value.name}")

                            // 🔥 Set mission type (Grid or Waypoint)
                            wsManager.selectedMissionType = _selectedMissionType.value.name
                            Log.i("SharedVM", "📋 Mission type set for WebSocket: ${_selectedMissionType.value.name}")

                            // 🔥 Set grid setup source (KML_IMPORT, MAP_DRAW, DRONE_POSITION, RC_CONTROL)
                            wsManager.gridSetupSource = _gridSetupSource.value.name
                            Log.i("SharedVM", "📋 Grid setup source set for WebSocket: ${_gridSetupSource.value.name}")

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
                                Log.i("SharedVM", "✅ WebSocket ready after ${waitTime}ms")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SharedVM", "Failed to connect WebSocket", e)
                    }

                    // ✅ Send mission status STARTED to backend (crash-safe)
                    // Only send if WebSocket is connected and has a mission_id
                    try {
                        val wsManager = WebSocketManager.getInstance()
                        if (wsManager.isConnected && wsManager.missionId != null) {
                            wsManager.sendMissionStatus(WebSocketManager.MISSION_STATUS_STARTED)
                            wsManager.sendMissionEvent(
                                eventType = "MISSION_STARTED",
                                eventStatus = "INFO",
                                description = "Mission started successfully"
                            )
                            Log.i("SharedVM", "✅ Mission status STARTED sent to backend")
                        } else {
                            Log.w("SharedVM", "⚠️ Skipping mission status - WebSocket not ready (connected=${wsManager.isConnected}, missionId=${wsManager.missionId})")
                        }
                    } catch (e: Exception) {
                        Log.e("SharedVM", "Failed to send STARTED status", e)
                    }

                    // Start yaw enforcement if yaw hold is enabled
                    if (_yawHoldEnabled.value && _lockedYaw.value != null) {
                        Log.i("SharedVM", "🧭 Starting yaw enforcement for locked yaw: ${_lockedYaw.value}°")
                        startYawEnforcement()
                    }

                    onResult(true, null)
                } else {
                    Log.e("SharedVM", "Mission start failed or not acknowledged")
                    onResult(false, "Mission start failed. Check vehicle status and try again.")
                }
            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to start mission", e)
                onResult(false, e.message)
            }
        }
    }

    fun readMissionFromFcu() {
        viewModelScope.launch {
            if (repo == null) {
                Log.w("SharedVM", "No repo available, cannot request mission readback")
                return@launch
            }
            try {
                repo?.requestMissionAndLog()
            } catch (e: Exception) {
                Log.e("SharedVM", "Exception during mission readback", e)
            }
        }
    }

    fun pauseMission(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val currentWp = _telemetryState.value.currentWaypoint
                val lastAutoWp = _telemetryState.value.lastAutoWaypoint
                val waypointToStore = if (lastAutoWp > 0) lastAutoWp else currentWp

                // DEBUG LOGS
                Log.i("SharedVM", "=== PAUSE MISSION ===")
                Log.i("SharedVM", "lastAutoWaypoint: $lastAutoWp")
                Log.i("SharedVM", "currentWaypoint: $currentWp")
                Log.i("SharedVM", "waypointToStore (will be pausedAtWaypoint): $waypointToStore")
                Log.i("DEBUG_PAUSE", "Pausing - lastAutoWp: $lastAutoWp, currentWp: $currentWp, storing: $waypointToStore")

                // Switch to LOITER to hold position
                // NOTE: The mode change will be detected by TelemetryRepository which will
                // trigger onModeChangedToLoiterFromAuto() to show the "Add Resume Here" popup
                val result = repo?.changeMode(MavMode.LOITER) ?: false

                if (result) {
                    // Don't set missionPaused here - let the mode change detection handle it
                    // The popup will be shown by onModeChangedToLoiterFromAuto()
                    Log.i("SharedVM", "LOITER mode change command sent. Waiting for mode change detection...")

                    // ✅ Send mission status PAUSED to backend (crash-safe)
                    try {
                        WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_PAUSED)
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MISSION_PAUSED",
                            eventStatus = "INFO",
                            description = "Mission paused"
                        )
                    } catch (e: Exception) {
                        Log.e("SharedVM", "Failed to send PAUSED status", e)
                    }

                    // Announce via TTS
                    ttsManager?.announceMissionPaused(waypointToStore ?: 0)
                    onResult(true, null)
                } else {
                    onResult(false, "Failed to pause mission")
                }
            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to pause mission", e)
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

                Log.i("ResumeMission", "═══════════════════════════════════════")
                Log.i("ResumeMission", "Starting Resume Mission")
                Log.i("ResumeMission", "Resume at waypoint: $resumeWaypointNumber")
                Log.i("ResumeMission", "Resume location: ${resumeLocation?.latitude}, ${resumeLocation?.longitude}")
                Log.i("ResumeMission", "═══════════════════════════════════════")

                // Step 1: Pre-flight Checks
                onProgress("Step 1/8: Pre-flight checks...")
                if (!_telemetryState.value.connected) {
                    onResult(false, "Not connected to flight controller")
                    return@launch
                }

                // Step 2: Retrieve Current Mission from FC
                onProgress("Step 2/8: Retrieving mission from flight controller...")
                Log.i("ResumeMission", "Retrieving current mission from FC...")
                val allWaypoints = repo?.getAllWaypoints()
                
                if (allWaypoints == null || allWaypoints.isEmpty()) {
                    Log.e("ResumeMission", "❌ Failed to retrieve mission from FC")
                    onResult(false, "Failed to retrieve mission from flight controller")
                    return@launch
                }

                // Log original mission structure
                Log.i("ResumeMission", "════════════════════════════════")
                Log.i("ResumeMission", "Original mission count: ${allWaypoints.size}")
                Log.i("ResumeMission", "Resume from waypoint: $resumeWaypointNumber")
                allWaypoints.forEach { wp ->
                    Log.i("ResumeMission", "  Original: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }

                // Step 3: Filter Waypoints for Resume, inserting resume location as first WP
                onProgress("Step 3/8: Filtering waypoints...")
                Log.i("ResumeMission", "Filtering waypoints for resume from waypoint $resumeWaypointNumber...")
                val filtered = repo?.filterWaypointsForResume(
                    allWaypoints,
                    resumeWaypointNumber,
                    resumeLatitude = resumeLocation?.latitude,
                    resumeLongitude = resumeLocation?.longitude
                )

                if (filtered == null || filtered.isEmpty()) {
                    Log.e("ResumeMission", "❌ Filtering resulted in empty mission")
                    onResult(false, "Mission filtering failed - no waypoints to resume")
                    return@launch
                }
                
                Log.i("ResumeMission", "────────────────────────────────")
                Log.i("ResumeMission", "Filtered mission count: ${filtered.size}")
                filtered.forEach { wp ->
                    Log.i("ResumeMission", "  Filtered: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }

                // Step 4: Resequence Waypoints
                onProgress("Step 4/8: Resequencing waypoints...")
                Log.i("ResumeMission", "Resequencing waypoints...")
                val resequenced = repo?.resequenceWaypoints(filtered)
                
                if (resequenced == null || resequenced.isEmpty()) {
                    Log.e("ResumeMission", "❌ Resequencing resulted in empty mission")
                    onResult(false, "Mission resequencing failed")
                    return@launch
                }
                
                Log.i("ResumeMission", "────────────────────────────────")
                Log.i("ResumeMission", "Resequenced mission count: ${resequenced.size}")
                resequenced.forEach { wp ->
                    Log.i("ResumeMission", "  Resequenced: seq=${wp.seq} cmd=${wp.command.value} current=${wp.current}")
                }
                Log.i("ResumeMission", "════════════════════════════════")

                // Step 5: Validate Mission Structure
                onProgress("Step 5/8: Validating mission...")
                val (isValid, validationError) = validateMissionStructure(resequenced)
                if (!isValid) {
                    Log.e("ResumeMission", "❌ Mission validation failed: $validationError")
                    onResult(false, "Mission validation failed: $validationError")
                    return@launch
                }
                Log.i("ResumeMission", "✅ Mission validation passed")

                // Step 6: Upload Modified Mission to FC
                onProgress("Step 6/8: Uploading mission to flight controller...")
                Log.i("ResumeMission", "Uploading ${resequenced.size} waypoints to FC...")
                val success = repo?.uploadMissionWithAck(resequenced) ?: false

                if (success) {
                    Log.i("ResumeMission", "✅ Mission upload confirmed by FC")
                    
                    // Verify by reading back the mission count
                    // Delay allows FC to fully commit mission to storage before verification
                    delay(1000)
                    val verifyCount = repo?.getMissionCount() ?: 0
                    if (verifyCount != resequenced.size) {
                        Log.e("ResumeMission", "⚠️ WARNING: FC reports $verifyCount waypoints but we uploaded ${resequenced.size}")
                    } else {
                        Log.i("ResumeMission", "✅ FC confirms $verifyCount waypoints stored")
                    }
                } else {
                    Log.e("ResumeMission", "❌ Mission upload FAILED - FC rejected mission")
                    onResult(false, "Mission upload failed - flight controller rejected mission")
                    return@launch
                }

                // Step 7: Set Current Waypoint to start execution
                onProgress("Step 7/8: Setting current waypoint...")
                Log.i("ResumeMission", "Setting current waypoint to 1 (start from first mission item after HOME)")
                val setWaypointSuccess = repo?.setCurrentWaypoint(1) ?: false

                if (!setWaypointSuccess) {
                    Log.w("ResumeMission", "Failed to set current waypoint, continuing anyway")
                }

                delay(500)

                // Step 8: Switch to AUTO Mode
                onProgress("Step 8/8: Switching to AUTO mode...")
                val currentMode = _telemetryState.value.mode
                Log.i("ResumeMission", "Current mode: $currentMode")

                var autoSuccess = false
                var retryCount = 0
                val maxRetries = 3

                while (!autoSuccess && retryCount < maxRetries) {
                    val attempt = retryCount + 1
                    Log.i("ResumeMission", "Attempt $attempt/$maxRetries: Sending AUTO mode command...")

                    autoSuccess = repo?.changeMode(MavMode.AUTO) ?: false

                    Log.i("ResumeMission", "Attempt $attempt result: ${if (autoSuccess) "SUCCESS" else "FAILED"}")

                    if (!autoSuccess) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            Log.w("ResumeMission", "Waiting 2 seconds before retry...")
                            delay(2000)
                        }
                    }
                }

                if (!autoSuccess) {
                    val finalMode = _telemetryState.value.mode
                    Log.e("ResumeMission", "❌ Failed to switch to AUTO after $maxRetries attempts")
                    Log.e("ResumeMission", "Final mode: $finalMode")
                    onResult(false, "Failed to switch to AUTO. Stuck in: $finalMode")
                    return@launch
                }

                Log.i("ResumeMission", "✅ Successfully switched to AUTO mode")

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
                    Log.e("SharedVM", "Failed to send RESUMED status", e)
                }

                // Mark mission as uploaded
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                Log.i("ResumeMission", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // Complete
                addNotification(
                    Notification(
                        message = "Mission resumed from waypoint $resumeWaypointNumber - Switch to AUTO to resume",
                        type = NotificationType.SUCCESS
                    )
                )
                ttsManager?.announceMissionResumed()

                Log.i("ResumeMission", "═══════════════════════════════════════")
                Log.i("ResumeMission", "✅ Resume Mission Complete!")
                Log.i("ResumeMission", "═══════════════════════════════════════")

                onResult(true, null)

            } catch (e: Exception) {
                Log.e("ResumeMission", "❌ Resume mission failed", e)
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
                    Log.i("SharedVM", "Resuming mission from current position")
                    val result = repo?.changeMode(MavMode.AUTO) ?: false

                    if (result) {
                        _telemetryState.update { it.copy(missionPaused = false) }

                        // ✅ Send mission status RESUMED to backend (crash-safe)
                        try {
                            WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                            WebSocketManager.getInstance().sendMissionEvent(
                                eventType = "MISSION_RESUMED",
                                eventStatus = "INFO",
                                description = "Mission resumed from current position"
                            )
                        } catch (e: Exception) {
                            Log.e("SharedVM", "Failed to send RESUMED status", e)
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
                Log.i("SharedVM", "Resuming mission from waypoint: $pausedWaypoint")

                // Set current waypoint in FCU
                val setWaypointSuccess = repo?.setCurrentWaypoint(pausedWaypoint) ?: false

                if (!setWaypointSuccess) {
                    Log.w("SharedVM", "Failed to set waypoint, continuing anyway")
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

                    // ✅ Send mission status RESUMED to backend (crash-safe)
                    try {
                        WebSocketManager.getInstance().sendMissionStatus(WebSocketManager.MISSION_STATUS_RESUMED)
                        WebSocketManager.getInstance().sendMissionEvent(
                            eventType = "MISSION_RESUMED",
                            eventStatus = "INFO",
                            description = "Mission resumed from waypoint $pausedWaypoint"
                        )
                    } catch (e: Exception) {
                        Log.e("SharedVM", "Failed to send RESUMED status", e)
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
                Log.e("SharedVM", "Failed to resume mission", e)
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
        Log.i("LevelSensorCal", "Updating calibration: empty=$emptyVoltageMv mV, full=$fullVoltageMv mV")
        _telemetryState.update { currentState ->
            currentState.copy(
                sprayTelemetry = currentState.sprayTelemetry.copy(
                    levelSensorEmptyMv = emptyVoltageMv,
                    levelSensorFullMv = fullVoltageMv
                )
            )
        }
        Log.i("LevelSensorCal", "Calibration updated successfully")
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

            Log.i("SprayControl", "═══════════════════════════════════════")
            Log.i("SprayControl", "🚿 SPRAY COMMAND (Sprayer Library Mode)")
            Log.i("SprayControl", "   State: ${if (enable) "ON" else "OFF"}")
            Log.i("SprayControl", "   SPRAY_PUMP_RATE: ${rate.toInt()}%")
            Log.i("SprayControl", "   Expected PWM: $expectedPwm µs (range 1051-1951)")
            Log.i("SprayControl", "   Method: MAV_CMD_DO_SPRAYER + SPRAY_PUMP_RATE param")
            Log.i("SprayControl", "═══════════════════════════════════════")

            repo?.let { repository ->
                try {
                    // Step 1: Set the SPRAY_PUMP_RATE parameter to control duty cycle
                    // This parameter controls the maximum pump output (0-100%)
                    val paramResult = setParameter("SPRAY_PUMP_RATE", rate)
                    if (paramResult != null) {
                        Log.i("SprayControl", "✓ SPRAY_PUMP_RATE set to ${rate.toInt()}%")
                    } else {
                        Log.w("SprayControl", "⚠ SPRAY_PUMP_RATE set (no confirmation received)")
                    }

                    // Step 2: Send DO_SPRAYER command to enable/disable
                    // MAV_CMD_DO_SPRAYER (216): param1 = 1 (enable) or 0 (disable)
                    repository.sendCommandRaw(
                        commandId = MAV_CMD_DO_SPRAYER,
                        param1 = if (enable) 1f else 0f
                    )
                    Log.i("SprayControl", "✓ DO_SPRAYER command sent: ${if (enable) "ENABLE" else "DISABLE"}")
                    Log.i("SprayControl", "✓ Command sent successfully")
                } catch (e: Exception) {
                    Log.e("SprayControl", "✗ Failed to send spray command: ${e.message}", e)
                }
            } ?: run {
                Log.e("SprayControl", "✗ Cannot control spray - not connected to drone")
            }
        }
    }

    fun setSprayEnabled(enabled: Boolean) {
        _sprayEnabled.value = enabled
        controlSpray(enabled)
        Log.i("SprayControl", "Spray ${if (enabled) "ENABLED" else "DISABLED"} at rate: ${_sprayRate.value.toInt()}%")

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
        Log.i("SprayControl", "🚿 Disabling spray due to mode change from Auto")

        // Always send DO_SPRAYER(0) to FC to ensure sprayer is OFF
        // This is critical because mission-embedded DO_SPRAYER commands work independently of app state
        viewModelScope.launch {
            repo?.let { repository ->
                try {
                    repository.sendCommandRaw(
                        commandId = MAV_CMD_DO_SPRAYER,
                        param1 = 0f  // 0 = Disable sprayer
                    )
                    Log.i("SprayControl", "✓ DO_SPRAYER(0) sent to FC - sprayer disabled")
                } catch (e: Exception) {
                    Log.e("SprayControl", "✗ Failed to send DO_SPRAYER disable: ${e.message}", e)
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
                Log.i("SprayControl", "🚿 Rate changed to ${newRate.toInt()}% - updating SPRAY_PUMP_RATE parameter (RC7 enabled)")
                controlSpray(true) // Re-send with new rate
            } else {
                Log.d("SprayControl", "Rate set to ${newRate.toInt()}% (RC7 disabled, SPRAY_PUMP_RATE will be set when RC7 enabled)")
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
            Log.i("YawHold", "🧭 Yaw hold ENABLED - locked to ${normalizedYaw}°")
            addNotification(Notification("Yaw locked at ${normalizedYaw.toInt()}°", NotificationType.INFO))
        } else {
            Log.w("YawHold", "⚠️ Cannot enable yaw hold - no heading data available")
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
            Log.i("YawHold", "🧭 Yaw hold DISABLED")
        }
    }

    /**
     * Start continuous yaw enforcement loop.
     * This should be called when entering AUTO mode with yaw hold enabled.
     */
    fun startYawEnforcement() {
        if (!_yawHoldEnabled.value || _lockedYaw.value == null) {
            Log.w("YawHold", "Cannot start yaw enforcement - yaw hold not enabled or no locked yaw")
            return
        }

        // Cancel any existing enforcement job
        yawEnforcementJob?.cancel()

        yawEnforcementJob = viewModelScope.launch {
            Log.i("YawHold", "🔄 Starting continuous yaw enforcement at ${_lockedYaw.value}°")

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
                        Log.d("YawHold", "📐 Yaw error: ${yawError}° - sending correction to ${targetYaw}°")
                        sendYawCommand(targetYaw)
                    }
                } else {
                    // Not in AUTO mode - stop enforcement
                    Log.i("YawHold", "Mode is $currentMode (not AUTO) - stopping yaw enforcement")
                    break
                }

                delay(YAW_ENFORCEMENT_INTERVAL)
            }

            Log.i("YawHold", "🛑 Yaw enforcement loop ended")
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
            Log.e("YawHold", "Failed to send yaw command: ${e.message}")
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
            Log.i("YawHold", "✓ WP_YAW_BEHAVIOR set to $value")
        } catch (e: Exception) {
            Log.e("YawHold", "Failed to set WP_YAW_BEHAVIOR: ${e.message}")
        }
    }

    // =================================================================
    /**
     * Update flow sensor calibration factor (BATT2_AMP_PERVLT parameter)
     * This will be sent to the autopilot to update the flow sensor calibration
     */
    fun updateFlowSensorCalibration(calibrationFactor: Float) {
        Log.i("FlowSensorCal", "Updating flow calibration factor: $calibrationFactor")

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
                Log.i("FlowSensorCal", "✓ Calibration factor sent to autopilot")
            } catch (e: Exception) {
                Log.e("FlowSensorCal", "Error sending calibration factor to autopilot", e)
            }
        }

        Log.i("FlowSensorCal", "Flow sensor calibration updated successfully")
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
                Log.e("SharedVM", "Error in emergency RTL callback", e)
            }
        }
    }

    /**
     * Trigger emergency RTL - called when app crashes during flight
     * This sends the RTL command synchronously to ensure it's sent before app termination
     */
    suspend fun triggerEmergencyRTL() {
        Log.w("SharedVM", "🚨 TRIGGERING EMERGENCY RTL 🚨")
        try {
            repo?.let { repository ->
                // Send RTL mode change command (mode 6 = RTL for ArduPilot)
                repository.changeMode(6u)
                Log.i("SharedVM", "✓ Emergency RTL command sent to drone")
            } ?: run {
                Log.e("SharedVM", "❌ Cannot send RTL - repository is null")
            }
        } catch (e: Exception) {
            Log.e("SharedVM", "❌ Failed to send emergency RTL command", e)
        }
    }

    // Expose FCU system and component IDs for mission building
    fun getFcuSystemId(): UByte = repo?.fcuSystemId ?: 0u
    fun getFcuComponentId(): UByte = repo?.fcuComponentId ?: 0u

    suspend fun cancelConnection() {
        repo?.let {
            try {
                it.closeConnection()
            } catch (e: Exception) {
                Log.e("SharedVM", "Error closing connection", e)
            }
        }
        DisconnectionRTLHandler.stopMonitoring()
        repo = null

        // Preserve mission pause state when disconnecting (e.g., for battery change)
        // Only reset connection-related fields, NOT mission pause state
        val currentState = _telemetryState.value
        val wasPaused = currentState.missionPaused
        val pausedAtWp = currentState.pausedAtWaypoint

        if (wasPaused) {
            Log.i("SharedVM", "Preserving mission pause state during disconnect (pausedAtWaypoint=$pausedAtWp)")
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
            Log.i("SharedVM", "Split plan toggle initiated")
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
                Log.i("SharedVM", "✓ Split plan confirmed and initiated")
            } else {
                Log.e("SharedVM", "✗ Split plan failed: $error")
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
                Log.i("SharedVM", "Initiating split plan...")

                if (repo == null) {
                    Log.w("SharedVM", "No repo available, cannot split plan")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    Log.w("SharedVM", "FCU not detected, cannot split plan")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                // Store current position as resume waypoint
                val currentLat = _telemetryState.value.latitude
                val currentLon = _telemetryState.value.longitude

                if (currentLat == null || currentLon == null) {
                    Log.w("SharedVM", "Current position not available, cannot split plan")
                    onResult(false, "Current position not available")
                    return@launch
                }

                _splitPlanWaypointLat.value = currentLat
                _splitPlanWaypointLon.value = currentLon

                Log.i("SharedVM", "✓ Stored split waypoint at Lat: $currentLat, Lon: $currentLon")

                // Switch to RTL mode
                Log.i("SharedVM", "Switching to RTL mode...")
                val rtlSuccess = repo?.changeMode(MavMode.RTL) ?: false

                if (!rtlSuccess) {
                    Log.e("SharedVM", "Failed to switch to RTL mode")
                    onResult(false, "Failed to switch to RTL mode")
                    return@launch
                }

                Log.i("SharedVM", "✓ RTL mode activated")
                addNotification(
                    Notification(
                        message = "Plan split initiated - returning to launch point",
                        type = NotificationType.INFO
                    )
                )

                // Wait for drone to land (altitude becomes 0 or very low)
                Log.i("SharedVM", "Waiting for drone to land...")
                val landTimeout = 300000L // 5 minutes timeout
                val landStart = System.currentTimeMillis()

                while (System.currentTimeMillis() - landStart < landTimeout) {
                    val altitude = _telemetryState.value.altitudeRelative ?: 0f
                    if (altitude <= 0.5f) {
                        Log.i("SharedVM", "✓ Drone has landed (altitude: $altitude)")
                        break
                    }
                    delay(500)
                }

                // Disarm the drone
                Log.i("SharedVM", "Disarming drone...")
                repo?.disarm()
                delay(1000)

                if (!_telemetryState.value.armed) {
                    Log.i("SharedVM", "✓ Drone disarmed successfully")
                } else {
                    Log.w("SharedVM", "Drone may not be fully disarmed yet")
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
                Log.e("SharedVM", "Failed to split plan", e)
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
                Log.i("SharedVM", "Resuming from split plan...")

                if (repo == null) {
                    Log.w("SharedVM", "No repo available, cannot resume from split")
                    onResult(false, "Not connected to vehicle")
                    return@launch
                }

                if (!_splitPlanActive.value) {
                    Log.w("SharedVM", "No active split plan to resume")
                    onResult(false, "No split plan active")
                    return@launch
                }

                if (!_telemetryState.value.fcuDetected) {
                    Log.w("SharedVM", "FCU not detected, cannot resume from split")
                    onResult(false, "FCU not detected")
                    return@launch
                }

                if (!_missionUploaded.value || lastUploadedCount == 0) {
                    Log.w("SharedVM", "No mission uploaded, cannot resume")
                    onResult(false, "No mission uploaded")
                    return@launch
                }

                if (!_telemetryState.value.armable) {
                    Log.w("SharedVM", "Vehicle not armable")
                    onResult(false, "Vehicle not armable. Check sensors and GPS.")
                    return@launch
                }

                val sats = _telemetryState.value.sats ?: 0
                if (sats < 6) {
                    Log.w("SharedVM", "Insufficient GPS satellites ($sats)")
                    onResult(false, "Insufficient GPS satellites ($sats). Need at least 6.")
                    return@launch
                }

                // Arm the vehicle
                Log.i("SharedVM", "Arming vehicle for split plan resume...")
                repo?.arm()
                delay(500)

                if (!_telemetryState.value.armed) {
                    Log.w("SharedVM", "Failed to arm vehicle")
                    onResult(false, "Failed to arm vehicle")
                    return@launch
                }

                Log.i("SharedVM", "✓ Vehicle armed successfully")

                // Send mission start command
                Log.i("SharedVM", "Sending mission start command...")
                repo?.sendMissionStartCommand()
                delay(500)

                // Switch to AUTO mode
                Log.i("SharedVM", "Switching to AUTO mode...")
                val autoSuccess = repo?.changeMode(MavMode.AUTO) ?: false

                if (!autoSuccess) {
                    Log.e("SharedVM", "Failed to switch to AUTO mode")
                    onResult(false, "Failed to switch to AUTO mode")
                    return@launch
                }

                Log.i("SharedVM", "✓ Mission resumed from split point")
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
                Log.e("SharedVM", "Failed to resume from split plan", e)
                onResult(false, e.message)
            }
        }
    }

    // --- Geofence Violation Detection ---
    // Geofence constants - Similar to ArduPilot's FENCE_MARGIN behavior
    companion object {
        // ═══════════════════════════════════════════════════════════════════════════
        // GEOFENCE BUFFER CONFIGURATION - INCREASED FOR BETTER FENCE ENFORCEMENT
        // ═══════════════════════════════════════════════════════════════════════════

        // Minimum buffer distance - triggers even when stationary
        // INCREASED from 3.0m to 9.0m for much better safety margin
        // This ensures drone stops well before the physical fence boundary
        private const val MIN_BUFFER_METERS = 9.0  // Minimum buffer when stationary

        // Maximum buffer distance - caps the dynamic buffer at high speeds
        // INCREASED from 15m to 25m for high-speed scenarios
        private const val MAX_BUFFER_METERS = 25.0  // Maximum buffer for high speed scenarios

        // Maximum deceleration capability (m/s²) - conservative estimate for multicopters
        // REDUCED from 3.5 to 2.5 for more conservative braking estimate
        // This accounts for wind, load, reaction time, and real-world deceleration
        private const val MAX_DECEL_M_S2 = 2.5  // Very conservative deceleration

        // System latency in seconds (GPS + telemetry + command execution)
        // GPS: ~200ms, Telemetry: ~200ms, Command: ~400ms = ~800ms total
        // INCREASED from 0.7 to 1.0 second for more conservative latency handling
        private const val SYSTEM_LATENCY_SECONDS = 1.0  // 1000ms total latency (conservative)

        // Safety margin multiplier for stopping distance (accounts for uncertainties)
        // INCREASED from 1.5 to 2.0 for better safety
        private const val STOPPING_DISTANCE_SAFETY_FACTOR = 2.0  // 100% safety margin for high speed

        // Breach confirmation - reduced to 2 samples for faster response at high speed
        private const val BREACH_CONFIRMATION_SAMPLES = 2 // Number of consecutive breach samples required
        private const val BREACH_CONFIRMATION_TIME_MS = 200L // 200ms window for breach confirmation

        // ULTRA High frequency monitoring interval - 5ms = 200 checks per second
        private const val GEOFENCE_MONITOR_INTERVAL_MS = 5L

        // Continuous command sending interval when breached - VERY AGGRESSIVE
        private const val BRAKE_COMMAND_INTERVAL_MS = 30L

        // Number of consecutive commands to send on first breach
        private const val EMERGENCY_COMMAND_BURST_COUNT = 10

        // ═══════════════════════════════════════════════════════════════════════════
        // DEFAULT FENCE RADIUS - Distance between waypoints and geofence boundary
        // INCREASED from 5m to 15m for much better separation
        // ═══════════════════════════════════════════════════════════════════════════
        private const val DEFAULT_FENCE_RADIUS_METERS = 15.0f
    }

    private val _geofenceViolationDetected = MutableStateFlow(false)
    val geofenceViolationDetected: StateFlow<Boolean> = _geofenceViolationDetected.asStateFlow()

    // Pre-emptive warning state - triggered when approaching fence
    private val _geofenceWarningTriggered = MutableStateFlow(false)
    val geofenceWarningTriggered: StateFlow<Boolean> = _geofenceWarningTriggered.asStateFlow()

    // Track if brake/RTL has been triggered - prevents multiple first commands
    @Volatile
    private var geofenceActionTaken = false

    // Track if RTL process has started - stops continuous BRAKE commands from interfering
    @Volatile
    private var rtlInitiated = false

    // Track if geofence is currently triggering a mode change (BRAKE/RTL)
    // Used to prevent resume popup from showing when geofence falls back to LOITER
    @Volatile
    private var geofenceTriggeringModeChange = false

    // Expose geofence triggering state for TelemetryRepository
    val isGeofenceTriggeringModeChange: Boolean
        get() = geofenceTriggeringModeChange

    // Track continuous brake sending
    @Volatile
    private var lastBrakeCommandTime = 0L

    // Breach confirmation tracking - prevents false positives from GPS glitches
    @Volatile
    private var breachConfirmationCount = 0
    @Volatile
    private var firstBreachDetectedTime: Long? = null

    // For periodic logging only
    private var lastLogTime = 0L

    // Background geofence monitoring job - runs independently at high frequency
    private var geofenceMonitorJob: kotlinx.coroutines.Job? = null

    init {
        // Start the ULTRA HIGH-FREQUENCY background geofence monitor
        startGeofenceMonitor()

        // Monitor connection status and announce via TTS
        viewModelScope.launch {
            isConnected.collect { connected ->
                ttsManager?.announceConnectionStatus(connected)
                Log.d("SharedVM", "Connection status changed: ${if (connected) "Connected" else "Disconnected"}")
            }
        }
    }

    /**
     * Start ULTRA high-frequency background geofence monitoring.
     * Runs in a SEPARATE coroutine at 100Hz (every 10ms) - does NOT wait for telemetry state updates.
     * Directly reads the latest telemetry values for instant response.
     * CRITICAL: This monitor MUST NEVER stop while app is running!
     */
    private fun startGeofenceMonitor() {
        geofenceMonitorJob?.cancel()
        geofenceMonitorJob = viewModelScope.launch {
            Log.i("Geofence", "🔄🔄🔄 ULTRA HIGH-FREQUENCY GEOFENCE MONITOR STARTED (${1000/GEOFENCE_MONITOR_INTERVAL_MS}Hz) 🔄🔄🔄")
            Log.i("Geofence", "🔒 Dynamic buffer: ${MIN_BUFFER_METERS}m (stationary) to ${MAX_BUFFER_METERS}m (max speed)")
            Log.i("Geofence", "📐 Using Mission Planner cross-track distance formula")

            while (isActive) {
                try {
                    // Check geofence using LATEST telemetry data directly
                    checkGeofenceNow()
                } catch (e: Exception) {
                    Log.e("Geofence", "Monitor error (continuing): ${e.message}")
                    // DON'T break - keep monitoring even if there's an error!
                }

                // High frequency polling - 10ms interval (100 times per second)
                delay(GEOFENCE_MONITOR_INTERVAL_MS)
            }

            Log.w("Geofence", "⚠️ Geofence monitor stopped - this should not happen!")
        }
    }

    /**
     * Calculate dynamic buffer distance based on current speed.
     * Uses improved physics-based stopping distance calculation with multiple factors:
     *
     * Total buffer = MIN_BUFFER + gps_uncertainty + latency_distance + stopping_distance + wind_margin
     *
     * Where:
     * - gps_uncertainty = 2-5m (GPS accuracy can vary)
     * - latency_distance = speed × system_latency (distance traveled during latency)
     * - stopping_distance = v² / (2 × deceleration) × safety_factor
     * - wind_margin = speed × 0.3 (accounts for wind pushing drone during deceleration)
     *
     * This ensures the drone triggers brake EARLY ENOUGH to stop before the fence,
     * accounting for GPS uncertainty, telemetry delays, command execution delays, wind, and momentum.
     *
     * Examples at different speeds (with MIN_BUFFER=8m, decel=2.5m/s², latency=1.0s, safety=2.0):
     * - 0 m/s:  8.0m (minimum buffer only - stationary safety margin)
     * - 2 m/s:  8 + 3 + 2.0 + 1.6 + 0.6 = 15.2m
     * - 3 m/s:  8 + 3 + 3.0 + 3.6 + 0.9 = 18.5m
     * - 5 m/s:  8 + 3 + 5.0 + 10 + 1.5 = 27.5m → capped at 25m
     * - 8 m/s:  capped at 25m (max buffer)
     */
    private fun calculateDynamicBuffer(currentSpeedMs: Float, altitudeMeters: Float = 0f): Double {
        if (currentSpeedMs <= 0) {
            return MIN_BUFFER_METERS
        }

        // 1. GPS Uncertainty - GPS accuracy can drift 2-5 meters
        val gpsUncertainty = 3.0  // Conservative 3m GPS uncertainty

        // 2. Distance traveled during system latency (GPS + telemetry + command delay)
        // At 3 m/s with 1.0s latency = 3.0m traveled before braking even starts
        val latencyDistance = currentSpeedMs * SYSTEM_LATENCY_SECONDS

        // 3. Physics-based stopping distance: v² / (2a)
        // At 3 m/s with 2.5 m/s² decel = 9/5 = 1.8m
        val stoppingDistance = (currentSpeedMs * currentSpeedMs) / (2 * MAX_DECEL_M_S2)

        // 4. Apply safety factor to stopping distance (accounts for momentum, load variation)
        val safeStoppingDistance = stoppingDistance * STOPPING_DISTANCE_SAFETY_FACTOR

        // 5. Wind margin - accounts for wind pushing drone during deceleration
        // Assumes wind could add 30% to stopping distance at speed
        val windMargin = currentSpeedMs * 0.3

        // 6. HIGH SPEED MULTIPLIER: At speeds above 5 m/s, apply additional safety margin
        // This accounts for drone momentum and slower reaction time at high speed
        val highSpeedMultiplier = if (currentSpeedMs > 5.0f) {
            1.0 + ((currentSpeedMs - 5.0f) * 0.15)  // +15% per m/s above 5 m/s (increased from 10%)
        } else {
            1.0
        }

        // 7. Altitude factor - at higher altitudes, GPS accuracy may be slightly worse
        // and wind effects are often stronger
        val altitudeFactor = if (altitudeMeters > 20f) {
            1.0 + ((altitudeMeters - 20f) * 0.01).coerceAtMost(0.2)  // Up to +20% at high altitude
        } else {
            1.0
        }

        // Total buffer = (minimum + gps + latency + stopping + wind) × multipliers
        val baseBuffer = MIN_BUFFER_METERS + gpsUncertainty + latencyDistance + safeStoppingDistance + windMargin
        val totalBuffer = baseBuffer * highSpeedMultiplier * altitudeFactor

        // Cap at maximum buffer
        return minOf(MAX_BUFFER_METERS, totalBuffer)
    }

    /**
     * ULTRA HIGH-FREQUENCY geofence check - runs every 5ms in background.
     * Reads LATEST telemetry values directly, does not wait for state updates.
     *
     * LOGIC based on Mission Planner / ArduPilot:
     * - Uses cross-track distance formula for accurate perpendicular distance to fence edges
     * - Also checks corner distances (important when perpendicular doesn't hit any segment)
     * - Dynamic buffer based on current speed to ensure drone can stop in time
     * - Returns 0 if outside fence (BREACH), otherwise returns distance to nearest edge
     * - Triggers BRAKE + LOITER if BREACH or within dynamic buffer distance at high speed
     * - LOITER holds drone at current position to prevent further fence violations
     */
    private fun checkGeofenceNow() {
        // Skip if geofence not enabled
        if (!_geofenceEnabled.value) return

        val polygon = _geofencePolygon.value
        if (polygon.size < 3) return

        // Get LATEST position directly from telemetry state (not waiting for collect)
        val currentState = _telemetryState.value
        val droneLat = currentState.latitude ?: return
        val droneLon = currentState.longitude ?: return

        // Skip if drone is not armed (no need to enforce geofence when disarmed)
        if (!currentState.armed) return

        // 🔥 FIX: Get current mode - important for debugging
        val currentMode = currentState.mode

        val dronePosition = LatLng(droneLat, droneLon)
        val currentSpeed = currentState.groundspeed ?: 0f
        val currentAltitude = currentState.altitudeRelative ?: 0f

        // Use Mission Planner's checkGeofenceDistance - returns 0 if BREACH (outside fence)
        // Otherwise returns the cross-track distance to nearest fence edge
        val distanceToFence = GeofenceUtils.checkGeofenceDistance(dronePosition, polygon)
        val isInsideFence = distanceToFence > 0  // If distance is 0, drone is OUTSIDE fence (BREACH)

        // Calculate dynamic buffer based on speed AND altitude (ArduPilot FENCE_MARGIN behavior)
        val dynamicBuffer = calculateDynamicBuffer(currentSpeed, currentAltitude)

        // Log every 500ms for debugging
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 500) {
            lastLogTime = now
            Log.d("Geofence", "📍 MONITOR: mode=$currentMode inside=$isInsideFence, dist=${String.format("%.1f", distanceToFence)}m, buffer=${String.format("%.1f", dynamicBuffer)}m, speed=${String.format("%.1f", currentSpeed)}m/s, actionTaken=$geofenceActionTaken")
        }

        // ═══ MISSION PLANNER / ARDUPILOT STYLE FENCE LOGIC WITH BREACH CONFIRMATION ═══
        // 🔥 FIX: Only trigger BRAKE/LOITER for CONFIRMED breach (drone outside fence for sustained period)
        // Being close to the edge (within buffer) should only WARN, not trigger LOITER
        // This prevents false positives from GPS glitches and instantaneous readings
        val isOutsideFence = distanceToFence == 0.0
        val isWithinBuffer = isInsideFence && distanceToFence <= dynamicBuffer

        // CASE 1: ACTUAL BREACH - drone is OUTSIDE the fence polygon
        if (isOutsideFence) {
            // Increment breach confirmation counter
            if (firstBreachDetectedTime == null) {
                firstBreachDetectedTime = now
                breachConfirmationCount = 1
                Log.w("Geofence", "⚠️ BREACH DETECTED (1/${BREACH_CONFIRMATION_SAMPLES}) - Starting confirmation...")
            } else {
                val timeSinceFirstBreach = now - firstBreachDetectedTime!!

                if (timeSinceFirstBreach <= BREACH_CONFIRMATION_TIME_MS) {
                    // Within confirmation window - increment counter
                    breachConfirmationCount++
                    Log.w("Geofence", "⚠️ BREACH CONFIRMED (${breachConfirmationCount}/${BREACH_CONFIRMATION_SAMPLES}) - ${timeSinceFirstBreach}ms elapsed")
                } else {
                    // Exceeded time window - restart confirmation
                    firstBreachDetectedTime = now
                    breachConfirmationCount = 1
                    Log.w("Geofence", "⚠️ BREACH confirmation timed out - Restarting...")
                }
            }

            // Only trigger LOITER if breach is CONFIRMED (multiple consecutive detections)
            if (breachConfirmationCount >= BREACH_CONFIRMATION_SAMPLES) {
                Log.e("Geofence", "🔴 HARD BREACH CONFIRMED! DRONE IS OUTSIDE FENCE!")

                _geofenceWarningTriggered.value = true
                _geofenceViolationDetected.value = true

                // FIRST TIME: Send BRAKE then LOITER
                if (!geofenceActionTaken) {
                    geofenceActionTaken = true
                    rtlInitiated = false // Will be set to true once LOITER process starts
                    lastBrakeCommandTime = now

                    Log.e("Geofence", "🚨🚨🚨 CONFIRMED BREACH! Outside fence - EMERGENCY BRAKE + LOITER! 🚨🚨🚨")
                    Log.e("Geofence", "Position: $droneLat, $droneLon")

                    // 🔊 TTS Announcement for critical geofence violation
                    speak("Critical geofence breach! Drone stopping!")

                    // Send BRAKE then LOITER - this will set rtlInitiated = true (loiter initiated)
                    sendEmergencyBrakeAndLoiter()
                }

                // CONTINUOUS ENFORCEMENT: Only send BRAKE if LOITER hasn't been initiated yet
                // Once LOITER process starts, we don't want to interfere with it
                if (!rtlInitiated && now - lastBrakeCommandTime > BRAKE_COMMAND_INTERVAL_MS) {
                    lastBrakeCommandTime = now
                    sendBrakeCommandImmediate()
                }
            } else {
                // Breach detected but not yet confirmed - just log
                Log.d("Geofence", "📍 Possible breach - waiting for confirmation (${breachConfirmationCount}/${BREACH_CONFIRMATION_SAMPLES})")
            }
        } else {
            // Reset breach confirmation if drone is back inside
            if (breachConfirmationCount > 0 || firstBreachDetectedTime != null) {
                Log.i("Geofence", "✓ Breach confirmation reset - drone back inside (dist: ${String.format("%.1f", distanceToFence)}m)")
                breachConfirmationCount = 0
                firstBreachDetectedTime = null
            }
        }

        // CASE 2: WARNING ZONE - drone is INSIDE fence but close to edge
        // 🔥 HIGH SPEED PREEMPTIVE BRAKE: If drone is approaching fence at high speed, stop it immediately
        if (isWithinBuffer) {
            // Set warning state for UI indication (yellow border, etc.)
            if (!_geofenceWarningTriggered.value) {
                Log.w("Geofence", "⚠️ WARNING: Close to fence edge! dist=${String.format("%.1f", distanceToFence)}m (buffer: ${String.format("%.1f", dynamicBuffer)}m)")
                _geofenceWarningTriggered.value = true
                // Only notify once per warning event
                addNotification(
                    Notification(
                        message = "⚠️ Warning: Approaching geofence boundary",
                        type = NotificationType.WARNING
                    )
                )

                // 🔊 TTS Announcement for geofence warning
                speak("Approaching geofence boundary")
            }

            // 🔥 HIGH SPEED PREVENTION: If drone is moving fast (>3 m/s) and very close to boundary,
            // send immediate BRAKE to prevent crossing
            val criticalDistance = dynamicBuffer * 0.5  // Half of buffer = critical zone
            if (currentSpeed > 3.0f && distanceToFence < criticalDistance) {
                Log.w("Geofence", "🛑 HIGH SPEED APPROACHING FENCE! Speed=${String.format("%.1f", currentSpeed)}m/s, dist=${String.format("%.1f", distanceToFence)}m - Sending preemptive BRAKE!")

                // Send brake only if we haven't already taken action
                if (!geofenceActionTaken) {
                    val now = System.currentTimeMillis()
                    if (now - lastBrakeCommandTime > BRAKE_COMMAND_INTERVAL_MS) {
                        lastBrakeCommandTime = now
                        sendPreemptiveBrake()
                    }
                }
            }
        }

        // Reset state when safely back inside
        // TWO-STAGE RESET:
        // 1. Reset geofenceActionTaken when drone is safely inside (beyond dynamic buffer) - allows re-trigger
        // 2. Reset warning flags only when further inside to prevent UI oscillation

        // Stage 1: Reset action taken when back inside fence beyond the dynamic buffer
        // This ensures geofence can re-trigger LOITER if drone goes out again
        // Use 2x dynamic buffer as hysteresis to prevent oscillation at boundary
        val rearmThreshold = dynamicBuffer * 2.0
        if (isInsideFence && distanceToFence > rearmThreshold && geofenceActionTaken) {
            Log.i("Geofence", "✓ Drone back inside fence (${String.format("%.1f", distanceToFence)}m from edge, threshold: ${String.format("%.1f", rearmThreshold)}m) - Geofence REARMED for re-trigger")
            geofenceActionTaken = false
            rtlInitiated = false
            _geofenceViolationDetected.value = false
        }

        // Stage 2: Clear warning when beyond the buffer zone (plus some hysteresis)
        // This clears the warning UI state
        val warningClearThreshold = dynamicBuffer * 1.5  // 50% hysteresis
        if (isInsideFence && distanceToFence > warningClearThreshold) {
            if (_geofenceWarningTriggered.value) {
                Log.i("Geofence", "✓ Drone safely away from fence edge (${String.format("%.1f", distanceToFence)}m) - Warning cleared")
                _geofenceWarningTriggered.value = false
            }
        }
    }

    /**
     * Send BRAKE command immediately - FIRE AND FORGET, no waiting!
     * Uses fire-and-forget pattern for maximum speed.
     * Falls back to LOITER if BRAKE fails.
     */
    private fun sendBrakeCommandImmediate() {
        viewModelScope.launch {
            try {
                geofenceTriggeringModeChange = true  // Mark that geofence is triggering mode change
                val brakeSuccess = repo?.changeMode(MavMode.BRAKE) ?: false
                if (!brakeSuccess) {
                    // Fallback to LOITER which holds position
                    Log.w("Geofence", "BRAKE failed, trying LOITER...")
                    repo?.changeMode(MavMode.LOITER)
                }
            } catch (e: Exception) {
                Log.e("Geofence", "BRAKE command error: ${e.message}")
                // Try LOITER as fallback
                try {
                    repo?.changeMode(MavMode.LOITER)
                } catch (e2: Exception) {
                    Log.e("Geofence", "LOITER fallback failed: ${e2.message}")
                }
            }
        }
    }

    /**
     * Send PREEMPTIVE BRAKE when approaching fence at high speed.
     * This triggers BEFORE actual breach to slow the drone down and prevent crossing.
     * After brake, transitions to LOITER to hold position.
     */
    private fun sendPreemptiveBrake() {
        viewModelScope.launch {
            try {
                Log.i("Geofence", "🛑 PREEMPTIVE BRAKE - High speed approach to fence boundary!")
                geofenceTriggeringModeChange = true

                // Send BRAKE first
                val brakeSuccess = repo?.changeMode(MavMode.BRAKE) ?: false
                Log.i("Geofence", "🛑 Preemptive BRAKE result: $brakeSuccess")

                if (!brakeSuccess) {
                    // Fallback to LOITER which holds position
                    Log.w("Geofence", "⚠️ Preemptive BRAKE failed, trying LOITER...")
                    repo?.changeMode(MavMode.LOITER)
                } else {
                    // Wait briefly then switch to LOITER to hold position
                    delay(300)
                    Log.i("Geofence", "🔒 Switching to LOITER to hold position...")
                    repo?.changeMode(MavMode.LOITER)
                }

                // Notify user
                addNotification(
                    Notification(
                        message = "⚠️ Stopped: Approaching geofence at high speed",
                        type = NotificationType.WARNING
                    )
                )
                ttsManager?.speak("Stopping! Approaching fence!")

            } catch (e: Exception) {
                Log.e("Geofence", "Preemptive BRAKE error: ${e.message}")
                // Try LOITER as fallback
                try {
                    repo?.changeMode(MavMode.LOITER)
                } catch (e2: Exception) {
                    Log.e("Geofence", "LOITER fallback failed: ${e2.message}")
                }
            } finally {
                // Reset flag after a short delay
                delay(1000)
                geofenceTriggeringModeChange = false
            }
        }
    }

    /**
     * EMERGENCY BURST: Send BRAKE to stop, then LOITER to hold position.
     * OPTIMIZED: Minimal delay between BRAKE and LOITER for fast response.
     * The BRAKE command just needs to be sent - drone starts decelerating immediately.
     *
     * LOITER holds the drone at its current position, preventing it from crossing the fence.
     * This ensures the pilot CANNOT push forward through the fence even if they try.
     */
    private fun sendEmergencyBrakeAndLoiter() {
        viewModelScope.launch {
            repo?.let { repository ->
                Log.i("Geofence", "═══════════════════════════════════════════════════")
                Log.i("Geofence", "🚨 EMERGENCY GEOFENCE BREACH - STOPPING DRONE!")
                Log.i("Geofence", "═══════════════════════════════════════════════════")

                // Mark that geofence is triggering mode change - prevents resume popup
                geofenceTriggeringModeChange = true

                // STEP 1: Send BRAKE command to stop immediately
                try {
                    Log.i("Geofence", "🛑 Sending BRAKE command...")
                    val brakeSuccess = repository.changeMode(MavMode.BRAKE)
                    Log.i("Geofence", "🛑 BRAKE result: $brakeSuccess")

                    if (!brakeSuccess) {
                        // Try LOITER as fallback
                        Log.w("Geofence", "⚠️ BRAKE failed, trying LOITER...")
                        repository.changeMode(MavMode.LOITER)
                    }
                } catch (e: Exception) {
                    Log.e("Geofence", "BRAKE error: ${e.message}")
                }

                // STEP 2: Minimal delay - just enough for command to register (200ms)
                Log.i("Geofence", "⏳ Brief pause before LOITER (200ms)...")
                delay(200)

                // STEP 3: Mark loiter as initiated - this stops continuous BRAKE commands
                rtlInitiated = true  // Note: Variable name kept for compatibility, but this means LOITER initiated
                Log.i("Geofence", "🔒 LOITER process initiated - stopping BRAKE enforcement")

                // STEP 4: Now send LOITER and KEEP TRYING until it works
                Log.i("Geofence", "🔒 Now sending LOITER command...")
                var loiterSuccess = false
                var attempts = 0
                val maxAttempts = 10

                while (!loiterSuccess && attempts < maxAttempts) {
                    attempts++
                    try {
                        Log.i("Geofence", "🔒 LOITER attempt #$attempts...")
                        loiterSuccess = repository.changeMode(MavMode.LOITER)
                        Log.i("Geofence", "🔒 LOITER attempt #$attempts result: $loiterSuccess")

                        if (loiterSuccess) {
                            Log.i("Geofence", "✓✓✓ LOITER MODE ACTIVATED SUCCESSFULLY! ✓✓✓")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("Geofence", "LOITER mode attempt #$attempts error: ${e.message}")
                    }

                    // Short delay before retry (150ms)
                    if (!loiterSuccess) {
                        delay(150)
                    }
                }

                if (loiterSuccess) {
                    addNotification(Notification(
                        message = "🚨 GEOFENCE BREACH - Drone stopped!",
                        type = NotificationType.ERROR
                    ))
                    ttsManager?.speak("Geofence breach! Drone stopped!")

                    // STEP 5: CONTINUOUS ENFORCEMENT - Keep sending LOITER to prevent pilot override
                    // This ensures the pilot CANNOT push through the fence even if they try
                    Log.i("Geofence", "🔒 Starting continuous LOITER enforcement...")
                    startContinuousLoiterEnforcement()
                } else {
                    Log.e("Geofence", "❌ LOITER failed after $maxAttempts attempts - drone should remain stopped from BRAKE")
                    addNotification(Notification(
                        message = "⚠️ GEOFENCE - Drone stopped",
                        type = NotificationType.WARNING
                    ))
                    ttsManager?.speak("Geofence! Drone stopped!")
                }

                Log.i("Geofence", "═══════════════════════════════════════════════════")

                // Reset the geofence triggering flag after a short delay
                delay(2000)
                geofenceTriggeringModeChange = false
                Log.d("Geofence", "Geofence mode change flag reset")
            } ?: Log.e("Geofence", "❌ No connection - cannot send emergency commands!")
        }
    }

    /**
     * Continuous LOITER enforcement - keeps sending LOITER command while drone is in violation.
     * This ensures the pilot CANNOT override and push through the fence.
     * Runs until the violation is cleared (drone back inside safe zone).
     */
    private fun startContinuousLoiterEnforcement() {
        viewModelScope.launch {
            var enforcementCount = 0
            while (_geofenceViolationDetected.value && currentCoroutineContext().isActive) {
                delay(500)  // Check every 500ms

                // Only enforce if still in violation and not safely back inside
                if (_geofenceViolationDetected.value) {
                    enforcementCount++
                    try {
                        Log.d("Geofence", "🔒 Continuous LOITER enforcement #$enforcementCount - preventing pilot override")
                        repo?.changeMode(MavMode.LOITER)
                    } catch (e: Exception) {
                        Log.e("Geofence", "Continuous LOITER enforcement error: ${e.message}")
                    }
                } else {
                    Log.i("Geofence", "✓ Geofence violation cleared - stopping continuous enforcement")
                    break
                }

                // Safety limit: Stop after 2 minutes of continuous enforcement
                if (enforcementCount > 240) {
                    Log.w("Geofence", "⚠️ Continuous LOITER enforcement timeout - stopping")
                    break
                }
            }
        }
    }

    /**
     * Handle drone returning to safe zone inside geofence
     */
    private fun handleReturnToSafeZone() {
        if (_geofenceViolationDetected.value || _geofenceWarningTriggered.value) {
            _geofenceViolationDetected.value = false
            _geofenceWarningTriggered.value = false
            geofenceActionTaken = false

            Log.i("Geofence", "✓ Drone returned to safe zone")
            addNotification(
                Notification(
                    message = "✓ GEOFENCE CLEAR: Drone back inside boundary",
                    type = NotificationType.INFO
                )
            )
        }
    }


    /**
     * Reset geofence state - call this when starting a new mission or re-enabling geofence
     */
    fun resetGeofenceState() {
        _geofenceViolationDetected.value = false
        _geofenceWarningTriggered.value = false
        geofenceActionTaken = false
        rtlInitiated = false
        lastBrakeCommandTime = 0L
        breachConfirmationCount = 0
        firstBreachDetectedTime = null
        Log.i("Geofence", "Geofence state reset - ready for new monitoring")

        // Restart the monitor if not running
        if (geofenceMonitorJob?.isActive != true) {
            startGeofenceMonitor()
        }
    }

    override fun onCleared() {
        super.onCleared()
        geofenceMonitorJob?.cancel()
        ttsManager?.shutdown()
        Log.d("SharedVM", "ViewModel cleared, geofence monitor stopped, TTS shutdown")
    }
}
