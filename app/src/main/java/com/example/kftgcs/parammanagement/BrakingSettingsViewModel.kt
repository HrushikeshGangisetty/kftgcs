package com.example.kftgcs.parammanagement

import android.app.Application
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
// Braking-related parameters surfaced by this screen.
// Order here is the order they appear in the UI.
// ─────────────────────────────────────────────────────────────────────
private val BRAKE_PARAM_NAMES = listOf(
    "LOIT_BRK_ACC_M",
    "LOIT_BRK_DELAY",
    "LOIT_BRK_JRK_M",
    "PHLD_BRK_ANGLE",
    "PHLD_BRK_RATE"
)

/** Result of writing one parameter during a batch save. */
data class BrakeWriteResult(
    val name: String,
    val requested: Float,
    val confirmed: Float?,   // null = write failed / timed out
    val success: Boolean
)

data class BrakingSettingsState(
    val isDroneConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Current on-drone values, keyed by param name. Missing key = not loaded yet. */
    val values: Map<String, Float> = emptyMap(),
    val metadata: Map<String, ParamMeta> = emptyMap(),
    // Batch-write feedback
    val isWriting: Boolean = false,
    val writeResults: List<BrakeWriteResult>? = null
)

/**
 * Backs the Braking Settings screen. Reads the small fixed set of brake-related
 * parameters from the autopilot, lets the user stage edits to several of them,
 * and writes them in one confirmed batch (PARAM_SET each), reporting per-param
 * success so the UI can prompt for a reboot afterwards.
 */
class BrakingSettingsViewModel(
    private val sharedViewModel: SharedViewModel,
    private val application: Application
) : ViewModel() {

    companion object {
        private const val TAG = "BrakingSettingsVM"
        /** Order-preserving list of the params this screen manages. */
        val paramNames: List<String> get() = BRAKE_PARAM_NAMES
    }

    private val _state = MutableStateFlow(BrakingSettingsState())
    val state: StateFlow<BrakingSettingsState> = _state.asStateFlow()

    init {
        // Track connection status and auto-load once connected.
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                val wasConnected = _state.value.isDroneConnected
                _state.update { it.copy(isDroneConnected = telemetry.connected) }
                if (telemetry.connected && !wasConnected && _state.value.values.isEmpty()) {
                    fetchBrakeParams()
                }
            }
        }

        // Load parameter metadata (descriptions / ranges / units).
        viewModelScope.launch {
            try {
                val metadata = ArduPilotParamMetadataRepository.loadMetadata(application)
                _state.update { it.copy(metadata = metadata) }
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to load param metadata", e)
                _state.update { it.copy(metadata = FALLBACK_PARAM_METADATA) }
            }
        }
    }

    /** Read every brake parameter from the autopilot. */
    fun fetchBrakeParams() {
        if (_state.value.isLoading) return
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val loaded = mutableMapOf<String, Float>()
            for (name in BRAKE_PARAM_NAMES) {
                val value = sharedViewModel.readParameter(name, 3000L)
                if (value != null) {
                    loaded[name] = value
                    _state.update { it.copy(values = it.values + (name to value)) }
                } else {
                    LogUtils.e(TAG, "⏱ Timed out reading $name")
                }
            }
            val missing = BRAKE_PARAM_NAMES.filter { it !in loaded }
            _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = if (missing.isEmpty()) null
                    else "Could not read: ${missing.joinToString(", ")}"
                )
            }
            LogUtils.d(TAG, "Loaded ${loaded.size}/${BRAKE_PARAM_NAMES.size} brake params")
        }
    }

    /**
     * Write a batch of brake parameters. [changes] maps param name -> new value.
     * Each is written via PARAM_SET (with retries) and confirmed; per-param
     * results are published in [BrakingSettingsState.writeResults].
     */
    fun writeParams(changes: Map<String, Float>) {
        if (changes.isEmpty()) return
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }
        if (_state.value.isWriting) return

        _state.update { it.copy(isWriting = true, writeResults = null, errorMessage = null) }

        viewModelScope.launch {
            val results = mutableListOf<BrakeWriteResult>()
            for ((name, newValue) in changes) {
                var ack: com.divpundir.mavlink.definitions.common.ParamValue? = null
                val maxRetries = 3
                for (attempt in 1..maxRetries) {
                    try {
                        LogUtils.d(TAG, "📤 Writing $name = $newValue (attempt $attempt/$maxRetries)")
                        ack = sharedViewModel.setParameter(name, newValue, timeoutMs = 5000L)
                        if (ack != null) break
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "❌ Error writing $name (attempt $attempt)", e)
                    }
                    if (attempt < maxRetries) delay(500)
                }

                if (ack != null) {
                    val confirmed = ack.paramValue
                    results.add(BrakeWriteResult(name, newValue, confirmed, true))
                    _state.update { it.copy(values = it.values + (name to confirmed)) }
                    LogUtils.d(TAG, "✅ $name confirmed = $confirmed")
                } else {
                    results.add(BrakeWriteResult(name, newValue, null, false))
                    LogUtils.e(TAG, "❌ Failed to write $name")
                }
            }
            _state.update { it.copy(isWriting = false, writeResults = results) }
        }
    }

    /** Reboot the autopilot so newly-written parameters take full effect. */
    fun rebootDrone() {
        viewModelScope.launch {
            sharedViewModel.rebootAutopilot()
        }
    }

    fun clearWriteResults() {
        _state.update { it.copy(writeResults = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
