package com.example.kftgcs.aquaculture.mission

import com.example.kftgcs.aquaculture.model.*
import kotlin.math.ceil

/** Samples the inset boundary contour in operator order, including its closing edge, on every lap. */
class BoundaryMissionGenerator {
    fun generate(boundary: PondBoundary, parameters: BoundaryMissionParameters): BoundaryMission {
        val pond = PondBoundaryInsetter.inset(boundary, parameters.indentationMeters)
        require(parameters.passes in 1..100) { "supports 1–100 boundary passes" }
        require(parameters.targetSpeedMetersPerSecond.isFinite() && parameters.targetSpeedMetersPerSecond in 0.3..10.0) { "Mission speed must be 0.3–10 m/s" }
        require(parameters.waypointSpacingMeters.isFinite() && parameters.waypointSpacingMeters in 2.0..100.0) { "Waypoint spacing must be 2–100 m" }
        require(parameters.altitudeMeters.isFinite() && parameters.altitudeMeters in 2.0..30.0) { "altitude must be 2–30 m" }
        require(parameters.feedReleaseSecondsPerPass.isFinite() && parameters.feedReleaseSecondsPerPass >= 0) { "Invalid feed release duration" }
        val vertices = pond.vertices
        val samples = vertices.indices.map {
            ceil(PondGeometry.distance(vertices[it], vertices[(it + 1) % vertices.size]) / parameters.waypointSpacingMeters).toInt()
        }
        require((samples.sum().toLong() + 1) * parameters.passes <= 2000) { "Too many waypoints; increase spacing or reduce feed/passes (2000 per load maximum)" }
        val lap = buildList {
            vertices.indices.forEach { edge ->
                val a = vertices[edge]
                val b = vertices[(edge + 1) % vertices.size]
                repeat(samples[edge]) { sample ->
                    val t = sample.toDouble() / samples[edge]
                    add(PondPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t))
                }
            }
            add(vertices.first())
        }
        val perimeter = lap.zipWithNext().sumOf { (a, b) -> PondGeometry.distance(a, b) }
        require(parameters.feedReleaseSecondsPerPass <= perimeter / parameters.targetSpeedMetersPerSecond + 1e-5) { "Feed release duration exceeds boundary lap time" }
        return BoundaryMission(
            (0 until parameters.passes).flatMap { pass -> lap.map { BoundaryWaypoint(it, pass) } },
            parameters, perimeter, perimeter * parameters.passes,
            perimeter * parameters.passes / parameters.targetSpeedMetersPerSecond, pond
        )
    }
}
