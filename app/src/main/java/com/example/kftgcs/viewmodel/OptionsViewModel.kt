package com.example.kftgcs.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FailsafeOptions(
    val missionCompletionAction: String = "HOVER",
    // Tank empty action is configured separately for Manual flight and Auto missions.
    val tankEmptyActionManual: String = "HOVER",
    val tankEmptyActionAuto: String = "HOVER",
    val lowVoltLevel1: Float = SharedViewModel.DEFAULT_LOW_VOLT_1,
    val lowVoltLevel2: Float = SharedViewModel.DEFAULT_LOW_VOLT_2,
    val lowVoltLevel2Action: String = "HOVER",
    // Altitude ceiling failsafe — mirrors the FC's FENCE_ALT_MAX parameter.
    val maxAltitudeEnabled: Boolean = true,
    val maxAltitude: Float = 120.0f,
    val maxAltitudeAction: String = "HOVER"
)

class OptionsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptionsVM"
        private const val PREFS_NAME = "failsafe_options"
        private const val KEY_MISSION_COMPLETION_ACTION = "mission_completion_action"
        // Legacy single key, kept only to migrate existing users into the two new keys below.
        private const val KEY_TANK_EMPTY_ACTION = "tank_empty_action"
        private const val KEY_TANK_EMPTY_ACTION_MANUAL = "tank_empty_action_manual"
        private const val KEY_TANK_EMPTY_ACTION_AUTO = "tank_empty_action_auto"
        private const val KEY_LOW_VOLT_LEVEL_1 = "low_volt_level_1"
        private const val KEY_LOW_VOLT_LEVEL_2 = "low_volt_level_2"
        private const val KEY_LOW_VOLT_LEVEL_2_ACTION = "low_volt_level_2_action"
        private const val KEY_MAX_ALTITUDE_ENABLED = "max_altitude_enabled"
        private const val KEY_MAX_ALTITUDE = "max_altitude"
        private const val KEY_MAX_ALTITUDE_ACTION = "max_altitude_action"

        private const val DEFAULT_MAX_ALTITUDE = 120.0f

        // ArduPilot parameter names
        private const val PARAM_BATT_LOW_VOLT = "BATT_LOW_VOLT"
        private const val PARAM_BATT_CRT_VOLT = "BATT_CRT_VOLT"
        private const val PARAM_BATT_FS_LOW_ACT = "BATT_FS_LOW_ACT"
        private const val PARAM_BATT_FS_CRT_ACT = "BATT_FS_CRT_ACT"
        private const val PARAM_FENCE_ALT_MAX = "FENCE_ALT_MAX"

        /**
         * Read the mission completion action from SharedPreferences.
         * Can be called from anywhere without a ViewModel instance.
         */
        fun getMissionCompletionAction(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_MISSION_COMPLETION_ACTION, "HOVER") ?: "HOVER"
        }

        /**
         * Map action string to ArduPilot BATT_FS_CRT_ACT parameter value.
         * 0=None, 1=Land, 2=RTL, 5=SmartRTL or Land, 6=SmartRTL or RTL
         */
        private fun actionToBattFsValue(action: String): Float = when (action) {
            "LAND" -> 1.0f
            "RTL" -> 2.0f
            "HOVER", "LOITER" -> 0.0f  // No failsafe action on FC (GCS handles BRAKE mode)
            else -> 2.0f
        }
    }

    private val _options = MutableStateFlow(FailsafeOptions())
    val options: StateFlow<FailsafeOptions> = _options.asStateFlow()

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    private val _isLoadingFromDrone = MutableStateFlow(false)
    val isLoadingFromDrone: StateFlow<Boolean> = _isLoadingFromDrone.asStateFlow()

    private val _loadStatus = MutableStateFlow<String?>(null)
    val loadStatus: StateFlow<String?> = _loadStatus.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Read BATT_LOW_VOLT and BATT_CRT_VOLT from the flight controller
     * and update the UI fields with the values currently on the drone.
     */
    fun loadFromDrone(sharedViewModel: SharedViewModel) {
        viewModelScope.launch {
            _isLoadingFromDrone.value = true
            _loadStatus.value = "Reading failsafe parameters from drone..."
            val failures = mutableListOf<String>()

            // Read BATT_LOW_VOLT → lowVoltLevel1
            val volt1 = sharedViewModel.readParameter(PARAM_BATT_LOW_VOLT)
            if (volt1 != null) {
                _options.value = _options.value.copy(lowVoltLevel1 = volt1)
                LogUtils.i(TAG, "✓ Read $PARAM_BATT_LOW_VOLT = $volt1 from drone")
            } else {
                failures.add(PARAM_BATT_LOW_VOLT)
                LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_LOW_VOLT from drone")
            }

            kotlinx.coroutines.delay(100) // small delay between requests

            // Read BATT_CRT_VOLT → lowVoltLevel2
            val volt2 = sharedViewModel.readParameter(PARAM_BATT_CRT_VOLT)
            if (volt2 != null) {
                _options.value = _options.value.copy(lowVoltLevel2 = volt2)
                LogUtils.i(TAG, "✓ Read $PARAM_BATT_CRT_VOLT = $volt2 from drone")
            } else {
                failures.add(PARAM_BATT_CRT_VOLT)
                LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_CRT_VOLT from drone")
            }

            kotlinx.coroutines.delay(100)

            // Read FENCE_ALT_MAX → maxAltitude (the altitude ceiling failsafe)
            val altMax = sharedViewModel.readParameter(PARAM_FENCE_ALT_MAX)
            if (altMax != null && altMax > 0f) {
                // Add the safety offset back so the pilot sees the ceiling they set, not the
                // biased value stored on the FC.
                val ceiling = altMax + sharedViewModel.FC_ALT_FENCE_SAFETY_OFFSET_M
                _options.value = _options.value.copy(maxAltitude = ceiling)
                LogUtils.i(TAG, "✓ Read $PARAM_FENCE_ALT_MAX = ${altMax} m → ceiling ${ceiling} m")
            } else {
                failures.add(PARAM_FENCE_ALT_MAX)
                LogUtils.e(TAG, "✗ Failed to read $PARAM_FENCE_ALT_MAX from drone")
            }

            _isLoadingFromDrone.value = false
            _loadStatus.value = if (failures.isEmpty()) {
                "Loaded failsafe values from drone ✓"
            } else {
                "Could not read: ${failures.joinToString()}. Using saved values."
            }
        }
    }

    private fun loadSettings() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Migration: if the new per-mode keys aren't set yet, fall back to the legacy single value.
        val legacyTankEmpty = prefs.getString(KEY_TANK_EMPTY_ACTION, "HOVER") ?: "HOVER"
        _options.value = FailsafeOptions(
            missionCompletionAction = prefs.getString(KEY_MISSION_COMPLETION_ACTION, "HOVER") ?: "HOVER",
            tankEmptyActionManual = prefs.getString(KEY_TANK_EMPTY_ACTION_MANUAL, legacyTankEmpty) ?: legacyTankEmpty,
            tankEmptyActionAuto = prefs.getString(KEY_TANK_EMPTY_ACTION_AUTO, legacyTankEmpty) ?: legacyTankEmpty,
            lowVoltLevel1 = prefs.getFloat(KEY_LOW_VOLT_LEVEL_1, SharedViewModel.DEFAULT_LOW_VOLT_1),
            lowVoltLevel2 = prefs.getFloat(KEY_LOW_VOLT_LEVEL_2, SharedViewModel.DEFAULT_LOW_VOLT_2),
            lowVoltLevel2Action = prefs.getString(KEY_LOW_VOLT_LEVEL_2_ACTION, "HOVER") ?: "HOVER",
            maxAltitudeEnabled = prefs.getBoolean(KEY_MAX_ALTITUDE_ENABLED, true),
            maxAltitude = prefs.getFloat(KEY_MAX_ALTITUDE, DEFAULT_MAX_ALTITUDE),
            maxAltitudeAction = prefs.getString(KEY_MAX_ALTITUDE_ACTION, "HOVER") ?: "HOVER"
        )
    }

    fun updateMissionCompletionAction(action: String) {
        _options.value = _options.value.copy(missionCompletionAction = action)
    }

    fun updateTankEmptyActionManual(action: String) {
        _options.value = _options.value.copy(tankEmptyActionManual = action)
    }

    fun updateTankEmptyActionAuto(action: String) {
        _options.value = _options.value.copy(tankEmptyActionAuto = action)
    }

    fun updateLowVoltLevel1(value: Float) {
        _options.value = _options.value.copy(lowVoltLevel1 = value)
    }

    fun updateLowVoltLevel2(value: Float) {
        _options.value = _options.value.copy(lowVoltLevel2 = value)
    }

    fun updateLowVoltLevel2Action(action: String) {
        _options.value = _options.value.copy(lowVoltLevel2Action = action)
    }

    fun updateMaxAltitudeEnabled(enabled: Boolean) {
        _options.value = _options.value.copy(maxAltitudeEnabled = enabled)
    }

    fun updateMaxAltitude(value: Float) {
        _options.value = _options.value.copy(maxAltitude = value)
    }

    fun updateMaxAltitudeAction(action: String) {
        _options.value = _options.value.copy(maxAltitudeAction = action)
    }

    /**
     * Save settings locally and sync to drone via MAVLink PARAM_SET.
     */
    fun saveAndSync(sharedViewModel: SharedViewModel) {
        val current = _options.value

        // Save locally first
        val saved = try {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MISSION_COMPLETION_ACTION, current.missionCompletionAction)
                .putString(KEY_TANK_EMPTY_ACTION_MANUAL, current.tankEmptyActionManual)
                .putString(KEY_TANK_EMPTY_ACTION_AUTO, current.tankEmptyActionAuto)
                .putFloat(KEY_LOW_VOLT_LEVEL_1, current.lowVoltLevel1)
                .putFloat(KEY_LOW_VOLT_LEVEL_2, current.lowVoltLevel2)
                .putString(KEY_LOW_VOLT_LEVEL_2_ACTION, current.lowVoltLevel2Action)
                .putBoolean(KEY_MAX_ALTITUDE_ENABLED, current.maxAltitudeEnabled)
                .putFloat(KEY_MAX_ALTITUDE, current.maxAltitude)
                .putString(KEY_MAX_ALTITUDE_ACTION, current.maxAltitudeAction)
                .commit()
        } catch (e: Exception) {
            false
        }

        if (!saved) {
            _syncStatus.value = "Failed to save locally"
            return
        }

        // Sync to drone
        viewModelScope.launch {
            _syncStatus.value = "Syncing to drone..."
            val results = mutableListOf<String>()

            // BATT_LOW_VOLT ← lowVoltLevel1
            val r1 = sharedViewModel.setParameter(PARAM_BATT_LOW_VOLT, current.lowVoltLevel1)
            if (r1 != null) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_LOW_VOLT = ${current.lowVoltLevel1}")
            } else {
                results.add(PARAM_BATT_LOW_VOLT)
                LogUtils.e(TAG, "✗ Failed to set $PARAM_BATT_LOW_VOLT")
            }

            // BATT_CRT_VOLT ← lowVoltLevel2
            val r2 = sharedViewModel.setParameter(PARAM_BATT_CRT_VOLT, current.lowVoltLevel2)
            if (r2 != null) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_CRT_VOLT = ${current.lowVoltLevel2}")
            } else {
                results.add(PARAM_BATT_CRT_VOLT)
                LogUtils.e(TAG, "✗ Failed to set $PARAM_BATT_CRT_VOLT")
            }

            // BATT_FS_LOW_ACT ← Level 1 action is alert only, so set to 0 (None)
            val r3 = sharedViewModel.setParameter(PARAM_BATT_FS_LOW_ACT, 0.0f)
            if (r3 != null) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_FS_LOW_ACT = 0 (alert only)")
            } else {
                results.add(PARAM_BATT_FS_LOW_ACT)
                LogUtils.e(TAG, "✗ Failed to set $PARAM_BATT_FS_LOW_ACT")
            }

            // BATT_FS_CRT_ACT ← Always set to 0 (None) on FC
            // GCS handles the critical voltage action (BRAKE/RTL/LAND) to avoid
            // dual failsafe conflict where both FC and GCS race to change flight mode
            val r4 = sharedViewModel.setParameter(PARAM_BATT_FS_CRT_ACT, 0.0f)
            if (r4 != null) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_FS_CRT_ACT = 0 (GCS handles action: ${current.lowVoltLevel2Action})")
            } else {
                results.add(PARAM_BATT_FS_CRT_ACT)
                LogUtils.e(TAG, "✗ Failed to set $PARAM_BATT_FS_CRT_ACT")
            }

            // FENCE_ALT_MAX ← maxAltitude (the altitude ceiling failsafe)
            //
            // FENCE_ENABLE is deliberately NOT touched here: it belongs to the geofence
            // upload flow, and switching the fence on for a drone flying without a geofence
            // would change its pre-arm and breach behaviour. FENCE_TYPE is likewise not
            // touched here — SharedViewModel.syncFenceParametersOnConnect owns it and only
            // ORs bits in. The GCS enforces the ceiling itself
            // (SharedViewModel.handleAltitudeFailsafe); this write just keeps the FC's own
            // limit correct as a second layer.
            if (current.maxAltitudeEnabled && current.maxAltitude > 0f) {
                // Written slightly BELOW the pilot's ceiling: ArduPilot arrests the climb
                // after detecting the breach and coasts past FENCE_ALT_MAX, so a verbatim
                // write exceeds the stated limit. See SharedViewModel.getFcAltitudeFenceMax().
                val fcLimit = (current.maxAltitude - sharedViewModel.FC_ALT_FENCE_SAFETY_OFFSET_M)
                    .coerceAtLeast(1f)
                val r5 = sharedViewModel.setParameter(PARAM_FENCE_ALT_MAX, fcLimit)
                if (r5 != null) {
                    LogUtils.i(TAG, "✓ $PARAM_FENCE_ALT_MAX = $fcLimit m (ceiling ${current.maxAltitude} m)")
                } else {
                    results.add(PARAM_FENCE_ALT_MAX)
                    LogUtils.e(TAG, "✗ Failed to set $PARAM_FENCE_ALT_MAX")
                }
            }

            if (results.isEmpty()) {
                _syncStatus.value = "Saved & synced to drone"
            } else {
                _syncStatus.value = "Saved locally. Failed to sync: ${results.joinToString()}"
            }
        }
    }
}
