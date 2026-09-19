package com.example.kftgcs.parammanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Frame class mapping  (mirrors ConfigMotorTest.cs get_motormax logic)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps ArduPilot FRAME_CLASS integer values to motor counts and display names.
 *
 * Source truth: Mission Planner ConfigMotorTest.cs Q_FRAME_CLASS switch block
 * plus the MAV_TYPE motor-count table below it.
 *
 *  FRAME_CLASS  →  MAV_TYPE           →  motorMax
 *  0 or 1       →  QUADROTOR          →  4
 *  2 or 5       →  HEXAROTOR          →  6
 *  3 or 4       →  OCTOROTOR          →  8
 *  6            →  HELICOPTER         →  1 (single main rotor)
 *  7            →  TRICOPTER          →  3
 *  12 or 13     →  DODECAROTOR        →  12
 *  (default)    →  QUADROTOR fallback →  4
 */
object FrameClassMap {

    data class FrameInfo(val motorCount: Int, val displayName: String)

    private val info: Map<Int, FrameInfo> = mapOf(
        0  to FrameInfo(4,  "Undefined / Quad"),
        1  to FrameInfo(4,  "Quad"),
        2  to FrameInfo(6,  "Hexa"),
        3  to FrameInfo(8,  "Octa"),
        4  to FrameInfo(8,  "OctaQuad"),
        5  to FrameInfo(6,  "Y6"),
        6  to FrameInfo(1,  "Helicopter"),
        7  to FrameInfo(3,  "Tri"),
        8  to FrameInfo(1,  "Single"),
        9  to FrameInfo(2,  "Coax"),
        10 to FrameInfo(2,  "Bicopter"),
        11 to FrameInfo(2,  "Heli_Dual"),
        12 to FrameInfo(12, "DodecaHexa"),
        13 to FrameInfo(12, "DodecaHexa-I")
    )

    fun motorCount(frameClass: Int): Int = info[frameClass]?.motorCount ?: 4

    fun displayName(frameClass: Int): String = info[frameClass]?.displayName ?: "Unknown ($frameClass)"
}

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

