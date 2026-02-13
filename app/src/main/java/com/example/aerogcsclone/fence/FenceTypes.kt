package com.example.aerogcsclone.fence

import com.google.android.gms.maps.model.LatLng

/**
 * Represents different types of geofence zones
 * Based on ArduPilot's native fence system for Mission Planner-style implementation
 */
sealed class FenceZone {
    /**
     * Polygon fence - drone must stay inside (inclusion) or outside (exclusion)
     */
    data class Polygon(
        val points: List<LatLng>,
        val isInclusion: Boolean = true  // true = stay inside, false = stay outside
    ) : FenceZone()

    /**
     * Circular fence - drone must stay inside (inclusion) or outside (exclusion)
     */
    data class Circle(
        val center: LatLng,
        val radiusMeters: Float,
        val isInclusion: Boolean = true
    ) : FenceZone()

    /**
     * Return point - where drone goes if fence is breached
     */
    data class ReturnPoint(
        val location: LatLng
    ) : FenceZone()
}

/**
 * Complete fence configuration
 */
data class FenceConfiguration(
    val zones: List<FenceZone>,
    val altitudeMin: Float? = null,  // Meters AGL
    val altitudeMax: Float? = null,  // Meters AGL
    val action: FenceAction = FenceAction.BRAKE,
    val margin: Float = 3.0f  // Safety margin in meters
)

/**
 * Fence actions - what FC does when fence is breached
 * These map directly to ArduPilot FENCE_ACTION parameter values
 */
enum class FenceAction(val value: Float) {
    REPORT_ONLY(0f),   // Just report, no action
    RTL(1f),           // Return to launch
    HOLD(2f),          // Hold position (LOITER)
    GUIDED(3f),        // Switch to GUIDED mode
    BRAKE(4f),         // Emergency brake (recommended for spray drones)
    SMART_RTL(5f)      // SmartRTL - retrace path home
}

/**
 * Fence status from flight controller
 */
data class FenceStatus(
    val enabled: Boolean = false,
    val breached: Boolean = false,
    val breachCount: Int = 0,
    val breachType: String? = null  // "altitude", "polygon", "circle"
)

