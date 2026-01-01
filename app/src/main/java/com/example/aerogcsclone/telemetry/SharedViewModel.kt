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

    // Track previous fence radius to calculate delta for scaling
    private var _previousFenceRadius: Float = 5f

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
     */
    fun onModeChangedToLoiterFromAuto(waypointNumber: Int) {
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

                // Step 3: Filter waypoints from resume point
                Log.i("SharedVM", "Filtering waypoints from resume point (background)...")
                val filtered = repo?.filterWaypointsForResume(allWaypoints, waypointNumber)
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

            Log.i("SharedVM", "═══════════════════════════════════════")
            Log.i("SharedVM", "=== CONFIRM ADD RESUME HERE ===")
            Log.i("SharedVM", "Resume waypoint: $resumeWaypoint")
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

                // Step 3: Filter waypoints from resume point
                val filtered = repo?.filterWaypointsForResume(allWaypoints, resumeWaypoint)
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
            if (!GeofenceUtils.isPointInPolygon(point, polygon)) {
                Log.w("SharedVM", "Point not in geofence: $point")
                return false
            }
        }
        return true
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
                // NOTE: The mode change will be detected by TelemetryRepository which will
                // trigger onModeChangedToLoiterFromAuto() to show the "Add Resume Here" popup
                val result = repo?.changeMode(MavMode.LOITER) ?: false

                if (result) {
                    // Don't set missionPaused here - let the mode change detection handle it
                    // The popup will be shown by onModeChangedToLoiterFromAuto()
                    Log.i("SharedVM", "LOITER mode change command sent. Waiting for mode change detection...")

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
    // Geofence constants - Similar to ArduPilot's FENCE_MARGIN behavior
    companion object {
        // Minimum buffer distance - triggers even when stationary
        // This is the absolute minimum distance from fence before triggering
        // Set to 2m so drone starts braking at 2-3m and stops near the outer fence (4m)
        private const val MIN_BUFFER_METERS = 3.5  // Start braking at 2m from inner fence

        // Maximum buffer distance - caps the dynamic buffer at high speeds
        private const val MAX_BUFFER_METERS = 9.0  // Maximum 12m buffer for high speed scenarios

        // Maximum deceleration capability (m/s²) - conservative estimate for multicopters
        // Using 3.0 for safe braking estimate (drones can do 3-5, but wind/load affects this)
        private const val MAX_DECEL_M_S2 = 5.5  // Conservative deceleration

        // System latency in seconds (GPS + telemetry + command execution)
        // GPS: ~200ms, Telemetry: ~100ms, Command: ~200ms = ~500ms total
        private const val SYSTEM_LATENCY_SECONDS = 0.5  // 500ms total latency

        // Safety margin multiplier for stopping distance (accounts for uncertainties)
        private const val STOPPING_DISTANCE_SAFETY_FACTOR = 1.3  // 30% safety margin

        // ULTRA High frequency monitoring interval - 10ms = 100 checks per second
        private const val GEOFENCE_MONITOR_INTERVAL_MS = 5L

        // Continuous command sending interval when breached - VERY AGGRESSIVE
        private const val BRAKE_COMMAND_INTERVAL_MS = 50L

        // Number of consecutive commands to send on first breach
        private const val EMERGENCY_COMMAND_BURST_COUNT = 5
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
     * Uses physics-based stopping distance calculation with latency compensation:
     *
     * Total buffer = MIN_BUFFER + latency_distance + stopping_distance
     *
     * Where:
     * - latency_distance = speed × system_latency (distance traveled during latency)
     * - stopping_distance = v² / (2 × deceleration) × safety_factor
     *
     * This ensures the drone triggers brake EARLY ENOUGH to stop before the fence,
     * accounting for GPS, telemetry, and command execution delays.
     * With 3m min buffer, drone will stop near the outer fence (2m offset).
     *
     * Examples at different speeds:
     * - 0 m/s:  3m (minimum buffer only - stops at outer fence)
     * - 3 m/s:  3 + 1.5 + 1.95 = 6.45m
     * - 5 m/s:  3 + 2.5 + 5.42 = 10.92m
     * - 8 m/s:  3 + 4.0 + 13.87 = capped at 12m
     */
    private fun calculateDynamicBuffer(currentSpeedMs: Float, altitudeMeters: Float = 0f): Double {
        if (currentSpeedMs <= 0) {
            return MIN_BUFFER_METERS
        }

        // 1. Distance traveled during system latency (GPS + telemetry + command delay)
        // At 3 m/s with 500ms latency = 1.5m traveled before braking even starts
        val latencyDistance = currentSpeedMs * SYSTEM_LATENCY_SECONDS

        // 2. Physics-based stopping distance: v² / (2a)
        // At 3 m/s with 3.0 m/s² decel = 9/6 = 1.5m
        val stoppingDistance = (currentSpeedMs * currentSpeedMs) / (2 * MAX_DECEL_M_S2)

        // 3. Apply safety factor to stopping distance only (latency is already worst-case)
        val safeStoppingDistance = stoppingDistance * STOPPING_DISTANCE_SAFETY_FACTOR

        // Total buffer = minimum + latency distance + safe stopping distance
        val totalBuffer = MIN_BUFFER_METERS + latencyDistance + safeStoppingDistance

        // Cap at maximum buffer
        return minOf(MAX_BUFFER_METERS, totalBuffer)
    }

    /**
     * ULTRA HIGH-FREQUENCY geofence check - runs every 10ms in background.
     * Reads LATEST telemetry values directly, does not wait for state updates.
     *
     * LOGIC based on Mission Planner / ArduPilot:
     * - Uses cross-track distance formula for accurate perpendicular distance to fence edges
     * - Also checks corner distances (important when perpendicular doesn't hit any segment)
     * - Dynamic buffer based on current speed to ensure drone can stop in time
     * - Returns 0 if outside fence (BREACH), otherwise returns distance to nearest edge
     * - Triggers BRAKE + RTL if BREACH or within dynamic buffer distance
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
            Log.d("Geofence", "📍 MONITOR: inside=$isInsideFence, dist=${String.format("%.1f", distanceToFence)}m, dynamicBuffer=${String.format("%.1f", dynamicBuffer)}m, speed=${String.format("%.1f", currentSpeed)}m/s, alt=${String.format("%.1f", currentAltitude)}m, actionTaken=$geofenceActionTaken")
        }

        // ═══ MISSION PLANNER / ARDUPILOT STYLE FENCE LOGIC ═══
        // BREACH if: distance is 0 (outside fence) OR within dynamic buffer distance
        val isOutsideFence = distanceToFence == 0.0
        val isWithinBuffer = distanceToFence > 0 && distanceToFence <= dynamicBuffer
        val shouldTriggerAction = isOutsideFence || isWithinBuffer

        if (shouldTriggerAction) {
            // Log IMMEDIATELY when trigger condition is met
            if (isOutsideFence) {
                Log.e("Geofence", "🔴 HARD BREACH! OUTSIDE FENCE! dist=${String.format("%.1f", distanceToFence)}m")
            } else {
                Log.e("Geofence", "🔴 BUFFER BREACH! dist=${String.format("%.1f", distanceToFence)}m (within ${String.format("%.1f", dynamicBuffer)}m buffer at ${String.format("%.1f", currentSpeed)}m/s)")
            }

            _geofenceWarningTriggered.value = true

            if (isOutsideFence) {
                _geofenceViolationDetected.value = true
            }

            // FIRST TIME: Send BRAKE then RTL
            if (!geofenceActionTaken) {
                geofenceActionTaken = true
                rtlInitiated = false // Will be set to true once RTL process starts
                lastBrakeCommandTime = now

                if (isOutsideFence) {
                    Log.e("Geofence", "🚨🚨🚨 BREACH DETECTED! Outside fence - EMERGENCY BRAKE + RTL! 🚨🚨🚨")
                    Log.e("Geofence", "Position: $droneLat, $droneLon")
                } else {
                    Log.w("Geofence", "⚠️⚠️⚠️ TOO CLOSE TO FENCE! Distance: ${String.format("%.1f", distanceToFence)}m (buffer: ${String.format("%.1f", dynamicBuffer)}m) - EMERGENCY STOP!")
                }

                // Send BRAKE then RTL - this will set rtlInitiated = true
                sendEmergencyBrakeAndRTLBurst()
            }

            // CONTINUOUS ENFORCEMENT: Only send BRAKE if RTL hasn't been initiated yet
            // Once RTL process starts, we don't want to interfere with it
            if (!rtlInitiated && now - lastBrakeCommandTime > BRAKE_COMMAND_INTERVAL_MS) {
                lastBrakeCommandTime = now
                sendBrakeCommandImmediate()
            }
        }

        // Reset state when safely back inside
        // TWO-STAGE RESET:
        // 1. Reset geofenceActionTaken when drone is safely inside (beyond dynamic buffer) - allows re-trigger
        // 2. Reset warning flags only when further inside to prevent UI oscillation

        // Stage 1: Reset action taken when back inside fence beyond the dynamic buffer
        // This ensures geofence can re-trigger RTL if drone goes out again
        // Use 2x dynamic buffer as hysteresis to prevent oscillation at boundary
        val rearmThreshold = dynamicBuffer * 2.0
        if (isInsideFence && distanceToFence > rearmThreshold && geofenceActionTaken) {
            Log.i("Geofence", "✓ Drone back inside fence (${String.format("%.1f", distanceToFence)}m from edge, threshold: ${String.format("%.1f", rearmThreshold)}m) - Geofence REARMED for re-trigger")
            geofenceActionTaken = false
            rtlInitiated = false
            _geofenceViolationDetected.value = false
        }

        // Stage 2: Full reset only when safely inside (3x max buffer from edge)
        // This clears the warning UI state
        val resetThreshold = MAX_BUFFER_METERS * 3.0
        if (isInsideFence && distanceToFence > resetThreshold) {
            if (_geofenceWarningTriggered.value) {
                Log.i("Geofence", "✓ Drone safely inside fence (${String.format("%.1f", distanceToFence)}m from edge) - Warning cleared")
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
                    // Fallback to LOITER which also stops movement
                    repo?.changeMode(MavMode.LOITER)
                }
            } catch (e: Exception) {
                Log.e("Geofence", "BRAKE command error: ${e.message}")
                // Try LOITER as fallback
                try {
                    repo?.changeMode(MavMode.LOITER)
                } catch (e2: Exception) {
                    Log.e("Geofence", "LOITER fallback also failed: ${e2.message}")
                }
            }
        }
    }

    /**
     * EMERGENCY BURST: Send BRAKE to stop, then immediately try RTL.
     * OPTIMIZED: Minimal delay between BRAKE and RTL for fast response.
     * The BRAKE command just needs to be sent - drone starts decelerating immediately.
     * We don't need to wait for full stop before sending RTL.
     */
    private fun sendEmergencyBrakeAndRTLBurst() {
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

                // STEP 2: Minimal delay - just enough for command to register (300ms)
                // Drone starts braking immediately, we don't need to wait for full stop
                Log.i("Geofence", "⏳ Brief pause before RTL (300ms)...")
                delay(300)

                // STEP 3: Mark RTL as initiated - this stops continuous BRAKE commands
                rtlInitiated = true
                Log.i("Geofence", "🏠 RTL process initiated - stopping BRAKE enforcement")

                // STEP 4: Now send RTL and KEEP TRYING until it works
                Log.i("Geofence", "🏠 Now sending RTL command...")
                var rtlSuccess = false
                var attempts = 0
                val maxAttempts = 10

                while (!rtlSuccess && attempts < maxAttempts) {
                    attempts++
                    try {
                        Log.i("Geofence", "🏠 RTL attempt #$attempts...")
                        rtlSuccess = repository.changeMode(MavMode.RTL)
                        Log.i("Geofence", "🏠 RTL attempt #$attempts result: $rtlSuccess")

                        if (rtlSuccess) {
                            Log.i("Geofence", "✓✓✓ RTL MODE ACTIVATED SUCCESSFULLY! ✓✓✓")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("Geofence", "RTL attempt #$attempts error: ${e.message}")
                    }

                    // Short delay before retry (200ms)
                    if (!rtlSuccess) {
                        delay(200)
                    }
                }

                if (rtlSuccess) {
                    addNotification(Notification(
                        message = "🚨 GEOFENCE BREACH - Returning home!",
                        type = NotificationType.ERROR
                    ))
                    ttsManager?.speak("Geofence breach! Returning home!")
                } else {
                    Log.e("Geofence", "❌ RTL failed after $maxAttempts attempts - drone should remain stopped")
                    addNotification(Notification(
                        message = "⚠️ GEOFENCE - Drone stopped, RTL failed",
                        type = NotificationType.WARNING
                    ))
                    ttsManager?.speak("Geofence! Drone stopped!")
                }

                Log.i("Geofence", "═══════════════════════════════════════════════════")
                Log.i("Geofence", "✓ EMERGENCY GEOFENCE RESPONSE COMPLETE")
                Log.i("Geofence", "═══════════════════════════════════════════════════")

                // Reset the geofence triggering flag after a short delay
                // This ensures mode change detection doesn't show resume popup during geofence action
                delay(2000)
                geofenceTriggeringModeChange = false
                Log.d("Geofence", "Geofence mode change flag reset")
            } ?: Log.e("Geofence", "❌ No connection - cannot send emergency commands!")
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
