package com.example.kftgcs.fence

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
 * Complete fence configuration.
 *
 * Deliberately carries no `action` or `margin`: FENCE_ACTION and FENCE_MARGIN are
 * operator-owned parameters that the GCS reads but never writes (DGCA requires the
 * vehicle to behave according to the parameters actually set on it). They used to
 * live here and were stamped onto the FC on every upload — see
 * MavlinkTelemetryRepository.configureFenceParameters.
 */
data class FenceConfiguration(
    val zones: List<FenceZone>,
    val altitudeMin: Float? = null,  // Meters AGL
    val altitudeMax: Float? = null,  // Meters AGL
    /**
     * Home-centred cylinder radius (FENCE_RADIUS), metres. Null leaves the FC's value alone.
     *
     * Normally null: the radius is operator-owned, like the action and margin. Set it only
     * where the GCS genuinely intends to overwrite the pilot's configured limit.
     */
    val circleRadiusMeters: Float? = null,
    /**
     * Set the home-cylinder bit in FENCE_TYPE without touching FENCE_RADIUS — i.e. "keep the
     * range fence switched on at whatever radius the operator chose".
     */
    val armCircleFence: Boolean = false
)

/**
 * Fence actions — what the FC does when a fence is breached.
 *
 * Values map directly to ArduPilot's FENCE_ACTION parameter. Taken verbatim from
 * AC_Fence.cpp:
 *
 *     @Values{Copter}: 0:Report Only,1:RTL or Land,2:Always Land,
 *                      3:SmartRTL or RTL or Land,4:Brake or Land,5:SmartRTL or Land
 *
 * Note there is NO "loiter" action in ArduPilot. The behaviour pilots describe as
 * loitering at the fence is BRAKE (4) — a braked stop-and-hold — so that is what
 * [pilotLabel] calls it. The previous version of this enum had 2 labelled
 * "Hold position (LOITER)" and a non-existent GUIDED(3); both were wrong, and 2 in
 * particular would have told a pilot "LOITER" while the drone landed.
 */
enum class FenceAction(val value: Float, val pilotLabel: String) {
    REPORT_ONLY(0f, "Report Only"),
    RTL(1f, "RTL"),
    ALWAYS_LAND(2f, "Land"),
    SMART_RTL(3f, "Smart RTL"),
    BRAKE(4f, "Loiter"),
    SMART_RTL_LAND(5f, "Smart RTL");

    companion object {
        /** Maps a raw FENCE_ACTION param value to an action, or null if unrecognised. */
        fun fromParam(value: Float?): FenceAction? =
            value?.let { v -> entries.firstOrNull { it.value == v } }
    }
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

