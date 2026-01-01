package com.example.aerogcsclone.grid

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Main grid generator for survey missions
 * Based on MissionPlanner grid algorithm with obstacle avoidance
 */
class GridGenerator {

    /**
     * Generate grid survey waypoints for a given polygon
     * @param polygon Survey area boundary
     * @param params Grid parameters (spacing, angle, speed, altitude)
     * @return GridSurveyResult containing waypoints and metadata
     */
    fun generateGridSurvey(
        polygon: List<LatLng>,
        params: GridSurveyParams
    ): GridSurveyResult {
        if (polygon.size < 3) {
            return GridSurveyResult(
                waypoints = emptyList(),
                gridLines = emptyList(),
                totalDistance = 0.0,
                estimatedTime = 0.0,
                numLines = 0,
                polygonArea = "0 ft²"
            )
        }

        // Apply indentation (shrink polygon inward for safe zone)
        val effectivePolygon = if (params.indentation > 0) {
            GridUtils.shrinkPolygon(polygon, params.indentation)
        } else {
            polygon
        }

        // Calculate polygon center and bounding box
        val center = GridUtils.calculatePolygonCenter(effectivePolygon)
        val (southwest, northeast) = GridUtils.calculateBoundingBox(effectivePolygon)

        // Calculate grid dimensions
        val width = GridUtils.haversineDistance(
            LatLng(southwest.latitude, southwest.longitude),
            LatLng(southwest.latitude, northeast.longitude)
        )
        val height = GridUtils.haversineDistance(
            LatLng(southwest.latitude, southwest.longitude),
            LatLng(northeast.latitude, southwest.longitude)
        )

        // Determine grid angle - use user input or auto-calculate from longest side
        val gridAngleRad = if (params.gridAngle == 0f) {
            Math.toRadians(GridUtils.getAngleOfLongestSide(polygon))
        } else {
            Math.toRadians(params.gridAngle.toDouble())
        }

        // Calculate the maximum dimension to ensure full coverage
        val maxDimension = max(width, height) * 1.5 // Add buffer for rotation

        // Calculate number of lines needed
        val numLines = ceil(maxDimension / params.lineSpacing).toInt()

        val gridLines = mutableListOf<Pair<LatLng, LatLng>>()
        val allSegments = mutableListOf<LineSegmentInfo>()

        // Pre-process obstacles: expand by buffer
        val effectiveBuffer = maxOf(params.obstacleBoundary.toDouble(), 1.0)
        val expandedObstacles = params.obstacles.mapNotNull { obstacle ->
            if (obstacle.size >= 3) {
                expandPolygonEdgeBased(obstacle, effectiveBuffer)
            } else null
        }
        val originalObstacles = params.obstacles.filter { it.size >= 3 }

        // Generate grid lines
        for (i in 0 until numLines) {
            val offset = (i - numLines / 2.0) * params.lineSpacing

            // Calculate perpendicular offset based on grid angle
            val perpOffsetX = offset * cos(gridAngleRad + PI/2)
            val perpOffsetY = offset * sin(gridAngleRad + PI/2)

            // Calculate line endpoints
            val lineLength = maxDimension
            val lineOffsetX = lineLength/2 * cos(gridAngleRad)
            val lineOffsetY = lineLength/2 * sin(gridAngleRad)

            val lineStart = GridUtils.moveLatLng(
                center,
                perpOffsetX - lineOffsetX,
                perpOffsetY - lineOffsetY
            )
            val lineEnd = GridUtils.moveLatLng(
                center,
                perpOffsetX + lineOffsetX,
                perpOffsetY + lineOffsetY
            )

            // Trim line to polygon intersection
            val trimmedLine = trimLineToPolygon(lineStart, lineEnd, effectivePolygon)

            if (trimmedLine != null) {
                val (start, end) = trimmedLine

                // Split the line if it intersects with any obstacles
                val lineSegments = if (params.obstacles.isNotEmpty()) {
                    splitLineAroundObstacles(start, end, originalObstacles, expandedObstacles)
                } else {
                    listOf(Pair(start, end))
                }

                // Store segments with their original line index for proper sequencing
                for (segment in lineSegments) {
                    allSegments.add(LineSegmentInfo(
                        start = segment.first,
                        end = segment.second,
                        originalLineIndex = i
                    ))
                }
            }
        }

        // Now build waypoints with proper sequencing (boustrophedon pattern)
        // and add connecting paths around obstacles when needed
        val waypoints = mutableListOf<GridWaypoint>()
        var actualLineIndex = 0
        var lastEndPoint: LatLng? = null

        // Group segments by original line index
        val segmentsByLine = allSegments.groupBy { it.originalLineIndex }
        val sortedLineIndices = segmentsByLine.keys.sorted()

        for (lineIdx in sortedLineIndices) {
            val segments = segmentsByLine[lineIdx] ?: continue

            // Sort segments along the line direction
            val sortedSegments = segments.sortedBy { seg ->
                // Use distance from a reference point to sort segments along the line
                seg.start.latitude + seg.start.longitude
            }

            // Determine direction based on boustrophedon pattern
            val reverseDirection = actualLineIndex % 2 == 1
            val orderedSegments = if (reverseDirection) sortedSegments.reversed() else sortedSegments

            for (segment in orderedSegments) {
                val (segStart, segEnd) = if (reverseDirection) {
                    Pair(segment.end, segment.start)
                } else {
                    Pair(segment.start, segment.end)
                }

                // If there was a previous endpoint, check if we need a connecting path
                if (lastEndPoint != null) {
                    val connectingPath = findConnectingPath(
                        lastEndPoint,
                        segStart,
                        originalObstacles,
                        expandedObstacles,
                        effectivePolygon
                    )

                    // Add connecting waypoints (skip first as it's the same as lastEndPoint)
                    for (i in 1 until connectingPath.size) {
                        waypoints.add(GridWaypoint(
                            position = connectingPath[i],
                            altitude = params.altitude,
                            speed = if (params.includeSpeedCommands) params.speed else null,
                            isLineStart = false,
                            isLineEnd = false,
                            lineIndex = actualLineIndex
                        ))
                    }
                }

                // Add the grid line to visualization
                gridLines.add(Pair(segStart, segEnd))

                // Add waypoints for this segment
                waypoints.add(GridWaypoint(
                    position = segStart,
                    altitude = params.altitude,
                    speed = if (params.includeSpeedCommands) params.speed else null,
                    isLineStart = true,
                    lineIndex = actualLineIndex
                ))

                waypoints.add(GridWaypoint(
                    position = segEnd,
                    altitude = params.altitude,
                    speed = if (params.includeSpeedCommands) params.speed else null,
                    isLineEnd = true,
                    lineIndex = actualLineIndex
                ))

                lastEndPoint = segEnd
                actualLineIndex++
            }
        }

        // Calculate total distance and time
        val totalDistance = calculateTotalDistance(waypoints)
        val estimatedTime = if (params.speed > 0) totalDistance / params.speed else 0.0
        val polygonArea = GridUtils.calculateAndFormatPolygonArea(polygon)

        return GridSurveyResult(
            waypoints = waypoints,
            gridLines = gridLines,
            totalDistance = totalDistance,
            estimatedTime = estimatedTime,
            numLines = gridLines.size,
            polygonArea = polygonArea
        )
    }

