package com.example.aerogcsclone.grid

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Main grid generator for survey missions
 * Based on MissionPlanner grid algorithm
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
        val waypoints = mutableListOf<GridWaypoint>()

        // CRITICAL: Use a separate counter for actual grid lines that intersect polygon
        // This ensures lineIndex matches the gridLines array index
        var actualLineIndex = 0

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

            // Trim line to polygon intersection (use effectivePolygon with indentation applied)
            val trimmedLine = trimLineToPolygon(lineStart, lineEnd, effectivePolygon)

            if (trimmedLine != null) {
                val (start, end) = trimmedLine
                gridLines.add(Pair(start, end))

                // Alternate direction for boustrophedon pattern (back and forth)
                // Use actualLineIndex for alternation to maintain proper back-and-forth pattern
                val (waypointStart, waypointEnd) = if (actualLineIndex % 2 == 0) {
                    Pair(start, end)
                } else {
                    Pair(end, start)
                }

                // Add waypoints for this line
                // CRITICAL: Use actualLineIndex (not i) to ensure lineIndex matches gridLines index
                waypoints.add(GridWaypoint(
                    position = waypointStart,
                    altitude = params.altitude,
                    speed = if (params.includeSpeedCommands) params.speed else null,
                    isLineStart = true,
                    lineIndex = actualLineIndex
                ))

                waypoints.add(GridWaypoint(
                    position = waypointEnd,
                    altitude = params.altitude,
                    speed = if (params.includeSpeedCommands) params.speed else null,
                    isLineEnd = true,
                    lineIndex = actualLineIndex
                ))

                // Increment actual line counter after adding a valid line
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
     * Uses a simplified approach - checks multiple points along the line
     */
    private fun trimLineToPolygon(
        lineStart: LatLng,
        lineEnd: LatLng,
        polygon: List<LatLng>
    ): Pair<LatLng, LatLng>? {
        val numSamples = 100
        val validPoints = mutableListOf<LatLng>()

        // Sample points along the line
        for (i in 0..numSamples) {
            val t = i.toDouble() / numSamples
            val lat = lineStart.latitude + t * (lineEnd.latitude - lineStart.latitude)
            val lng = lineStart.longitude + t * (lineEnd.longitude - lineStart.longitude)
            val point = LatLng(lat, lng)

            if (GridUtils.isPointInPolygon(point, polygon)) {
                validPoints.add(point)
            }
        }

        // Return first and last valid points if any exist
        return if (validPoints.isNotEmpty()) {
            Pair(validPoints.first(), validPoints.last())
        } else {
            null
        }
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
     * @param center Center point of the survey
     * @param width Width in meters
     * @param height Height in meters
     * @param params Grid parameters
     * @return GridSurveyResult
     */
    fun generateRectangularSurvey(
        center: LatLng,
        width: Double,
        height: Double,
        params: GridSurveyParams
    ): GridSurveyResult {
        // Create rectangular polygon
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
     * @param polygon Survey area
     * @return Optimal angle in degrees
     */
    fun calculateOptimalGridAngle(polygon: List<LatLng>): Float {
        if (polygon.size < 3) return 0f

        val longestSideAngle = GridUtils.getAngleOfLongestSide(polygon)
        // Align grid perpendicular to longest side for maximum efficiency
        return ((longestSideAngle + 90) % 360).toFloat()
    }

    /**
     * Estimate coverage area for given parameters
     * @param polygon Survey area
     * @param lineSpacing Line spacing in meters
     * @return Coverage percentage (0-100)
     */
    fun estimateCoverage(polygon: List<LatLng>, lineSpacing: Float): Float {
        val area = GridUtils.calculatePolygonArea(polygon)
        if (area <= 0) return 0f

        // Simple estimation based on line spacing
        // This is a rough approximation - real coverage depends on sensor width
        val estimatedCoveredArea = area * (1.0 - lineSpacing / 100.0) // Simplified model
        return (estimatedCoveredArea / area * 100).coerceIn(0.0, 100.0).toFloat()
    }
}
