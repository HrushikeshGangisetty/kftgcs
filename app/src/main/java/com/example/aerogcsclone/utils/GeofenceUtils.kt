package com.example.aerogcsclone.utils

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Utility class for generating polygon geofences around mission plans
 */
object GeofenceUtils {

    /**
     * Generates a polygon buffer around a list of waypoints
     * @param waypoints List of waypoints to create buffer around
     * @param bufferDistanceMeters Buffer distance in meters (default 5m)
     * @return List of LatLng points forming the buffer polygon that ALWAYS includes all waypoints
     */
    fun generatePolygonBuffer(waypoints: List<LatLng>, bufferDistanceMeters: Double = 5.0): List<LatLng> {
        if (waypoints.isEmpty()) return emptyList()

        // For a single point, create a circular buffer
        if (waypoints.size == 1) {
            return createCircularBuffer(waypoints.first(), bufferDistanceMeters)
        }

        // For two points, create a capsule shape
        if (waypoints.size == 2) {
            return createCapsuleBuffer(waypoints[0], waypoints[1], bufferDistanceMeters)
        }

        // For multiple points, create a bounding polygon with buffer
        // Use convex hull to get the outer boundary, then expand it
        val hull = convexHull(waypoints)
        if (hull.isEmpty()) return emptyList()

        // Create buffer around the hull - this ensures all points are covered
        // since convex hull by definition contains all points
        return createExpandedBuffer(hull, bufferDistanceMeters)
    }

    /**
     * Generates a square geofence around a list of waypoints
     * @param waypoints List of waypoints to create square around
     * @param bufferDistanceMeters Buffer distance in meters (default 5m)
     * @return List of 4 LatLng points forming a square that includes all waypoints
     */
    fun generateSquareGeofence(waypoints: List<LatLng>, bufferDistanceMeters: Double = 5.0): List<LatLng> {
        if (waypoints.isEmpty()) return emptyList()

        // Find bounding box
        val minLat = waypoints.minOf { it.latitude }
        val maxLat = waypoints.maxOf { it.latitude }
        val minLon = waypoints.minOf { it.longitude }
        val maxLon = waypoints.maxOf { it.longitude }

        val earthRadius = 6371000.0
        val avgLat = (minLat + maxLat) / 2

        // Convert buffer distance to degrees
        val latBuffer = (bufferDistanceMeters / earthRadius) * 180 / PI
        val lonBuffer = (bufferDistanceMeters / (earthRadius * cos(avgLat * PI / 180))) * 180 / PI

        // Create square with buffer
        return listOf(
            LatLng(minLat - latBuffer, minLon - lonBuffer), // Bottom-left
            LatLng(maxLat + latBuffer, minLon - lonBuffer), // Top-left
            LatLng(maxLat + latBuffer, maxLon + lonBuffer), // Top-right
            LatLng(minLat - latBuffer, maxLon + lonBuffer)  // Bottom-right
        )
    }

    /**
     * Creates a circular buffer around a single point
     */
    private fun createCircularBuffer(center: LatLng, radiusMeters: Double, numPoints: Int = 32): List<LatLng> {
        val points = mutableListOf<LatLng>()
        val earthRadius = 6371000.0 // Earth's radius in meters

        for (i in 0 until numPoints) {
            val angle = 2 * PI * i / numPoints
            val lat = center.latitude + (radiusMeters / earthRadius) * cos(angle) * 180 / PI
            val lon = center.longitude + (radiusMeters / (earthRadius * cos(center.latitude * PI / 180))) * sin(angle) * 180 / PI
            points.add(LatLng(lat, lon))
        }

        return points
    }

