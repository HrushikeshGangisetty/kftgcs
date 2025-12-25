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
import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.definitions.common.MavCmd
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
    }

    /**
     * Clear all mission-related waypoints and polygons from the map.
     * Called when mission is completed.
     */
    fun clearMissionFromMap() {
        Log.i("SharedVM", "Clearing mission data from map")
        _uploadedWaypoints.value = emptyList()
        _gridWaypoints.value = emptyList()
        _surveyPolygon.value = emptyList()
        _gridLines.value = emptyList()
        _planningWaypoints.value = emptyList()
        _missionAreaSqMeters.value = 0.0
        _missionAreaFormatted.value = "0 acres"
        _missionUploaded.value = false
        lastUploadedCount = 0
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

    fun announceSelectedAutomatic() {
        ttsManager?.announceSelectedAutomatic()
    }

    fun announceSelectedManual() {
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
        completed: Boolean = false
    ) {
        _telemetryState.value = _telemetryState.value.copy(
            missionElapsedSec = if (isActive) elapsedSeconds else null,
            totalDistanceMeters = if (isActive || completed) distanceMeters else null,
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
                    // Preserve SharedViewModel-managed fields (pause state) while updating from repository
                    _telemetryState.update { currentState ->
                        // DEBUG LOG: Track state synchronization
                        Log.i("DEBUG_STATE", "Before sync - repoLastAuto: ${repoState.lastAutoWaypoint}, currentLastAuto: ${currentState.lastAutoWaypoint}, repoCurrent: ${repoState.currentWaypoint}, currentCurrent: ${currentState.currentWaypoint}")

                        repoState.copy(
                            missionPaused = currentState.missionPaused,
                            pausedAtWaypoint = currentState.pausedAtWaypoint
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

    private val _fenceRadius = MutableStateFlow(5f)
    val fenceRadius: StateFlow<Float> = _fenceRadius.asStateFlow()

    private val _geofenceEnabled = MutableStateFlow(false)
    val geofenceEnabled: StateFlow<Boolean> = _geofenceEnabled.asStateFlow()

    private val _geofencePolygon = MutableStateFlow<List<LatLng>>(emptyList())
    val geofencePolygon: StateFlow<List<LatLng>> = _geofencePolygon.asStateFlow()
    
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

    fun setPlanningWaypoints(waypoints: List<LatLng>) {
        _planningWaypoints.value = waypoints
        updateGeofencePolygon()
        updateSurveyArea()
    }

    fun setFenceRadius(radius: Float) {
        // Ensure minimum 5m radius
        _fenceRadius.value = radius.coerceAtLeast(5f)
        updateGeofencePolygon()
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

            Log.i("Geofence", "✓ Geofence ENABLED - monitoring active")
            addNotification(
                Notification(
                    message = "Geofence enabled - monitoring active",
                    type = NotificationType.INFO
                )
            )
        } else {
            _geofencePolygon.value = emptyList()
            Log.i("Geofence", "Geofence DISABLED")
        }
    }

    /**
     * Manually update the geofence polygon (for user adjustments via dragging)
     */
    fun updateGeofencePolygonManually(polygon: List<LatLng>) {
        if (_geofenceEnabled.value && polygon.size >= 3) {
            _geofencePolygon.value = polygon
            Log.i("Geofence", "Geofence polygon manually updated with ${polygon.size} vertices")
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
            if (!isPointInPolygon(point, polygon)) {
                Log.w("SharedVM", "Point not in geofence: $point")
                return false
            }
        }
        return true
    }

    private fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return true // No valid polygon

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            if (((yi > point.latitude) != (yj > point.latitude)) &&
                (point.longitude < (xj - xi) * (point.latitude - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
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
            _telemetryState.value = _telemetryState.value.copy(missionCompleted = false, missionCompletedHandled = false, missionElapsedSec = null)
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
                val currentMode = _telemetryState.value.mode
                if (currentMode?.contains("Auto", ignoreCase = true) != true) {
                    onResult(false, "Mission not running")
                    return@launch
                }

                // Use lastAutoWaypoint (tracked during AUTO mode) for accurate pause tracking
                // Falls back to currentWaypoint if lastAutoWaypoint is not set (-1)
                val lastAutoWp = _telemetryState.value.lastAutoWaypoint
                val currentWp = _telemetryState.value.currentWaypoint
                val waypointToStore = if (lastAutoWp > 0) lastAutoWp else currentWp

                // DEBUG LOGS
                Log.i("SharedVM", "=== PAUSE MISSION ===")
                Log.i("SharedVM", "lastAutoWaypoint: $lastAutoWp")
                Log.i("SharedVM", "currentWaypoint: $currentWp")
                Log.i("SharedVM", "waypointToStore (will be pausedAtWaypoint): $waypointToStore")
                Log.i("DEBUG_PAUSE", "Pausing - lastAutoWp: $lastAutoWp, currentWp: $currentWp, storing: $waypointToStore")

                // Switch to LOITER to hold position
                val result = repo?.changeMode(MavMode.LOITER) ?: false

                if (result) {
                    _telemetryState.update { 
                        it.copy(
                            missionPaused = true,
                            pausedAtWaypoint = waypointToStore
                        ) 
                    }
                    Log.i("SharedVM", "Mission paused successfully. pausedAtWaypoint set to: ${_telemetryState.value.pausedAtWaypoint}")
                    addNotification(
                        Notification(
                            message = "Mission paused at waypoint ${waypointToStore ?: "?"} - holding position",
                            type = NotificationType.INFO
                        )
                    )
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
                Log.i("ResumeMission", "═══════════════════════════════════════")
                Log.i("ResumeMission", "Starting Resume Mission")
                Log.i("ResumeMission", "Resume at waypoint: $resumeWaypointNumber")
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

                // Step 3: Filter Waypoints for Resume
                onProgress("Step 3/8: Filtering waypoints...")
                Log.i("ResumeMission", "Filtering waypoints for resume from waypoint $resumeWaypointNumber...")
                val filtered = repo?.filterWaypointsForResume(allWaypoints, resumeWaypointNumber)
                
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

                // Mark mission as uploaded
                _missionUploaded.value = true
                lastUploadedCount = resequenced.size
                Log.i("ResumeMission", "✅ Mission upload status updated: uploaded=$_missionUploaded, count=$lastUploadedCount")

                // Complete
                addNotification(
                    Notification(
                        message = "Mission resumed from waypoint $resumeWaypointNumber",
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
    // Set to true to use RC_CHANNELS_OVERRIDE, false to use DO_SET_SERVO
    // RC_CHANNELS_OVERRIDE is better for real-time control but requires RC passthrough to be enabled
    // DO_SET_SERVO directly sets servo output, works on most setups
    private val USE_RC_OVERRIDE_FOR_SPRAY = false
    private val SPRAY_SERVO_NUMBER = 7 // SERVO7 output (can be changed based on hardware setup)
    private val SPRAY_RC_CHANNEL = 7 // RC channel 7 (if using RC override)

    /**
     * Control spray system by setting servo PWM output.
     * Uses either DO_SET_SERVO or RC_CHANNELS_OVERRIDE based on configuration.
     *
     * PWM mapping:
     * - OFF: 1000 PWM
     * - 10% rate: 1100 PWM
     * - 50% rate: 1500 PWM
     * - 100% rate: 2000 PWM
     *
     * @param enable true to enable spray at current rate, false to disable (PWM = 1000)
     */
    fun controlSpray(enable: Boolean) {
        viewModelScope.launch {
            val pwmValue = if (enable) {
                // Map spray rate (10-100%) to PWM range (1100-2000)
                // Formula: PWM = 1000 + (rate/100 * 1000)
                // At 10%: 1000 + (10/100 * 1000) = 1100
                // At 100%: 1000 + (100/100 * 1000) = 2000
                val rate = _sprayRate.value.coerceIn(10f, 100f)
                val pwm = (1000 + (rate / 100f * 1000f)).toInt()
                pwm.coerceIn(1100, 2000)
            } else {
                1000 // OFF
            }

            Log.i("SprayControl", "═══════════════════════════════════════")
            Log.i("SprayControl", "🚿 SPRAY COMMAND")
            Log.i("SprayControl", "   State: ${if (enable) "ON" else "OFF"}")
            Log.i("SprayControl", "   Rate: ${_sprayRate.value.toInt()}%")
            Log.i("SprayControl", "   PWM: $pwmValue")
            Log.i("SprayControl", "   Method: ${if (USE_RC_OVERRIDE_FOR_SPRAY) "RC_CHANNELS_OVERRIDE (ch$SPRAY_RC_CHANNEL)" else "DO_SET_SERVO (servo $SPRAY_SERVO_NUMBER)"}")
            Log.i("SprayControl", "═══════════════════════════════════════")

            repo?.let { repository ->
                try {
                    if (USE_RC_OVERRIDE_FOR_SPRAY) {
                        // Use RC_CHANNELS_OVERRIDE for real-time control
                        repository.sendRcChannelOverride(SPRAY_RC_CHANNEL, pwmValue.toUShort())
                    } else {
                        // Use DO_SET_SERVO for direct servo control
                        repository.sendServoCommand(SPRAY_SERVO_NUMBER, pwmValue)
                    }
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
     * This ensures spray is turned off when pilot takes manual control
     */
    fun disableSprayOnModeChange() {
        if (_sprayEnabled.value) {
            Log.i("SprayControl", "🚿 Auto-disabling spray due to mode change from Auto")
            _sprayEnabled.value = false
            controlSpray(false)
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
            if (_sprayEnabled.value) {
                Log.i("SprayControl", "🚿 Rate changed to ${newRate.toInt()}% - updating PWM")
                controlSpray(true) // Re-send with new rate
            } else {
                Log.d("SprayControl", "Rate set to ${newRate.toInt()}% (spray disabled, command will be sent when enabled)")
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
        _telemetryState.value = TelemetryState()
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
    // Geofence timing constants
    companion object {
        private const val GEOFENCE_CHECK_INTERVAL_MS = 100L  // Check every 100ms for INSTANT response
        private const val RTL_COOLDOWN_MS = 5000L

        // Pre-emptive buffer - triggers BEFORE actual boundary breach
        // Formula: buffer = (max_speed² / (2 * deceleration)) + safety_margin
        // At 7 m/s with typical drone deceleration of 5 m/s², stopping distance ≈ 4.9m
        // Adding 1m safety margin = ~6m buffer
        private const val GEOFENCE_WARNING_BUFFER_METERS = 6.0  // Default for 7 m/s speeds

        // BRAKE stabilization time before RTL
        private const val BRAKE_STABILIZATION_MS = 800L  // Time to wait for drone to stop
    }

    private val _geofenceViolationDetected = MutableStateFlow(false)
    val geofenceViolationDetected: StateFlow<Boolean> = _geofenceViolationDetected.asStateFlow()

    // Pre-emptive warning state - triggered when approaching fence
    private val _geofenceWarningTriggered = MutableStateFlow(false)
    val geofenceWarningTriggered: StateFlow<Boolean> = _geofenceWarningTriggered.asStateFlow()

    private var lastGeofenceCheck = 0L

    // Track if RTL/BRAKE has been triggered for current breach to avoid spamming commands
    private var rtlTriggeredForCurrentBreach = false
    private var brakeTriggeredForCurrentBreach = false

    // Cooldown to prevent RTL spam (wait 5 seconds between RTL triggers)
    private var lastRtlTriggerTime = 0L

    init {
        // Monitor drone position and check geofence violations
        viewModelScope.launch {
            telemetryState.collect { state ->
                checkGeofenceViolation(state)
                // Do NOT update geofence polygon on every position change - it should stay stationary
            }
        }

        // Monitor connection status and announce via TTS
        viewModelScope.launch {
            isConnected.collect { connected ->
                ttsManager?.announceConnectionStatus(connected)
                Log.d("SharedVM", "Connection status changed: ${if (connected) "Connected" else "Disconnected"}")
            }
        }
    }

    /**
     * Calculate dynamic buffer distance based on current speed.
     * Uses physics formula: d = v² / (2a) + safety_margin
     *
     * @param currentSpeedMs Current ground speed in m/s
     * @param deceleration Typical drone deceleration (default 5 m/s² for most copters)
     * @param safetyMargin Additional buffer for reaction time (default 2m)
     * @return Buffer distance in meters
     */
    private fun calculateDynamicBuffer(
        currentSpeedMs: Float,
        deceleration: Float = 5.0f,  // Typical ArduPilot BRAKE deceleration
        safetyMargin: Float = 2.0f
    ): Double {
        // Physics: stopping distance = v² / (2a)
        val stoppingDistance = (currentSpeedMs * currentSpeedMs) / (2 * deceleration)
        val totalBuffer = stoppingDistance + safetyMargin

        // Minimum buffer of 3m, maximum of 15m
        return totalBuffer.toDouble().coerceIn(3.0, 15.0)
    }

    /**
     * Enhanced geofence check with pre-emptive BRAKE and dynamic buffer.
     * Checks every 100ms for instant response.
     */
    private fun checkGeofenceViolation(state: TelemetryState) {
        val currentTime = System.currentTimeMillis()

        // Check every 100ms for INSTANT response (critical for high-speed breach)
        if (currentTime - lastGeofenceCheck < GEOFENCE_CHECK_INTERVAL_MS) return
        lastGeofenceCheck = currentTime

        // Skip if geofence not enabled or no polygon defined
        if (!_geofenceEnabled.value) {
            return
        }

        val polygon = _geofencePolygon.value
        if (polygon.isEmpty() || polygon.size < 3) {
            Log.d("Geofence", "No valid geofence polygon (${polygon.size} points)")
            return
        }

        val droneLat = state.latitude
        val droneLon = state.longitude

        if (droneLat == null || droneLon == null) {
            Log.d("Geofence", "No drone position available")
            return
        }

        val dronePosition = LatLng(droneLat, droneLon)
        val currentSpeed = state.groundspeed ?: 0f

        // Calculate distance to nearest fence edge
        val distanceToFence = GeofenceUtils.distanceToPolygonEdge(dronePosition, polygon)

        // Calculate dynamic buffer based on current speed
        val dynamicBuffer = calculateDynamicBuffer(currentSpeed)

        val isInsideFence = isPointInPolygon(dronePosition, polygon)

        // Log position check periodically (every 2 seconds)
        if (currentTime % 2000 < 200) {
            Log.d("Geofence", "Position check: lat=$droneLat, lon=$droneLon, inside=$isInsideFence, " +
                    "distToFence=${String.format("%.1f", distanceToFence)}m, buffer=${String.format("%.1f", dynamicBuffer)}m, speed=${String.format("%.1f", currentSpeed)}m/s")
        }

        // ═══ CRITICAL: Pre-emptive Detection ═══
        // Check if approaching fence boundary (within dynamic buffer AND still inside)
        val isApproachingFence = isInsideFence && distanceToFence <= dynamicBuffer

        when {
            // Case 1: Drone has breached the fence
            !isInsideFence -> {
                handleGeofenceBreach(droneLat, droneLon, distanceToFence, currentSpeed)
            }

            // Case 2: Drone approaching fence (pre-emptive trigger)
            isApproachingFence && !_geofenceWarningTriggered.value -> {
                handleApproachingFence(distanceToFence, dynamicBuffer, currentSpeed)
            }

            // Case 3: Drone safely inside (with hysteresis to prevent oscillation)
            isInsideFence && distanceToFence > dynamicBuffer * 2 -> {
                handleReturnToSafeZone()
            }
        }
    }

    /**
     * Handle actual geofence breach - drone is outside the fence
     */
    private fun handleGeofenceBreach(lat: Double, lon: Double, distance: Double, speed: Float) {
        _geofenceViolationDetected.value = true

        val timeSinceLastRtl = System.currentTimeMillis() - lastRtlTriggerTime

        if (!rtlTriggeredForCurrentBreach && timeSinceLastRtl > RTL_COOLDOWN_MS) {
            rtlTriggeredForCurrentBreach = true
            lastRtlTriggerTime = System.currentTimeMillis()

            Log.e("Geofence", "🚨🚨🚨 GEOFENCE BREACH DETECTED! 🚨🚨🚨")
            Log.e("Geofence", "Position: $lat, $lon | Distance: ${String.format("%.1f", distance)}m outside | Speed: ${String.format("%.1f", speed)}m/s")

            addNotification(
                Notification(
                    message = "🚨 GEOFENCE BREACH! EMERGENCY BRAKE ENGAGED!",
                    type = NotificationType.ERROR
                )
            )

            // CRITICAL: Execute BRAKE → RTL sequence
            triggerEmergencyBrakeAndRTL()
        } else if (rtlTriggeredForCurrentBreach) {
            Log.d("Geofence", "Still outside fence, BRAKE→RTL already triggered")
        } else {
            Log.d("Geofence", "RTL cooldown active (${(RTL_COOLDOWN_MS - timeSinceLastRtl) / 1000}s remaining)")
        }
    }

    /**
     * Handle drone approaching fence - pre-emptive action
     */
    private fun handleApproachingFence(distance: Double, buffer: Double, speed: Float) {
        _geofenceWarningTriggered.value = true
        brakeTriggeredForCurrentBreach = true
        lastRtlTriggerTime = System.currentTimeMillis()

        Log.w("Geofence", "⚠️ APPROACHING FENCE! Distance: ${String.format("%.1f", distance)}m | Buffer: ${String.format("%.1f", buffer)}m | Speed: ${String.format("%.1f", speed)}m/s")

        addNotification(
            Notification(
                message = "⚠️ APPROACHING GEOFENCE! Pre-emptive BRAKE activated!",
                type = NotificationType.WARNING
            )
        )

        // Trigger pre-emptive BRAKE → RTL
        triggerEmergencyBrakeAndRTL()
    }

    /**
     * Handle drone returning to safe zone inside geofence
     */
    private fun handleReturnToSafeZone() {
        if (_geofenceViolationDetected.value || _geofenceWarningTriggered.value) {
            _geofenceViolationDetected.value = false
            _geofenceWarningTriggered.value = false
            rtlTriggeredForCurrentBreach = false
            brakeTriggeredForCurrentBreach = false

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
     * Execute emergency BRAKE → RTL sequence.
     *
     * 1. Immediately switch to BRAKE mode (stops all horizontal movement)
     * 2. Wait for drone to stabilize (800ms)
     * 3. Switch to RTL mode for return to home
     * 4. Fallback to direct RTL if BRAKE fails
     */
    private fun triggerEmergencyBrakeAndRTL() {
        viewModelScope.launch {
            repo?.let { repository ->
                try {
                    Log.i("Geofence", "═══════════════════════════════════════")
                    Log.i("Geofence", "🛑 EMERGENCY BRAKE SEQUENCE INITIATED")
                    Log.i("Geofence", "═══════════════════════════════════════")

                    // Step 1: Send BRAKE command (Mode 17)
                    Log.i("Geofence", "Step 1: Sending BRAKE command...")
                    val brakeSuccess = repository.changeMode(MavMode.BRAKE)

                    if (brakeSuccess) {
                        Log.i("Geofence", "✓ BRAKE mode engaged - drone stopping")

                        addNotification(
                            Notification(
                                message = "🛑 BRAKE ENGAGED - Stopping drone",
                                type = NotificationType.WARNING
                            )
                        )

                        // Step 2: Wait for drone to stabilize
                        Log.i("Geofence", "Step 2: Waiting ${BRAKE_STABILIZATION_MS}ms for stabilization...")
                        delay(BRAKE_STABILIZATION_MS)

                        // Step 3: Switch to RTL
                        Log.i("Geofence", "Step 3: Switching to RTL mode...")
                        val rtlSuccess = repository.changeMode(MavMode.RTL)

                        if (rtlSuccess) {
                            Log.i("Geofence", "✓ RTL mode activated - returning home")
                            addNotification(
                                Notification(
                                    message = "🏠 RTL ACTIVATED: Returning to launch point",
                                    type = NotificationType.WARNING
                                )
                            )
                        } else {
                            Log.e("Geofence", "✗ RTL failed after BRAKE - retrying...")
                            delay(300)
                            repository.changeMode(MavMode.RTL)
                        }

                    } else {
                        // BRAKE failed - fallback to direct RTL
                        Log.w("Geofence", "⚠️ BRAKE mode not available - falling back to direct RTL")

                        val rtlSuccess = repository.changeMode(MavMode.RTL)
                        if (rtlSuccess) {
                            Log.i("Geofence", "✓ Fallback RTL successful")
                            addNotification(
                                Notification(
                                    message = "🏠 RTL ACTIVATED: Returning to launch point",
                                    type = NotificationType.WARNING
                                )
                            )
                        } else {
                            Log.e("Geofence", "✗ Both BRAKE and RTL failed!")
                            addNotification(
                                Notification(
                                    message = "❌ EMERGENCY: Failed to stop drone!",
                                    type = NotificationType.ERROR
                                )
                            )
                        }
                    }

                    // TTS announcement
                    ttsManager?.speak("Geofence breach detected. Emergency brake engaged. Returning to home.")

                    Log.i("Geofence", "═══════════════════════════════════════")

                } catch (e: Exception) {
                    Log.e("Geofence", "❌ Emergency sequence failed: ${e.message}", e)

                    // Last resort - try RTL anyway
                    try {
                        repository.changeMode(MavMode.RTL)
                    } catch (e2: Exception) {
                        Log.e("Geofence", "❌ Last resort RTL also failed", e2)
                    }

                    addNotification(
                        Notification(
                            message = "❌ EMERGENCY FAILED: ${e.message}",
                            type = NotificationType.ERROR
                        )
                    )
                }
            } ?: run {
                Log.e("Geofence", "❌ Cannot send emergency commands - not connected to drone!")
                addNotification(
                    Notification(
                        message = "❌ NO CONNECTION: Cannot send emergency commands!",
                        type = NotificationType.ERROR
                    )
                )
            }
        }
    }

    /**
     * Reset geofence state - call this when starting a new mission or re-enabling geofence
     */
    fun resetGeofenceState() {
        _geofenceViolationDetected.value = false
        _geofenceWarningTriggered.value = false
        rtlTriggeredForCurrentBreach = false
        brakeTriggeredForCurrentBreach = false
        lastRtlTriggerTime = 0L
        Log.i("Geofence", "Geofence state reset (including pre-emptive warning flags)")
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager?.shutdown()
        Log.d("SharedVM", "ViewModel cleared, TTS shutdown")
    }
}
