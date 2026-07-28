package com.example.kftgcs.telemetry

import androidx.compose.ui.graphics.Color

/**
 * Reactive models for the CAN-hub distance/proximity telemetry that the flight controller relays as
 * standard MAVLink messages. Both are populated by [MavlinkTelemetryRepository] and surfaced through
 * [TelemetryState] (see [TelemetryState.terrainData] / [TelemetryState.proximityData]).
 *
 * Naming note: this file intentionally uses [ProximityData] rather than "ObstacleData" to avoid a
 * clash with the unrelated LatLng mission-obstacle model in `com.example.kftgcs.obstacle`.
 */

/**
 * How long a distance reading stays displayable after it was received. `DISTANCE_SENSOR` streams at
 * a few Hz, so anything older than this means the stream stopped (sensor lost the target, CAN hub
 * dropped out, link died) and the reading must be cleared rather than latched on screen.
 */
const val SENSOR_STALE_AFTER_MS = 1500L

/**
 * Wraps MAVLink `DISTANCE_SENSOR` (ID 132) — a single downward-facing rangefinder giving distance to
 * ground (terrain). Raw MAVLink distances are centimetres; all fields here are already in metres.
 */
data class TerrainData(
    val currentDistanceM: Float,
    val minDistanceM: Float,
    val maxDistanceM: Float,
    /** True when the sensor reports MAV_SENSOR_ROTATION_PITCH_270 (downward-facing). */
    val isDownwardFacing: Boolean,
    /** Sensor signal quality 1..100, or null when unknown/unset (raw 0). */
    val signalQuality: Int? = null,
    /** `System.currentTimeMillis()` when this reading was parsed; see [isStaleAt]. */
    val receivedAtMs: Long = System.currentTimeMillis()
) {
    /**
     * True when [currentDistanceM] is a usable reading strictly inside the sensor's valid range.
     * Bounds are exclusive: MAVLink's "no target" convention reports a distance at or beyond
     * `max_distance`, and a reading at or below `min_distance` is equally untrustworthy.
     */
    val hasValidReading: Boolean
        get() = maxDistanceM > minDistanceM &&
                currentDistanceM > minDistanceM &&
                currentDistanceM < maxDistanceM

    /** True when this reading is older than [maxAgeMs] and should no longer be displayed. */
    fun isStaleAt(nowMs: Long, maxAgeMs: Long = SENSOR_STALE_AFTER_MS): Boolean =
        nowMs - receivedAtMs > maxAgeMs
}

/**
 * Wraps MAVLink `DISTANCE_SENSOR` (ID 132) reported by the FORWARD-facing rangefinder
 * (orientation MAV_SENSOR_ROTATION_NONE / 0). The obstacle-avoidance hardware sends a single
 * forward distance on this message rather than the 360° sector scan of `OBSTACLE_DISTANCE` (330),
 * so this model wraps one reading. Raw MAVLink distances are centimetres; all fields here are metres.
 */
data class ProximityData(
    val currentDistanceM: Float,
    val minDistanceM: Float,
    val maxDistanceM: Float,
    /** Sensor signal quality 1..100, or null when unknown/unset (raw 0). */
    val signalQuality: Int? = null,
    /** `System.currentTimeMillis()` when this reading was parsed; see [isStaleAt]. */
    val receivedAtMs: Long = System.currentTimeMillis()
) {
    /**
     * True when [currentDistanceM] is a usable reading strictly inside the sensor's valid range.
     * Bounds are exclusive: the sensor signals "no target" by reporting a distance at or beyond
     * `max_distance`, which must NOT render as an obstacle sitting at full scale.
     */
    val hasValidReading: Boolean
        get() = maxDistanceM > minDistanceM &&
                currentDistanceM > minDistanceM &&
                currentDistanceM < maxDistanceM

    /** Forward obstacle distance in metres when the reading is valid, else null. */
    val forwardDistanceM: Float?
        get() = if (hasValidReading) currentDistanceM else null

    /** True when this reading is older than [maxAgeMs] and should no longer be displayed. */
    fun isStaleAt(nowMs: Long, maxAgeMs: Long = SENSOR_STALE_AFTER_MS): Boolean =
        nowMs - receivedAtMs > maxAgeMs
}

/**
 * Proximity-radar colour bands in metres. Hybrid source: seeded from ArduPilot's AVOID_DIST_MAX
 * (caution) / AVOID_MARGIN (critical) on connect, overridable by the pilot in SensorSettingsScreen.
 * Exposed reactively as `SharedViewModel.radarThresholds` and persisted via UserSettingsManager.
 */
data class RadarThresholds(
    val cautionM: Float,
    val criticalM: Float
)

/**
 * Maps a sector distance (metres) to the app's safe/caution/critical colour using the supplied
 * thresholds. Mirrors the green/yellow/red convention in ui.obstacle.ObstacleDetectionScreen.
 */
fun proximityColor(distanceM: Float, thresholds: RadarThresholds): Color = when {
    distanceM.isNaN() -> Color.Gray
    distanceM < thresholds.criticalM -> Color.Red
    distanceM < thresholds.cautionM -> Color.Yellow
    else -> Color.Green
}
