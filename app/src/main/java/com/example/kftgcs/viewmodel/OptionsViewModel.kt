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
    val maxAltitude: Float = 120.0f
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

        private const val DEFAULT_MAX_ALTITUDE = 120.0f

        // Keys for editedFields
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

        /** Per-attempt timeout for a parameter read, matching the connect-time sync's 4 s. */
        private const val PARAM_READ_TIMEOUT_MS = 4000L

        /** Gap between a failed parameter read and its retry. */
        private const val PARAM_RETRY_GAP_MS = 300L

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
    private var loadedSessionId: Long? = null

    /**
     * Pending explicit parameter edits. Refresh must preserve them, and Save must never
     * send an untouched field. Only touched on the main thread.
     */
    private val editedFields = mutableSetOf<String>()

    init {
        loadSettings()
    }

    /**
     * Read one parameter, retrying once before giving up.
     *
     * [SharedViewModel.readParameter] returns null both for "the FC did not answer in time" and
     * for "not connected", and a single dropped PARAM_VALUE on a busy link was enough to leave a
     * field showing its cached value with no indication anything had failed. The connect-time
     * sync already found the 3 s default too short for the BATT_FS_*_ACT reads and uses 4 s;
     * match that here, and give each parameter a second chance the way the fence parameter
     * helper in the repository already does.
     */
    private suspend fun readParamWithRetry(
        sharedViewModel: SharedViewModel,
        paramId: String,
        attempts: Int = 2
    ): Float? {
        repeat(attempts) { attempt ->
            if (loadedSessionId != sharedViewModel.failsafeSessionId) {
                _loadStatus.value = "Connection changed; refresh the settings."
                throw kotlinx.coroutines.CancellationException("Failsafe connection changed")
            }
            val value = sharedViewModel.readParameter(paramId, timeoutMs = PARAM_READ_TIMEOUT_MS)
            if (loadedSessionId != sharedViewModel.failsafeSessionId) {
                _loadStatus.value = "Connection changed; refresh the settings."
                throw kotlinx.coroutines.CancellationException("Failsafe connection changed")
            }
            if (value != null && value.isFinite()) return value
            LogUtils.w(TAG, "↻ Retrying read of $paramId (attempt ${attempt + 1}/$attempts)")
            kotlinx.coroutines.delay(PARAM_RETRY_GAP_MS)
        }
        return null
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

        viewModelScope.launch {
            try {
                // The screen auto-loads the instant it opens, which on a fresh connect is while
                // the connect-time sync is still running its ~15 parameter round trips. Every one
                // of those holds the same parameter mutex, and the fence upload pushes its own
                // traffic down a separate channel at the same time — so our reads both queued
                // behind them and lost replies to the congestion. That is the "battery values
                // don't load the first time, you have to hit refresh two or three times" report.
                // Wait for the sync to finish before asking for anything.
                _loadStatus.value = "Waiting for the connection sync to finish…"
                sharedViewModel.awaitConnectSync()
                _loadStatus.value = "Reading failsafe parameters from drone..."

                droneSyncMutex.withLock {
                    if (loadedSessionId != sharedViewModel.failsafeSessionId) editedFields.clear()
                    loadedSessionId = sharedViewModel.failsafeSessionId
                    val failures = mutableListOf<String>()
                    val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    // Read BATT_LOW_VOLT → lowVoltLevel1
                    val volt1 = readParamWithRetry(sharedViewModel, PARAM_BATT_LOW_VOLT)
                    // 0 is ArduPilot's "disabled" default, not a threshold. Adopting it put 0.0 in
                    // the field, and pressing Update then saved 0 V to the FC and the prefs —
                    // after which `voltage <= 0` never fires and the failsafe is silently off.
                    // Same rule the connect-time sync applies.
                    if (volt1 != null && volt1 > 0f) {
                        if (FIELD_LOW_VOLT_1 !in editedFields) {
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
                    val volt2 = readParamWithRetry(sharedViewModel, PARAM_BATT_CRT_VOLT)
                    if (volt2 != null && volt2 > 0f) {
                        if (FIELD_LOW_VOLT_2 !in editedFields) {
                            _options.value = _options.value.copy(lowVoltLevel2 = volt2)
                            prefs.edit().putFloat(KEY_LOW_VOLT_LEVEL_2, volt2).apply()
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_BATT_CRT_VOLT = $volt2 from drone")
                    } else {
                        failures.add(PARAM_BATT_CRT_VOLT)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_CRT_VOLT from drone")
                    }

                    kotlinx.coroutines.delay(100)

                    // Refresh the shared action/radius/type/enable cache used by the monitors.
                    failures.addAll(sharedViewModel.refreshFenceParameters())

                    // Read the exact altitude above home used by the flight controller.
                    val altMax = readParamWithRetry(sharedViewModel, PARAM_FENCE_ALT_MAX)
                    sharedViewModel.recordAltitudeCeilingFromFc(altMax)
                    if (altMax != null && altMax.isFinite() && altMax > 0f) {
                        // Show the FC's exact above-home ceiling without an artificial offset.
                        val ceiling = altMax
                        if (FIELD_MAX_ALTITUDE !in editedFields) {
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
                    val lowAct = readParamWithRetry(sharedViewModel, PARAM_BATT_FS_LOW_ACT)
                    val lowActionName = lowAct?.let { BatteryFsAction.fromFcValue(it) }
                    if (lowActionName != null) {
                        if (FIELD_LOW_ACTION_1 !in editedFields) {
                            _options.value = _options.value.copy(lowVoltLevel1Action = lowActionName)
                            prefs.edit().putString(KEY_LOW_VOLT_LEVEL_1_ACTION, lowActionName).apply()
                        }
                        LogUtils.i(TAG, "✓ Read $PARAM_BATT_FS_LOW_ACT = $lowAct → $lowActionName from drone")
                    } else {
                        failures.add(PARAM_BATT_FS_LOW_ACT)
                        LogUtils.e(TAG, "✗ Failed to read $PARAM_BATT_FS_LOW_ACT from drone (got $lowAct)")
                    }

                    kotlinx.coroutines.delay(100)

                    val crtAct = readParamWithRetry(sharedViewModel, PARAM_BATT_FS_CRT_ACT)
                    val crtActionName = crtAct?.let { BatteryFsAction.fromFcValue(it) }
                    if (crtActionName != null) {
                        if (FIELD_LOW_ACTION_2 !in editedFields) {
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
                        "Could not read: ${failures.joinToString()}. Unconfirmed fields may show saved values; no parameters were written."
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
            maxAltitude = prefs.getFloat(KEY_MAX_ALTITUDE, DEFAULT_MAX_ALTITUDE)
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
        editedFields.add(FIELD_LOW_VOLT_1)
        _options.value = _options.value.copy(lowVoltLevel1 = value)
    }

    fun updateLowVoltLevel2(value: Float) {
        editedFields.add(FIELD_LOW_VOLT_2)
        _options.value = _options.value.copy(lowVoltLevel2 = value)
    }

    fun updateLowVoltLevel1Action(action: String) {
        editedFields.add(FIELD_LOW_ACTION_1)
        _options.value = _options.value.copy(lowVoltLevel1Action = action)
    }

    fun updateLowVoltLevel2Action(action: String) {
        editedFields.add(FIELD_LOW_ACTION_2)
        _options.value = _options.value.copy(lowVoltLevel2Action = action)
    }

    fun updateMaxAltitudeEnabled(enabled: Boolean) {
        _options.value = _options.value.copy(maxAltitudeEnabled = enabled)
    }

    fun updateMaxAltitude(value: Float) {
        editedFields.add(FIELD_MAX_ALTITUDE)
        _options.value = _options.value.copy(maxAltitude = value)
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
                sharedViewModel.awaitConnectSync()
                droneSyncMutex.withLock {
                    syncToDrone(sharedViewModel)
                }
            } finally {
                saveInProgress = false
            }
        }
    }

    /** Only explicit edits may generate PARAM_SET. Reading/opening the screen never does. */
    private suspend fun syncToDrone(sharedViewModel: SharedViewModel) {
        if (editedFields.isNotEmpty() && loadedSessionId != sharedViewModel.failsafeSessionId) {
            _syncStatus.value = "Not saved: connection changed. Refresh and review the current drone's settings."
            return
        }
        val current = _options.value
        val edited = editedFields.toSet()
        val values = mapOf(
            FIELD_LOW_VOLT_1 to current.lowVoltLevel1,
            FIELD_LOW_VOLT_2 to current.lowVoltLevel2,
            FIELD_LOW_ACTION_1 to BatteryFsAction.toFcValue(current.lowVoltLevel1Action),
            FIELD_LOW_ACTION_2 to BatteryFsAction.toFcValue(current.lowVoltLevel2Action),
            FIELD_MAX_ALTITUDE to current.maxAltitude
        )
        val validationError = when {
            edited.any { values[it] == null || values[it]?.isFinite() != true } ->
                "Not saved: edited parameters must have valid finite values"
            (FIELD_LOW_VOLT_1 in edited || FIELD_LOW_VOLT_2 in edited) &&
                (current.lowVoltLevel1 <= 0f || current.lowVoltLevel2 <= 0f ||
                    current.lowVoltLevel2 >= current.lowVoltLevel1) ->
                "Not saved: voltage thresholds must be positive and critical lower than warning"
            FIELD_MAX_ALTITUDE in edited && current.maxAltitude < 1f ->
                "Not saved: maximum altitude must be at least 1 m above home"
            else -> null
        }
        if (validationError != null) {
            _syncStatus.value = validationError
            return
        }

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // These settings belong to the GCS. FC-backed thresholds/actions are cached only
        // after the drone confirms a write, so a rejected edit cannot change enforcement.
        val saved = prefs.edit()
            .putString(KEY_MISSION_COMPLETION_ACTION, current.missionCompletionAction)
            .putString(KEY_TANK_EMPTY_ACTION_MANUAL, current.tankEmptyActionManual)
            .putString(KEY_TANK_EMPTY_ACTION_AUTO, current.tankEmptyActionAuto)
            .putBoolean(KEY_MAX_ALTITUDE_ENABLED, current.maxAltitudeEnabled)
            .commit()
        if (!saved) {
            _syncStatus.value = "Failed to save locally"
            return
        }
        val failures = mutableListOf<String>()
        suspend fun write(field: String, name: String, key: String, action: String? = null) {
            if (field !in edited) return
            if (loadedSessionId != sharedViewModel.failsafeSessionId) {
                failures.add("$name (connection changed)")
                return
            }
            val value = values.getValue(field) ?: return
            val confirmed = if (field == FIELD_MAX_ALTITUDE) {
                sharedViewModel.applyAltitudeCeilingToFc(value)
            } else {
                sharedViewModel.paramAckMatches(sharedViewModel.setParameter(name, value, timeoutMs = 5000L), value)
            }
            if (!confirmed) {
                failures.add(name)
                return
            }
            if (action != null) prefs.edit().putString(key, action).apply()
            else prefs.edit().putFloat(key, value).apply()
            // Preserve an edit made while its previous value was being sent.
            val latest = _options.value
            val unchanged = when (field) {
                FIELD_LOW_VOLT_1 -> latest.lowVoltLevel1 == current.lowVoltLevel1
                FIELD_LOW_VOLT_2 -> latest.lowVoltLevel2 == current.lowVoltLevel2
                FIELD_LOW_ACTION_1 -> latest.lowVoltLevel1Action == current.lowVoltLevel1Action
                FIELD_LOW_ACTION_2 -> latest.lowVoltLevel2Action == current.lowVoltLevel2Action
                else -> latest.maxAltitude == current.maxAltitude
            }
            if (unchanged) editedFields.remove(field)
        }
        write(FIELD_LOW_VOLT_1, PARAM_BATT_LOW_VOLT, KEY_LOW_VOLT_LEVEL_1)
        write(FIELD_LOW_VOLT_2, PARAM_BATT_CRT_VOLT, KEY_LOW_VOLT_LEVEL_2)
        write(FIELD_LOW_ACTION_1, PARAM_BATT_FS_LOW_ACT, KEY_LOW_VOLT_LEVEL_1_ACTION, current.lowVoltLevel1Action)
        write(FIELD_LOW_ACTION_2, PARAM_BATT_FS_CRT_ACT, KEY_LOW_VOLT_LEVEL_2_ACTION, current.lowVoltLevel2Action)
        write(FIELD_MAX_ALTITUDE, PARAM_FENCE_ALT_MAX, KEY_MAX_ALTITUDE)
        _syncStatus.value = when {
            failures.isNotEmpty() -> "Failed to sync: ${failures.joinToString()}. Edits retained for retry."
            edited.isEmpty() -> "GCS settings saved. Drone parameters unchanged."
            else -> "Saved; edited parameters confirmed by drone"
        }
    }
}
