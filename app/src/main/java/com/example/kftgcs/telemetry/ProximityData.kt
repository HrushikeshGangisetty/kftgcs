package com.example.kftgcs.telemetry

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Reactive models for the Jiyi 24 GHz CAN radar telemetry that the flight controller relays as
 * standard MAVLink messages. Both are populated by [MavlinkTelemetryRepository] and surfaced through
 * [TelemetryState] (see [TelemetryState.terrainData] / [TelemetryState.proximityData]).
 *
 * ## Source of truth: the `jiyi_radar.lua` driver, NOT custom firmware
 *
 * The radar is decoded on the FC by an ArduPilot Lua script (`APM/scripts/jiyi_radar.lua`), which
 * feeds two scripting rangefinder backends via `handle_script_msg()`. ArduPilot then publishes them
 * as ordinary `DISTANCE_SENSOR` (132), split by orientation:
 *
 *   RNGFND1 / orientation 25 (PITCH_270, downward) -> terrain      -> [TerrainData]
 *   RNGFND2 / orientation  0 (NONE, forward)       -> obstacle     -> [ProximityData]
 *
 * This app previously read a modified-firmware stream whose conventions were different. The Lua
 * driver reports three things in ways a naive reader gets WRONG, and every field below exists to
 * decode them correctly:
 *
 * 1. **The forward radar never signals "no target" by going quiet or by reporting max range.**
 *    Silence would age RNGFND2 out to NoData, drag PRX1 to NoData and fail prearm, so the driver
 *    actively reports a SYNTHETIC IN-RANGE CLEAR at a fixed value near the top of the usable
 *    window (`clear_m = win_hi - max(0.3, win_hi * 0.03)`). Taken at face value that renders as a
 *    permanent obstacle parked at ~19.4 m. See [ObstacleWindow] and [ProximityData.isClear].
 *
 * 2. **The downward radar is the opposite: it is never given a synthetic in-range distance.**
 *    With no usable return — or when its enable switch (RC9) is low — it reports
 *    OUT-OF-RANGE-HIGH at `RNGFND1_MAX + 5 m`: fresh enough to stay out of NoData, but deliberately
 *    not "Good", so surface tracking stands down. See [TerrainData.isNoReturn].
 *
 * 3. **Absence of the message means the radar is DEAD, not clear.** The driver emits nothing at all
 *    for a sensor that has not produced a decodable CAN frame in 500 ms, so the backend falls to
 *    NoData and ArduPilot stops relaying it. A null [TerrainData] / [ProximityData] therefore means
 *    "radar offline" and must not be drawn as an all-clear.
 *
 * Naming note: this file intentionally uses [ProximityData] rather than "ObstacleData" to avoid a
 * clash with the unrelated LatLng mission-obstacle model in `com.example.kftgcs.obstacle`.
 */

/**
 * How long a distance reading stays displayable after it was received. The Lua driver refreshes
 * every live backend on every 20 ms cycle and the FC is asked for `DISTANCE_SENSOR` at 8 Hz
 * (~4 Hz per instance), so anything older than this means the stream stopped — the radar went off
 * the CAN bus, or the link died — and the reading must be cleared rather than latched on screen.
 */
const val SENSOR_STALE_AFTER_MS = 1500L

/**
 * Tolerance for recognising the forward radar's synthetic clear. The driver emits one exact
 * constant, which survives to us only through MAVLink's centimetre quantisation, so a 2 cm band is
 * ample. It is kept deliberately tight: a real target that genuinely sits within 2 cm of the clear
 * value is indistinguishable from a clear, and that ambiguity band should be as narrow as possible.
 */
private const val CLEAR_MATCH_TOLERANCE_M = 0.02f

/**
 * Margin used to recognise the terrain radar's OUT-OF-RANGE-HIGH sentinel (`RNGFND1_MAX + 5 m`).
 * Only needs to separate the sentinel from a reading just above max; 4 m leaves plenty of room.
 */
private const val NO_RETURN_SENTINEL_MARGIN_M = 4f

