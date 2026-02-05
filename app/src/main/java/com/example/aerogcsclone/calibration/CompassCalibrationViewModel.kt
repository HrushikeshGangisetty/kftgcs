package com.example.aerogcsclone.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aerogcsclone.telemetry.SharedViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

/**
 * ViewModel for ArduPilot Compass (Magnetometer) Calibration.
 *
 * Implements the MissionPlanner/ArduPilot compass calibration protocol:
 *
 * STEP 1: Start Calibration
 *   - Send MAV_CMD_DO_START_MAG_CAL (42424) with parameters (0, 1, 1, 0, 0, 0, 0)
 *     param1=0: Bitmask of magnetometers (0 = all)
 *     param2=1: Retry on failure
 *     param3=1: Autosave calibration
 *     param4-7=0: Reserved
 *   - Wait for COMMAND_ACK (MAV_RESULT_ACCEPTED or MAV_RESULT_IN_PROGRESS)
 *   - Subscribe to MAG_CAL_PROGRESS and MAG_CAL_REPORT messages
 *
 * STEP 2: Monitor Progress
 *   - Receive MAG_CAL_PROGRESS messages continuously
 *     - compass_id: Which compass (0, 1, 2, etc.)
 *     - completion_pct: Progress 0-100%
 *     - cal_status: Current status (NOT_STARTED, WAITING_TO_START, RUNNING_STEP_ONE, etc.)
 *     - direction_x/y/z: Guidance for user rotation
 *   - Update UI with progress bars for each compass
 *
 * STEP 3: Receive Final Report
 *   - Receive MAG_CAL_REPORT messages when calibration completes
 *     - compass_id: Which compass
 *     - cal_status: Final status (SUCCESS, FAILED, BAD_ORIENTATION, etc.)
 *     - ofs_x/y/z: Calibration offsets
 *     - diag_x/y/z: Diagonal scale factors
 *     - offdiag_x/y/z: Off-diagonal scale factors
 *     - fitness: Quality metric (lower is better, <100 is good)
 *     - autosaved: Whether offsets were automatically saved
 *
 * STEP 4: Accept or Cancel
 *   - If user accepts: Send MAV_CMD_DO_ACCEPT_MAG_CAL (42425)
 *   - If user cancels: Send MAV_CMD_DO_CANCEL_MAG_CAL (42426)
 *   - Unsubscribe from progress/report messages
 *
 * STEP 5: Reboot
 *   - Prompt user to reboot autopilot to apply new offsets
 *   - New parameters (COMPASS_OFS_X/Y/Z, etc.) are now stored
 */
class CompassCalibrationViewModel(private val sharedViewModel: SharedViewModel) : ViewModel() {

    private val _uiState = MutableStateFlow(CompassCalibrationUiState())
    val uiState: StateFlow<CompassCalibrationUiState> = _uiState.asStateFlow()

    private var progressListenerJob: Job? = null
    private var reportListenerJob: Job? = null
    private var statusTextListenerJob: Job? = null

    // MAVLink command IDs (as per ArduPilot specification)
    private val MAV_CMD_DO_START_MAG_CAL = 42424u
    private val MAV_CMD_DO_ACCEPT_MAG_CAL = 42425u
    private val MAV_CMD_DO_CANCEL_MAG_CAL = 42426u

    // Timeout for COMMAND_ACK
    private val ackTimeoutMs = 5000L

