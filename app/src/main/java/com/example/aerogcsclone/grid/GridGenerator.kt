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
        val maxDimension = max(width, height) * 1.5

        // Calculate number of lines needed
        val numLines = ceil(maxDimension / params.lineSpacing).toInt()

        val gridLines = mutableListOf<Pair<LatLng, LatLng>>()
        val waypoints = mutableListOf<GridWaypoint>()

        // Pre-process obstacles: expand by LARGER buffer (minimum 3 meters for safety)
        val effectiveBuffer = maxOf(params.obstacleBoundary.toDouble(), 3.0)
        val expandedObstacles = params.obstacles.mapNotNull { obstacle ->
            if (obstacle.size >= 3) {
                expandPolygonEdgeBased(obstacle, effectiveBuffer)
            } else null
        }
        val originalObstacles = params.obstacles.filter { it.size >= 3 }

        // Collect all valid line segments first
        data class GridSegment(
            val start: LatLng,
            val end: LatLng,
            val lineIndex: Int,
            val segmentIndex: Int
        )

        val allSegments = mutableListOf<GridSegment>()

        // Generate grid lines and split around obstacles
        for (i in 0 until numLines) {
            val offset = (i - numLines / 2.0) * params.lineSpacing

            val perpOffsetX = offset * cos(gridAngleRad + PI/2)
            val perpOffsetY = offset * sin(gridAngleRad + PI/2)

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

                // Add all segments for this line
                lineSegments.forEachIndexed { segIdx, segment ->
                    allSegments.add(GridSegment(
                        start = segment.first,
                        end = segment.second,
                        lineIndex = i,
                        segmentIndex = segIdx
                    ))
                }
            }
        }

        // Now process segments in proper boustrophedon order
        // Group by line index
        val segmentsByLine = allSegments.groupBy { it.lineIndex }
        val sortedLineIndices = segmentsByLine.keys.sorted()

        var actualLineIndex = 0
        var previousEnd: LatLng? = null
        val processedSegments = mutableSetOf<GridSegment>()

        for ((lineNum, lineIdx) in sortedLineIndices.withIndex()) {
            val lineSegments = segmentsByLine[lineIdx] ?: continue

            // Sort segments by position along the line
            val sortedSegments = lineSegments.sortedBy { seg ->
                seg.start.latitude + seg.start.longitude
            }

            // Reverse direction for odd lines (boustrophedon pattern)
            val reverseDirection = lineNum % 2 == 1
            val orderedSegments = if (reverseDirection) sortedSegments.reversed() else sortedSegments

            for (segment in orderedSegments) {
                if (segment in processedSegments) continue
                processedSegments.add(segment)

                // Determine segment direction
                val (segStart, segEnd) = if (reverseDirection) {
                    Pair(segment.end, segment.start)
                } else {
                    Pair(segment.start, segment.end)
                }

                // Add the grid line for visualization
                gridLines.add(Pair(segStart, segEnd))

                // Add waypoints
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

                previousEnd = segEnd
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
     * Returns segments that are OUTSIDE obstacles
     */
    private fun splitLineAroundObstacles(
        start: LatLng,
        end: LatLng,
        originalObstacles: List<List<LatLng>>,
        expandedObstacles: List<List<LatLng>>
    ): List<Pair<LatLng, LatLng>> {
        // Use very high sampling for accurate detection
        val numSamples = 500
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

            // Check original obstacles first
            for (obstacle in originalObstacles) {
                if (isPointInPolygonRobust(point, obstacle)) {
                    isInsideObstacle = true
                    break
                }
            }

            // Then check expanded obstacles (buffer zone)
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

        // Build segments from consecutive valid (outside obstacle) points
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
                // End of valid segment
                if (segmentStart != null && lastValidPoint != null && validPointCount >= 5) {
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

        // Add final segment if exists
        if (segmentStart != null && lastValidPoint != null && validPointCount >= 5) {
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