    /**
     * Data class to store line segment information
     */
    private data class LineSegmentInfo(
        val start: LatLng,
        val end: LatLng,
        val originalLineIndex: Int
    )

    /**
     * Find a connecting path between two points that avoids obstacles
     * Uses a simple obstacle avoidance strategy - goes around obstacle edges
     */
    private fun findConnectingPath(
        from: LatLng,
        to: LatLng,
        originalObstacles: List<List<LatLng>>,
        expandedObstacles: List<List<LatLng>>,
        surveyPolygon: List<LatLng>
    ): List<LatLng> {
        // Check if direct path is clear
        if (isPathClear(from, to, originalObstacles, expandedObstacles)) {
            return listOf(from, to)
        }

        // Find which obstacle(s) block the path
        val blockingObstacles = mutableListOf<List<LatLng>>()
        for (i in originalObstacles.indices) {
            if (lineIntersectsPolygon(from, to, originalObstacles[i]) ||
                (i < expandedObstacles.size && lineIntersectsPolygon(from, to, expandedObstacles[i]))) {
                // Use expanded obstacle for path planning
                if (i < expandedObstacles.size) {
                    blockingObstacles.add(expandedObstacles[i])
                } else {
                    blockingObstacles.add(originalObstacles[i])
                }
            }
        }

        if (blockingObstacles.isEmpty()) {
            return listOf(from, to)
        }

        // Simple obstacle avoidance: go around the obstacle using its vertices
        // Find the best path around the first blocking obstacle
        val obstacle = blockingObstacles.first()
        val path = findPathAroundObstacle(from, to, obstacle, surveyPolygon)

        return path
    }

