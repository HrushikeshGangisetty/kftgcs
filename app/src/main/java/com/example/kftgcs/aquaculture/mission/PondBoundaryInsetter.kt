package com.example.kftgcs.aquaculture.mission

import com.example.kftgcs.aquaculture.model.PondBoundary
import kotlin.math.*

/** Offset edge lines inward in local metres; reject collapsed or intersecting contours.
 * Unlike centroid shrinking, this holds clearance from every edge, including concave corners.
 */
object PondBoundaryInsetter {
    const val MIN_INDENTATION_METERS = 3.0

    fun inset(boundary: PondBoundary, indentationMeters: Double): PondBoundary {
        require(indentationMeters.isFinite() && indentationMeters in MIN_INDENTATION_METERS..50.0) {
            "Boundary indentation must be 3–50 m"
        }
        val pond = PondGeometry.normalized(boundary)
        val points = PondGeometry.projected(pond.vertices)
        val signedArea = points.indices.sumOf { cross(points[it], points[(it + 1) % points.size]) }
        val orientation = if (signedArea > 0) 1.0 else -1.0
        val meanScale = cos(Math.toRadians(pond.vertices.map { it.latitude }.average()))
        val smallestScale = pond.vertices.minOf { cos(Math.toRadians(it.latitude)) }
        // Account for latitude scale variation and coordinate quantization in MissionItemInt.
        val offset = indentationMeters / min(1.0, smallestScale / meanScale) * 1.001 + 0.02
        val inset = points.indices.map { index ->
            val previous = points[(index - 1 + points.size) % points.size]
            val vertex = points[index]
            val next = points[(index + 1) % points.size]
            val incoming = unit(subtract(vertex, previous))
            val outgoing = unit(subtract(next, vertex))
            val n1 = -incoming.second * orientation to incoming.first * orientation
            val n2 = -outgoing.second * orientation to outgoing.first * orientation
            val firstLine = add(vertex, multiply(n1, offset))
            val secondLine = add(vertex, multiply(n2, offset))
            val denominator = cross(incoming, outgoing)
            if (abs(denominator) < 1e-8) {
                require(incoming.first * outgoing.first + incoming.second * outgoing.second > 0) {
                    "Pond corners are too sharp for the requested indentation"
                }
                firstLine
            } else {
                val t = cross(subtract(secondLine, firstLine), outgoing) / denominator
                add(firstLine, multiply(incoming, t))
            }
        }
        val result = try {
            PondGeometry.normalized(PondBoundary(inset.map { PondGeometry.unproject(it, pond.vertices) }))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Pond is too narrow or complex for a ${indentationMeters.toInt()} m indentation")
        }
        val resultArea = inset.indices.sumOf { cross(inset[it], inset[(it + 1) % inset.size]) }
        require(resultArea * signedArea > 0 && abs(resultArea) < abs(signedArea)) {
            "No usable inner boundary at the requested indentation"
        }
        // Validate entire straight segments, not just their sampled waypoints.
        inset.indices.forEach { i ->
            val a = inset[i]
            val b = inset[(i + 1) % inset.size]
            require(inside(a, points)) { "Indented path would leave the pond; reduce indentation or adjust boundary" }
            points.indices.forEach { j ->
                val c = points[j]
                val d = points[(j + 1) % points.size]
                val clearance = if (PondGeometry.intersects(a, b, c, d)) 0.0 else minOf(
                    pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d),
                    pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b))
                require(clearance >= offset - 1e-6) {
                    "Pond is too narrow for the requested indentation; adjust its boundary"
                }
            }
        }
        return result
    }

    private fun inside(point: Pair<Double, Double>, ring: List<Pair<Double, Double>>): Boolean {
        var inside = false
        ring.indices.forEach { i ->
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if ((a.second > point.second) != (b.second > point.second) &&
                point.first < (b.first - a.first) * (point.second - a.second) / (b.second - a.second) + a.first) inside = !inside
        }
        return inside
    }

    private fun pointSegmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val edge = subtract(b, a)
        val delta = subtract(p, a)
        val t = ((delta.first * edge.first + delta.second * edge.second) /
            (edge.first * edge.first + edge.second * edge.second)).coerceIn(0.0, 1.0)
        val closest = add(a, multiply(edge, t))
        return hypot(p.first - closest.first, p.second - closest.second)
    }

    private fun add(a: Pair<Double, Double>, b: Pair<Double, Double>) = a.first + b.first to a.second + b.second
    private fun subtract(a: Pair<Double, Double>, b: Pair<Double, Double>) = a.first - b.first to a.second - b.second
    private fun multiply(a: Pair<Double, Double>, scalar: Double) = a.first * scalar to a.second * scalar
    private fun cross(a: Pair<Double, Double>, b: Pair<Double, Double>) = a.first * b.second - a.second * b.first
    private fun unit(a: Pair<Double, Double>): Pair<Double, Double> {
        val length = hypot(a.first, a.second)
        return a.first / length to a.second / length
    }
}
