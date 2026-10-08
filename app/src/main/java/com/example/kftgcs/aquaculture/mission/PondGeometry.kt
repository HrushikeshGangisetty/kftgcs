package com.example.kftgcs.aquaculture.mission

import com.example.kftgcs.aquaculture.model.PondBoundary
import com.example.kftgcs.aquaculture.model.PondPoint
import kotlin.math.*

/** Local planar polygon checks/area; geodesic edge distances. Suitable for small demo ponds. */
object PondGeometry {
    private const val EARTH_RADIUS = 6371009.0

    fun distance(a: PondPoint, b: PondPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) *
            sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun normalized(boundary: PondBoundary): PondBoundary {
        val original = boundary.vertices
        val points = if (original.size > 1 && original.first() == original.last()) original.dropLast(1) else original
        require(points.size in 3..200 && points.distinct().size == points.size) { "Draw 3–200 distinct pond vertices" }
        require(points.maxOf { it.longitude } - points.minOf { it.longitude } < 1.0) { "ponds cannot cross the date line or span more than 1 degree" }
        val xy = projected(points)
        for (i in points.indices) {
            require(distance(points[i], points[(i + 1) % points.size]) in 1.0..5000.0) { "Pond edges must be 1–5000 m long" }
            for (j in i + 1 until points.size) {
                if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
                require(!intersects(xy[i], xy[(i + 1) % xy.size], xy[j], xy[(j + 1) % xy.size])) { "Pond boundary must not cross or touch itself" }
            }
        }
        require(areaSquareMeters(PondBoundary(points)) >= 1.0) { "Pond boundary has no usable area" }
        return PondBoundary(points.toList())
    }

    fun perimeterMeters(boundary: PondBoundary): Double = boundary.vertices.indices.sumOf {
        distance(boundary.vertices[it], boundary.vertices[(it + 1) % boundary.vertices.size])
    }

    fun areaSquareMeters(boundary: PondBoundary): Double {
        if (boundary.vertices.size < 3) return 0.0
        val xy = projected(boundary.vertices)
        return abs(xy.indices.sumOf { i ->
            val next = xy[(i + 1) % xy.size]
            xy[i].first * next.second - next.first * xy[i].second
        }) / 2
    }

    internal fun projected(points: List<PondPoint>): List<Pair<Double, Double>> {
        val origin = points.first()
        val scale = cos(Math.toRadians(points.map { it.latitude }.average()))
        return points.map { EARTH_RADIUS * Math.toRadians(it.longitude - origin.longitude) * scale to
            EARTH_RADIUS * Math.toRadians(it.latitude - origin.latitude) }
    }

    internal fun unproject(point: Pair<Double, Double>, reference: List<PondPoint>): PondPoint {
        val origin = reference.first()
        val scale = cos(Math.toRadians(reference.map { it.latitude }.average()))
        return PondPoint(origin.latitude + Math.toDegrees(point.second / EARTH_RADIUS),
            origin.longitude + Math.toDegrees(point.first / (EARTH_RADIUS * scale)))
    }

    internal fun intersects(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
        fun cross(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>) =
            (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
        fun onSegment(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>) =
            abs(cross(p, q, r)) < 1e-6 && r.first >= min(p.first, q.first) - 1e-6 &&
                r.first <= max(p.first, q.first) + 1e-6 && r.second >= min(p.second, q.second) - 1e-6 &&
                r.second <= max(p.second, q.second) + 1e-6
        return (cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0) ||
            onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)
    }
}
