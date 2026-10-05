package com.example.kftgcs.parammanagement

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
// Static option lists (mimic Mission Planner Setup → Optional Hardware → Battery Monitor)
// ─────────────────────────────────────────────────────────────────────

/** BATT_MONITOR enum — common values supported across ArduPilot vehicle types. */
data class BattMonitorOption(val key: Int, val label: String)

val BATT_MONITOR_OPTIONS = listOf(
    BattMonitorOption(0,  "0: Disabled"),
    BattMonitorOption(3,  "3: Analog Voltage Only"),
    BattMonitorOption(4,  "4: Analog Voltage and Current"),
    BattMonitorOption(5,  "5: Solo"),
    BattMonitorOption(6,  "6: Bebop"),
    BattMonitorOption(7,  "7: SMBus-Generic"),
    BattMonitorOption(8,  "8: DroneCAN-BatteryInfo"),
    BattMonitorOption(9,  "9: ESC"),
    BattMonitorOption(10, "10: Sum Of Selected Monitors"),
    BattMonitorOption(11, "11: FuelFlow"),
    BattMonitorOption(12, "12: FuelLevelPWM"),
    BattMonitorOption(13, "13: SMBus-SUI3"),
    BattMonitorOption(14, "14: SMBus-SUI6"),
    BattMonitorOption(15, "15: NeoDesign"),
    BattMonitorOption(16, "16: SMBus-Maxell"),
    BattMonitorOption(17, "17: Generator-Elec"),
    BattMonitorOption(18, "18: Generator-Fuel"),
    BattMonitorOption(19, "19: Rotoye"),
    BattMonitorOption(20, "20: MPPT"),
    BattMonitorOption(21, "21: INA2XX"),
    BattMonitorOption(22, "22: LTC2946"),
    BattMonitorOption(23, "23: Torqeedo"),
    BattMonitorOption(27, "27: AD7091R5")
)

/**
 * Sensor preset → defaults for BATT_VOLT_MULT and BATT_AMP_PERVLT.
 * Picking a preset prefills those two fields; user can still tweak via calibration.
 * Values are taken from Mission Planner's HwIDPicker / common ArduPilot defaults.
 */
data class SensorPreset(
    val label: String,
    val voltMult: Float?,    // null = "Other / leave as-is"
    val ampPerVolt: Float?
)

val SENSOR_PRESETS = listOf(
    SensorPreset("Other",                                     null,     null),
    SensorPreset("3DR Power Module / APM Power Module",       10.1f,    17.0f),
    SensorPreset("Holybro PM02 (12S)",                        18.0f,    36.364f),
    SensorPreset("Holybro PM06",                              18.0f,    36.8f),
    SensorPreset("Holybro PM07 (Pixhawk 4)",                  18.0f,    24.0f),
    SensorPreset("Holybro PM08 (14S)",                        21.0f,    40.0f),
    SensorPreset("Mauch HS-050-LV",                           12.02f,   50.0f),
    SensorPreset("Mauch HS-100-LV",                           12.02f,   100.0f),
    SensorPreset("Mauch HS-200-LV",                           12.02f,   200.0f),
    SensorPreset("CUAV HV PM",                                21.0f,    40.0f),
    SensorPreset("4-in-1 ESC (typical)",                      11.0f,    38.0f)
)

/**
 * HW Ver preset → defaults for BATT_VOLT_PIN and BATT_CURR_PIN.
 * These are the ADC pin assignments for common autopilot boards.
 */
data class HwVerPreset(
    val label: String,
    val voltPin: Int?,    // null = "Other / leave as-is"
    val currPin: Int?
)

val HW_VER_PRESETS = listOf(
    HwVerPreset("Other",                                       null,     null),
    HwVerPreset("APM1",                                        0,        1),
    HwVerPreset("APM2",                                        1,        2),
    HwVerPreset("APM2.5 / APM2.6",                             13,       12),
    HwVerPreset("Pixhawk1 / Cube / mRo",                       2,        3),
    HwVerPreset("Pixhawk4 / Pixhack v3",                       4,        3),
    HwVerPreset("CUAV V5+ / V5 Nano",                          4,        3),
    HwVerPreset("Pixracer / mRo X2.1",                         2,        3),
    HwVerPreset("4-in-1 ESC (Pixhawk pinout)",                 13,       12),
    HwVerPreset("Navio2 / Edge",                               0,        1)
)

