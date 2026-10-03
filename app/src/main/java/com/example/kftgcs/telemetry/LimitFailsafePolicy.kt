package com.example.kftgcs.telemetry

import com.example.kftgcs.fence.FenceAction

/** Shared rules for the GCS altitude and home-distance monitors. */
internal object LimitFailsafePolicy {
    fun validLimit(value: Float?): Boolean = value != null && value.isFinite() && value > 0f

    // Compare against the caller's action boundary, independently of warning/stopping lead.
    fun reached(value: Double, limit: Float): Boolean =
        validLimit(limit) && value.isFinite() && value >= limit.toDouble()

    fun validRangeMargin(radius: Float?, margin: Float?): Boolean =
        radius != null && validLimit(radius) && margin != null && margin.isFinite() && margin >= 0f && margin < radius

    /** Invalid/unread margins retain protection at the actual radius, without guessing a buffer. */
    fun rangeActionThreshold(radius: Float?, margin: Float?): Float? {
        val confirmedRadius = radius?.takeIf { validLimit(it) } ?: return null
        val confirmedMargin = margin?.takeIf { validRangeMargin(confirmedRadius, it) } ?: 0f
        return confirmedRadius - confirmedMargin
    }

    fun freshPosition(stampMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean =
        stampMs != null && stampMs <= nowMs && nowMs - stampMs <= maxAgeMs

    // Keep recovery reachable even for a radius/ceiling smaller than the normal margin.
    fun rearmThreshold(limit: Float, normalMargin: Float): Float =
        limit - minOf(normalMargin, limit * 0.2f)

    fun actionModes(action: FenceAction): List<UInt> = when (action) {
        FenceAction.REPORT_ONLY -> emptyList()
        FenceAction.RTL -> listOf(MavMode.RTL, MavMode.LAND)
        FenceAction.ALWAYS_LAND -> listOf(MavMode.LAND)
        FenceAction.SMART_RTL -> listOf(MavMode.SMART_RTL, MavMode.RTL, MavMode.LAND)
        FenceAction.BRAKE -> listOf(MavMode.BRAKE, MavMode.LAND)
        FenceAction.SMART_RTL_LAND -> listOf(MavMode.SMART_RTL, MavMode.LAND)
    }
}
