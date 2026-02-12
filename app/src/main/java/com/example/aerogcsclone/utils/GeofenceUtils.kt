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
     * Creates an expanded buffer around a convex hull using vertex normal offset.
     * This uses the bisector angle method which provides more accurate buffer distances
     * at polygon corners, ensuring the buffer is at least bufferDistanceMeters from all edges.
     *
     * Algorithm:
     * 1. For each vertex, calculate the bisector angle between the two adjacent edges
     * 2. Calculate the offset distance using miter join formula: d / cos(angle/2)
     * 3. Move the vertex outward along the bisector by the calculated distance
     *
     * This ensures that even at sharp corners, the inner polygon is at least
     * bufferDistanceMeters away from all points on the original polygon.
     */
    private fun createExpandedBuffer(hull: List<LatLng>, bufferDistanceMeters: Double): List<LatLng> {
        if (hull.size < 3) return hull

        val bufferedPoints = mutableListOf<LatLng>()
        val earthRadius = 6371000.0
        val n = hull.size

        for (i in 0 until n) {
            val prev = hull[(i - 1 + n) % n]
            val current = hull[i]
            val next = hull[(i + 1) % n]

            // Calculate edge vectors (in lat/lon space, scaled for approximate meters)
            val avgLat = current.latitude
            val lonScale = cos(avgLat * PI / 180)

            // Edge from prev to current
            val e1Lat = current.latitude - prev.latitude
            val e1Lon = (current.longitude - prev.longitude) * lonScale
            val e1Len = sqrt(e1Lat * e1Lat + e1Lon * e1Lon)

            // Edge from current to next
            val e2Lat = next.latitude - current.latitude
            val e2Lon = (next.longitude - current.longitude) * lonScale
            val e2Len = sqrt(e2Lat * e2Lat + e2Lon * e2Lon)

            if (e1Len < 1e-10 || e2Len < 1e-10) {
                // Degenerate case - just use centroid-based offset
                val centroid = calculateCentroid(hull)
                val toCentroidLat = current.latitude - centroid.latitude
                val toCentroidLon = current.longitude - centroid.longitude
                val distToCentroid = sqrt(toCentroidLat * toCentroidLat + toCentroidLon * toCentroidLon)

                if (distToCentroid > 1e-10) {
                    val outwardLat = toCentroidLat / distToCentroid
                    val outwardLon = toCentroidLon / distToCentroid
                    val offsetLat = outwardLat * (bufferDistanceMeters / earthRadius) * 180 / PI
                    val offsetLon = outwardLon * (bufferDistanceMeters / (earthRadius * lonScale)) * 180 / PI
                    bufferedPoints.add(LatLng(current.latitude + offsetLat, current.longitude + offsetLon))
                } else {
                    bufferedPoints.add(current)
                }
                continue
            }

            // Normalize edge vectors
            val e1NormLat = e1Lat / e1Len
            val e1NormLon = e1Lon / e1Len
            val e2NormLat = e2Lat / e2Len
            val e2NormLon = e2Lon / e2Len

            // Calculate outward normals (perpendicular to edges, pointing outward for CCW polygon)
            // For edge 1: normal is (-e1NormLon, e1NormLat) or (e1NormLon, -e1NormLat) depending on orientation
            // We want outward normals, so we use the right-hand perpendicular for CCW
            val n1Lat = -e1NormLon / lonScale  // Adjust back for lon scaling
            val n1Lon = e1NormLat * lonScale

            val n2Lat = -e2NormLon / lonScale
            val n2Lon = e2NormLat * lonScale

            // Bisector direction is the sum of the two normals (normalized)
            val bisectLat = n1Lat + n2Lat
            val bisectLon = n1Lon + n2Lon
            val bisectLen = sqrt(bisectLat * bisectLat + bisectLon * bisectLon)

            if (bisectLen < 1e-10) {
                // Normals are opposite (180° corner) - use edge normal
                val offsetLat = n1Lat * (bufferDistanceMeters / earthRadius) * 180 / PI
                val offsetLon = n1Lon * (bufferDistanceMeters / earthRadius) * 180 / PI
                bufferedPoints.add(LatLng(current.latitude + offsetLat, current.longitude + offsetLon))
                continue
            }

            // Normalized bisector
            val bisectNormLat = bisectLat / bisectLen
            val bisectNormLon = bisectLon / bisectLen

            // Calculate the angle between the two edge normals (for miter calculation)
            val dotProduct = n1Lat * n2Lat + n1Lon * n2Lon
            val cosHalfAngle = sqrt((1 + dotProduct.coerceIn(-1.0, 1.0)) / 2)

            // Miter offset distance: buffer / cos(angle/2)
            // Clamp the miter factor to prevent extreme offsets at sharp corners
            val miterFactor = (1.0 / cosHalfAngle.coerceAtLeast(0.3)).coerceAtMost(3.0)
            val miterDistance = bufferDistanceMeters * miterFactor

            // Apply offset in bisector direction
            val offsetLat = bisectNormLat * (miterDistance / earthRadius) * 180 / PI
            val offsetLon = bisectNormLon * (miterDistance / (earthRadius * lonScale)) * 180 / PI

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

    /**
     * Calculate the Haversine distance between two points in meters
     * This is the most accurate distance calculation for geographic coordinates
     */
    fun haversineDistance(p1: LatLng, p2: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        val lat1Rad = p1.latitude * PI / 180
        val lat2Rad = p2.latitude * PI / 180
        val deltaLat = (p2.latitude - p1.latitude) * PI / 180
        val deltaLon = (p2.longitude - p1.longitude) * PI / 180

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2) * sin(deltaLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * Calculate bearing from point p1 to point p2 in degrees (0-360)
     * Matches Mission Planner's GetBearing function
     */
    private fun getBearing(p1: LatLng, p2: LatLng): Double {
        val lat1Rad = p1.latitude * PI / 180
        val lat2Rad = p2.latitude * PI / 180
        val deltaLon = (p2.longitude - p1.longitude) * PI / 180

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

        var bearing = atan2(y, x) * 180 / PI
        if (bearing < 0) bearing += 360
        return bearing
    }

    /**
     * Calculate the minimum distance from a point to the nearest edge of a polygon (in meters)
     * Uses Mission Planner's cross-track distance formula for accurate perpendicular distance calculation.
     *
     * Algorithm based on Mission Planner:
     * 1. For each fence segment (line from point A to point B):
     *    - Calculate the distance from lineStart to lineEnd (lineDist)
     *    - Calculate the distance from lineStart to drone location (distToLocation)
     *    - Calculate the bearing from lineStart to drone (bearToLocation)
     *    - Calculate the bearing from lineStart to lineEnd (lineBear)
     *    - Calculate the angle difference
     *    - Calculate alongLine = cos(angle) * distToLocation (projection along line)
     *    - If alongLine is within the segment bounds:
     *      - Cross-track distance = sin(angle) * distToLocation
     * 2. Also check distance to each vertex (corner distance check)
     * 3. Return the minimum of all distances
     */
    fun distanceToPolygonEdge(point: LatLng, polygon: List<LatLng>): Double {
        if (polygon.size < 2) return Double.MAX_VALUE

        var minDistance = Double.MAX_VALUE
        val n = polygon.size

        // Step 1: Cross-Track Distance Calculation for each fence segment
        // This calculates the perpendicular distance from the drone to each segment
        for (i in 0 until n) {
            val lineStart = polygon[i]
            val lineEnd = polygon[(i + 1) % n]

            // Calculate distances and bearings (Mission Planner style)
            val lineDist = haversineDistance(lineStart, lineEnd)
            val distToLocation = haversineDistance(lineStart, point)
            val bearToLocation = getBearing(lineStart, point)
            val lineBear = getBearing(lineStart, lineEnd)

            // Calculate angle difference (normalize to 0-360)
            var angle = bearToLocation - lineBear
            if (angle < 0) angle += 360

            // Convert angle to radians for trig functions
            val angleRad = angle * PI / 180

            // Calculate projection along the line
            val alongLine = cos(angleRad) * distToLocation

            // Check if perpendicular projection falls within the line segment
            if (alongLine >= 0 && alongLine <= lineDist) {
                // Cross-track distance calculation
                val crossTrackDist = abs(sin(angleRad) * distToLocation)
                if (crossTrackDist < minDistance) {
                    minDistance = crossTrackDist
                }
            }
        }

        // Step 2: Corner Distance Check - check distance to each vertex
        // This is important for corners where the perpendicular doesn't hit any segment
        for (i in 0 until n) {
            val vertexDist = haversineDistance(point, polygon[i])
            if (vertexDist < minDistance) {
                minDistance = vertexDist
            }
        }

        return minDistance
    }

    /**
     * Check if a point is inside a polygon using ray casting algorithm
     * This is equivalent to Mission Planner's PolygonTools.isInside function
     */
    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            if (((yi > point.latitude) != (yj > point.latitude)) &&
                (point.longitude < (xj - xi) * (point.latitude - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    /**
     * Complete geofence check that matches Mission Planner's behavior:
     * - Returns 0 if breach (outside inclusion polygon or inside exclusion polygon)
     * - Returns the distance to the nearest fence edge if inside
     *
     * For an INCLUSION polygon (which is what we use):
     * - If drone is OUTSIDE the polygon = BREACH (return 0)
     * - If drone is INSIDE, return the distance to the nearest edge
     */
    fun checkGeofenceDistance(point: LatLng, polygon: List<LatLng>): Double {
        if (polygon.size < 3) return Double.MAX_VALUE

        // Check if inside the inclusion polygon
        val isInside = isPointInPolygon(point, polygon)

        // If outside an INCLUSION polygon = BREACH
        if (!isInside) {
            return 0.0
        }

        // If inside, return the distance to the nearest edge
        return distanceToPolygonEdge(point, polygon)
    }

    /**
     * Scale an existing polygon outward or inward by a specified delta distance.
     * Positive deltaMeters expands the polygon, negative contracts it.
     * This preserves the polygon shape while changing its size.
     *
     * @param polygon The existing polygon to scale
     * @param deltaMeters The distance change in meters (positive = expand, negative = contract)
     * @return Scaled polygon
     */
    fun scalePolygon(polygon: List<LatLng>, deltaMeters: Double): List<LatLng> {
        if (polygon.size < 3) return polygon
        if (deltaMeters == 0.0) return polygon

        val earthRadius = 6371000.0
        val scaledPoints = mutableListOf<LatLng>()

        // Calculate centroid
        val centroid = calculateCentroid(polygon)

        for (i in polygon.indices) {
            val current = polygon[i]

            // Calculate direction from centroid to current point (outward direction)
            val toCentroidLat = current.latitude - centroid.latitude
            val toCentroidLon = current.longitude - centroid.longitude
            val distToCentroid = sqrt(toCentroidLat * toCentroidLat + toCentroidLon * toCentroidLon)

            if (distToCentroid < 1e-10) {
                // Point is at centroid, can't determine direction
                scaledPoints.add(current)
                continue
            }

            // Normalize the outward direction
            val outwardLat = toCentroidLat / distToCentroid
            val outwardLon = toCentroidLon / distToCentroid

            // Apply delta distance in the outward direction
            val avgLat = current.latitude
            val offsetLat = outwardLat * (deltaMeters / earthRadius) * 180 / PI
            val offsetLon = outwardLon * (deltaMeters / (earthRadius * cos(avgLat * PI / 180))) * 180 / PI

            scaledPoints.add(LatLng(
                current.latitude + offsetLat,
                current.longitude + offsetLon
            ))
        }

        return scaledPoints
    }

    /**
     * Calculate the centroid of a polygon (public version)
     */
    fun getCentroid(points: List<LatLng>): LatLng {
        return calculateCentroid(points)
    }
}
