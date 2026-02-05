package com.example.aerogcsclone.utils

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Utility class for obstacle avoidance path planning
 * Provides functions to detect path-obstacle intersections and generate avoidance waypoints
 */
object ObstaclePathPlanner {

    private const val TAG = "ObstaclePathPlanner"

    // Buffer distance to maintain from obstacle edges (meters)
    private const val OBSTACLE_BUFFER_METERS = 5.0

    // Earth's radius in meters
    private const val EARTH_RADIUS = 6371000.0

    /**
     * Data class representing an obstacle zone defined by a polygon
     */
    data class ObstacleZone(
        val id: String,
        val points: List<LatLng>,
        val name: String = "Obstacle"
    )

    /**
     * Process waypoints to avoid obstacles
     * Inserts intermediate waypoints where the original path would cross obstacles
     *
     * @param waypoints Original list of waypoints
     * @param obstacles List of obstacle zones to avoid
     * @return Modified list of waypoints that routes around obstacles
     */
    fun processWaypointsAroundObstacles(
        waypoints: List<LatLng>,
        obstacles: List<ObstacleZone>
    ): List<LatLng> {
        if (waypoints.isEmpty() || obstacles.isEmpty()) {
            return waypoints
        }

        val processedWaypoints = mutableListOf<LatLng>()

        for (i in 0 until waypoints.size) {
            val currentPoint = waypoints[i]
            processedWaypoints.add(currentPoint)

            // Check if there's a next waypoint to process the segment
            if (i < waypoints.size - 1) {
                val nextPoint = waypoints[i + 1]

                // Find avoidance path for this segment if it crosses any obstacle
                val avoidancePoints = findAvoidancePathForSegment(currentPoint, nextPoint, obstacles)
                processedWaypoints.addAll(avoidancePoints)
            }
        }

        return processedWaypoints
    }

    /**
     * Find avoidance waypoints for a single segment that crosses obstacles
     */
    private fun findAvoidancePathForSegment(
        start: LatLng,
        end: LatLng,
        obstacles: List<ObstacleZone>
    ): List<LatLng> {
        val avoidancePoints = mutableListOf<LatLng>()

        for (obstacle in obstacles) {
            if (obstacle.points.size < 3) {
                continue
            }

            val intersects = lineIntersectsPolygon(start, end, obstacle.points)

            if (intersects) {
                // Find the best route around this obstacle
                val routeAroundObstacle = findRouteAroundObstacle(start, end, obstacle.points)
                avoidancePoints.addAll(routeAroundObstacle)
            }
        }

        return avoidancePoints
    }

    /**
     * Check if a line segment intersects with a polygon
     */
    fun lineIntersectsPolygon(start: LatLng, end: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        // Check if start or end point is inside the polygon
        val startInside = isPointInsidePolygon(start, polygon)
        val endInside = isPointInsidePolygon(end, polygon)

        if (startInside || endInside) {
            return true
        }

        // Check if the line segment intersects any edge of the polygon
        for (i in polygon.indices) {
            val p1 = polygon[i]
            val p2 = polygon[(i + 1) % polygon.size]

            val intersects = lineSegmentsIntersect(start, end, p1, p2)
            if (intersects) {
                return true
            }
        }

        return false
    }

    /**
     * Check if a point is inside a polygon using ray casting algorithm
     */
    fun isPointInsidePolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        val x = point.longitude
        val y = point.latitude

        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    /**
     * Check if two line segments intersect
     */
    private fun lineSegmentsIntersect(
        a1: LatLng, a2: LatLng,
        b1: LatLng, b2: LatLng
    ): Boolean {
        val d1 = direction(b1, b2, a1)
        val d2 = direction(b1, b2, a2)
        val d3 = direction(a1, a2, b1)
        val d4 = direction(a1, a2, b2)

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true
        }

        if (d1 == 0.0 && onSegment(b1, b2, a1)) return true
        if (d2 == 0.0 && onSegment(b1, b2, a2)) return true
        if (d3 == 0.0 && onSegment(a1, a2, b1)) return true
        if (d4 == 0.0 && onSegment(a1, a2, b2)) return true