internal fun sanitizeDecimalString(text: String): String {
    var seenDot = false
    return buildString {
        for (c in text) {
            when {
                c.isDigit() -> append(c)
                c == '.' && !seenDot -> { append(c); seenDot = true }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────

data class BatteryMonitorState(
    // Connection & lifecycle
    val isDroneConnected: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,

    // Loaded values from drone (null = not yet read)
    val loadedMonitor: Int? = null,
    val loadedCapacityMah: Int? = null,
    val loadedVoltMult: Float? = null,
    val loadedAmpPerVolt: Float? = null,
    val loadedVoltPin: Int? = null,
    val loadedCurrPin: Int? = null,

    // Live telemetry (mirrors of TelemetryState fields)
    val measuredVoltageLive: Float? = null,    // Battery Voltage Calced
    val measuredCurrentLive: Float? = null,    // Current Calced

    val consumedMahLive: Int? = null,          // FC's mAh counter since boot

    // User-entered measurements for calibration (Volts)
    val measuredVoltageInput: String = "",

    // Current calibration by the charger method (no clamp meter needed)
    val loggedMahInput: String = "",           // mAh the FC counted for the flight
    val chargedMahInput: String = "",          // mAh the charger put back in

    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    /**
     * Charger-method BATT_AMP_PERVLT:
     *   newAmpPerVolt = (chargedMah / loggedMah) × currentAmpPerVolt
     * Consumed mAh is current integrated over time, so a wrong current scale shows up as
     * the same ratio between what the FC counted and what the charger replaced.
     * Null unless the flight was big enough to calibrate on and the ratio is plausible —
     * a typo here would silently wreck every mAh/% reading afterwards.
     */
    val calculatedAmpPerVolt: Float?
        get() {
            val logged = loggedMahInput.toFloatOrNull() ?: return null
            val charged = chargedMahInput.toFloatOrNull() ?: return null
            val apv = loadedAmpPerVolt ?: return null
            if (logged < MIN_CAL_MAH || charged < MIN_CAL_MAH) return null
            val ratio = charged / logged
            if (ratio !in 0.5f..3f) return null
            return ratio * apv
        }

    /** Why [calculatedAmpPerVolt] is null despite both fields being filled, else null. */
    val ampCalRejectReason: String?
        get() {
            val logged = loggedMahInput.toFloatOrNull() ?: return null
            val charged = chargedMahInput.toFloatOrNull() ?: return null
            if (logged < MIN_CAL_MAH || charged < MIN_CAL_MAH)
                return "Use a flight of at least $MIN_CAL_MAH mAh — small numbers are mostly sensor offset"
            if (charged / logged !in 0.5f..3f)
                return "Charged/logged ratio ${"%.2f".format(charged / logged)} is implausible — check both numbers"
            return null
        }

    companion object {
        const val MIN_CAL_MAH = 3000
    }

    /**
     * Voltage Divider Calced (Mission Planner formula):
     *   newMult = (measuredVoltage / calcedVoltage) × currentMult
     * Returns null when inputs are missing or calced voltage is ~0.
     */
    val calculatedVoltageDivider: Float?
        get() {
            val measured = measuredVoltageInput.toFloatOrNull() ?: return null
            val calced = measuredVoltageLive ?: return null
            val mult = loadedVoltMult ?: return null
            if (calced <= 0.01f || measured <= 0f) return null
            return (measured / calced) * mult
        }
}

// ─────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────

class BatteryMonitorViewModel(
    private val sharedViewModel: SharedViewModel
) : ViewModel() {

    companion object {
        private const val TAG = "BatteryMonitorVM"
        private const val PARAM_TIMEOUT_MS = 4000L
        private const val WRITE_TIMEOUT_MS = 5000L
        private const val INTER_PARAM_DELAY_MS = 80L
    }

    private val _state = MutableStateFlow(BatteryMonitorState())
    val state: StateFlow<BatteryMonitorState> = _state.asStateFlow()

    init {
        // Mirror connection + live voltage/current from the SharedViewModel
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { t ->
                _state.update {
                    it.copy(
                        isDroneConnected = t.connected,
                        measuredVoltageLive = t.voltage,
                        measuredCurrentLive = t.currentA,
                        consumedMahLive = t.consumedMah
                    )
                }
            }
        }
    }

    // ── Read all six BATT_* params from the drone ────────────────────
    fun loadFromDrone() {
        if (_state.value.isLoading) return
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }
        _state.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }

        viewModelScope.launch {
            val monitor    = sharedViewModel.readParameter("BATT_MONITOR",    PARAM_TIMEOUT_MS)
            delay(INTER_PARAM_DELAY_MS)
            val capacity   = sharedViewModel.readParameter("BATT_CAPACITY",   PARAM_TIMEOUT_MS)
            delay(INTER_PARAM_DELAY_MS)
            val voltMult   = sharedViewModel.readParameter("BATT_VOLT_MULT",  PARAM_TIMEOUT_MS)
            delay(INTER_PARAM_DELAY_MS)
            val ampPerVolt = sharedViewModel.readParameter("BATT_AMP_PERVLT", PARAM_TIMEOUT_MS)
            delay(INTER_PARAM_DELAY_MS)
            val voltPin    = sharedViewModel.readParameter("BATT_VOLT_PIN",   PARAM_TIMEOUT_MS)
            delay(INTER_PARAM_DELAY_MS)
            val currPin    = sharedViewModel.readParameter("BATT_CURR_PIN",   PARAM_TIMEOUT_MS)

            val anyFailure = listOf(monitor, capacity, voltMult, ampPerVolt, voltPin, currPin).any { it == null }

            _state.update {
                it.copy(
                    isLoading = false,
                    loadedMonitor    = monitor?.toInt() ?: it.loadedMonitor,
                    loadedCapacityMah = capacity?.toInt() ?: it.loadedCapacityMah,
                    loadedVoltMult   = voltMult ?: it.loadedVoltMult,
                    loadedAmpPerVolt = ampPerVolt ?: it.loadedAmpPerVolt,
                    loadedVoltPin    = voltPin?.toInt() ?: it.loadedVoltPin,
                    loadedCurrPin    = currPin?.toInt() ?: it.loadedCurrPin,
                    errorMessage = if (anyFailure) "Some BATT_* parameters failed to read — try again" else null
                )
            }
            LogUtils.d(TAG, "📥 Loaded: MONITOR=$monitor CAPACITY=$capacity VOLT_MULT=$voltMult AMP_PERVLT=$ampPerVolt VOLT_PIN=$voltPin CURR_PIN=$currPin")
        }
    }

    // ── Write all changed params to the drone ────────────────────────
    /**
     * Writes any of the 6 BATT_* params whose desired value differs from the loaded value.
     * Pass null for any field you don't want to change.
     */
    fun saveToDrone(
        monitor: Int?,
        capacityMah: Int?,
        voltMult: Float?,
        ampPerVolt: Float?,
        voltPin: Int?,
        currPin: Int?
    ) {
        if (_state.value.isSaving) return
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(errorMessage = "Drone not connected") }
            return
        }
        _state.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }

        viewModelScope.launch {
            data class Write(val name: String, val value: Float, val isInt: Boolean)
            val writes = mutableListOf<Write>()

            val s = _state.value
            if (monitor != null && monitor != s.loadedMonitor)
                writes += Write("BATT_MONITOR", monitor.toFloat(), true)
            if (capacityMah != null && capacityMah != s.loadedCapacityMah)
                writes += Write("BATT_CAPACITY", capacityMah.toFloat(), true)
            if (voltMult != null) {
                if (s.loadedVoltMult != null && floatsEqual(voltMult, s.loadedVoltMult)) {
                    LogUtils.d(TAG, "⚠️ BATT_VOLT_MULT unchanged (${voltMult}), skipping write")
                } else {
                    writes += Write("BATT_VOLT_MULT", voltMult, false)
                }
            }
            if (ampPerVolt != null && (s.loadedAmpPerVolt == null || !floatsEqual(ampPerVolt, s.loadedAmpPerVolt)))
                writes += Write("BATT_AMP_PERVLT", ampPerVolt, false)
            if (voltPin != null && voltPin != s.loadedVoltPin)
                writes += Write("BATT_VOLT_PIN", voltPin.toFloat(), true)
            if (currPin != null && currPin != s.loadedCurrPin)
                writes += Write("BATT_CURR_PIN", currPin.toFloat(), true)

            if (writes.isEmpty()) {
                _state.update { it.copy(isSaving = false, successMessage = "No changes to save") }
                return@launch
            }

            val confirmed = mutableMapOf<String, Float>()
            val failed = mutableListOf<String>()

            writes.forEach { w ->
                val ack = sharedViewModel.setParameter(w.name, w.value, WRITE_TIMEOUT_MS)
                if (ack != null) {
                    confirmed[w.name] = ack.paramValue
                    LogUtils.d(TAG, "✅ ${w.name} = ${ack.paramValue}")
                } else {
                    failed += w.name
                    LogUtils.e(TAG, "❌ Failed to write ${w.name}")
                }
                delay(INTER_PARAM_DELAY_MS)
            }

            val allSucceeded = failed.isEmpty()
            _state.update { cur ->
                cur.copy(
                    isSaving = false,
                    loadedMonitor     = confirmed["BATT_MONITOR"]?.toInt()      ?: cur.loadedMonitor,
                    loadedCapacityMah = confirmed["BATT_CAPACITY"]?.toInt()     ?: cur.loadedCapacityMah,
                    loadedVoltMult    = confirmed["BATT_VOLT_MULT"]              ?: cur.loadedVoltMult,
                    loadedAmpPerVolt  = confirmed["BATT_AMP_PERVLT"]             ?: cur.loadedAmpPerVolt,
                    loadedVoltPin     = confirmed["BATT_VOLT_PIN"]?.toInt()      ?: cur.loadedVoltPin,
                    loadedCurrPin     = confirmed["BATT_CURR_PIN"]?.toInt()      ?: cur.loadedCurrPin,
                    measuredVoltageInput = if (allSucceeded) "" else cur.measuredVoltageInput,
                    loggedMahInput  = if (allSucceeded) "" else cur.loggedMahInput,
                    chargedMahInput = if (allSucceeded) "" else cur.chargedMahInput,
                    successMessage = if (allSucceeded)
                        "Saved ${writes.size} parameter(s) to drone"
                    else
                        "Saved ${confirmed.size}/${writes.size}; failed: ${failed.joinToString()}",
                    errorMessage = if (!allSucceeded) "Some writes failed — see message" else null
                )
            }

            // Re-read params so loadedVoltMult reflects the freshly written value
            if (allSucceeded) {
                delay(200L)
                loadFromDrone()
            }
        }
    }

    // ── Update measurement inputs (calibration) ──────────────────────
    fun updateMeasuredVoltageInput(text: String) {
        _state.update { it.copy(measuredVoltageInput = sanitizeDecimalString(text)) }
    }

    fun updateLoggedMahInput(text: String) {
        _state.update { it.copy(loggedMahInput = sanitizeDecimalString(text)) }
    }

    fun updateChargedMahInput(text: String) {
        _state.update { it.copy(chargedMahInput = sanitizeDecimalString(text)) }
    }

    fun clearMessages() {
        _state.update { it.copy(errorMessage = null, successMessage = null) }
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private fun floatsEqual(a: Float, b: Float, eps: Float = 0.0001f): Boolean = kotlin.math.abs(a - b) < eps
}
