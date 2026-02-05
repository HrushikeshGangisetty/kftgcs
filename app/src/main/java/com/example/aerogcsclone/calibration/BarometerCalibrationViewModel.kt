package com.example.aerogcsclone.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divpundir.mavlink.definitions.common.MavCmd
import com.example.aerogcsclone.telemetry.SharedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// UI state for the barometer calibration screen
// Keeps simple fields to minimize UI changes while adding robust backend logic

data class BarometerCalibrationUiState(
    val isConnected: Boolean = false,
    val statusText: String = "",
    val isCalibrating: Boolean = false,
    val progress: Int = 0,
    val isStopped: Boolean = false,
    val isFlatSurface: Boolean = true,
    val isWindGood: Boolean = true
)

class BarometerCalibrationViewModel(
    private val sharedViewModel: SharedViewModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(BarometerCalibrationUiState())
    val uiState: StateFlow<BarometerCalibrationUiState> = _uiState

    private var statusJob: Job? = null

    // Tuning knobs
    private val ackTimeoutMs = 4000L
    private val maxRetries = 1
    private val finalOutcomeTimeoutMs = 10000L

    init {
        // Observe connection state from SharedViewModel
        viewModelScope.launch {
            sharedViewModel.isConnected.collect { connected ->
                _uiState.update { it.copy(isConnected = connected) }
            }
        }
    }

    fun checkConditions(flatSurface: Boolean, windGood: Boolean) {
        _uiState.update { it.copy(isFlatSurface = flatSurface, isWindGood = windGood) }
    }

    fun startCalibration() {
        val state = _uiState.value
        // Validate environment conditions with clear messaging
        if (!state.isFlatSurface && !state.isWindGood) {
            val message = "Place the drone on a flat surface. Wind condition is not good. It is better to stop flying and calibrating the drone."
            _uiState.update {
                it.copy(statusText = message)
            }
            // Announce the warning message
            sharedViewModel.speak(message)
            return
        } else if (!state.isFlatSurface) {
            val message = "Place the drone on a flat surface."
            _uiState.update { it.copy(statusText = message) }
            sharedViewModel.speak(message)
            return
        } else if (!state.isWindGood) {
            val message = "Wind condition is not good. It is better to stop flying and calibrating the drone."
            _uiState.update { it.copy(statusText = message) }
            sharedViewModel.speak(message)
            return
        }
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(statusText = "Please connect to the drone first") }
            return
        }

        viewModelScope.launch {
            // Announce calibration started
            sharedViewModel.announceCalibrationStarted()

            _uiState.update {
                it.copy(
                    statusText = "Starting barometer calibration...",
                    isCalibrating = true,
                    progress = 0,
                    isStopped = false
                )
            }

            // Start listening to STATUSTEXT messages relevant to barometer calibration
            startStatusListener()

            try {
                var started = false
                var lastAckText: String? = null
                var lastAckResult: UInt? = null

                repeat(maxRetries + 1) { attempt ->
                    // Send MAV_CMD_PREFLIGHT_CALIBRATION with barometer flag (param3 = 1)
                    sharedViewModel.sendCalibrationCommand(
                        command = MavCmd.PREFLIGHT_CALIBRATION,
                        param1 = 0f, // gyro
                        param2 = 0f, // mag
                        param3 = 1f, // baro
                        param4 = 0f, // radio
                        param5 = 0f, // accel
                        param6 = 0f, // esc
                        param7 = 0f
                    )


                    val ack = sharedViewModel.awaitCommandAck(241u, ackTimeoutMs)
                    val result = ack?.result?.value
                    lastAckResult = result
                    lastAckText = ack?.result?.entry?.name ?: result?.toString()
                    val ok = (result == 0u /* ACCEPTED */) || (result == 5u /* IN_PROGRESS */)
                    if (ok) {
                        started = true
                        return@repeat
                    } else if (ack != null) {
                        // Received a non-accepted ACK (e.g. temporarily rejected / denied)
                        // Do not immediately stop listening to STATUSTEXT; allow autopilot to still report final outcome.
                        started = false
                        return@repeat
                    }
                    // ack == null -> retry
                }

                if (!started) {
                    if (lastAckResult != null) {
                        // We received an ACK but it was not accepted; wait for STATUSTEXT outcome before failing.
                        _uiState.update {
                            it.copy(
                                statusText = "Start returned: ${lastAckText ?: "ACK"}. Waiting for status updates...",
                                isCalibrating = true,
                                progress = 5
                            )
                        }

                        val success = awaitFinalOutcome(finalOutcomeTimeoutMs)
                        if (success == true) {
                            _uiState.update { it.copy(statusText = "Barometer calibration successful", isCalibrating = false, progress = 100) }
                        } else if (success == false) {
                            _uiState.update { it.copy(statusText = "Barometer calibration failed", isCalibrating = false) }
                        } else {
                            _uiState.update { it.copy(statusText = "No explicit success received after ACK (${lastAckText ?: "unknown"}). Assuming completion if STATUSTEXT not received.", isCalibrating = false, progress = 100) }
                        }

                        stopStatusListener()
                        return@launch
                    } else {
                        // No ACK at all -> fail
                        _uiState.update {
                            it.copy(
                                isCalibrating = false,
                                statusText = "Failed to start barometer calibration: No ACK"
                            )
                        }
                        stopStatusListener()
                        return@launch
                    }
                }

                _uiState.update { it.copy(statusText = "Calibrating barometer...", progress = 10) }

                // Await final outcome from STATUSTEXT
                val success = awaitFinalOutcome(finalOutcomeTimeoutMs)
                if (success == true) {
                    _uiState.update { it.copy(statusText = "Barometer calibration successful", isCalibrating = false, progress = 100) }
                    // Announce success
                    sharedViewModel.announceCalibrationFinished(isSuccess = true)
                } else if (success == false) {
                    _uiState.update { it.copy(statusText = "Barometer calibration failed", isCalibrating = false) }
                    // Announce failure
                    sharedViewModel.announceCalibrationFinished(isSuccess = false)
                } else {
                    // Timeout: assume completion but inform user no explicit success was received
                    _uiState.update { it.copy(statusText = "Assuming barometer calibration completed (no explicit success received)", isCalibrating = false, progress = 100) }
                }

                stopStatusListener()
            } catch (e: Exception) {
                _uiState.update { it.copy(statusText = "Error: ${e.message}", isCalibrating = false) }
                // Announce failure
                sharedViewModel.announceCalibrationFinished(isSuccess = false)
                stopStatusListener()
            }
        }
    }

    fun stopCalibration() {
        viewModelScope.launch {
            // No dedicated cancel for baro; send neutral PREFLIGHT_CALIBRATION
            try {
                sharedViewModel.sendCalibrationCommand(
                    command = MavCmd.PREFLIGHT_CALIBRATION,
                    param1 = 0f, param2 = 0f, param3 = 0f, param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
                )
            } catch (_: Exception) { /* ignore */ }
            _uiState.update { it.copy(statusText = "Calibration stopped.", isStopped = true, isCalibrating = false) }
            stopStatusListener()
        }
    }

    private fun startStatusListener() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            sharedViewModel.calibrationStatus.collectLatest { text ->
                if (text.isNullOrBlank()) return@collectLatest
                val lower = text.lowercase()
                val relevant = listOf("baro", "barometer", "pressure", "calib").any { lower.contains(it) }
                if (!relevant) return@collectLatest

                _uiState.update { it.copy(statusText = text) }

                // Treat any clear success indicator as completion
                if (lower.contains("fail")) {
                    _uiState.update { it.copy(isCalibrating = false) }
                } else if (lower.contains("success") || lower.contains("complete") || lower.contains("completed") || lower.contains("done")) {
                    _uiState.update { it.copy(progress = 100, isCalibrating = false) }
                }
            }
        }
    }

    private fun stopStatusListener() {
        statusJob?.cancel()
        statusJob = null
    }

    private suspend fun awaitFinalOutcome(timeoutMs: Long): Boolean? = withTimeoutOrNull(timeoutMs) {
        sharedViewModel.calibrationStatus
            .mapNotNull { it }
            .first { t ->
                val l = t.lowercase()
                // Prefer barometer mentions but accept generic calibration outcome keywords
                (l.contains("baro") || l.contains("barometer") || l.contains("pressure") || l.contains("calib")) &&
                        (l.contains("success") || l.contains("fail") || l.contains("complete") || l.contains("completed") || l.contains("done"))
            }
            .let { finalTxt ->
                val l = finalTxt.lowercase()
                (l.contains("success") || l.contains("complete") || l.contains("completed") || l.contains("done"))
            }
    }
}
