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
    val tankEmptyAction: String = "HOVER",
    val lowVoltLevel1: Float = 22.2f,
    val lowVoltLevel2: Float = 21.0f,
    val lowVoltLevel2Action: String = "HOVER"
)

class OptionsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptionsVM"
        private const val PREFS_NAME = "failsafe_options"
        private const val KEY_MISSION_COMPLETION_ACTION = "mission_completion_action"
        private const val KEY_TANK_EMPTY_ACTION = "tank_empty_action"
        private const val KEY_LOW_VOLT_LEVEL_1 = "low_volt_level_1"
        private const val KEY_LOW_VOLT_LEVEL_2 = "low_volt_level_2"
        private const val KEY_LOW_VOLT_LEVEL_2_ACTION = "low_volt_level_2_action"

        // ArduPilot parameter names
        private const val PARAM_BATT_LOW_VOLT = "BATT_LOW_VOLT"
        private const val PARAM_BATT_CRT_VOLT = "BATT_CRT_VOLT"
        private const val PARAM_BATT_FS_LOW_ACT = "BATT_FS_LOW_ACT"
        private const val PARAM_BATT_FS_CRT_ACT = "BATT_FS_CRT_ACT"

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
            _loadStatus.value = "Reading voltage parameters from drone..."
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

            _isLoadingFromDrone.value = false
            _loadStatus.value = if (failures.isEmpty()) {
                "Loaded voltage values from drone ✓"
            } else {
                "Could not read: ${failures.joinToString()}. Using saved values."
            }
        }
    }

    private fun loadSettings() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _options.value = FailsafeOptions(
            missionCompletionAction = prefs.getString(KEY_MISSION_COMPLETION_ACTION, "HOVER") ?: "HOVER",
            tankEmptyAction = prefs.getString(KEY_TANK_EMPTY_ACTION, "HOVER") ?: "HOVER",
            lowVoltLevel1 = prefs.getFloat(KEY_LOW_VOLT_LEVEL_1, 22.2f),
            lowVoltLevel2 = prefs.getFloat(KEY_LOW_VOLT_LEVEL_2, 21.0f),
            lowVoltLevel2Action = prefs.getString(KEY_LOW_VOLT_LEVEL_2_ACTION, "HOVER") ?: "HOVER"
        )
    }

    fun updateMissionCompletionAction(action: String) {
        _options.value = _options.value.copy(missionCompletionAction = action)
    }

    fun updateTankEmptyAction(action: String) {
        _options.value = _options.value.copy(tankEmptyAction = action)
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
                .putString(KEY_TANK_EMPTY_ACTION, current.tankEmptyAction)
                .putFloat(KEY_LOW_VOLT_LEVEL_1, current.lowVoltLevel1)
                .putFloat(KEY_LOW_VOLT_LEVEL_2, current.lowVoltLevel2)
                .putString(KEY_LOW_VOLT_LEVEL_2_ACTION, current.lowVoltLevel2Action)
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

            if (results.isEmpty()) {
                _syncStatus.value = "Saved & synced to drone"
            } else {
                _syncStatus.value = "Saved locally. Failed to sync: ${results.joinToString()}"
            }
        }
    }
}
