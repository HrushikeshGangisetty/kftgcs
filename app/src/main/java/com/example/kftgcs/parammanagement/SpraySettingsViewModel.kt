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
// Spraying-configuration parameters surfaced by this screen.
// ─────────────────────────────────────────────────────────────────────
/** Describes one editable field on the Spraying Configuration screen. */
data class SprayField(
    val param: String,
    val label: String,
    val min: Float,
    val max: Float,
    /** When non-empty, render as a dropdown with these (value -> label) options. */
    val options: Map<Int, String> = emptyMap()
)

// NOTE: SpraySettingsScreen slices this list by fixed index ranges (0..3, 3..6, 6..9) to lay out
// its three columns, so keep the entries in this order and keep the size at 9.
val SPRAY_FIELDS = listOf(
    SprayField("SPRAY_ENABLE", "Enable Spraying", 0f, 1f, mapOf(0 to "Disable", 1 to "Enable")),
    // This screen configures the sprayer switch on RC7 (set it to 15 = Sprayer). Spray MONITORING
    // does not depend on this field — TelemetryRepository resolves whichever channel has
    // RCx_OPTION = 15, so an airframe wired to RC6 is still monitored correctly.
    SprayField("RC7_OPTION", "RC7 Switch (15 = Sprayer)", 0f, 102f),
    SprayField("BRD_PWM_COUNT", "BRD PWM Count", 0f, 8f),
    SprayField("SERVO9_FUNCTION", "Servo9 Function [AUX 1]", 0f, 130f),
    SprayField("SERVO9_MIN", "PWM Min", 500f, 2200f),
    SprayField("SERVO9_MAX", "PWM Max", 800f, 2200f),
    SprayField("SERVO10_FUNCTION", "Servo10 Function [AUX 2]", 0f, 130f),
    SprayField("SERVO10_MIN", "PWM Min", 500f, 2200f),
    SprayField("SERVO10_MAX", "PWM Max", 800f, 2200f)
)

/** Result of writing one parameter during a batch update. */
data class SprayWriteResult(
    val name: String,
    val requested: Float,
    val confirmed: Float?,   // null = write failed / timed out
    val success: Boolean
)

data class SpraySettingsState(
    val isDroneConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Current on-drone values, keyed by param name. Missing key = not loaded yet. */
    val values: Map<String, Float> = emptyMap(),
    // Batch-write feedback
    val isWriting: Boolean = false,
    val writeResults: List<SprayWriteResult>? = null
)

/**
 * Backs the Spraying Configuration screen. Reads the fixed set of spray / servo
 * parameters from the autopilot, lets the user edit them, and writes them in one
 * confirmed batch (PARAM_SET each) when the user taps Update — reporting per-param
 * success so the UI can prompt for a reboot afterwards.
 */
class SpraySettingsViewModel(
    private val sharedViewModel: SharedViewModel,
    private val application: Application
) : ViewModel() {

    companion object {
        private const val TAG = "SpraySettingsVM"
        val fields: List<SprayField> get() = SPRAY_FIELDS
        private val paramNames: List<String> get() = SPRAY_FIELDS.map { it.param }
    }

    private val _state = MutableStateFlow(SpraySettingsState())
    val state: StateFlow<SpraySettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                val wasConnected = _state.value.isDroneConnected
                _state.update { it.copy(isDroneConnected = telemetry.connected) }
                if (telemetry.connected && !wasConnected && _state.value.values.isEmpty()) {
                    fetchSprayParams()
                }
            }
        }
    }

    /** Read every spray parameter from the autopilot. */
    fun fetchSprayParams() {
        if (_state.value.isLoading) return
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val loaded = mutableMapOf<String, Float>()
            for (name in paramNames) {
                val value = sharedViewModel.readParameter(name, 3000L)
                if (value != null) {
                    loaded[name] = value
                    _state.update { it.copy(values = it.values + (name to value)) }
                } else {
                    LogUtils.e(TAG, "⏱ Timed out reading $name")
                }
            }
            val missing = paramNames.filter { it !in loaded }
            _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = if (missing.isEmpty()) null
                    else "Could not read: ${missing.joinToString(", ")}"
                )
            }
            LogUtils.d(TAG, "Loaded ${loaded.size}/${paramNames.size} spray params")
        }
    }

    /**
     * Write a batch of spray parameters. [changes] maps param name -> new value.
     * Each is written via PARAM_SET (with retries) and confirmed.
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
            val results = mutableListOf<SprayWriteResult>()
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
                    results.add(SprayWriteResult(name, newValue, confirmed, true))
                    _state.update { it.copy(values = it.values + (name to confirmed)) }
                    LogUtils.d(TAG, "✅ $name confirmed = $confirmed")
                } else {
                    results.add(SprayWriteResult(name, newValue, null, false))
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
