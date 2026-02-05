package com.example.aerogcsclone.obstacle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages obstacle detection sensors (proximity, LIDAR, ultrasonic, or simulated)
 */
class ObstacleSensorManager(
    private val context: Context,
    private val config: ObstacleDetectionConfig
) : SensorEventListener {

    private val tag = "ObstacleSensorMgr"
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var proximitySensor: Sensor? = null

    private val _sensorReading = MutableStateFlow<SensorReading?>(null)
    val sensorReading: StateFlow<SensorReading?> = _sensorReading.asStateFlow()

    private var isMonitoring = false
    private var calibratedMinDistance = 0f
    private var calibratedMaxDistance = 50f

    /**
     * Initialize sensor hardware
     */
    fun initialize(): Boolean {
        return when (config.sensorType) {
            SensorType.PROXIMITY -> initializeProximitySensor()
            SensorType.LIDAR -> initializeLidarSensor()
            SensorType.ULTRASONIC -> initializeUltrasonicSensor()
            SensorType.SIMULATED -> initializeSimulatedSensor()
        }
    }

    private fun initializeProximitySensor(): Boolean {
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            return false
        }
        return true
    }

    private fun initializeLidarSensor(): Boolean {
        // TODO: Initialize LIDAR sensor via USB/serial
        return initializeSimulatedSensor()
    }

    private fun initializeUltrasonicSensor(): Boolean {
        // TODO: Initialize ultrasonic sensor
        return initializeSimulatedSensor()
    }

    private fun initializeSimulatedSensor(): Boolean {
        return true
    }

    /**
     * Calibrate sensor to determine min/max range
     */
    fun calibrate(): CalibrationResult {
        return when (config.sensorType) {
            SensorType.PROXIMITY -> calibrateProximitySensor()
            SensorType.SIMULATED -> calibrateSimulatedSensor()
            else -> CalibrationResult(
                success = true,
                minDistance = config.minDetectionRange,
                maxDistance = config.maxDetectionRange,
                accuracy = 0.5f,
                message = "Using default calibration"
            )
        }
    }

    private fun calibrateProximitySensor(): CalibrationResult {
        proximitySensor?.let { sensor ->
            calibratedMaxDistance = sensor.maximumRange
            calibratedMinDistance = 0f

            return CalibrationResult(
                success = true,
                minDistance = calibratedMinDistance,
                maxDistance = calibratedMaxDistance,
                accuracy = 0.1f,
                message = "Proximity sensor calibrated"
            )
        }

        return CalibrationResult(
            success = false,
            minDistance = 0f,
            maxDistance = 0f,
            accuracy = 0f,
            message = "Proximity sensor not available"
        )
    }

    private fun calibrateSimulatedSensor(): CalibrationResult {
        calibratedMinDistance = config.minDetectionRange
        calibratedMaxDistance = config.maxDetectionRange

        return CalibrationResult(
            success = true,
            minDistance = calibratedMinDistance,
            maxDistance = calibratedMaxDistance,
            accuracy = 1.0f,
            message = "Simulated sensor calibrated"
        )
    }

    /**
     * Start monitoring sensor readings
     */
    fun startMonitoring() {
        if (isMonitoring) return

        when (config.sensorType) {
            SensorType.PROXIMITY -> startProximityMonitoring()
            SensorType.SIMULATED -> startSimulatedMonitoring()
            else -> {
                startSimulatedMonitoring()
            }
        }

        isMonitoring = true
    }

    private fun startProximityMonitoring() {
        proximitySensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    private fun startSimulatedMonitoring() {
        // Simulated sensor will emit readings via injectSimulatedReading()
        // or emit safe readings by default
        _sensorReading.value = SensorReading(
            distance = calibratedMaxDistance,
            quality = 1.0f
        )
    }

    /**
     * Stop monitoring sensor readings
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        when (config.sensorType) {
            SensorType.PROXIMITY -> sensorManager.unregisterListener(this)
            else -> {} // No cleanup needed for simulated
        }

        isMonitoring = false
        _sensorReading.value = null
    }

    /**
     * SensorEventListener implementation
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PROXIMITY) {
                val distance = it.values[0]
                _sensorReading.value = SensorReading(
                    distance = distance,
                    quality = 1.0f
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    /**
     * Inject simulated sensor reading (for testing)
     */
    fun injectSimulatedReading(distance: Float) {
        if (config.sensorType == SensorType.SIMULATED) {
            _sensorReading.value = SensorReading(
                distance = distance,
                quality = 1.0f
            )
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopMonitoring()
    }
}

/**
 * Result of sensor calibration
 */
data class CalibrationResult(
    val success: Boolean,
    val minDistance: Float,
    val maxDistance: Float,
    val accuracy: Float,
    val message: String
)