/**
 * Wraps MAVLink `DISTANCE_SENSOR` (ID 132) from the DOWNWARD-facing radar
 * (MAV_SENSOR_ROTATION_PITCH_270 / 25) — distance to ground. Raw MAVLink distances are centimetres;
 * all fields here are already in metres.
 *
 * The Lua driver applies a 5-wide median and passes real readings through UNCLAMPED (its
 * `clamp_to_window` is false for this sensor), so a genuine return may legitimately fall below
 * `RNGFND1_MIN` — that is a real close-to-ground measurement, not a fault, and [isBelowMin]
 * separates it from the [isNoReturn] sentinel rather than lumping both into one blank readout.
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
     * Bounds are exclusive: the driver's "no usable return" convention reports a distance well
     * beyond `max_distance`, and a reading at or below `min_distance` is outside the rated window.
     */
    val hasValidReading: Boolean
        get() = maxDistanceM > minDistanceM &&
                currentDistanceM > minDistanceM &&
                currentDistanceM < maxDistanceM

    /**
     * True when the driver is reporting OUT-OF-RANGE-HIGH: it is alive but has no usable return,
     * either because nothing was decoded for 400 ms or because the terrain enable switch (RC9) is
     * low and the sensor has stood down. Deliberately NOT a fabricated clearance — the pilot must
     * see "no return", never a made-up height above ground.
     */
    val isNoReturn: Boolean
        get() = maxDistanceM > 0f &&
                currentDistanceM >= maxDistanceM + NO_RETURN_SENTINEL_MARGIN_M

    /**
     * True for a real return that sits at or below the rated minimum — the ground is closer than
     * `RNGFND1_MIN`. Worth showing (as a floor, not a measurement) instead of blanking the gauge
     * during the last metre of a landing.
     */
    val isBelowMin: Boolean
        get() = !isNoReturn && maxDistanceM > minDistanceM && currentDistanceM <= minDistanceM

    /** True when this reading is older than [maxAgeMs] and should no longer be displayed. */
    fun isStaleAt(nowMs: Long, maxAgeMs: Long = SENSOR_STALE_AFTER_MS): Boolean =
        nowMs - receivedAtMs > maxAgeMs
}

/**
 * The forward radar's usable reporting window, reproducing `load_window()` in `jiyi_radar.lua`
 * exactly. Without it the app cannot tell the driver's synthetic clear from a real obstacle.
 *
 * The driver intersects RNGFND2's own min/max (which arrive in the `DISTANCE_SENSOR` message) with
 * `PRX1_MIN` / `PRX1_MAX` (which do not, and are read as parameters on connect), because PRX1 gates
 * proximity readings independently of the rangefinder window.
 *
 * @property winLoM  bottom of the usable window; real targets closer than this are reported AT
 *                   [clampLoM] rather than dropped, so PRX1 stays healthy.
 * @property winHiM  top of the usable window.
 * @property clearM  the synthetic "nothing detected" value the driver emits.
 */
data class ObstacleWindow(
    val winLoM: Float,
    val winHiM: Float,
    val clearM: Float
) {
    /** Lower clamp the driver applies to real readings (`win_lo + 0.05`). */
    val clampLoM: Float get() = winLoM + 0.05f
}

/**
 * Builds the forward radar's [ObstacleWindow] the same way `load_window()` does on the FC.
 *
 * [prxMinM] / [prxMaxM] come from the vehicle's `PRX1_MIN` / `PRX1_MAX`; pass null when they have
 * not been read yet. Omitting them is safe — the driver faults loudly if PRX1's window conflicts
 * with RNGFND2's, so in a correctly configured installation RNGFND2's own bounds already give the
 * right answer, and this only refines it.
 *
 * Returns null when the message's own bounds are unusable, in which case no clear can be inferred.
 */
fun obstacleWindowOf(
    minDistanceM: Float,
    maxDistanceM: Float,
    prxMinM: Float? = null,
    prxMaxM: Float? = null
): ObstacleWindow? {
    if (maxDistanceM <= 0f || maxDistanceM <= minDistanceM) return null

    var lo = minDistanceM
    var hi = maxDistanceM
    if (prxMinM != null && prxMinM > 0f && prxMinM > lo) lo = prxMinM
    if (prxMaxM != null && prxMaxM > 0f && prxMaxM < hi) hi = prxMaxM
    // The driver's own conflict fallback: an empty intersection means the PRX1 window is
    // misconfigured, and it reverts to RNGFND2's bounds after shouting about it.
    if (lo >= hi) {
        lo = minDistanceM
        hi = maxDistanceM
    }

    val margin = maxOf(0.3f, hi * 0.03f)
    var clear = hi - margin
    if (clear <= lo) clear = (lo + hi) / 2f
    return ObstacleWindow(winLoM = lo, winHiM = hi, clearM = clear)
}

