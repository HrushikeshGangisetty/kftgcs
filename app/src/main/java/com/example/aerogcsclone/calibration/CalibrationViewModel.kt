package com.example.aerogcsclone.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divpundir.mavlink.definitions.common.MavCmd
import com.example.aerogcsclone.telemetry.SharedViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * ViewModel for IMU (Accelerometer) Calibration - MissionPlanner Protocol
 *
 * Protocol Flow (replicating MissionPlanner):
 *
 * 1. User clicks "Start IMU Calibration"
 *    - Send MAV_CMD_PREFLIGHT_CALIBRATION with param5=1 (start accel calibration)
 *    - Set _inCalibration = true
 *    - Button text changes to "Click when Done"
 *    - Subscribe to STATUSTEXT and COMMAND_LONG messages
 *
 * 2. FC sends STATUSTEXT: "Place vehicle level and press any key"
 *    - Display message to user
 *    - OR FC sends COMMAND_LONG with MAV_CMD_ACCELCAL_VEHICLE_POS (param1 = position enum)
 *    - Extract position from param1 and display to user
 *
 * 3. User places drone and clicks "Click when Done"
 *    - Send MAV_CMD_ACCELCAL_VEHICLE_POS with param1 = current position value
 *    - This tells FC that position is ready
 *
 * 4. FC continues to next position, sends next STATUSTEXT or COMMAND_LONG
 *    - Repeat until all 6 positions complete
 *
 * 5. FC sends STATUSTEXT: "Calibration successful" or "Calibration failed"
 *    - Stop calibration, unsubscribe listeners
 */
