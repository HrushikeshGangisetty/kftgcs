package com.example.aerogcsclone.telemetry

import android.util.Log
import kotlin.math.abs

/**
 * Flow rate filter with moving average and spike detection
 * Helps detect sensor faults and erratic readings
 */
class FlowRateFilter(private val windowSize: Int = 5) {
    private val values = mutableListOf<Float>()

    /**
     * Add a new value and return the filtered (averaged) value
     */
    fun addValue(value: Float): Float {
        values.add(value)
        if (values.size > windowSize) values.removeAt(0)
        return values.average().toFloat()
    }

    /**
     * Detect if a new value is a spike (anomaly) compared to the moving average
     * @param newValue The new value to check
     * @param threshold Multiplier for spike detection (default 2.0 = 200% deviation)
     * @return true if the value is likely an erratic reading
     */
    fun detectSpike(newValue: Float, threshold: Float = 2.0f): Boolean {
        if (values.isEmpty()) return false
        val avg = values.average().toFloat()
        return abs(newValue - avg) > avg * threshold
    }

    /**
     * Get current average without adding new value
     */
    fun getAverage(): Float? {
        return if (values.isEmpty()) null else values.average().toFloat()
    }

    /**
     * Clear all stored values
     */
    fun reset() {
        values.clear()
    }
}

/**
 * Voltage filter with moving average and spike rejection for tank level sensor (BATT3)
 * Helps smooth out fluctuating readings from analog level sensors
 */
class VoltageFilter(private val windowSize: Int = 10) {
    private val values = mutableListOf<Int>()
    private var lastStableValue: Int? = null

    /**
     * Add a new voltage reading and return the filtered (median) value
     * Uses median instead of average to better reject outliers
     */
    fun addValue(value: Int): Int {
        values.add(value)
        if (values.size > windowSize) values.removeAt(0)

        // Use median for better outlier rejection
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }

        lastStableValue = median
        return median
    }

    /**
     * Detect if a new value is a spike (anomaly) compared to recent values
     * @param newValue The new value to check
     * @param maxDeviation Maximum allowed deviation in mV from the median
     * @return true if the value is likely an erratic reading
     */
    fun detectSpike(newValue: Int, maxDeviation: Int = 50): Boolean {
        if (values.size < 3) return false
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        return abs(newValue - median) > maxDeviation
    }

    /**
     * Get current median without adding new value
     */
    fun getMedian(): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }

    /**
     * Get last stable value (useful when current reading is a spike)
     */
    fun getLastStable(): Int? = lastStableValue

    /**
     * Clear all stored values
     */
    fun reset() {
        values.clear()
        lastStableValue = null
    }

    /**
     * Get the number of values currently in the filter
     */
    fun size(): Int = values.size
}

/**
 * Calibration point for piecewise tank level calibration
 * Supports non-linear tank shapes
 */
data class CalibrationPoint(
    val voltageMv: Int,
    val levelPercent: Int
)

/**
 * Validates and converts flow rate from MAVLink raw values
 * Includes comprehensive input validation
 */
object FlowRateValidator {
    private const val MAX_FLOW_RATE_CA = 60000  // Max ~600 L/h in centi-Amps
    private const val TAG = "Spray Telemetry"

    /**
     * Validate and convert flow rate from centi-Amps to L/h
     * @param currentBattery Raw MAVLink current_battery value (cA)
     * @return Flow rate in L/h, or null if invalid
     */
    fun validateAndConvert(currentBattery: Short): Float? {
        return when {
            currentBattery.toInt() == -1 -> {
                Log.w(TAG, "Flow rate is -1 (invalid/not configured)")
                null
            }
            currentBattery < 0 -> {
                Log.w(TAG, "Negative flow rate detected: $currentBattery - treating as invalid")
                null
            }
            currentBattery > MAX_FLOW_RATE_CA -> {
                Log.w(TAG, "Flow rate exceeds maximum: $currentBattery cA (>${MAX_FLOW_RATE_CA/100} L/h) - likely sensor fault")
                null
            }
            currentBattery == 0.toShort() -> {
                // 0 is valid (no flow)
                0f
            }
            else -> {
                currentBattery / 100f  // Convert cA to Amps (= L/h)
            }
        }
    }
}

/**
 * Tank level calculator with support for piecewise calibration
 * Handles non-linear tank shapes and sensor drift
 */
object TankLevelCalculator {
    private const val TAG = "Spray Telemetry"

    /**
     * Calculate tank level using piecewise linear interpolation
     * Supports non-linear tank shapes by using multiple calibration points
     *
     * @param voltageMv Current voltage reading in millivolts
     * @param calibrationPoints List of voltage-to-level calibration points
     * @return Tank level percentage (0-100), or null if invalid
     */
    fun calculateTankLevel(
        voltageMv: Int,
        calibrationPoints: List<CalibrationPoint>
    ): Int? {
        if (calibrationPoints.isEmpty()) {
            Log.e(TAG, "No calibration points provided")
            return null
        }

        if (voltageMv < 0) {
            Log.w(TAG, "Invalid voltage: $voltageMv mV")
            return null
        }

        val sorted = calibrationPoints.sortedBy { it.voltageMv }

        // Find bracketing points
        val lower = sorted.lastOrNull { it.voltageMv <= voltageMv } ?: sorted.first()
        val upper = sorted.firstOrNull { it.voltageMv >= voltageMv } ?: sorted.last()

        // If both points are the same, return that level
        if (lower == upper) {
            Log.d(TAG, "Exact calibration match: ${lower.levelPercent}%")
            return lower.levelPercent
        }

        // Linear interpolation between bracketing points
        val voltageDiff = upper.voltageMv - lower.voltageMv
        if (voltageDiff == 0) {
            return lower.levelPercent
        }

        val ratio = (voltageMv - lower.voltageMv).toFloat() / voltageDiff
        val level = (lower.levelPercent + ratio * (upper.levelPercent - lower.levelPercent))
            .toInt()
            .coerceIn(0, 100)

        Log.d(TAG, "Interpolated level: $level% (voltage: $voltageMv mV between ${lower.voltageMv}-${upper.voltageMv} mV)")
        return level
    }

    /**
     * Simple two-point linear calibration (backward compatible)
     * @param voltageMv Current voltage reading
     * @param emptyVoltageMv Voltage when tank is empty
     * @param fullVoltageMv Voltage when tank is full
     * @return Tank level percentage (0-100), or null if invalid
     */
    fun calculateTankLevelSimple(
        voltageMv: Int,
        emptyVoltageMv: Int,
        fullVoltageMv: Int
    ): Int? {
        if (voltageMv < 0) {
            Log.w(TAG, "Invalid voltage: $voltageMv mV")
            return null
        }

        if (fullVoltageMv <= emptyVoltageMv) {
            Log.e(TAG, "Invalid calibration: full ($fullVoltageMv mV) <= empty ($emptyVoltageMv mV)")
            return null
        }

        return when {
            voltageMv <= emptyVoltageMv -> {
                Log.d(TAG, "Voltage at or below empty threshold: $voltageMv <= $emptyVoltageMv mV")
                0
            }
            voltageMv >= fullVoltageMv -> {
                Log.d(TAG, "Voltage at or above full threshold: $voltageMv >= $fullVoltageMv mV")
                100
            }
            else -> {
                val level = ((voltageMv - emptyVoltageMv).toFloat() /
                        (fullVoltageMv - emptyVoltageMv) * 100)
                    .toInt()
                    .coerceIn(0, 100)
                Log.d(TAG, "Calculated tank level: $level% (from $voltageMv mV)")
                level
            }
        }
    }
}

