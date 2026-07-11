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
    val signalQuality: Int? = null
) {
    /** True when [currentDistanceM] is a usable reading inside the sensor's valid range. */
    val hasValidReading: Boolean
        get() = maxDistanceM > minDistanceM && currentDistanceM in minDistanceM..maxDistanceM
}

/**
 * Wraps MAVLink `OBSTACLE_DISTANCE` (ID 330) — a horizontal proximity scan split into angular
 * sectors. Raw MAVLink distances are centimetres; [distancesM] holds metres, with sectors that are
 * unknown (UINT16_MAX) or "no obstacle" (max_distance + 1) collapsed to [Float.NaN].
 */
data class ProximityData(
    val distancesM: List<Float>,
    /** Angular width of each sector in degrees (uses increment_f when non-zero). */
    val incrementDeg: Float,
    /** Angle of the 0-index sector relative to forward, degrees, clockwise-positive. */
    val angleOffsetDeg: Float,
    val minDistanceM: Float,
    val maxDistanceM: Float
) {
    /** Closest valid obstacle distance across all sectors, or null when nothing is detected. */
    val closestM: Float?
        get() = distancesM.asSequence()
            .filter { !it.isNaN() && it in minDistanceM..maxDistanceM }
            .minOrNull()
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