class CalibrationViewModel(private val sharedViewModel: SharedViewModel) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    // Calibration state tracking
    private var _inCalibration = false
    private var count = 0
    private var currentPosition: AccelCalibrationPosition? = null

    private var statusTextCollectorJob: Job? = null
    private var commandLongCollectorJob: Job? = null

    // MAV_CMD_ACCELCAL_VEHICLE_POS command ID
    private val MAV_CMD_ACCELCAL_VEHICLE_POS: UInt = 42429u

    init {
        // Observe connection state
        viewModelScope.launch {
            sharedViewModel.isConnected.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
            }
        }
    }

    /**
     * Main calibration button click handler
     * - If not in calibration: Start calibration
     * - If in calibration: User confirms current position is ready
     */
    fun onButtonClick() {
        if (!_inCalibration) {
            startCalibration()
        } else {
            onPositionReady()
        }
    }

    fun onCalibrationButtonClick() {
        onButtonClick()
    }

    /**
     * Start the IMU calibration process
     */
    private fun startCalibration() {
        if (!_uiState.value.isConnected) {
            _uiState.update {
                it.copy(
                    calibrationState = CalibrationState.Failed("Not connected to drone"),
                    statusText = "Please connect to the drone first"
                )
            }
            return
        }

        viewModelScope.launch {
            // Reset any "spoken once" keys so announcements for this calibration run will play
            sharedViewModel.resetTtsSpokenKeys()

            // Announce calibration started via TTS
            sharedViewModel.announceCalibrationStarted()

            _uiState.update {
                it.copy(
                    calibrationState = CalibrationState.Initiating,
                    statusText = "Starting IMU calibration...",
                    currentPositionIndex = 0,
                    buttonText = "Click when Done"
                )
            }

            count = 0
            _inCalibration = true
            currentPosition = null

            // Subscribe to STATUSTEXT and COMMAND_LONG messages
            startMessageListeners()

            try {
                // Send PREFLIGHT_CALIBRATION command with param5=1 for accelerometer
                sharedViewModel.sendCalibrationCommand(
                    command = MavCmd.PREFLIGHT_CALIBRATION,
                    param1 = 0f, // Gyro
                    param2 = 0f, // Magnetometer
                    param3 = 0f, // Ground pressure
                    param4 = 0f, // Radio
                    param5 = 1f, // Accelerometer - START
                    param6 = 0f, // Compass/Motor
                    param7 = 0f  // Airspeed
                )

                _uiState.update {
                    it.copy(
                        statusText = "Waiting for flight controller response...",
                        buttonText = "Click when Done"
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        calibrationState = CalibrationState.Failed("Error: ${e.message}"),
                        statusText = "Error: ${e.message}",
                        buttonText = "Start Calibration"
                    )
                }
                _inCalibration = false
                stopMessageListeners()
            }
        }
    }

    /**
     * User confirms current position is ready
     */
    private fun onPositionReady() {
        val position = currentPosition
        if (position == null) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    calibrationState = CalibrationState.ProcessingPosition(position),
                    statusText = "Processing ${position.name}...",
                    buttonText = "Click when Done"
                )
            }

            try {
                // Send ACCELCAL_VEHICLE_POS command with param1 = position value
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_ACCELCAL_VEHICLE_POS,
                    param1 = position.paramValue.toFloat(),
                    param2 = 0f,
                    param3 = 0f,
                    param4 = 0f,
                    param5 = 0f,
                    param6 = 0f,
                    param7 = 0f
                )

                count++

                _uiState.update {
                    it.copy(
                        statusText = "Waiting for next position...",
                        currentPositionIndex = count,
                        buttonText = "Click when Done"
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        calibrationState = CalibrationState.Failed("Error: ${e.message}"),
                        statusText = "Error: ${e.message}",
                        buttonText = "Start Calibration"
                    )
                }
                _inCalibration = false
                stopMessageListeners()
            }
        }
    }

    /**
     * Start listening to STATUSTEXT and COMMAND_LONG messages
     */
    private fun startMessageListeners() {
        // Listen to STATUSTEXT messages
        statusTextCollectorJob = viewModelScope.launch {
            sharedViewModel.calibrationStatus.collect { statusText ->
                statusText?.let { text ->
                    handleStatusText(text)
                }
            }
        }

        // Listen to COMMAND_LONG messages (for ACCELCAL_VEHICLE_POS)
        commandLongCollectorJob = viewModelScope.launch {
            sharedViewModel.commandLong.collect { cmdLong ->
                // Check if this is ACCELCAL_VEHICLE_POS command
                if (cmdLong.command.value == MAV_CMD_ACCELCAL_VEHICLE_POS) {
                    val posValue = cmdLong.param1.toInt()
                    val position = AccelCalibrationPosition.fromParamValue(posValue)

                    if (position != null) {
                        currentPosition = position

                        // Announce the IMU position via TTS
                        sharedViewModel.announceIMUPosition(position.name)

                        _uiState.update {
                            it.copy(
                                calibrationState = CalibrationState.AwaitingUserInput(
                                    position = position,
                                    instruction = position.instruction
                                ),
                                statusText = "Please place vehicle ${position.name}",
                                currentPositionIndex = AccelCalibrationPosition.entries.indexOf(position),
                                buttonText = "Click when Done"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle STATUSTEXT messages from FC
     */
    private fun handleStatusText(text: String) {
        val lower = text.lowercase()

        // Update status text in UI
        _uiState.update { state ->
            state.copy(statusText = text)
        }

        // Check for completion messages
        when {
            lower.contains("calibration successful") || lower.contains("calibration complete") -> {
                // Announce calibration success via TTS
                sharedViewModel.announceCalibrationFinished(isSuccess = true)

                // Announce reboot drone message
                sharedViewModel.announceRebootDrone()

                _uiState.update {
                    it.copy(
                        calibrationState = CalibrationState.Success("Calibration completed successfully!"),
                        statusText = "Success! Calibration completed.",
                        showRebootDialog = true,
                        buttonText = "Start Calibration"
                    )
                }
                _inCalibration = false
                stopMessageListeners()
                // Reset spoken keys so next calibration run can re-announce positions
                sharedViewModel.resetTtsSpokenKeys()
            }

            lower.contains("calibration failed") -> {
                // Announce calibration failure via TTS
                sharedViewModel.announceCalibrationFinished(isSuccess = false)

                _uiState.update {
                    it.copy(
                        calibrationState = CalibrationState.Failed("Calibration failed"),
                        statusText = text,
                        buttonText = "Start Calibration"
                    )
                }
                _inCalibration = false
                stopMessageListeners()
                // Reset spoken keys so next calibration run can re-announce positions
                sharedViewModel.resetTtsSpokenKeys()
            }

            // Try to parse position from STATUSTEXT (fallback if FC doesn't send COMMAND_LONG)
            else -> {
                AccelCalibrationPosition.fromStatusText(text)?.let { position ->
                    currentPosition = position

                    // Announce the IMU position via TTS
                    sharedViewModel.announceIMUPosition(position.name)

                    _uiState.update {
                        it.copy(
                            calibrationState = CalibrationState.AwaitingUserInput(
                                position = position,
                                instruction = position.instruction
                            ),
                            statusText = text,
                            currentPositionIndex = AccelCalibrationPosition.entries.indexOf(position),
                            buttonText = "Click when Done"
                        )
                    }
                }
            }
        }
    }

    /**
     * Stop listening to messages
     */
    private fun stopMessageListeners() {
        statusTextCollectorJob?.cancel()
        statusTextCollectorJob = null
        commandLongCollectorJob?.cancel()
        commandLongCollectorJob = null
    }

    /**
     * Cancel calibration
     */
    fun cancelCalibration() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    calibrationState = CalibrationState.Cancelled,
                    statusText = "Calibration cancelled",
                    currentPositionIndex = 0,
                    showCancelDialog = false,
                    buttonText = "Start Calibration"
                )
            }
            _inCalibration = false
            count = 0
            currentPosition = null
            stopMessageListeners()
            // Reset spoken keys after cancel so future runs can re-announce
            sharedViewModel.resetTtsSpokenKeys()
        }
    }

    fun resetCalibration() {
        stopMessageListeners()
        _inCalibration = false
        count = 0
        currentPosition = null
        _uiState.update {
            it.copy(
                calibrationState = CalibrationState.Idle,
                statusText = "",
                currentPositionIndex = 0,
                showCancelDialog = false,
                buttonText = "Start Calibration"
            )
        }
        // Reset spoken keys when resetting calibration UI
        sharedViewModel.resetTtsSpokenKeys()
    }

    fun showCancelDialog(show: Boolean) {
        _uiState.update { it.copy(showCancelDialog = show) }
    }

    fun dismissRebootDialog() {
        _uiState.update { it.copy(showRebootDialog = false) }
    }

    /**
     * Initiate autopilot reboot after successful calibration.
     * Sends MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN command.
     */
    fun initiateReboot() {
        viewModelScope.launch {
            sharedViewModel.rebootAutopilot()
            // Keep the dialog open so user knows reboot was sent
            // They can dismiss it manually after seeing the drone reboot
        }
    }

    // Legacy method for compatibility with existing UI
    fun onNextPosition() {
        onPositionReady()
    }

    override fun onCleared() {
        super.onCleared()
        stopMessageListeners()
    }
}
