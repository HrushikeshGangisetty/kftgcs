package com.example.kftgcs.aquaculture.mission

import com.example.kftgcs.aquaculture.model.*
import kotlin.math.ceil
import kotlin.math.min

/** DEMO strategy, independent of biological formulas. Each load ends with a return for refill. */
class DemoMissionParameterPlanner(private val generator: BoundaryMissionGenerator = BoundaryMissionGenerator()) {
    fun plan(boundary: PondBoundary, input: AquacultureInput, feed: AquacultureFeedResult,
             altitudeMeters: Double, baseSpeed: Double, spacingMeters: Double,
             indentationMeters: Double = PondBoundaryInsetter.MIN_INDENTATION_METERS): AquacultureMissionPlan {
        require(baseSpeed.isFinite() && baseSpeed in 0.3..10.0) { "Base speed must be 0.3–10 m/s" }
        val pond = PondGeometry.normalized(boundary)
        val perimeter = PondGeometry.perimeterMeters(PondBoundaryInsetter.inset(pond, indentationMeters))
        // Placeholder average target of 1.5 kg per lap. Not a biological or hardware calibration.
        val totalPasses = maxOf(feed.requiredLoads, ceil(feed.feedPerSessionKg / 1.5 - 1e-10).toInt())
        require(totalPasses <= 100) { "supports at most 100 passes per session" }
        val quantities = (0 until feed.requiredLoads).map { load ->
            min(input.hopperCapacityKg, feed.feedPerSessionKg - load * input.hopperCapacityKg)
        }
        val passes = IntArray(quantities.size) { 1 }
        repeat(totalPasses - quantities.size) {
            val index = quantities.indices.maxBy { quantities[it] / passes[it] }
            passes[index]++
        }
        val loads = quantities.mapIndexed { index, kg ->
            val duration = kg / input.dischargeRateKgPerSecond / passes[index]
            // Feed changes speed even when a threshold leaves the pass count unchanged.
            val speed = min(baseSpeed / (1 + feed.feedPerSessionKg / 10), perimeter / duration)
            val parameters = BoundaryMissionParameters(passes[index], speed, duration, spacingMeters, altitudeMeters, indentationMeters)
            AquacultureLoadMission(index + 1, kg, generator.generate(pond, parameters))
        }
        require(loads.sumOf { it.mission.waypoints.size } <= 5000) { "supports at most 5000 waypoints per session" }
        return AquacultureMissionPlan(pond, feed, loads)
    }
}
