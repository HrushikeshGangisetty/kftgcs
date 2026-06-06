package com.example.kftgcs.parammanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.SharedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

data class ServoOutputUiState(
    val successMessage: String? = null,
    val errorMessage:   String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class ServoOutputViewModel(
    sharedViewModel: SharedViewModel
) : ViewModel() {

    val repository = ServoRepository(sharedViewModel, viewModelScope)

    val servoChannels: StateFlow<List<ServoChannel>> = repository.servoChannels
    val vehicleState:  StateFlow<VehicleState>       = repository.vehicleState
    val isLoadingAll:  StateFlow<Boolean>            = repository.isLoadingAll

    private val _uiState = MutableStateFlow(ServoOutputUiState())
    val uiState: StateFlow<ServoOutputUiState> = _uiState.asStateFlow()

    // ── Parameter load ────────────────────────────────────────────────────────

    fun loadChannels() {
        viewModelScope.launch { repository.requestServoParameters() }
    }

    // ── Live telemetry entry point ────────────────────────────────────────────

    /**
     * Manually inject a live PWM value for one channel's "Position" bar.
     *
     * In normal operation this is driven automatically: [ServoRepository] collects
     * the high-frequency SERVO_OUTPUT_RAW telemetry stream (wired via
     * SharedViewModel.servoOutputRaw) and updates all 16 bars itself — NOT the
     * parameter protocol. This method is retained for tests / simulated input.
     */
    fun onServoOutputRawReceived(channel: Int, pwmValue: Int) {
        repository.onServoOutputRawReceived(channel, pwmValue)
    }

    // ── Parameter writes ──────────────────────────────────────────────────────

    fun setFunction(channelIndex: Int, function: ServoFunction) {
        viewModelScope.launch {
            repository.setServoFunction(channelIndex, function).fold(
                onSuccess = { _uiState.update { it.copy(successMessage = "CH $channelIndex function saved") } },
                onFailure = { e -> _uiState.update { it.copy(errorMessage = "CH $channelIndex: ${e.message}") } }
            )
        }
    }

    fun setMin(channelIndex: Int, minPwm: Int) {
        viewModelScope.launch {
            repository.setServoMin(channelIndex, minPwm).fold(
                onSuccess = { _uiState.update { it.copy(successMessage = "CH $channelIndex MIN = $minPwm µs") } },
                onFailure = { e -> _uiState.update { it.copy(errorMessage = "CH $channelIndex: ${e.message}") } }
            )
        }
    }

    fun setMax(channelIndex: Int, maxPwm: Int) {
        viewModelScope.launch {
            repository.setServoMax(channelIndex, maxPwm).fold(
                onSuccess = { _uiState.update { it.copy(successMessage = "CH $channelIndex MAX = $maxPwm µs") } },
                onFailure = { e -> _uiState.update { it.copy(errorMessage = "CH $channelIndex: ${e.message}") } }
            )
        }
    }

    fun setTrim(channelIndex: Int, trimPwm: Int) {
        viewModelScope.launch {
            repository.setServoTrim(channelIndex, trimPwm).fold(
                onSuccess = { _uiState.update { it.copy(successMessage = "CH $channelIndex TRIM = $trimPwm µs") } },
                onFailure = { e -> _uiState.update { it.copy(errorMessage = "CH $channelIndex: ${e.message}") } }
            )
        }
    }

    fun setReverse(channelIndex: Int, reversed: Boolean) {
        viewModelScope.launch {
            repository.setServoReverse(channelIndex, reversed).fold(
                onSuccess = { _uiState.update { it.copy(successMessage = "CH $channelIndex reversed=$reversed") } },
                onFailure = { e -> _uiState.update { it.copy(errorMessage = "CH $channelIndex: ${e.message}") } }
            )
        }
    }

    // ── Message housekeeping ──────────────────────────────────────────────────

    fun clearMessages() {
        _uiState.update { it.copy(successMessage = null, errorMessage = null) }
        repository.clearLastError()
    }
}
