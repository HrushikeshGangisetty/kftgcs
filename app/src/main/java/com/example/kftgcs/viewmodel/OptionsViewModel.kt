package com.example.kftgcs.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.BatteryFsAction
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FailsafeOptions(
    val missionCompletionAction: String = "HOVER",
    // Tank empty action is configured separately for Manual flight and Auto missions.
    val tankEmptyActionManual: String = "HOVER",
    val tankEmptyActionAuto: String = "HOVER",
    val lowVoltLevel1: Float = SharedViewModel.DEFAULT_LOW_VOLT_1,
    val lowVoltLevel2: Float = SharedViewModel.DEFAULT_LOW_VOLT_2,
    // Both battery failsafe actions mirror the FC's BATT_FS_LOW_ACT / BATT_FS_CRT_ACT — the FC is
    // the source of truth. Values are BatteryFsAction names ("HOVER" = None on the FC).
    val lowVoltLevel1Action: String = BatteryFsAction.HOVER,
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
        private const val KEY_LOW_VOLT_LEVEL_1_ACTION = "low_volt_level_1_action"
        private const val KEY_LOW_VOLT_LEVEL_2_ACTION = "low_volt_level_2_action"
        private const val KEY_MAX_ALTITUDE_ENABLED = "max_altitude_enabled"
        private const val KEY_MAX_ALTITUDE = "max_altitude"
        private const val KEY_MAX_ALTITUDE_ACTION = "max_altitude_action"

        private const val DEFAULT_MAX_ALTITUDE = 120.0f

        // Keys for fieldsEditedSinceLoad
        private const val FIELD_LOW_VOLT_1 = "low_volt_1"
        private const val FIELD_LOW_VOLT_2 = "low_volt_2"
        private const val FIELD_LOW_ACTION_1 = "low_action_1"
        private const val FIELD_LOW_ACTION_2 = "low_action_2"
        private const val FIELD_MAX_ALTITUDE = "max_altitude"

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
    }

    private val _options = MutableStateFlow(FailsafeOptions())
    val options: StateFlow<FailsafeOptions> = _options.asStateFlow()

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    private val _isLoadingFromDrone = MutableStateFlow(false)
    val isLoadingFromDrone: StateFlow<Boolean> = _isLoadingFromDrone.asStateFlow()

    private val _loadStatus = MutableStateFlow<String?>(null)
    val loadStatus: StateFlow<String?> = _loadStatus.asStateFlow()

    /**
     * Serialises "read from drone" against "save & sync to drone".
     *
     * They used to run freely in parallel: the screen auto-loads on open, so a pilot who edited a
     * field and pressed Update while that ~10 s load was still going had the FC's old value read
     * back over their edit — or over the value they had just saved.
     */
    private val droneSyncMutex = Mutex()

    /** True while a save is queued or running, so a double tap cannot start a second one. */
    private var saveInProgress = false

    /**
     * Fields the pilot has typed into since the current load began. A load that lands later must
     * not overwrite them. Only touched on the main thread.
     */
    private val fieldsEditedSinceLoad = mutableSetOf<String>()

    init {
        loadSettings()
    }

    /**
     * Read BATT_LOW_VOLT and BATT_CRT_VOLT from the flight controller
     * and update the UI fields with the values currently on the drone.
     */
    fun loadFromDrone(sharedViewModel: SharedViewModel) {
        if (_isLoadingFromDrone.value) return
        _isLoadingFromDrone.value = true
        _loadStatus.value = "Reading failsafe parameters from drone..."
        // Anything typed from here on is the pilot's and must survive the load.
        fieldsEditedSinceLoad.clear()

        viewModelScope.launch {
            try {
                droneSyncMutex.withLock {
                    val failures = mutableListOf<String>()
                    val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    // Read BATT_LOW_VOLT → lowVoltLevel1
                    val volt1 = sharedViewModel.readParameter(PARAM_BATT_LOW_VOLT)
                    // 0 is ArduPilot's "disabled" default, not a threshold. Adopting it put 0.0 in
                    // the field, and pressing Update then saved 0 V to the FC and the prefs —
                    // after which `voltage <= 0` never fires and the failsafe is silently off.
                    // Same rule the connect-time sync applies.
                    if (volt1 != null && volt1 > 0f) {
                        if (FIELD_LOW_VOLT_1 !in fieldsEditedSinceLoad) {
                            _options.value = _options.value.copy(lowVoltLevel1 = volt1)
                            // The GCS failsafe enforces what is in prefs, so what the FC says
                            // has to land there too or screen and enforcement drift apart.
                            prefs.edit().putFloat(KEY_LOW_VOLT_LEVEL_1, volt1).apply()
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_BATT_LOW_VOLT = $volt1 from drone")
                    } else {
                        failures.add(PARAM_BATT_LOW_VOLT)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_LOW_VOLT from drone")
                    }

                    kotlinx.coroutines.delay(100) // small delay between requests

                    // Read BATT_CRT_VOLT → lowVoltLevel2
                    val volt2 = sharedViewModel.readParameter(PARAM_BATT_CRT_VOLT)
                    if (volt2 != null && volt2 > 0f) {
                        if (FIELD_LOW_VOLT_2 !in fieldsEditedSinceLoad) {
                            _options.value = _options.value.copy(lowVoltLevel2 = volt2)
                            prefs.edit().putFloat(KEY_LOW_VOLT_LEVEL_2, volt2).apply()
                        }
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
                        if (FIELD_MAX_ALTITUDE !in fieldsEditedSinceLoad) {
                            _options.value = _options.value.copy(maxAltitude = ceiling)
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_FENCE_ALT_MAX = ${altMax} m → ceiling ${ceiling} m")
                    } else {
                        failures.add(PARAM_FENCE_ALT_MAX)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_FENCE_ALT_MAX from drone")
                    }

                    kotlinx.coroutines.delay(100)

                    // Read BATT_FS_LOW_ACT / BATT_FS_CRT_ACT → the level 1 / level 2 actions.
                    // The FC is the source of truth: these used to be neither read nor shown, so
                    // the screen always said "Hover" whatever the drone was actually set to.
                    val lowAct = sharedViewModel.readParameter(PARAM_BATT_FS_LOW_ACT)
                    val lowActionName = lowAct?.let { BatteryFsAction.fromFcValue(it) }
                    if (lowActionName != null) {
                        if (FIELD_LOW_ACTION_1 !in fieldsEditedSinceLoad) {
                            _options.value = _options.value.copy(lowVoltLevel1Action = lowActionName)
                            prefs.edit().putString(KEY_LOW_VOLT_LEVEL_1_ACTION, lowActionName).apply()
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_BATT_FS_LOW_ACT = $lowAct → $lowActionName from drone")
                    } else {
                        failures.add(PARAM_BATT_FS_LOW_ACT)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_FS_LOW_ACT from drone (got $lowAct)")
                    }

                    kotlinx.coroutines.delay(100)

                    val crtAct = sharedViewModel.readParameter(PARAM_BATT_FS_CRT_ACT)
                    val crtActionName = crtAct?.let { BatteryFsAction.fromFcValue(it) }
                    if (crtActionName != null) {
                        if (FIELD_LOW_ACTION_2 !in fieldsEditedSinceLoad) {
                            _options.value = _options.value.copy(lowVoltLevel2Action = crtActionName)
                            prefs.edit().putString(KEY_LOW_VOLT_LEVEL_2_ACTION, crtActionName).apply()
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_BATT_FS_CRT_ACT = $crtAct → $crtActionName from drone")
                    } else {
                        failures.add(PARAM_BATT_FS_CRT_ACT)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_FS_CRT_ACT from drone (got $crtAct)")
                    }

                    _loadStatus.value = if (failures.isEmpty()) {
                        "Loaded failsafe values from drone ✓"
                    } else {
                        "Could not read: ${failures.joinToString()}. Using saved values."
                    }
                }
            } finally {
                _isLoadingFromDrone.value = false
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
            lowVoltLevel1Action = prefs.getString(KEY_LOW_VOLT_LEVEL_1_ACTION, BatteryFsAction.HOVER) ?: BatteryFsAction.HOVER,
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
        fieldsEditedSinceLoad.add(FIELD_LOW_VOLT_1)
        _options.value = _options.value.copy(lowVoltLevel1 = value)
    }

    fun updateLowVoltLevel2(value: Float) {
        fieldsEditedSinceLoad.add(FIELD_LOW_VOLT_2)
        _options.value = _options.value.copy(lowVoltLevel2 = value)
    }

    fun updateLowVoltLevel1Action(action: String) {
        fieldsEditedSinceLoad.add(FIELD_LOW_ACTION_1)
        _options.value = _options.value.copy(lowVoltLevel1Action = action)
    }

    fun updateLowVoltLevel2Action(action: String) {
        fieldsEditedSinceLoad.add(FIELD_LOW_ACTION_2)
        _options.value = _options.value.copy(lowVoltLevel2Action = action)
    }

    fun updateMaxAltitudeEnabled(enabled: Boolean) {
        _options.value = _options.value.copy(maxAltitudeEnabled = enabled)
    }

    fun updateMaxAltitude(value: Float) {
        fieldsEditedSinceLoad.add(FIELD_MAX_ALTITUDE)
        _options.value = _options.value.copy(maxAltitude = value)
    }

    fun updateMaxAltitudeAction(action: String) {
        _options.value = _options.value.copy(maxAltitudeAction = action)
    }

    /**
     * Save settings locally and sync to drone via MAVLink PARAM_SET.
     */
    fun saveAndSync(sharedViewModel: SharedViewModel) {
        // A double tap must not start a second save while the first is queued or running.
        if (saveInProgress) return
        saveInProgress = true
        _syncStatus.value = "Syncing to drone..."

        viewModelScope.launch {
            try {
                // Waits for any in-flight load, then snapshots the options AFTER it has landed so
                // the save cannot be based on values the load is about to replace.
                droneSyncMutex.withLock {
                    syncToDrone(sharedViewModel)
                }
            } finally {
                saveInProgress = false
            }
        }
    }

    /** Body of [saveAndSync]. Callers must hold [droneSyncMutex]. */
    private suspend fun syncToDrone(sharedViewModel: SharedViewModel) {
        val current = _options.value

        // Refuse thresholds the failsafe cannot work with, before anything is persisted or sent:
        //  - <= 0 V never triggers (voltage <= 0 is never true), i.e. the failsafe is off;
        //  - critical at or above warning means the warning band is empty, so Level 1 never fires.
        // A 12S default (43/42 V) left on a 6S drone is caught by the pilot at the pre-arm popup.
        val thresholdError = when {
            current.lowVoltLevel1 <= 0f || current.lowVoltLevel2 <= 0f ->
                "Not saved: voltage thresholds must be above 0 V"
            current.lowVoltLevel2 >= current.lowVoltLevel1 ->
                "Not saved: Level 2 (critical) must be lower than Level 1 (warning)"
            else -> null
        }
        if (thresholdError != null) {
            _syncStatus.value = thresholdError
            return
        }

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
                .putString(KEY_LOW_VOLT_LEVEL_1_ACTION, current.lowVoltLevel1Action)
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

        // What is now saved is what is on screen; nothing the pilot typed is still pending.
        fieldsEditedSinceLoad.clear()

        // Sync to drone
        run {
            val results = mutableListOf<String>()

            // A write counts only when the FC's reply carries the value we wrote — an ack that
            // merely names the parameter can be a stale reply, or the FC clamping/rejecting it.
            suspend fun write(name: String, value: Float): Boolean {
                val ack = sharedViewModel.setParameter(name, value, timeoutMs = 5000L)
                return if (sharedViewModel.paramAckMatches(ack, value)) {
                    true
                } else {
                    results.add(name)
                    LogUtils.e(TAG, "✗ Failed to set $name (FC reports ${ack?.paramValue})")
                    false
                }
            }

            // BATT_LOW_VOLT ← lowVoltLevel1
            if (write(PARAM_BATT_LOW_VOLT, current.lowVoltLevel1)) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_LOW_VOLT = ${current.lowVoltLevel1}")
            }

            // BATT_CRT_VOLT ← lowVoltLevel2
            if (write(PARAM_BATT_CRT_VOLT, current.lowVoltLevel2)) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_CRT_VOLT = ${current.lowVoltLevel2}")
            }

            // BATT_FS_LOW_ACT ← the level 1 action the pilot selected (or the value the FC
            // already had, which Options loaded into the field). These used to be forced to 0
            // here, overriding whatever the drone was configured with.
            val lowActValue = BatteryFsAction.toFcValue(current.lowVoltLevel1Action)
            if (lowActValue == null) {
                results.add(PARAM_BATT_FS_LOW_ACT)
                LogUtils.e(TAG, "✗ Unknown level 1 action '${current.lowVoltLevel1Action}'")
            } else if (write(PARAM_BATT_FS_LOW_ACT, lowActValue)) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_FS_LOW_ACT = ${lowActValue.toInt()} (${current.lowVoltLevel1Action})")
            }

            // BATT_FS_CRT_ACT ← the level 2 action the pilot selected. The GCS enforces the same
            // action (see SharedViewModel.handleBatteryVoltageFailsafe), so the FC and the GCS
            // ask for the same mode rather than racing each other.
            val crtActValue = BatteryFsAction.toFcValue(current.lowVoltLevel2Action)
            if (crtActValue == null) {
                results.add(PARAM_BATT_FS_CRT_ACT)
                LogUtils.e(TAG, "✗ Unknown level 2 action '${current.lowVoltLevel2Action}'")
            } else if (write(PARAM_BATT_FS_CRT_ACT, crtActValue)) {
                LogUtils.i(TAG, "✓ $PARAM_BATT_FS_CRT_ACT = ${crtActValue.toInt()} (${current.lowVoltLevel2Action})")
            }

            // ═══ Altitude ceiling → FENCE_ALT_MAX + RTL_ALT + FENCE_TYPE/FENCE_ENABLE ═══
            //
            // Delegated to SharedViewModel.applyAltitudeCeilingToFc() rather than written
            // here, because the ceiling is THREE parameters that have to move together and
            // this path used to move only one of them:
            //
            //   FENCE_ALT_MAX  — the limit itself, biased below the pilot's ceiling because
            //                    ArduPilot arrests the climb AFTER detecting the breach.
            //   RTL_ALT        — must stay under the ceiling, or the breach action (an RTL)
            //                    starts by climbing to RTL_ALT and straight through it. This
            //                    was previously synced on connect only, so changing the
            //                    ceiling in Options left RTL_ALT agreeing with the old one.
            //   FENCE_TYPE /
            //   FENCE_ENABLE   — without these the FC ignores FENCE_ALT_MAX entirely and the
            //                    ceiling is held by the GCS over the telemetry link alone.
            //
            // FENCE_ACTION and FENCE_MARGIN are still never written: the breach behaviour
            // stays the operator's.
            if (current.maxAltitudeEnabled && current.maxAltitude > 0f) {
                // Reported in the status line too: this used to be fire-and-forget, so a ceiling
                // that never reached the FC still ended in "Saved & synced to drone".
                if (sharedViewModel.applyAltitudeCeilingToFc()) {
                    LogUtils.i(TAG, "Altitude ceiling ${current.maxAltitude} m pushed to the FC (see OptionsSync/FenceSync logs)")
                } else {
                    results.add(PARAM_FENCE_ALT_MAX)
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