        return false
    }

    /**
     * Calculate the cross product direction
     */
    private fun direction(pi: LatLng, pj: LatLng, pk: LatLng): Double {
        return (pk.longitude - pi.longitude) * (pj.latitude - pi.latitude) -
               (pj.longitude - pi.longitude) * (pk.latitude - pi.latitude)
    }

    /**
     * Check if point pk lies on segment pi-pj
     */
    private fun onSegment(pi: LatLng, pj: LatLng, pk: LatLng): Boolean {
        return minOf(pi.longitude, pj.longitude) <= pk.longitude &&
               pk.longitude <= maxOf(pi.longitude, pj.longitude) &&
               minOf(pi.latitude, pj.latitude) <= pk.latitude &&
               pk.latitude <= maxOf(pi.latitude, pj.latitude)
    }

    /**
     * Find route around an obstacle by choosing the shorter path around the vertices
     */
    private fun findRouteAroundObstacle(
        start: LatLng,
        end: LatLng,
        obstaclePolygon: List<LatLng>
    ): List<LatLng> {
        if (obstaclePolygon.size < 3) return emptyList()

        // Expand the obstacle polygon with buffer
        val expandedPolygon = expandPolygon(obstaclePolygon, OBSTACLE_BUFFER_METERS)

        // Find intersection points on the polygon boundary
        val entryIndex = findClosestVertexOnSide(start, expandedPolygon, start, end)
        val exitIndex = findClosestVertexOnSide(end, expandedPolygon, start, end)

        if (entryIndex == -1 || exitIndex == -1) {
            // Fallback: use closest vertices
            val fallbackEntry = findBestEntryVertex(start, expandedPolygon)
            val fallbackExit = findBestExitVertex(end, expandedPolygon)
            return generatePathBetweenIndices(expandedPolygon, fallbackEntry, fallbackExit)
        }

        // Generate both clockwise and counterclockwise paths
        val clockwisePath = generatePathBetweenIndices(expandedPolygon, entryIndex, exitIndex, clockwise = true)
        val counterClockwisePath = generatePathBetweenIndices(expandedPolygon, entryIndex, exitIndex, clockwise = false)

        // Calculate total distances including start and end
        val clockwiseTotal = calculatePathDistance(listOf(start) + clockwisePath + listOf(end))
        val counterClockwiseTotal = calculatePathDistance(listOf(start) + counterClockwisePath + listOf(end))

        val chosenPath = if (clockwiseTotal <= counterClockwiseTotal) clockwisePath else counterClockwisePath

        return chosenPath
    }

    /**
     * Find the closest vertex that is on a specific side of the line from start to end
     */
    private fun findClosestVertexOnSide(
        targetPoint: LatLng,
        polygon: List<LatLng>,
        lineStart: LatLng,
        lineEnd: LatLng
    ): Int {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE

        for (i in polygon.indices) {
            val vertex = polygon[i]
            val distance = haversineDistance(targetPoint, vertex)

            // Check if vertex is on the correct side (not crossing the line to obstacle)
            if (distance < bestDistance) {
                // Verify this vertex doesn't require crossing through the obstacle center
                val polygonCenter = calculateCentroid(polygon)
                val distToCenter = haversineDistance(targetPoint, polygonCenter)
                val vertexToCenter = haversineDistance(vertex, polygonCenter)

                // Prefer vertices that are farther from center relative to our position
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = i
                }
            }
        }

        return bestIndex
    }

    /**
     * Generate a path between two indices on the polygon
     */
    private fun generatePathBetweenIndices(
        polygon: List<LatLng>,
        startIndex: Int,
        endIndex: Int,
        clockwise: Boolean = true
    ): List<LatLng> {
        if (polygon.isEmpty() || startIndex < 0 || endIndex < 0) return emptyList()

        val n = polygon.size
        val path = mutableListOf<LatLng>()
        var current = startIndex

        path.add(polygon[current])

        var iterations = 0
        val maxIterations = n + 1

        while (current != endIndex && iterations < maxIterations) {
            current = if (clockwise) {
                (current + 1) % n
            } else {
                (current - 1 + n) % n
            }
            path.add(polygon[current])
            iterations++
        }

        return path
    }

    /**
     * Find a path around the polygon vertices in a given direction
     */
    private fun findPathAroundPolygon(
        start: LatLng,
        end: LatLng,
        polygon: List<LatLng>,
        clockwise: Boolean
    ): List<LatLng> {
        if (polygon.isEmpty()) return emptyList()

        // Find entry and exit vertices (closest vertices to start and end that don't cross the polygon)
        val entryIndex = findBestEntryVertex(start, polygon)
        val exitIndex = findBestExitVertex(end, polygon)

        if (entryIndex == -1 || exitIndex == -1) return emptyList()

        // Collect vertices along the path
        val pathVertices = mutableListOf<LatLng>()
        val n = polygon.size

        if (entryIndex == exitIndex) {
            // Same vertex, just add it
            pathVertices.add(polygon[entryIndex])
        } else {
            // Traverse around polygon
            var current = entryIndex
            pathVertices.add(polygon[current])

            val maxIterations = n + 1 // Prevent infinite loop
            var iterations = 0

            while (current != exitIndex && iterations < maxIterations) {
                current = if (clockwise) {
                    (current + 1) % n
                } else {
                    (current - 1 + n) % n
                }
                pathVertices.add(polygon[current])
                iterations++
            }
        }

        return pathVertices
    }

    /**
     * Find the best entry vertex - the one closest to start that provides clear line of sight
     */
    private fun findBestEntryVertex(start: LatLng, polygon: List<LatLng>): Int {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE

        for (i in polygon.indices) {
            val distance = haversineDistance(start, polygon[i])
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        return bestIndex
    }

    /**
     * Find the best exit vertex - the one closest to end
     */
    private fun findBestExitVertex(end: LatLng, polygon: List<LatLng>): Int {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE

        for (i in polygon.indices) {
            val distance = haversineDistance(polygon[i], end)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        return bestIndex
    }

    /**
     * Expand a polygon outward by a buffer distance
     */
    private fun expandPolygon(polygon: List<LatLng>, bufferMeters: Double): List<LatLng> {
        if (polygon.size < 3) return polygon

        val centroid = calculateCentroid(polygon)
        val expandedPoints = mutableListOf<LatLng>()

        for (point in polygon) {
            // Calculate direction from centroid to vertex
            val direction = calculateBearing(centroid, point)

            // Move the vertex outward by buffer distance
            val expandedPoint = movePoint(point, direction, bufferMeters)
            expandedPoints.add(expandedPoint)
        }

        return expandedPoints
    }

    /**
     * Calculate the centroid of a polygon
     */
    private fun calculateCentroid(polygon: List<LatLng>): LatLng {
        val avgLat = polygon.map { it.latitude }.average()
        val avgLon = polygon.map { it.longitude }.average()
        return LatLng(avgLat, avgLon)
    }

    /**
     * Calculate bearing from one point to another
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * Move a point in a given direction by a distance
     */
    private fun movePoint(point: LatLng, bearingDegrees: Double, distanceMeters: Double): LatLng {
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(point.latitude)
        val lon1 = Math.toRadians(point.longitude)
        val angularDistance = distanceMeters / EARTH_RADIUS

        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Calculate haversine distance between two points in meters
     */
    private fun haversineDistance(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS * c
    }

    /**
     * Calculate total distance of a path
     */
    private fun calculatePathDistance(path: List<LatLng>): Double {
        if (path.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 0 until path.size - 1) {
            totalDistance += haversineDistance(path[i], path[i + 1])
        }
        return totalDistance
    }

    /**
     * Check if any point in a list is inside any obstacle
     */
    fun isAnyPointInsideObstacles(points: List<LatLng>, obstacles: List<ObstacleZone>): Boolean {
        for (point in points) {
            for (obstacle in obstacles) {
                if (isPointInsidePolygon(point, obstacle.points)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Check if a path intersects any obstacle
     */
    fun pathIntersectsObstacles(path: List<LatLng>, obstacles: List<ObstacleZone>): Boolean {
        if (path.size < 2) return false

        for (i in 0 until path.size - 1) {
            for (obstacle in obstacles) {
                if (lineIntersectsPolygon(path[i], path[i + 1], obstacle.points)) {
                    return true
                }
            }
        }
        return false
    }
}