/**
 * Wraps MAVLink `DISTANCE_SENSOR` (ID 132) from the FORWARD-facing radar
 * (MAV_SENSOR_ROTATION_NONE / 0) — obstacle distance ahead. The hardware reports a single forward
 * distance on this message rather than the 360° sector scan of `OBSTACLE_DISTANCE` (330), so this
 * model wraps one reading. Raw MAVLink distances are centimetres; all fields here are metres.
 *
 * [window] is what makes this readable under the Lua driver: with it, [isClear] separates the
 * driver's synthetic all-clear from a real return, and [isAtWindowFloor] flags a return that the
 * driver pinned to the bottom of the window rather than measured.
 */
data class ProximityData(
    val currentDistanceM: Float,
    val minDistanceM: Float,
    val maxDistanceM: Float,
    /** Sensor signal quality 1..100, or null when unknown/unset (raw 0). */
    val signalQuality: Int? = null,
    /** Reporting window reproduced from the FC driver; null when it could not be derived. */
    val window: ObstacleWindow? = null,
    /** `System.currentTimeMillis()` when this reading was parsed; see [isStaleAt]. */
    val receivedAtMs: Long = System.currentTimeMillis()
) {
    /**
     * True when [currentDistanceM] is inside the sensor's valid range. Bounds are exclusive.
     * Under the Lua driver this is true for essentially every frame from a live radar, including
     * the synthetic clear — so it says "the radar is reporting", not "there is an obstacle".
     * Use [forwardDistanceM] for the latter.
     */
    val hasValidReading: Boolean
        get() = maxDistanceM > minDistanceM &&
                currentDistanceM > minDistanceM &&
                currentDistanceM < maxDistanceM

    /**
     * True when this frame is the driver's synthetic "nothing detected" report rather than a
     * measurement: the radar is alive and the path ahead is clear.
     *
     * Falls back to the out-of-range convention when no [window] could be derived, so a vehicle
     * whose parameters have not been read yet still degrades to something sane rather than
     * inventing an obstacle.
     */
    val isClear: Boolean
        get() = window?.let { abs(currentDistanceM - it.clearM) <= CLEAR_MATCH_TOLERANCE_M }
            ?: !hasValidReading

    /**
     * Forward obstacle distance in metres when a real target is being reported, else null.
     * Null covers both "clear" and an unusable reading; callers that must tell those apart read
     * [isClear] (radar alive, nothing ahead) against a null [ProximityData] (radar offline).
     */
    val forwardDistanceM: Float?
        get() = if (hasValidReading && !isClear) currentDistanceM else null

    /**
     * True when a real target is being reported AT the bottom of the usable window. The driver
     * clamps anything nearer than `win_lo` up to `win_lo + 0.05` instead of dropping it, so the
     * obstacle is *at least* this close and possibly closer — the displayed number is a floor.
     */
    val isAtWindowFloor: Boolean
        get() {
            val w = window ?: return false
            return forwardDistanceM != null && currentDistanceM <= w.clampLoM + CLEAR_MATCH_TOLERANCE_M
        }

    /** True when this reading is older than [maxAgeMs] and should no longer be displayed. */
    fun isStaleAt(nowMs: Long, maxAgeMs: Long = SENSOR_STALE_AFTER_MS): Boolean =
        nowMs - receivedAtMs > maxAgeMs
}

/**
 * Enable-switch state for one radar, as announced by the Lua driver over `STATUSTEXT`
 * ("JIYI: obstacle ON", "JIYI: terrain OFF"). Each radar has its own RC channel that the pilot
 * flips; the switches do not stop the radar reporting, they govern whether ArduPilot may ACT on it.
 *
 * Worth surfacing because the terrain radar's switch changes what its readings MEAN: with RC9 low
 * the driver deliberately reports "no usable return" even with clear ground underneath, and without
 * this the gauge looks broken.
 */
data class RadarSwitchState(
    /** RC8 / proximity avoidance. Null until the driver has announced it. */
    val obstacleEnabled: Boolean? = null,
    /** RC9 / terrain surface tracking. Null until the driver has announced it. */
    val terrainEnabled: Boolean? = null
)

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