    /**
     * Find a path around an obstacle by selecting the shorter route
     * around the obstacle's perimeter
     */
    private fun findPathAroundObstacle(
        from: LatLng,
        to: LatLng,
        obstacle: List<LatLng>,
        surveyPolygon: List<LatLng>
    ): List<LatLng> {
        if (obstacle.isEmpty()) return listOf(from, to)

        // Find the closest vertex on the obstacle to 'from' and 'to'
        val fromVertex = findClosestVertex(from, obstacle)
        val toVertex = findClosestVertex(to, obstacle)

        if (fromVertex == toVertex) {
            // Same closest vertex, just go through it
            return listOf(from, obstacle[fromVertex], to)
        }

        // Build two paths: clockwise and counter-clockwise around the obstacle
        val n = obstacle.size

        // Path 1: from -> clockwise vertices -> to
        val clockwisePath = mutableListOf<LatLng>()
        clockwisePath.add(from)
        var idx = fromVertex
        while (idx != toVertex) {
            clockwisePath.add(obstacle[idx])
            idx = (idx + 1) % n
        }
        clockwisePath.add(obstacle[toVertex])
        clockwisePath.add(to)

        // Path 2: from -> counter-clockwise vertices -> to
        val counterClockwisePath = mutableListOf<LatLng>()
        counterClockwisePath.add(from)
        idx = fromVertex
        while (idx != toVertex) {
            counterClockwisePath.add(obstacle[idx])
            idx = (idx - 1 + n) % n
        }
        counterClockwisePath.add(obstacle[toVertex])
        counterClockwisePath.add(to)

        // Calculate distances and choose shorter path
        val clockwiseDistance = calculatePathDistance(clockwisePath)
        val counterClockwiseDistance = calculatePathDistance(counterClockwisePath)

        // Also verify paths are within survey polygon
        val clockwiseValid = clockwisePath.all { GridUtils.isPointInPolygon(it, surveyPolygon) ||
            surveyPolygon.any { sv -> GridUtils.haversineDistance(it, sv) < 5.0 } }
        val counterClockwiseValid = counterClockwisePath.all { GridUtils.isPointInPolygon(it, surveyPolygon) ||
            surveyPolygon.any { sv -> GridUtils.haversineDistance(it, sv) < 5.0 } }

        return when {
            clockwiseValid && counterClockwiseValid ->
                if (clockwiseDistance <= counterClockwiseDistance) clockwisePath else counterClockwisePath
            clockwiseValid -> clockwisePath
            counterClockwiseValid -> counterClockwisePath
            else -> listOf(from, to) // Fallback to direct path
        }
    }

    /**
     * Find the closest vertex index on a polygon to a given point
     */
    private fun findClosestVertex(point: LatLng, polygon: List<LatLng>): Int {
        var minDist = Double.MAX_VALUE
        var closestIdx = 0

        for (i in polygon.indices) {
            val dist = GridUtils.haversineDistance(point, polygon[i])
            if (dist < minDist) {
                minDist = dist
                closestIdx = i
            }
        }

        return closestIdx
    }

