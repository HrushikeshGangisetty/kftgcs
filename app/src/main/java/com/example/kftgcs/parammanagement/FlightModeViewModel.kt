package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────
// Allowed flight modes (restricted as per requirements)
// ─────────────────────────────────────────────────────────────────────
data class FlightModeOption(val key: Int, val label: String)

val ALLOWED_FLIGHT_MODES = listOf(
    FlightModeOption(0,  "Stabilize"),
    FlightModeOption(2,  "Alt Hold"),
    FlightModeOption(3,  "Auto"),
    FlightModeOption(5,  "Loiter"),
    FlightModeOption(6,  "RTL")
)

// ArduCopter PWM boundaries for each of the 6 flight mode slots
// Slot 1: <1230 | 2: 1230–1360 | 3: 1360–1490 | 4: 1490–1620 | 5: 1620–1750 | 6: ≥1750
val SLOT_PWM_LABELS = listOf(
    "PWM < 1230",
    "PWM 1230–1360",
    "PWM 1360–1490",
    "PWM 1490–1620",
    "PWM 1620–1750",
    "PWM ≥ 1750"
)

// ─────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────
data class FlightModeState(
    /** Current mode key per slot (index 0 = slot 1 … index 5 = slot 6), null = not yet loaded */
    val modes: List<Int?> = List(6) { null },
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savingSlot: Int? = null,
    val isDroneConnected: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

// ─────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────
class FlightModeViewModel(
    private val sharedViewModel: SharedViewModel
) : ViewModel() {

    companion object {
        private const val TAG = "FlightModeVM"
        private val PARAM_NAMES = listOf(
            "FLTMODE1", "FLTMODE2", "FLTMODE3",
            "FLTMODE4", "FLTMODE5", "FLTMODE6"
        )
    }

    private val _state = MutableStateFlow(FlightModeState())
    val state: StateFlow<FlightModeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                _state.update { it.copy(isDroneConnected = telemetry.connected) }
            }
        }
    }

    // ── Load all 6 FLTMODE params from drone ─────────────────────────
    fun loadFromDrone() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }

        viewModelScope.launch {
            val modes = MutableList<Int?>(6) { null }
            var anyFailure = false

            PARAM_NAMES.forEachIndexed { idx, paramName ->
                val value = sharedViewModel.readParameter(paramName, timeoutMs = 4000L)
                if (value != null) {
                    modes[idx] = value.toInt()
                    LogUtils.d(TAG, "📥 $paramName = ${value.toInt()}")
                } else {
                    anyFailure = true
                    LogUtils.e(TAG, "⏱ Timeout reading $paramName")
                }
                delay(80) // small gap between reads
            }

            _state.update {
                it.copy(
                    modes = modes.toList(),
                    isLoading = false,
                    errorMessage = if (anyFailure) "Some parameters could not be read" else null
                )
            }
        }
    }

    // ── Write a single slot ───────────────────────────────────────────
    fun saveSlot(slotIndex: Int, modeKey: Int) {
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }
        val paramName = PARAM_NAMES[slotIndex]
        _state.update { it.copy(isSaving = true, savingSlot = slotIndex, errorMessage = null, successMessage = null) }

        viewModelScope.launch {
            val ack = sharedViewModel.setParameter(paramName, modeKey.toFloat(), timeoutMs = 5000L)
            if (ack != null) {
                val confirmed = ack.paramValue.toInt()
                LogUtils.d(TAG, "✅ $paramName confirmed = $confirmed")
                _state.update { current ->
                    val updated = current.modes.toMutableList()
                    updated[slotIndex] = confirmed
                    current.copy(
                        modes = updated,
                        isSaving = false,
                        savingSlot = null,
                        successMessage = "${paramName} set to ${modeLabel(confirmed)}"
                    )
                }
            } else {
                LogUtils.e(TAG, "❌ Failed to write $paramName")
                _state.update {
                    it.copy(isSaving = false, savingSlot = null, errorMessage = "Failed to write $paramName")
                }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(errorMessage = null, successMessage = null) }
    }

    // ── Pending local edit (before saving) ───────────────────────────
    fun updateLocalMode(slotIndex: Int, modeKey: Int) {
        _state.update { current ->
            val updated = current.modes.toMutableList()
            updated[slotIndex] = modeKey
            current.copy(modes = updated)
        }
    }

    private fun modeLabel(key: Int) =
        ALLOWED_FLIGHT_MODES.find { it.key == key }?.label ?: "Mode $key"
}

