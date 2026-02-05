package com.example.aerogcsclone.obstacle

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Core obstacle detection logic
 * Analyzes sensor readings and determines threat levels
 */
class ObstacleDetector(
    private val config: ObstacleDetectionConfig
) {

    // Consecutive HIGH threat detection counter
    private var consecutiveHighDetections = 0

    /**
     * Detect obstacle from sensor reading and drone state
     */
    fun detectObstacle(
        reading: SensorReading?,
        droneLocation: LatLng?,
        droneHeading: Float?,
        targetLocation: LatLng?
    ): ObstacleInfo? {
        // No reading = no obstacle
        if (reading == null) {
            consecutiveHighDetections = 0
            return null
        }

        val distance = reading.distance

        // Check if obstacle is in detection range
        if (distance < config.minDetectionRange || distance > config.maxDetectionRange) {
            consecutiveHighDetections = 0
            return null
        }

        // Classify threat level
        val threatLevel = classifyThreatLevel(distance)

        // Update consecutive detection counter
        if (threatLevel == ThreatLevel.HIGH) {
            consecutiveHighDetections++
        } else {
            consecutiveHighDetections = 0
        }

        // Calculate obstacle location if we have drone position and heading
        val obstacleLocation = if (droneLocation != null && droneHeading != null) {
            calculateObstacleLocation(droneLocation, droneHeading, distance)
        } else null

        // Check if obstacle is in flight path
        val isInPath = targetLocation != null && obstacleLocation != null &&
                isObstacleInPath(droneLocation, targetLocation, obstacleLocation)

        return if (threatLevel != ThreatLevel.NONE && isInPath) {
            ObstacleInfo(
                distance = distance,
                bearing = reading.bearing ?: droneHeading,
                elevation = reading.elevation,
                location = obstacleLocation,
                threatLevel = threatLevel,
                consecutiveDetections = consecutiveHighDetections
            )
        } else {
            consecutiveHighDetections = 0
            null
        }
    }

    /**
     * Classify threat level based on distance
     */
    private fun classifyThreatLevel(distance: Float): ThreatLevel {
        return when {
            distance >= config.lowThreatThreshold -> ThreatLevel.LOW
            distance >= config.mediumThreatThreshold -> ThreatLevel.MEDIUM
            distance < config.highThreatThreshold -> ThreatLevel.HIGH
            else -> ThreatLevel.NONE
        }
    }

    /**
     * Check if should trigger RTL based on consecutive HIGH detections
     */
    fun shouldTriggerRTL(): Boolean {
        return consecutiveHighDetections >= config.minimumConsecutiveDetections &&
                config.enableAutoRTL
    }

    /**
     * Calculate obstacle GPS location based on drone position, heading, and distance
     */
    private fun calculateObstacleLocation(
        droneLocation: LatLng,
        heading: Float,
        distance: Float
    ): LatLng {
        val earthRadius = 6371000.0 // Earth's radius in meters
        val distanceInRadians = distance / earthRadius
        val bearingInRadians = Math.toRadians(heading.toDouble())

        val lat1 = Math.toRadians(droneLocation.latitude)
        val lon1 = Math.toRadians(droneLocation.longitude)

        val lat2 = asin(
            sin(lat1) * cos(distanceInRadians) +
            cos(lat1) * sin(distanceInRadians) * cos(bearingInRadians)
        )

        val lon2 = lon1 + atan2(
            sin(bearingInRadians) * sin(distanceInRadians) * cos(lat1),
            cos(distanceInRadians) - sin(lat1) * sin(lat2)
        )

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Check if obstacle is in the flight path between drone and target
     */
    private fun isObstacleInPath(
        droneLocation: LatLng?,
        targetLocation: LatLng,
        obstacleLocation: LatLng
    ): Boolean {
        if (droneLocation == null) return false

        // Calculate bearing to target
        val bearingToTarget = calculateBearing(droneLocation, targetLocation)

        // Calculate bearing to obstacle
        val bearingToObstacle = calculateBearing(droneLocation, obstacleLocation)

        // Calculate angle difference
        var angleDifference = abs(bearingToTarget - bearingToObstacle)
        if (angleDifference > 180) {
            angleDifference = 360 - angleDifference
        }

        // Obstacle is in path if angle difference is within tolerance
        return angleDifference <= config.angleToleranceDegrees
    }

    /**
     * Calculate bearing between two GPS points
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360

        return bearing.toFloat()
    }

    /**
     * Calculate distance between two GPS points (Haversine formula)
     */
    fun calculateDistance(from: LatLng, to: LatLng): Float {
        val earthRadius = 6371000.0 // meters

        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }

    /**
     * Reset detection state
     */
    fun reset() {
        consecutiveHighDetections = 0
    }
}