    /**
     * Calculate total distance of a path
     */
    private fun calculatePathDistance(path: List<LatLng>): Double {
        if (path.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += GridUtils.haversineDistance(path[i], path[i + 1])
        }
        return total
    }

    /**
     * Check if a direct path between two points is clear of obstacles
     */
    private fun isPathClear(
        from: LatLng,
        to: LatLng,
        originalObstacles: List<List<LatLng>>,
        expandedObstacles: List<List<LatLng>>
    ): Boolean {
        // Sample points along the path
        val numSamples = 50
        for (i in 0..numSamples) {
            val t = i.toDouble() / numSamples
            val lat = from.latitude + t * (to.latitude - from.latitude)
            val lng = from.longitude + t * (to.longitude - from.longitude)
            val point = LatLng(lat, lng)

            // Check against all obstacles
            for (obstacle in originalObstacles) {
                if (isPointInPolygonRobust(point, obstacle)) {
                    return false
                }
            }
            for (obstacle in expandedObstacles) {
                if (isPointInPolygonRobust(point, obstacle)) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Check if a line segment intersects a polygon
     */
    private fun lineIntersectsPolygon(start: LatLng, end: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        // Check if either endpoint is inside the polygon
        if (isPointInPolygonRobust(start, polygon) || isPointInPolygonRobust(end, polygon)) {
            return true
        }

        // Check if line intersects any edge of the polygon
        val n = polygon.size
        for (i in 0 until n) {
            val p1 = polygon[i]
            val p2 = polygon[(i + 1) % n]
            if (lineSegmentsIntersect(start, end, p1, p2)) {
                return true
            }
        }

        return false
    }

    /**
     * Check if two line segments intersect
     */
    private fun lineSegmentsIntersect(a1: LatLng, a2: LatLng, b1: LatLng, b2: LatLng): Boolean {
        val d1 = direction(b1, b2, a1)
        val d2 = direction(b1, b2, a2)
        val d3 = direction(a1, a2, b1)
        val d4 = direction(a1, a2, b2)

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true
        }

        return false
    }

    /**
     * Calculate cross product direction
     */
    private fun direction(pi: LatLng, pj: LatLng, pk: LatLng): Double {
        return (pk.longitude - pi.longitude) * (pj.latitude - pi.latitude) -
               (pj.longitude - pi.longitude) * (pk.latitude - pi.latitude)
    }

    /**
     * Trim a line to intersect with polygon boundaries
     */
    private fun trimLineToPolygon(
        lineStart: LatLng,
        lineEnd: LatLng,
        polygon: List<LatLng>
    ): Pair<LatLng, LatLng>? {
        val numSamples = 100
        val validPoints = mutableListOf<LatLng>()

        for (i in 0..numSamples) {
            val t = i.toDouble() / numSamples
            val lat = lineStart.latitude + t * (lineEnd.latitude - lineStart.latitude)
            val lng = lineStart.longitude + t * (lineEnd.longitude - lineStart.longitude)
            val point = LatLng(lat, lng)

            if (GridUtils.isPointInPolygon(point, polygon)) {
                validPoints.add(point)
            }
        }

        return if (validPoints.isNotEmpty()) {
            Pair(validPoints.first(), validPoints.last())
        } else {
            null
        }
    }

    /**
     * Split a line around obstacle zones
     * Returns a list of line segments that avoid the obstacles
     */
    private fun splitLineAroundObstacles(
        start: LatLng,
        end: LatLng,
        originalObstacles: List<List<LatLng>>,
        expandedObstacles: List<List<LatLng>>
    ): List<Pair<LatLng, LatLng>> {
        val numSamples = 1000
        val segments = mutableListOf<Pair<LatLng, LatLng>>()

        if (expandedObstacles.isEmpty() && originalObstacles.isEmpty()) {
            return listOf(Pair(start, end))
        }

        // Sample points along the line
        val pointsAlongLine = mutableListOf<Pair<LatLng, Boolean>>()

        for (i in 0..numSamples) {
            val t = i.toDouble() / numSamples
            val lat = start.latitude + t * (end.latitude - start.latitude)
            val lng = start.longitude + t * (end.longitude - start.longitude)
            val point = LatLng(lat, lng)

            var isInsideObstacle = false

            for (obstacle in originalObstacles) {
                if (isPointInPolygonRobust(point, obstacle)) {
                    isInsideObstacle = true
                    break
                }
            }

            if (!isInsideObstacle) {
                for (obstacle in expandedObstacles) {
                    if (isPointInPolygonRobust(point, obstacle)) {
                        isInsideObstacle = true
                        break
                    }
                }
            }

            pointsAlongLine.add(Pair(point, !isInsideObstacle))
        }

        // Build segments
        var segmentStart: LatLng? = null
        var lastValidPoint: LatLng? = null
        var validPointCount = 0

        for ((point, isValid) in pointsAlongLine) {
            if (isValid) {
                if (segmentStart == null) {
                    segmentStart = point
                }
                lastValidPoint = point
                validPointCount++
            } else {
                if (segmentStart != null && lastValidPoint != null && validPointCount >= 10) {
                    val segLength = GridUtils.haversineDistance(segmentStart, lastValidPoint)
                    if (segLength >= 1.0) {
                        segments.add(Pair(segmentStart, lastValidPoint))
                    }
                }
                segmentStart = null
                lastValidPoint = null
                validPointCount = 0
            }
        }

        if (segmentStart != null && lastValidPoint != null && validPointCount >= 10) {
            val segLength = GridUtils.haversineDistance(segmentStart, lastValidPoint)
            if (segLength >= 1.0) {
                segments.add(Pair(segmentStart, lastValidPoint))
            }
        }

        return if (segments.isEmpty() && pointsAlongLine.all { it.second }) {
            listOf(Pair(start, end))
        } else if (segments.isEmpty()) {
            emptyList()
        } else {
            segments
        }
    }

    /**
     * Robust point-in-polygon test
     */
    private fun isPointInPolygonRobust(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        val x = point.longitude
        val y = point.latitude
        var inside = false

        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            if (abs(x - xi) < 1e-10 && abs(y - yi) < 1e-10) {
                return true
            }

            val intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / (yj - yi) + xi)

            if (intersect) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    /**
     * Expand a polygon outward by buffer distance
     */
    private fun expandPolygonEdgeBased(polygon: List<LatLng>, bufferMeters: Double): List<LatLng> {
        if (polygon.size < 3 || bufferMeters <= 0) return polygon

        val n = polygon.size
        val expanded = mutableListOf<LatLng>()

        val centroidLat = polygon.map { it.latitude }.average()
        val centroidLon = polygon.map { it.longitude }.average()

        for (i in 0 until n) {
            val prev = polygon[(i - 1 + n) % n]
            val curr = polygon[i]
            val next = polygon[(i + 1) % n]

            val bufferLatDeg = bufferMeters / 111111.0
            val bufferLonDeg = bufferMeters / (111111.0 * cos(Math.toRadians(curr.latitude)))

            val edge1Lat = curr.latitude - prev.latitude
            val edge1Lon = curr.longitude - prev.longitude
            val edge2Lat = next.latitude - curr.latitude
            val edge2Lon = next.longitude - curr.longitude

            val len1 = sqrt(edge1Lat * edge1Lat + edge1Lon * edge1Lon)
            val len2 = sqrt(edge2Lat * edge2Lat + edge2Lon * edge2Lon)

            if (len1 < 1e-10 || len2 < 1e-10) {
                val dirLat = curr.latitude - centroidLat
                val dirLon = curr.longitude - centroidLon
                val dirLen = sqrt(dirLat * dirLat + dirLon * dirLon)
                if (dirLen > 1e-10) {
                    expanded.add(LatLng(
                        curr.latitude + (dirLat / dirLen) * bufferLatDeg,
                        curr.longitude + (dirLon / dirLen) * bufferLonDeg
                    ))
                } else {
                    expanded.add(curr)
                }
                continue
            }

            val n1Lat = edge1Lat / len1
            val n1Lon = edge1Lon / len1
            val n2Lat = edge2Lat / len2
            val n2Lon = edge2Lon / len2

            var perp1Lat = -n1Lon
            var perp1Lon = n1Lat
            var perp2Lat = -n2Lon
            var perp2Lon = n2Lat

            val midEdge1Lat = (prev.latitude + curr.latitude) / 2
            val midEdge1Lon = (prev.longitude + curr.longitude) / 2
            val testPointLat = midEdge1Lat + perp1Lat * 0.0001
            val testPointLon = midEdge1Lon + perp1Lon * 0.0001

            val distOriginal = sqrt((midEdge1Lat - centroidLat).pow(2) + (midEdge1Lon - centroidLon).pow(2))
            val distTest = sqrt((testPointLat - centroidLat).pow(2) + (testPointLon - centroidLon).pow(2))

            if (distTest < distOriginal) {
                perp1Lat = -perp1Lat
                perp1Lon = -perp1Lon
                perp2Lat = -perp2Lat
                perp2Lon = -perp2Lon
            }

            var avgLat = perp1Lat + perp2Lat
            var avgLon = perp1Lon + perp2Lon
            val avgLen = sqrt(avgLat * avgLat + avgLon * avgLon)

            if (avgLen < 1e-10) {
                avgLat = perp1Lat
                avgLon = perp1Lon
            } else {
                avgLat /= avgLen
                avgLon /= avgLen
            }

            val dot = perp1Lat * avgLat + perp1Lon * avgLon
            val offsetMult = if (dot > 0.3) minOf(1.0 / dot, 2.5) else 2.5

            val newLat = curr.latitude + avgLat * bufferLatDeg * offsetMult
            val newLon = curr.longitude + avgLon * bufferLonDeg * offsetMult

            expanded.add(LatLng(newLat, newLon))
        }

        return expanded
    }

    /**
     * Calculate total distance of waypoint path
     */
    private fun calculateTotalDistance(waypoints: List<GridWaypoint>): Double {
        if (waypoints.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 0 until waypoints.size - 1) {
            totalDistance += GridUtils.haversineDistance(
                waypoints[i].position,
                waypoints[i + 1].position
            )
        }
        return totalDistance
    }

    /**
     * Generate a simple rectangular survey pattern for testing
     */
    fun generateRectangularSurvey(
        center: LatLng,
        width: Double,
        height: Double,
        params: GridSurveyParams
    ): GridSurveyResult {
        val halfWidth = width / 2
        val halfHeight = height / 2

        val polygon = listOf(
            GridUtils.moveLatLng(center, -halfWidth, -halfHeight),
            GridUtils.moveLatLng(center, halfWidth, -halfHeight),
            GridUtils.moveLatLng(center, halfWidth, halfHeight),
            GridUtils.moveLatLng(center, -halfWidth, halfHeight)
        )

        return generateGridSurvey(polygon, params)
    }

    /**
     * Auto-calculate optimal grid angle based on polygon shape
     */
    fun calculateOptimalGridAngle(polygon: List<LatLng>): Float {
        if (polygon.size < 3) return 0f
        val longestSideAngle = GridUtils.getAngleOfLongestSide(polygon)
        return ((longestSideAngle + 90) % 360).toFloat()
    }

    /**
     * Estimate coverage area for given parameters
     */
    fun estimateCoverage(polygon: List<LatLng>, lineSpacing: Float): Float {
        val area = GridUtils.calculatePolygonArea(polygon)
        if (area <= 0) return 0f
        val estimatedCoveredArea = area * (1.0 - lineSpacing / 100.0)
        return (estimatedCoveredArea / area * 100).coerceIn(0.0, 100.0).toFloat()
    }
}