    /**
     * Creates a capsule-shaped buffer around two points
     */
    private fun createCapsuleBuffer(p1: LatLng, p2: LatLng, bufferDistanceMeters: Double, numPoints: Int = 16): List<LatLng> {
        val points = mutableListOf<LatLng>()
        val earthRadius = 6371000.0

        // Calculate perpendicular direction
        val dx = p2.longitude - p1.longitude
        val dy = p2.latitude - p1.latitude
        val length = sqrt(dx * dx + dy * dy)

        if (length < 1e-10) {
            // Points are the same, just create a circle
            return createCircularBuffer(p1, bufferDistanceMeters, numPoints)
        }

        // Normalized perpendicular vector
        val perpX = -dy / length
        val perpY = dx / length

        // Convert buffer distance to degrees
        val avgLat = (p1.latitude + p2.latitude) / 2
        val latOffset = (bufferDistanceMeters / earthRadius) * 180 / PI
        val lonOffset = (bufferDistanceMeters / (earthRadius * cos(avgLat * PI / 180))) * 180 / PI

        // Create semicircle around first point
        for (i in 0..numPoints/2) {
            val angle = -PI / 2 + PI * i / (numPoints/2)
            val dirX = perpX * cos(angle) - (dx/length) * sin(angle)
            val dirY = perpY * cos(angle) - (dy/length) * sin(angle)
            val lat = p1.latitude + dirY * latOffset
            val lon = p1.longitude + dirX * lonOffset
            points.add(LatLng(lat, lon))
        }

        // Create semicircle around second point
        for (i in 0..numPoints/2) {
            val angle = PI / 2 + PI * i / (numPoints/2)
            val dirX = perpX * cos(angle) + (dx/length) * sin(angle)
            val dirY = perpY * cos(angle) + (dy/length) * sin(angle)
            val lat = p2.latitude + dirY * latOffset
            val lon = p2.longitude + dirX * lonOffset
            points.add(LatLng(lat, lon))
        }

        return points
    }

    /**
     * Creates an expanded buffer around a convex hull
     * This ensures the buffer extends outward from all hull vertices
     */
    private fun createExpandedBuffer(hull: List<LatLng>, bufferDistanceMeters: Double): List<LatLng> {
        if (hull.size < 3) return hull

        val bufferedPoints = mutableListOf<LatLng>()
        val earthRadius = 6371000.0
        val centroid = calculateCentroid(hull)

        for (i in hull.indices) {
            val current = hull[i]

            // Calculate direction from centroid to current point (outward direction)
            val toCentroidLat = current.latitude - centroid.latitude
            val toCentroidLon = current.longitude - centroid.longitude
            val distToCentroid = sqrt(toCentroidLat * toCentroidLat + toCentroidLon * toCentroidLon)

            if (distToCentroid < 1e-10) {
                // Point is at centroid, use a default direction
                bufferedPoints.add(current)
                continue
            }

            // Normalize the outward direction
            val outwardLat = toCentroidLat / distToCentroid
            val outwardLon = toCentroidLon / distToCentroid

            // Apply buffer distance in the outward direction
            val avgLat = current.latitude
            val offsetLat = outwardLat * (bufferDistanceMeters / earthRadius) * 180 / PI
            val offsetLon = outwardLon * (bufferDistanceMeters / (earthRadius * cos(avgLat * PI / 180))) * 180 / PI

            bufferedPoints.add(LatLng(
                current.latitude + offsetLat,
                current.longitude + offsetLon
            ))
        }

        return bufferedPoints
    }

    /**
     * Calculate the centroid of a list of points
     */
    private fun calculateCentroid(points: List<LatLng>): LatLng {
        if (points.isEmpty()) return LatLng(0.0, 0.0)
        val avgLat = points.map { it.latitude }.average()
        val avgLon = points.map { it.longitude }.average()
        return LatLng(avgLat, avgLon)
    }

    /**
     * Computes convex hull using Graham scan algorithm
     * The convex hull by definition contains ALL input points
     */
    private fun convexHull(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points

        val sorted = points.sortedWith { a, b ->
            when {
                a.latitude < b.latitude -> -1
                a.latitude > b.latitude -> 1
                else -> a.longitude.compareTo(b.longitude)
            }
        }

        // Build lower hull
        val lower = mutableListOf<LatLng>()
        for (point in sorted) {
            while (lower.size >= 2 && crossProduct(lower[lower.size - 2], lower[lower.size - 1], point) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(point)
        }

        // Build upper hull
        val upper = mutableListOf<LatLng>()
        for (point in sorted.reversed()) {
            while (upper.size >= 2 && crossProduct(upper[upper.size - 2], upper[upper.size - 1], point) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(point)
        }

        // Remove last point of each half because it's repeated
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)

        return lower + upper
    }

    /**
     * Calculates cross product for convex hull algorithm
     */
    private fun crossProduct(o: LatLng, a: LatLng, b: LatLng): Double {
        return (a.latitude - o.latitude) * (b.longitude - o.longitude) -
               (a.longitude - o.longitude) * (b.latitude - o.latitude)
    }
}