    init {
        // Observe connection state
        viewModelScope.launch {
            sharedViewModel.isConnected.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
            }
        }
    }

    /**
     * STEP 1: Start compass calibration using MAV_CMD_DO_START_MAG_CAL.
     *
     * Sends command with proper parameters matching MissionPlanner:
     * - param1 = 0 (calibrate all compasses)
     * - param2 = 1 (retry on failure)
     * - param3 = 1 (autosave results)
     */
    fun startCalibration() {
        if (!_uiState.value.isConnected) {
            _uiState.update {
                it.copy(
                    calibrationState = CompassCalibrationState.Failed("Not connected to drone"),
                    statusText = "Please connect to the drone first"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    calibrationState = CompassCalibrationState.Starting,
                    statusText = "Starting compass calibration...",
                    compassProgress = emptyMap(),
                    compassReports = emptyMap(),
                    overallProgress = 0,
                    calibrationComplete = false
                )
            }

            // CRITICAL: Request MAG_CAL_PROGRESS and MAG_CAL_REPORT messages from autopilot
            // These messages are NOT sent by default and must be explicitly requested
            try {
                sharedViewModel.requestMagCalMessages(hz = 10f) // Request at 10 Hz
                delay(200) // Give autopilot time to process message interval requests
            } catch (e: Exception) {
                // Message request failed, continue anyway
            }

            // Subscribe to MAG_CAL_PROGRESS and MAG_CAL_REPORT before sending command
            startProgressListener()
            startReportListener()
            startStatusTextListener() // Also listen to STATUSTEXT as fallback

            // --- ADDED: Timeout for progress message ---
            val progressTimeoutMs = 7000L
            var progressReceived = false
            val progressJob = launch {
                sharedViewModel.magCalProgress.take(1).collect {
                    progressReceived = true
                }
            }
            val statusTextJob = launch {
                sharedViewModel.calibrationStatus.take(1).collect {
                    progressReceived = true
                }
            }

            try {
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_DO_START_MAG_CAL,
                    param1 = 0f,
                    param2 = 1f,
                    param3 = 1f,
                    param4 = 0f,
                    param5 = 0f,
                    param6 = 0f,
                    param7 = 0f
                )

                val ack = sharedViewModel.awaitCommandAck(MAV_CMD_DO_START_MAG_CAL, ackTimeoutMs)
                val ackResult = ack?.result?.value

                if (ackResult == 0u || ackResult == 5u) {
                    // Announce calibration started via TTS
                    sharedViewModel.announceCalibrationStarted()

                    _uiState.update {
                        it.copy(
                            calibrationState = CompassCalibrationState.InProgress(
                                currentInstruction = "Rotate vehicle slowly - point each side down towards earth"
                            ),
                            statusText = "Calibrating... Rotate vehicle on all axes"
                        )
                    }

                    // --- ADDED: Wait for progress or timeout ---
                    delay(progressTimeoutMs)
                    if (!progressReceived) {
                        _uiState.update {
                            it.copy(
                                calibrationState = CompassCalibrationState.Failed("No progress received from autopilot!"),
                                statusText = "No progress received from autopilot.\n\nCheck connection, firmware, and try again."
                            )
                        }
                        stopAllListeners()
                        stopMagCalMessageStreaming()
                    }
                } else {
                    val ackName = ack?.result?.entry?.name ?: "NO_ACK"
                    _uiState.update {
                        it.copy(
                            calibrationState = CompassCalibrationState.Failed("Calibration rejected by autopilot: $ackName"),
                            statusText = "Failed to start calibration: $ackName"
                        )
                    }
                    stopAllListeners()
                    stopMagCalMessageStreaming()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        calibrationState = CompassCalibrationState.Failed("Error: ${e.message}"),
                        statusText = "Error starting calibration: ${e.message}"
                    )
                }
                stopAllListeners()
                stopMagCalMessageStreaming()
            } finally {
                progressJob.cancel()
                statusTextJob.cancel()
            }
        }
    }

    /**
     * STEP 4a: Accept calibration results using MAV_CMD_DO_ACCEPT_MAG_CAL.
     *
     * This tells the autopilot to save the calibration offsets to parameters.
     */
    fun acceptCalibration() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    statusText = "Accepting calibration...",
                    showAcceptDialog = false
                )
            }

            try {
                // Send MAV_CMD_DO_ACCEPT_MAG_CAL
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_DO_ACCEPT_MAG_CAL,
                    param1 = 0f, // Bitmask (0 = accept all)
                    param2 = 0f,
                    param3 = 0f,
                    param4 = 0f,
                    param5 = 0f,
                    param6 = 0f,
                    param7 = 0f
                )

                delay(500)

                _uiState.update {
                    it.copy(
                        statusText = "Calibration accepted! Please reboot the autopilot.",
                        calibrationState = CompassCalibrationState.Success(
                            message = "Calibration completed and saved successfully!",
                            compassReports = it.compassReports.values.toList()
                        )
                    )
                }

                stopAllListeners()

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statusText = "Error accepting calibration: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * STEP 4b: Cancel compass calibration using MAV_CMD_DO_CANCEL_MAG_CAL.
     */
    fun cancelCalibration() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    statusText = "Cancelling calibration...",
                    showCancelDialog = false
                )
            }

            try {
                // Send MAV_CMD_DO_CANCEL_MAG_CAL
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_DO_CANCEL_MAG_CAL,
                    param1 = 0f, // Bitmask (0 = cancel all)
                    param2 = 0f,
                    param3 = 0f,
                    param4 = 0f,
                    param5 = 0f,
                    param6 = 0f,
                    param7 = 0f
                )

                delay(500)

                _uiState.update {
                    it.copy(
                        calibrationState = CompassCalibrationState.Cancelled,
                        statusText = "Calibration cancelled",
                        compassProgress = emptyMap(),
                        compassReports = emptyMap(),
                        overallProgress = 0,
                        calibrationComplete = false
                    )
                }

                stopAllListeners()
                stopMagCalMessageStreaming() // FIXED: Also stop message streaming from autopilot

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statusText = "Error cancelling: ${e.message}"
                    )
                }
            }
        }
    }

    fun resetCalibration() {
        stopAllListeners()
        _uiState.update {
            it.copy(
                calibrationState = CompassCalibrationState.Idle,
                statusText = "",
                compassProgress = emptyMap(),
                compassReports = emptyMap(),
                overallProgress = 0,
                showCancelDialog = false,
                showAcceptDialog = false,
                calibrationComplete = false
            )
        }
    }

    fun showCancelDialog(show: Boolean) {
        _uiState.update { it.copy(showCancelDialog = show) }
    }

    fun showAcceptDialog(show: Boolean) {
        _uiState.update { it.copy(showAcceptDialog = show) }
    }

    /**
     * STEP 2: Listen to MAG_CAL_PROGRESS messages for ongoing progress updates.
     *
     * Message structure:
     * - compass_id: UByte (0, 1, 2 for compass 1, 2, 3)
     * - cal_mask: UByte
     * - cal_status: MagCalStatus enum
     * - attempt: UByte
     * - completion_pct: UByte (0-100)
     * - completion_mask: UByte
     * - direction_x/y/z: Float (guidance vector)
     */
    private fun startProgressListener() {
        progressListenerJob?.cancel()
        progressListenerJob = viewModelScope.launch {
            sharedViewModel.magCalProgress.collect { mavProgress ->
                val compassId = mavProgress.compassId.toInt()
                val calStatus = mavProgress.calStatus.entry?.name ?: "UNKNOWN"

                // Create CompassProgress data object
                val progress = CompassProgress(
                    compassId = mavProgress.compassId,
                    calMask = mavProgress.calMask,
                    calStatus = calStatus,
                    attempt = mavProgress.attempt,
                    completionPct = mavProgress.completionPct,
                    completionMask = mavProgress.completionMask,
                    directionX = mavProgress.directionX,
                    directionY = mavProgress.directionY,
                    directionZ = mavProgress.directionZ
                )

                // Update progress map
                val updatedProgress = _uiState.value.compassProgress.toMutableMap()
                updatedProgress[compassId] = progress

                // Calculate overall progress (average of all compasses)
                val overallPct = if (updatedProgress.isNotEmpty()) {
                    updatedProgress.values.map { it.completionPct.toInt() }.average().toInt()
                } else {
                    0
                }

                // Build instruction based on direction vector
                val instruction = buildRotationInstruction(
                    mavProgress.directionX,
                    mavProgress.directionY,
                    mavProgress.directionZ
                )

                _uiState.update {
                    it.copy(
                        compassProgress = updatedProgress,
                        overallProgress = overallPct,
                        statusText = "Calibrating... $overallPct%",
                        calibrationState = CompassCalibrationState.InProgress(
                            currentInstruction = instruction
                        )
                    )
                }
            }
        }
    }

    /**
     * Listen to STATUSTEXT messages for calibration status updates.
     * This is a fallback for when MAG_CAL_PROGRESS messages aren't being sent.
     * ArduPilot often sends calibration status via STATUSTEXT.
     */
    private fun startStatusTextListener() {
        statusTextListenerJob?.cancel()
        statusTextListenerJob = viewModelScope.launch {
            sharedViewModel.calibrationStatus.collect { statusText ->
                statusText?.let { text ->
                    val lower = text.lowercase()

                    // Check for compass/mag calibration keywords
                    if (!lower.contains("mag") && !lower.contains("compass") && !lower.contains("calib")) {
                        return@collect // Not compass-related
                    }

                    // Update status text in UI
                    _uiState.update { state ->
                        state.copy(statusText = text)
                    }

                    // Parse progress percentage from STATUSTEXT (e.g., "Calibration: 45%", "progress <45%>")
                    val progressRegex1 = """(\d+)%""".toRegex()
                    val progressRegex2 = """progress\s*<(\d+)%>""".toRegex()

                    val progressMatch = progressRegex2.find(lower) ?: progressRegex1.find(lower)
                    if (progressMatch != null) {
                        val progress = progressMatch.groupValues[1].toIntOrNull() ?: 0

                        _uiState.update {
                            it.copy(
                                overallProgress = progress,
                                statusText = "Calibrating... $progress%",
                                calibrationState = CompassCalibrationState.InProgress(
                                    currentInstruction = "Rotate vehicle slowly - point each side down"
                                )
                            )
                        }
                    }

                    // Check for success
                    if (lower.contains("calibration successful") ||
                        lower.contains("mag calibration successful") ||
                        lower.contains("compass calibration successful") ||
                        (lower.contains("calibration") && lower.contains("complete") && lower.contains("reboot"))) {

                        // Announce calibration finished successfully via TTS
                        sharedViewModel.announceCalibrationFinished(isSuccess = true)

                        _uiState.update {
                            it.copy(
                                calibrationState = CompassCalibrationState.Success(
                                    message = "Compass calibration completed successfully!",
                                    compassReports = it.compassReports.values.toList()
                                ),
                                statusText = "Success! Please reboot the autopilot.",
                                overallProgress = 100,
                                calibrationComplete = true
                            )
                        }
                        stopAllListeners()
                    }

                    // Check for failure
                    if (lower.contains("calibration failed") ||
                        lower.contains("mag cal failed") ||
                        lower.contains("compass cal failed")) {


                        // Announce calibration failed via TTS
                        sharedViewModel.announceCalibrationFinished(isSuccess = false)

                        _uiState.update {
                            it.copy(
                                calibrationState = CompassCalibrationState.Failed(text),
                                statusText = "Calibration failed - please retry"
                            )
                        }
                        stopAllListeners()
                    }
                }
            }
        }
    }

    /**
     * STEP 3: Listen to MAG_CAL_REPORT messages for final calibration results.
     *
     * Message structure:
     * - compass_id: UByte
     * - cal_status: MagCalStatus enum (SUCCESS, FAILED, BAD_ORIENTATION, etc.)
     * - autosaved: UByte (1 if saved automatically)
     * - fitness: Float (quality metric - lower is better, <100 is good)
     * - ofs_x/y/z: Float (offset values)
     * - diag_x/y/z: Float (diagonal scale factors)
     * - offdiag_x/y/z: Float (off-diagonal scale factors)
     * - orientation_confidence: Float
     * - old_orientation: MavSensorOrientation enum
     * - new_orientation: MavSensorOrientation enum
     * - scale_factor: Float
     */
    private fun startReportListener() {
        reportListenerJob?.cancel()
        reportListenerJob = viewModelScope.launch {
            sharedViewModel.magCalReport.collect { mavReport ->
                val compassId = mavReport.compassId.toInt()
                val calStatus = mavReport.calStatus.entry?.name ?: "UNKNOWN"

                // Create CompassReport data object
                val report = CompassReport(
                    compassId = mavReport.compassId,
                    calStatus = calStatus,
                    autosaved = mavReport.autosaved,
                    fitness = mavReport.fitness,
                    ofsX = mavReport.ofsX,
                    ofsY = mavReport.ofsY,
                    ofsZ = mavReport.ofsZ,
                    diagX = mavReport.diagX,
                    diagY = mavReport.diagY,
                    diagZ = mavReport.diagZ,
                    offdiagX = mavReport.offdiagX,
                    offdiagY = mavReport.offdiagY,
                    offdiagZ = mavReport.offdiagZ,
                    orientationConfidence = mavReport.orientationConfidence,
                    oldOrientation = mavReport.oldOrientation.entry?.name ?: "UNKNOWN",
                    newOrientation = mavReport.newOrientation.entry?.name ?: "UNKNOWN",
                    scaleFactor = mavReport.scaleFactor
                )

                // Update reports map
                val updatedReports = _uiState.value.compassReports.toMutableMap()
                updatedReports[compassId] = report

                // Check if all compasses have reported
                val allReported = checkIfAllCompassesReported(updatedReports)

                // Determine overall success or failure
                val allSuccess = updatedReports.values.all {
                    it.calStatus.contains("SUCCESS", ignoreCase = true)
                }
                val anyFailed = updatedReports.values.any {
                    it.calStatus.contains("FAIL", ignoreCase = true)
                }

                when {
                    allReported && allSuccess -> {
                        // All compasses calibrated successfully
                        _uiState.update {
                            it.copy(
                                compassReports = updatedReports,
                                calibrationComplete = true,
                                overallProgress = 100,
                                statusText = "Calibration complete - Review results and Accept",
                                showAcceptDialog = true,
                                calibrationState = CompassCalibrationState.InProgress(
                                    currentInstruction = "Calibration complete! Review results below."
                                )
                            )
                        }
                    }
                    allReported && anyFailed -> {
                        // At least one compass failed
                        val failedCompasses = updatedReports.filter {
                            it.value.calStatus.contains("FAIL", ignoreCase = true)
                        }.keys.joinToString(", ")

                        _uiState.update {
                            it.copy(
                                compassReports = updatedReports,
                                calibrationState = CompassCalibrationState.Failed(
                                    "Calibration failed for compass(es): $failedCompasses"
                                ),
                                statusText = "Calibration failed - please retry",
                                calibrationComplete = true
                            )
                        }
                        stopAllListeners()
                    }
                    else -> {
                        // Still waiting for more reports
                        _uiState.update {
                            it.copy(
                                compassReports = updatedReports,
                                statusText = "Received calibration report for compass $compassId..."
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if all active compasses have reported their results.
     * We consider calibration complete when we have reports for all compasses
     * that showed progress.
     */
    private fun checkIfAllCompassesReported(reports: Map<Int, CompassReport>): Boolean {
        val progressCompasses = _uiState.value.compassProgress.keys
        if (progressCompasses.isEmpty()) {
            // If we have at least one report, consider it complete
            return reports.isNotEmpty()
        }
        // Check if all compasses that showed progress have reported
        return progressCompasses.all { it in reports.keys }
    }

    /**
     * Build user-friendly rotation instruction based on direction vector.
     */
    private fun buildRotationInstruction(dirX: Float, dirY: Float, dirZ: Float): String {
        // Determine dominant axis
        val absX = kotlin.math.abs(dirX)
        val absY = kotlin.math.abs(dirY)
        val absZ = kotlin.math.abs(dirZ)

        return when {
            absZ > absX && absZ > absY -> {
                if (dirZ > 0) "Point TOP towards the ground"
                else "Point BOTTOM towards the ground"
            }
            absX > absY && absX > absZ -> {
                if (dirX > 0) "Point RIGHT side towards the ground"
                else "Point LEFT side towards the ground"
            }
            absY > absX && absY > absZ -> {
                if (dirY > 0) "Point FRONT towards the ground"
                else "Point BACK towards the ground"
            }
            else -> "Rotate vehicle slowly - point each side down towards earth"
        }
    }

    private fun stopAllListeners() {
        progressListenerJob?.cancel()
        progressListenerJob = null
        reportListenerJob?.cancel()
        reportListenerJob = null
        statusTextListenerJob?.cancel()
        statusTextListenerJob = null
    }

    /**
     * Stop the automatic streaming of MAG_CAL_PROGRESS and MAG_CAL_REPORT messages.
     * This is called when calibration is stopped or completed.
     */
    private fun stopMagCalMessageStreaming() {
        viewModelScope.launch {
            try {
                sharedViewModel.stopMagCalMessages()
                delay(100) // Give time for the stop command to take effect
            } catch (e: Exception) {
                // Failed to stop message streaming
            }
        }
    }

    /**
     * Initiate autopilot reboot after successful calibration.
     * Sends MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN command.
     */
    fun initiateReboot() {
        viewModelScope.launch {
            sharedViewModel.rebootAutopilot()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAllListeners()
        stopMagCalMessageStreaming()
    }
}