data class MotorTestState(
    /** Total number of motors derived from FRAME_CLASS. 0 = not yet fetched. */
    val motorMax: Int = 0,
    val frameClassText: String = "Class: --",
    val frameTypeText: String  = "Type: --",

    /** True while fetching FRAME_CLASS / FRAME_TYPE from the drone. */
    val isLoadingLayout: Boolean = false,

    /** Mirrors SharedViewModel connection status. */
    val isDroneConnected: Boolean = false,

    /** One-shot success/info message cleared after display. */
    val statusMessage: String? = null,

    /** One-shot error message cleared after display. */
    val errorMessage: String? = null,

    /**
     * Non-null → show the MOT_SPIN_MIN input dialog.
     * Value is the pre-filled suggestion (current% + 3).
     */
    val spinMinDialogSuggestion: Int? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Isolated ViewModel for the Motor Test screen.
 *
 * All drone communication is routed through [SharedViewModel] using its existing
 * suspend API so this ViewModel never touches internal repository state directly.
 * This guarantees zero interference with ongoing telemetry loops or the full-param
 * state machines already running in other ViewModels.
 *
 * All exceptions are caught locally and surfaced as [MotorTestState.errorMessage]
 * so a failed MAVLink command simply shows a Toast/Snackbar — it never crashes
 * or disrupts the host tab.
 */
class MotorTestViewModel(
    private val sharedViewModel: SharedViewModel
) : ViewModel() {

    companion object {
        private const val TAG = "MotorTestVM"

        /**
         * MAVLink MAV_CMD_DO_MOTOR_TEST = 209.
         * Payload (from Mission Planner ConfigMotorTest.cs testMotor()):
         *   param1 = motor index (1-based)
         *   param2 = throttle type  (0 = MOTOR_TEST_THROTTLE_PERCENT)
         *   param3 = throttle value (0–100 %)
         *   param4 = duration in seconds
         *   param5 = motor count    (0 = individual; N = run N motors in sequence from param1)
         *   param6 = test order     (0 = motor sequence order)
         *   param7 = 0
         */
        private const val MAV_CMD_DO_MOTOR_TEST: UInt = 209u

        /** Throttle type: percent (0–100). Matches MOTOR_TEST_THROTTLE_PERCENT in ArduPilot. */
        private const val THROTTLE_TYPE_PERCENT = 0f
    }

    private val _state = MutableStateFlow(MotorTestState())
    val state: StateFlow<MotorTestState> = _state.asStateFlow()

    init {
        // Mirror drone connection status so the UI can gate controls accordingly.
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { tel ->
                _state.update { it.copy(isDroneConnected = tel.connected) }
            }
        }
    }

    // ── Layout fetch ──────────────────────────────────────────────────────────

    /**
     * Asynchronously reads FRAME_CLASS and FRAME_TYPE from the flight controller,
     * maps FRAME_CLASS → motor count, and updates the UI state.
     *
     * Safe to call multiple times; re-entrant calls while loading are ignored.
     */
    fun fetchLayout() {
        if (_state.value.isLoadingLayout) return
        _state.update { it.copy(isLoadingLayout = true, errorMessage = null, statusMessage = null) }

        viewModelScope.launch {
            try {
                // Read both params; timeouts produce null (handled below).
                val frameClassRaw = sharedViewModel.readParameter("FRAME_CLASS", 5000L)
                val frameTypeRaw  = sharedViewModel.readParameter("FRAME_TYPE",  5000L)

                val frameClassInt = frameClassRaw?.toInt() ?: 1
                val frameTypeInt  = frameTypeRaw?.toInt()  ?: 0

                val motorMax  = FrameClassMap.motorCount(frameClassInt)
                val className = FrameClassMap.displayName(frameClassInt)

                LogUtils.d(TAG, "Frame class=$frameClassInt ($className), type=$frameTypeInt → $motorMax motors")

                _state.update {
                    it.copy(
                        isLoadingLayout = false,
                        motorMax        = motorMax,
                        frameClassText  = "Class: $className ($frameClassInt)",
                        frameTypeText   = "Type: $frameTypeInt",
                        statusMessage   = "$motorMax motors detected (${className})"
                    )
                }
            } catch (e: CancellationException) {
                throw e // always propagate coroutine cancellation
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to fetch layout parameters", e)
                _state.update {
                    it.copy(
                        isLoadingLayout = false,
                        motorMax        = 4,
                        frameClassText  = "Class: N/A (defaulted to Quad)",
                        frameTypeText   = "Type: --",
                        errorMessage    = "Could not read FRAME_CLASS. Defaulted to 4 motors."
                    )
                }
            }
        }
    }

    // ── Individual motor test ─────────────────────────────────────────────────

    /**
     * Sends MAV_CMD_DO_MOTOR_TEST for a single motor.
     *
     * @param motorIndex  1-based motor index (Motor A = 1, Motor B = 2, …)
     * @param throttlePercent  0–99 %
     * @param durationSecs  Duration in seconds
     */
    fun testMotor(motorIndex: Int, throttlePercent: Int, durationSecs: Int) {
        viewModelScope.launch {
            try {
                val label = ('A' + (motorIndex - 1))
                _state.update { it.copy(statusMessage = "Testing Motor $label at $throttlePercent% for ${durationSecs}s…") }
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_DO_MOTOR_TEST,
                    param1    = motorIndex.toFloat(),
                    param2    = THROTTLE_TYPE_PERCENT,
                    param3    = throttlePercent.toFloat(),
                    param4    = durationSecs.toFloat(),
                    param5    = 0f,
                    param6    = 0f,
                    param7    = 0f
                )
                LogUtils.d(TAG, "DO_MOTOR_TEST sent: motor=$motorIndex throttle=$throttlePercent% duration=${durationSecs}s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "testMotor $motorIndex failed", e)
                _state.update { it.copy(errorMessage = "Motor $motorIndex test failed: ${e.message}") }
            }
        }
    }

    // ── Test All (simultaneous) ───────────────────────────────────────────────

    /**
     * Sends one DO_MOTOR_TEST command per motor in rapid succession so all motors
     * spin simultaneously. Matches Mission Planner's but_TestAll loop.
     */
    fun testAllMotors(throttlePercent: Int, durationSecs: Int) {
        val motorMax = _state.value.motorMax
        if (motorMax <= 0) {
            _state.update { it.copy(errorMessage = "No motors detected — fetch layout first.") }
            return
        }
        viewModelScope.launch {
            try {
                _state.update { it.copy(statusMessage = "Testing all $motorMax motors at $throttlePercent%…") }
                for (i in 1..motorMax) {
                    sharedViewModel.sendCalibrationCommandRaw(
                        commandId = MAV_CMD_DO_MOTOR_TEST,
                        param1    = i.toFloat(),
                        param2    = THROTTLE_TYPE_PERCENT,
                        param3    = throttlePercent.toFloat(),
                        param4    = durationSecs.toFloat(),
                        param5    = 0f,
                        param6    = 0f,
                        param7    = 0f
                    )
                }
                LogUtils.d(TAG, "DO_MOTOR_TEST sent to all $motorMax motors")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "testAllMotors failed", e)
                _state.update { it.copy(errorMessage = "Test all failed: ${e.message}") }
            }
        }
    }

    // ── Stop All ──────────────────────────────────────────────────────────────

    /**
     * Stops all motors by sending 0% throttle / 0 s duration to each.
     * Matches Mission Planner's but_StopAll loop.
     */
    fun stopAllMotors() {
        val motorMax = _state.value.motorMax.coerceAtLeast(1) // send at least 1 stop command
        viewModelScope.launch {
            try {
                _state.update { it.copy(statusMessage = "Stopping all motors…") }
                for (i in 1..motorMax) {
                    sharedViewModel.sendCalibrationCommandRaw(
                        commandId = MAV_CMD_DO_MOTOR_TEST,
                        param1    = i.toFloat(),
                        param2    = THROTTLE_TYPE_PERCENT,
                        param3    = 0f,  // 0 % throttle = stop
                        param4    = 0f,  // 0 s duration
                        param5    = 0f,
                        param6    = 0f,
                        param7    = 0f
                    )
                }
                _state.update { it.copy(statusMessage = "All motors stopped") }
                LogUtils.d(TAG, "Stop-all sent to $motorMax motors")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "stopAllMotors failed", e)
                _state.update { it.copy(errorMessage = "Stop all failed: ${e.message}") }
            }
        }
    }

    // ── Sequence test ─────────────────────────────────────────────────────────

    /**
     * Sends a single MAV_CMD_DO_MOTOR_TEST command with param5 = motorMax.
     * This tells ArduPilot to run motors 1 … motorMax in sequence, each for
     * [durationSecs] seconds — matching Mission Planner's but_TestAllSeq:
     *   testMotor(1, speed, time, motormax)
     */
    fun testSequence(throttlePercent: Int, durationSecs: Int) {
        val motorMax = _state.value.motorMax
        if (motorMax <= 0) {
            _state.update { it.copy(errorMessage = "No motors detected — fetch layout first.") }
            return
        }
        viewModelScope.launch {
            try {
                val estSecs = motorMax * durationSecs
                _state.update {
                    it.copy(statusMessage = "Sequence started: $motorMax motors × ${durationSecs}s ≈ ${estSecs}s total")
                }
                sharedViewModel.sendCalibrationCommandRaw(
                    commandId = MAV_CMD_DO_MOTOR_TEST,
                    param1    = 1f,                        // start from motor 1
                    param2    = THROTTLE_TYPE_PERCENT,
                    param3    = throttlePercent.toFloat(),
                    param4    = durationSecs.toFloat(),
                    param5    = motorMax.toFloat(),        // FC will iterate 1 … motorMax
                    param6    = 0f,
                    param7    = 0f
                )
                LogUtils.d(TAG, "DO_MOTOR_TEST sequence: $motorMax motors, ${durationSecs}s each")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "testSequence failed", e)
                _state.update { it.copy(errorMessage = "Sequence test failed: ${e.message}") }
            }
        }
    }

    // ── MOT_SPIN_ARM ──────────────────────────────────────────────────────────

    /**
     * Writes MOT_SPIN_ARM = [valuePercent] / 100.0f.
     *
     * The 20 % safety limit mirrors Mission Planner's but_mot_spin_arm_Click guard.
     * [valuePercent] default suggestion = throttle% + 2 (deadzone + 2 %).
     */
    fun setSpinArm(valuePercent: Int) {
        if (valuePercent >= 20) {
            _state.update { it.copy(errorMessage = "MOT_SPIN_ARM must be below 20 % (entered $valuePercent %)") }
            return
        }
        viewModelScope.launch {
            try {
                val floatValue = valuePercent / 100.0f
                val ack = sharedViewModel.setParameter("MOT_SPIN_ARM", floatValue, timeoutMs = 5000L)
                if (ack != null) {
                    _state.update { it.copy(statusMessage = "MOT_SPIN_ARM set to $valuePercent % ($floatValue)") }
                    LogUtils.d(TAG, "MOT_SPIN_ARM confirmed = ${ack.paramValue}")
                } else {
                    _state.update { it.copy(errorMessage = "MOT_SPIN_ARM write timed out — check connection") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "setSpinArm failed", e)
                _state.update { it.copy(errorMessage = "Failed to set MOT_SPIN_ARM: ${e.message}") }
            }
        }
    }

    // ── MOT_SPIN_MIN dialog preparation ──────────────────────────────────────

    /**
     * Reads the current MOT_SPIN_MIN from the FC, computes a default suggestion
     * (current% + 3), and triggers the input dialog by setting
     * [MotorTestState.spinMinDialogSuggestion].
     *
     * The 20 % safety limit mirrors Mission Planner's but_mot_spin_min_Click guard.
     */
    fun prepareSpinMinDialog(throttlePercent: Int) {
        if (throttlePercent >= 20) {
            _state.update { it.copy(errorMessage = "Throttle must be below 20 % to adjust MOT_SPIN_MIN") }
            return
        }
        viewModelScope.launch {
            try {
                val current = sharedViewModel.readParameter("MOT_SPIN_MIN", 4000L)
                if (current == null) {
                    // A timed-out read is NOT a value of 0. Suggesting 0 + 3 % here invited the
                    // pilot to overwrite a real MOT_SPIN_MIN with a much lower one.
                    _state.update {
                        it.copy(errorMessage = "Could not read MOT_SPIN_MIN from the drone — try again")
                    }
                    return@launch
                }
                // MOT_SPIN_MIN is a 0.0–1.0 fraction on the FC; convert to percent. Rounded, not
                // truncated: 0.29f * 100f is 28.999998f and used to display as 28 %.
                val currentPercent = Math.round(current * 100f)
                val suggestion = currentPercent + 3  // "arm min + 3 %" per Mission Planner
                _state.update { it.copy(spinMinDialogSuggestion = suggestion) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "prepareSpinMinDialog failed — using default suggestion", e)
                _state.update { it.copy(spinMinDialogSuggestion = 3) } // sensible fallback
            }
        }
    }

    /** Writes MOT_SPIN_MIN = [valuePercent] / 100.0f and dismisses the dialog. */
    fun setSpinMin(valuePercent: Int) {
        dismissSpinMinDialog()
        if (valuePercent >= 20) {
            _state.update { it.copy(errorMessage = "MOT_SPIN_MIN must be below 20 % (entered $valuePercent %)") }
            return
        }
        viewModelScope.launch {
            try {
                val floatValue = valuePercent / 100.0f
                val ack = sharedViewModel.setParameter("MOT_SPIN_MIN", floatValue, timeoutMs = 5000L)
                if (ack != null) {
                    _state.update { it.copy(statusMessage = "MOT_SPIN_MIN set to $valuePercent % ($floatValue)") }
                    LogUtils.d(TAG, "MOT_SPIN_MIN confirmed = ${ack.paramValue}")
                } else {
                    _state.update { it.copy(errorMessage = "MOT_SPIN_MIN write timed out — check connection") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "setSpinMin failed", e)
                _state.update { it.copy(errorMessage = "Failed to set MOT_SPIN_MIN: ${e.message}") }
            }
        }
    }

    fun dismissSpinMinDialog() {
        _state.update { it.copy(spinMinDialogSuggestion = null) }
    }

    // ── Message helpers ───────────────────────────────────────────────────────

    fun clearMessages() {
        _state.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
