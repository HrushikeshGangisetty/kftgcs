package com.example.kftgcs.grid

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Main grid generator for survey missions
 * Based on MissionPlanner grid algorithm with obstacle avoidance
 */
class GridGenerator {

    companion object {
        /**
         * Floor for the obstacle buffer, in metres. Zero: the pilot decides, and zero is a
         * legitimate choice meaning "plan right up to the drawn edge".
         *
         * Kept as a named constant rather than dropping the clamp entirely, because the clamp
         * still has one job: a stored plan carrying a NEGATIVE buffer would otherwise shrink the
         * obstacle and route spray lines through it. maxOf(buffer, 0.0) turns that into "no
         * expansion", which is merely useless rather than dangerous.
         *
         * This was briefly 3.0, which silently overrode the lower half of the slider's travel.
         * Keep it equal to the slider's minimum; if one moves, move the other.
         */
        const val MIN_OBSTACLE_BUFFER_M = 0.0

        /**
         * How far a mitered corner may be pushed out, as a multiple of the buffer.
         *
         * A sharp corner's miter distance is buffer / cos(half-angle), which runs to infinity as
         * the corner closes. Left unbounded, a single near-spike vertex on a hand-drawn obstacle
         * would fling one point of the ring hundreds of metres across the field and swallow most
         * of the spray grid. 2.5 caps the corner at a ~47 degree included angle; anything sharper
         * is simply cut off at that radius, which loses a sliver of clearance at the very tip of
         * a spike and holds the full buffer everywhere else.
         */
        private const val MITER_LIMIT = 2.5

        /**
         * The no-fly ring actually held around an obstacle, for map display.
         *
         * This is the same geometry the planner flies: it applies the identical
         * MIN_OBSTACLE_BUFFER_M clamp and the identical edge-based expansion used to split
         * the spray lines, so the shaded area on the map is the real cleared zone rather
         * than a second, drifting approximation of it. Returns null when the polygon is not
         * a usable shape.
         */
        fun obstacleBufferZone(obstacle: List<LatLng>, bufferMeters: Double): List<LatLng>? {
            if (obstacle.size < 3) return null
            val effective = maxOf(bufferMeters, MIN_OBSTACLE_BUFFER_M)
            return GridGenerator().expandPolygonEdgeBased(obstacle, effective)
        }
    }

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

        // ===== THE GRID FRAME =====
        //
        // Two axes, both in metres, both measured from `center`:
        //
        //   along  — parallel to the spray lines, the direction the drone flies down a line
        //   across — perpendicular to them, the direction the grid steps from one line to the
        //            next. It INCREASES with the line index, which is what lets a detour know
        //            which way is "ahead of the sweep" and therefore not yet sprayed.
        //
        // The grid lines below are built as center + t * (cos(gridAngle), sin(gridAngle)) in
        // (east, north) metres, and stepped by (cos(gridAngle + 90°), sin(gridAngle + 90°)),
        // so these two projections are exactly the inverse of that construction.
        //
        // Segment ordering used to key on `latitude + longitude` instead of `along`. That is
        // monotonic along a line only when the grid runs somewhere between north-east and
        // south-west; rotate it into the north-west quadrant and the key runs backwards, so the
        // pieces of a line split by an obstacle came out in the wrong order — and since the
        // boustrophedon reverses alternate lines on top of that, the path doubled back on
        // itself rather than sweeping the field.
        val cosGrid = cos(gridAngleRad)
        val sinGrid = sin(gridAngleRad)
        val metresPerDegLonAtCenter = 111111.0 * cos(Math.toRadians(center.latitude))

        fun alongAxis(p: LatLng): Double {
            val east = (p.longitude - center.longitude) * metresPerDegLonAtCenter
            val north = (p.latitude - center.latitude) * 111111.0
            return east * cosGrid + north * sinGrid
        }

        fun acrossAxis(p: LatLng): Double {
            val east = (p.longitude - center.longitude) * metresPerDegLonAtCenter
            val north = (p.latitude - center.latitude) * 111111.0
            return -east * sinGrid + north * cosGrid
        }

        /** Inverse of [alongAxis] / [acrossAxis]: grid-frame metres back to a position. */
        fun fromGridFrame(along: Double, across: Double): LatLng {
            val east = along * cosGrid - across * sinGrid
            val north = along * sinGrid + across * cosGrid
            return LatLng(
                center.latitude + north / 111111.0,
                center.longitude + east / metresPerDegLonAtCenter
            )
        }

        // How far the survey area extends across the sweep. A hop around an obstacle goes past
        // whichever end of it is nearer; these two are what stop it choosing an end that would
        // take the aircraft outside the field.
        val fieldAcrossMin = effectivePolygon.minOf { acrossAxis(it) }
        val fieldAcrossMax = effectivePolygon.maxOf { acrossAxis(it) }

        // Calculate the maximum dimension to ensure full coverage
        val maxDimension = max(width, height) * 1.5

        // Calculate number of lines needed
        val numLines = ceil(maxDimension / params.lineSpacing).toInt()

        val gridLines = mutableListOf<Pair<LatLng, LatLng>>()
        val waypoints = mutableListOf<GridWaypoint>()

        // Pre-process obstacles: expand by the buffer the pilot asked for.
        //
        // The clamp is to MIN_OBSTACLE_BUFFER_M, which equals the slider's own minimum, so it
        // is a no-op for anything the slider can produce and every position of the travel
        // moves the grid. It only catches a stored plan carrying a zero/negative buffer.
        val effectiveBuffer = maxOf(params.obstacleBoundary.toDouble(), MIN_OBSTACLE_BUFFER_M)
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
            // Position of this piece along its own line: 0 is the piece nearest the line's
            // start, and an obstacle that cuts the line adds 1, 2, ... after it. This used to
            // double as a "zone" that the flight order grouped by across the WHOLE field, which
            // put an untouched full-width line in the same group as the near halves of the
            // lines beside it. It is now only ever compared within a single line.
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

        // ===== FLIGHT ORDER =====
        //
        // The field is swept line by line, alternating direction as usual. Where an obstacle
        // splits a run of lines, that run is flown as TWO PASSES: the half of each line on the
        // side the aircraft arrives from, a short step around the edge of the obstacle, then the
        // other half on the way back. The sweep then carries on past the obstacle.
        //
        // What a pilot sees, and it is the same on every field, obstacle and heading:
        //
        //     ... normal sweep, arriving at (say) the top of the field ...
        //     far halves, working across the obstacle     (pass 1)
        //     step around the edge of the obstacle
        //     near halves, working back                   (pass 2)
        //     ... normal sweep continues past the obstacle ...
        //
        // WHICH half comes first is decided by where the aircraft actually is when it reaches
        // the obstacle, not by a fixed rule. Arrive at the top and the top halves go first;
        // arrive at the bottom and the bottom halves do. This is the whole point, and getting it
        // wrong is what the doubled line on the map was: a fixed "near side first" rule meant an
        // aircraft arriving at the top of the field had to fly the entire length of the field to
        // reach the start of the near half — a full-length dry leg running a few metres beside
        // the line it had just sprayed. On a simulated 8-line field with a 3-line obstacle that
        // one decision is the difference between 213 m and 122 m of dry transit, and between a
        // longest leg of 79 m and one of 30 m.
        //
        // Two rejected alternatives, so they are not re-tried:
        //
        //   * Stepping around the obstacle on EVERY line and rejoining the same line. Puts a
        //     detour in the middle of every affected line; reads as the aircraft repeatedly
        //     wandering off the pattern.
        //   * Keying the pass on a segment's index along its own line, across the WHOLE field.
        //     A line the obstacle did not touch produces one segment and so lands in "pass 1"
        //     beside the HALVES of the lines it did touch — so pass 1 mixes full-width lines
        //     from both sides of the obstacle with half lines, and the aircraft crosses the
        //     entire field to collect what it skipped. The passes here are scoped to a BLOCK, a
        //     run of consecutive lines the obstacle splits the same way, so lines outside the
        //     block are never drawn into it.
        //
        // Cost of the two-pass shape, stated plainly: after pass 2 the aircraft is back at the
        // end of the block it entered from, and has to transit across the block to reach the
        // first line beyond it. That leg runs PERPENDICULAR to the lines, across their ends, so
        // it does not retrace anything; it is bounded by the width of the obstacle, not the
        // width of the field (30 m in the simulation above). The sprayer is off for it, since it
        // sits between an isLineEnd and the next isLineStart and the converter's DO_SPRAYER
        // stop/start bracket already covers that. It costs flight time, not a double dose.

        /**
         * Waypoints that step around whatever blocks the straight run from [a] to [b].
         *
         * Used for the hop between the two passes, and for any block-to-block transit that
         * happens to clip an obstacle. The detour is a rectangular bump: out to a clear
         * across-offset, along past the obstacle, and the caller's next waypoint steps back in.
         *
         * Returns empty when nothing actually blocks the run, which is the common case.
         */
        fun detourAround(a: LatLng, b: LatLng): List<LatLng> {
            val blocking = expandedObstacles.filter { lineIntersectsObstacle(a, b, it) }
            if (blocking.isEmpty()) return emptyList()

            // Across-extent of everything in the way, so one detour clears all of it.
            var obsAcrossMin = Double.MAX_VALUE
            var obsAcrossMax = -Double.MAX_VALUE
            for (obstacle in blocking) {
                for (vertex in obstacle) {
                    val c = acrossAxis(vertex)
                    if (c < obsAcrossMin) obsAcrossMin = c
                    if (c > obsAcrossMax) obsAcrossMax = c
                }
            }

            // Hop past the end the aircraft is already nearest to, so the hop is short. Ties and
            // the off-field case both fall to the side that stays inside the survey area.
            val hereAcross = acrossAxis(a)
            val forward = obsAcrossMax + effectiveBuffer
            val backward = obsAcrossMin - effectiveBuffer
            val preferForward = (obsAcrossMax - hereAcross) <= (hereAcross - obsAcrossMin)
            val detourAcross = when {
                preferForward && forward <= fieldAcrossMax -> forward
                !preferForward && backward >= fieldAcrossMin -> backward
                forward <= fieldAcrossMax -> forward
                else -> backward
            }

            return listOf(
                fromGridFrame(alongAxis(a), detourAcross),
                fromGridFrame(alongAxis(b), detourAcross)
            )
        }

        // ═══ Blocks ═══
        //
        // A block is a maximal run of CONSECUTIVE line indices that the obstacles cut into the
        // same number of pieces. A line the obstacles miss has one piece and forms (with its
        // neighbours) a one-pass block; a run of lines cut in two forms a two-pass block. The
        // consecutiveness test matters as much as the piece count: two runs of unsplit lines on
        // opposite sides of an obstacle must not merge into one block just because they happen
        // to agree about how many pieces they have.
        val segmentsByLine = allSegments.groupBy { it.lineIndex }
        val piecesPerLine = segmentsByLine.mapValues { (_, segs) ->
            segs.sortedBy { seg -> alongAxis(seg.start) }
        }

        val blocks = mutableListOf<MutableList<Int>>()
        for (lineIdx in piecesPerLine.keys.sorted()) {
            val previous = blocks.lastOrNull()?.lastOrNull()
            val continuesBlock = previous != null &&
                    lineIdx == previous + 1 &&
                    piecesPerLine.getValue(previous).size == piecesPerLine.getValue(lineIdx).size
            if (continuesBlock) {
                blocks.last().add(lineIdx)
            } else {
                blocks.add(mutableListOf(lineIdx))
            }
        }

        var actualLineIndex = 0

        // Where the aircraft is, at the end of the last piece flown. Null before the first one.
        //
        // Everything below is driven off this rather than off a parity counter. The previous
        // version tracked a reverseDirection flag and flipped it once per line, which is a fine
        // model of a plain boustrophedon and a bad one the moment an obstacle appears: the flag
        // said "fly this piece backwards" without reference to where the aircraft actually was.
        // That is what put a full-length dry leg right beside a line that had just been sprayed.
        var currentPosition: LatLng? = null

        /** Distance to whichever end of [segment] is nearer to [from]. */
        fun nearestEndDistance(from: LatLng, segment: GridSegment): Double =
            minOf(
                GridUtils.haversineDistance(from, segment.start),
                GridUtils.haversineDistance(from, segment.end)
            )

        for (block in blocks) {
            val passCount = piecesPerLine.getValue(block.first()).size

            // ═══ Enter the block at the corner the aircraft has actually reached ═══
            //
            // Two choices, both settled by "which is nearer": which END of the block to start
            // from, and which STRIP of the obstacle to spray first. Getting the second one wrong
            // is what the doubled line was. Arriving at the top of the field and then being told
            // to start on the near side of the obstacle meant flying the whole length of the
            // field to get there — alongside the line just sprayed, which is exactly what it
            // looked like on the map.
            //
            // So: arrive at the top, spray the far halves first and the near halves on the way
            // back. Arrive at the bottom, spray the near halves first. Either way the aircraft
            // carries straight on from where it already is, and the crossover between the two
            // strips happens at the obstacle, where it is a short step around the edge.
            val here = currentPosition
            val enterAscending = if (here == null) true else {
                val toFirst = piecesPerLine.getValue(block.first()).minOf { nearestEndDistance(here, it) }
                val toLast = piecesPerLine.getValue(block.last()).minOf { nearestEndDistance(here, it) }
                toFirst <= toLast
            }
            val entryLine = if (enterAscending) block.first() else block.last()

            // Start on an OUTER strip, never a middle one, so the passes then run through the
            // strips in order and each crossover is to a neighbour across a single obstacle.
            // (More than two strips only happens when two obstacles cut the same line.)
            val entryPieces = piecesPerLine.getValue(entryLine)
            val startAtLastPass = here != null && passCount > 1 &&
                    nearestEndDistance(here, entryPieces.last()) <
                    nearestEndDistance(here, entryPieces.first())
            val passOrder = if (startAtLastPass) {
                (passCount - 1 downTo 0).toList()
            } else {
                (0 until passCount).toList()
            }

            var ascending = enterAscending

            for (pass in passOrder) {
                val lineOrder = if (ascending) block else block.asReversed()

                for (lineIdx in lineOrder) {
                    // Safe: every line in a block has the same piece count, by construction.
                    val segment = piecesPerLine.getValue(lineIdx)[pass]

                    // Fly the piece from whichever end the aircraft is nearer to. On a field
                    // with no obstacles this reproduces the ordinary boustrophedon exactly —
                    // the far end of the line you just flew is always nearer to the far end of
                    // the next one — so the plain case is unchanged, and the obstacle case
                    // stops needing a special rule.
                    val from = currentPosition
                    val (segStart, segEnd) = if (from == null ||
                        GridUtils.haversineDistance(from, segment.start) <=
                        GridUtils.haversineDistance(from, segment.end)
                    ) {
                        Pair(segment.start, segment.end)
                    } else {
                        Pair(segment.end, segment.start)
                    }

                    currentPosition?.let { previousEnd ->
                        for (detourPoint in detourAround(previousEnd, segStart)) {
                            waypoints.add(GridWaypoint(
                                position = detourPoint,
                                altitude = params.altitude,
                                speed = if (params.includeSpeedCommands) params.speed else null,
                                isLineStart = false,
                                isLineEnd = false,
                                isTransition = true,
                                lineIndex = actualLineIndex
                            ))
                        }
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

                    currentPosition = segEnd
                    actualLineIndex++
                }

                ascending = !ascending
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
        val numSamples = 1000
        val segments = mutableListOf<Pair<LatLng, LatLng>>()

        // Check the originals AS WELL as the expanded rings, not just the expanded ones.
        //
        // The tempting argument is that an expanded polygon strictly contains its original, so
        // the originals are redundant. That only holds if expandPolygonEdgeBased is guaranteed
        // to produce a containing, non-self-intersecting ring — and offsetting a polygon is
        // exactly the operation that stops being well behaved on concave corners and short
        // edges, where an offset ring can fold through itself. When that happens the fold
        // reverses the inside/outside test over part of the shape and a spray line is planned
        // straight through the real obstacle. Testing the union is unconditionally safe: it can
        // only ever mark MORE of the line unsafe, never less.
        //
        // The cost is one extra point-in-polygon per obstacle per sample. That is planner-side
        // work on a few polygons, paid once when the grid is generated, and is not worth
        // trading a "the drone flew into the tree" failure mode for.
        val allObstaclesToCheck = expandedObstacles + originalObstacles

        if (allObstaclesToCheck.isEmpty()) {
            return listOf(Pair(start, end))
        }

        // Sample points along the line and check if each is inside any obstacle
        val pointsAlongLine = mutableListOf<Pair<LatLng, Boolean>>()

        for (i in 0..numSamples) {
            val t = i.toDouble() / numSamples
            val lat = start.latitude + t * (end.latitude - start.latitude)
            val lng = start.longitude + t * (end.longitude - start.longitude)
            val point = LatLng(lat, lng)

            var isInsideAnyObstacle = false

            // Check against all obstacles using multiple algorithms for robustness
            for (obstacle in allObstaclesToCheck) {
                if (obstacle.size >= 3) {
                    // Use both winding number and ray casting for maximum accuracy
                    val insideByWinding = isPointInsidePolygonWinding(point, obstacle)
                    val insideByRayCast = isPointInPolygonRobust(point, obstacle)
                    if (insideByWinding || insideByRayCast) {
                        isInsideAnyObstacle = true
                        break
                    }
                }
            }

            // isValid = true means point is OUTSIDE all obstacles (safe to fly)
            pointsAlongLine.add(Pair(point, !isInsideAnyObstacle))
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
                // End of valid segment - we hit an obstacle
                if (segmentStart != null && lastValidPoint != null && validPointCount >= 3) {
                    val segLength = GridUtils.haversineDistance(segmentStart, lastValidPoint)
                    if (segLength >= 0.5) { // At least 0.5 meter segment
                        segments.add(Pair(segmentStart, lastValidPoint))
                    }
                }
                segmentStart = null
                lastValidPoint = null
                validPointCount = 0
            }
        }

        // Add final segment if exists
        if (segmentStart != null && lastValidPoint != null && validPointCount >= 3) {
            val segLength = GridUtils.haversineDistance(segmentStart, lastValidPoint)
            if (segLength >= 0.5) {
                segments.add(Pair(segmentStart, lastValidPoint))
            }
        }

        // If no segments found but entire line is valid, return original line
        return if (segments.isEmpty() && pointsAlongLine.all { it.second }) {
            listOf(Pair(start, end))
        } else if (segments.isEmpty()) {
            // Entire line is inside obstacle(s)
            emptyList()
        } else {
            segments
        }
    }

    /**
     * Winding number algorithm for point-in-polygon test
     * More robust than ray casting for complex polygons
     */
    private fun isPointInsidePolygonWinding(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        val x = point.longitude
        val y = point.latitude
        var windingNumber = 0

        for (i in polygon.indices) {
            val x1 = polygon[i].longitude
            val y1 = polygon[i].latitude
            val x2 = polygon[(i + 1) % polygon.size].longitude
            val y2 = polygon[(i + 1) % polygon.size].latitude

            if (y1 <= y) {
                if (y2 > y) {
                    // Upward crossing
                    val cross = (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1)
                    if (cross > 0) {
                        windingNumber++
                    }
                }
            } else {
                if (y2 <= y) {
                    // Downward crossing
                    val cross = (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1)
                    if (cross < 0) {
                        windingNumber--
                    }
                }
            }
        }

        return windingNumber != 0
    }

    /**
     * Robust point-in-polygon test using ray casting
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
     * Expand a polygon outward by [bufferMeters], returning the offset ring.
     *
     * Worked in a local metric frame, NOT in raw degrees. The previous version built its edge
     * normals as `(-dLon, dLat)` straight from lat/lon differences, which is only perpendicular
     * on the ground where one degree of longitude equals one degree of latitude — i.e. at the
     * equator. It also decided outward-vs-inward once, from the midpoint of a single edge's
     * distance to the centroid, and then applied that one decision to BOTH of the vertex's
     * normals. On any polygon that is not a small convex blob near the equator, some vertices
     * were pushed inward instead of outward; the ring folded through itself, the inside/outside
     * test flipped over part of the shape, and spray lines were planned through the obstacle.
     *
     * Here: project to metres, normalise the winding so "outward" is a property of the ring
     * rather than a per-vertex guess, offset each edge along its true outward normal, and miter
     * the corners.
     *
     * Note this is a miter offset, not a full polygon-offset with self-intersection removal, so
     * a deeply concave shape can still fold on itself at a narrow neck. Callers must not treat
     * the result as a guaranteed superset of the input — splitLineAroundObstacles deliberately
     * tests the original polygons alongside these rings for exactly that reason.
     */
    private fun expandPolygonEdgeBased(polygon: List<LatLng>, bufferMeters: Double): List<LatLng> {
        if (polygon.size < 3 || bufferMeters <= 0) return polygon

        // Local east/north frame about the polygon's mean position. Over an obstacle-sized
        // shape the flat-earth approximation is far below the metre we care about here.
        val refLat = polygon.map { it.latitude }.average()
        val refLon = polygon.map { it.longitude }.average()
        val metresPerDegLat = 111111.0
        val metresPerDegLon = 111111.0 * cos(Math.toRadians(refLat))
        if (abs(metresPerDegLon) < 1e-6) return polygon  // at the poles; nothing sensible to do

        // Project, dropping consecutive duplicate vertices. A zero-length edge has no direction,
        // so it has no normal, and leaving one in poisons the miter at both its ends.
        val ring = mutableListOf<Pair<Double, Double>>()
        for (p in polygon) {
            val e = (p.longitude - refLon) * metresPerDegLon
            val n = (p.latitude - refLat) * metresPerDegLat
            val last = ring.lastOrNull()
            if (last == null || hypot(e - last.first, n - last.second) > 1e-6) {
                ring.add(Pair(e, n))
            }
        }
        // The ring is implicitly closed, so a repeated first/last vertex is also a duplicate.
        if (ring.size >= 2) {
            val f = ring.first()
            val l = ring.last()
            if (hypot(f.first - l.first, f.second - l.second) <= 1e-6) ring.removeAt(ring.size - 1)
        }
        if (ring.size < 3) return polygon

        // Normalise the winding to counter-clockwise. With a known winding, the outward normal
        // of the edge from A to B is simply its right-hand normal — no centroid heuristic, and
        // no chance of one vertex disagreeing with the next about which way is out.
        var twiceArea = 0.0
        for (i in ring.indices) {
            val (x1, y1) = ring[i]
            val (x2, y2) = ring[(i + 1) % ring.size]
            twiceArea += x1 * y2 - x2 * y1
        }
        if (abs(twiceArea) < 1e-9) return polygon  // degenerate: collinear vertices, no interior
        val ccw = if (twiceArea > 0) ring.toList() else ring.reversed()

        val n = ccw.size

        // Outward unit normal of edge i (from vertex i to vertex i+1).
        val normals = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            val (x1, y1) = ccw[i]
            val (x2, y2) = ccw[(i + 1) % n]
            val dx = x2 - x1
            val dy = y2 - y1
            val len = hypot(dx, dy)
            // Right-hand normal of a CCW ring points away from the interior.
            normals.add(Pair(dy / len, -dx / len))
        }

        // Miter each vertex: vertex i is shared by edge i-1 and edge i, so it moves along the
        // bisector of their two outward normals, far enough that BOTH offset edges pass through
        // it. That distance is buffer / cos(half-angle), which grows without bound as the corner
        // gets sharper — hence the limit below.
        val expanded = ArrayList<LatLng>(n)
        for (i in 0 until n) {
            val (px, py) = normals[(i - 1 + n) % n]
            val (cx, cy) = normals[i]

            val bx = px + cx
            val by = py + cy
            val blen = hypot(bx, by)

            val (ux, uy) = if (blen < 1e-9) {
                // The two edges double back on each other (a spike). There is no bisector;
                // push straight out along the outgoing edge's normal.
                Pair(cx, cy)
            } else {
                Pair(bx / blen, by / blen)
            }

            // cos(half-angle) between the bisector and either normal.
            val cosHalf = ux * px + uy * py
            val miterMult = if (cosHalf > 1.0 / MITER_LIMIT) 1.0 / cosHalf else MITER_LIMIT
            val dist = bufferMeters * miterMult

            val (vx, vy) = ccw[i]
            val outE = vx + ux * dist
            val outN = vy + uy * dist
            expanded.add(
                LatLng(
                    refLat + outN / metresPerDegLat,
                    refLon + outE / metresPerDegLon
                )
            )
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

    /**
     * Calculate transition waypoints around obstacle boundaries.
     * This ensures the drone routes around the obstacle edge instead of flying diagonally across it.
     *
     * Strategy:
     * 1. Find which obstacle lies between start and end points
     * 2. Determine the closest edge of that obstacle to both points
     * 3. Generate waypoints along that edge (either top or bottom)
     *
     * @param start The ending point of Zone 0 (last point before transition)
     * @param end The starting point of Zone 1 (first point after transition)
     * @param expandedObstacles The obstacle polygons expanded by buffer
     * @return List of intermediate waypoints to follow the obstacle boundary
     */
    private fun calculateTransitionWaypointsAroundObstacle(
        start: LatLng,
        end: LatLng,
        expandedObstacles: List<List<LatLng>>
    ): List<LatLng> {
        if (expandedObstacles.isEmpty()) return emptyList()

        val transitionPoints = mutableListOf<LatLng>()

        // Find which obstacle is between start and end
        var relevantObstacle: List<LatLng>? = null
        for (obstacle in expandedObstacles) {
            if (obstacle.size < 3) continue
            // Check if direct path from start to end would cross this obstacle
            if (lineIntersectsObstacle(start, end, obstacle)) {
                relevantObstacle = obstacle
                break
            }
        }

        if (relevantObstacle == null || relevantObstacle.size < 3) {
            return emptyList()
        }

        // Determine if we should go around the top or bottom of the obstacle
        // Calculate centroid of obstacle
        val obsCentroidLat = relevantObstacle.map { it.latitude }.average()

        // Determine the "direction" of the transition (up or down based on latitude)
        // If start is below end (going up), we should go around the top
        // If start is above end (going down), we should go around the bottom
        val goingUp = start.latitude < end.latitude

        // Sort obstacle points by latitude to find top and bottom vertices
        val sortedByLat = relevantObstacle.sortedBy { it.latitude }

        // Find corner points to route around
        val bottomPoints = sortedByLat.take(2).sortedBy { it.longitude }
        val topPoints = sortedByLat.takeLast(2).sortedBy { it.longitude }

        // Choose route based on which edge is closest to our start/end points
        // and which direction we're transitioning
        val startToObsCenterLat = obsCentroidLat - start.latitude

        // Determine which edge to follow (top or bottom)
        val useTopEdge = if (goingUp) {
            // Going up - prefer top edge if start is closer to bottom
            startToObsCenterLat > 0
        } else {
            // Going down - prefer bottom edge if start is closer to top
            startToObsCenterLat < 0
        }

        val edgePoints = if (useTopEdge) topPoints else bottomPoints

        // Find the closest point on the edge to start
        val closestToStart = edgePoints.minByOrNull {
            GridUtils.haversineDistance(start, it)
        } ?: return emptyList()

        // Find the closest point on the edge to end
        val closestToEnd = edgePoints.minByOrNull {
            GridUtils.haversineDistance(end, it)
        } ?: return emptyList()

        // Add edge points in order from start to end
        if (closestToStart != closestToEnd) {
            // Add both corner points
            val distStartToFirst = GridUtils.haversineDistance(start, edgePoints[0])
            val distStartToSecond = GridUtils.haversineDistance(start, edgePoints[1])

            if (distStartToFirst < distStartToSecond) {
                transitionPoints.add(edgePoints[0])
                transitionPoints.add(edgePoints[1])
            } else {
                transitionPoints.add(edgePoints[1])
                transitionPoints.add(edgePoints[0])
            }
        } else {
            // Just add the single closest point
            transitionPoints.add(closestToStart)
        }

        return transitionPoints
    }

    /**
     * Check if a line from start to end intersects with an obstacle polygon
     */
    private fun lineIntersectsObstacle(start: LatLng, end: LatLng, obstacle: List<LatLng>): Boolean {
        if (obstacle.size < 3) return false

        // Check if the line passes through the obstacle
        val numSamples = 20
        for (i in 1 until numSamples) {
            val t = i.toDouble() / numSamples
            val lat = start.latitude + t * (end.latitude - start.latitude)
            val lng = start.longitude + t * (end.longitude - start.longitude)
            val point = LatLng(lat, lng)

            if (isPointInsidePolygonWinding(point, obstacle) ||
                isPointInPolygonRobust(point, obstacle)) {
                return true
            }
        }

        // Also check if line segment intersects any edge of the polygon
        for (i in obstacle.indices) {
            val p1 = obstacle[i]
            val p2 = obstacle[(i + 1) % obstacle.size]
            if (lineSegmentsIntersect(start, end, p1, p2)) {
                return true
            }
        }

        return false
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

        return false
    }

    /**
     * Calculate the cross product direction
     */
    private fun direction(pi: LatLng, pj: LatLng, pk: LatLng): Double {
        return (pk.longitude - pi.longitude) * (pj.latitude - pi.latitude) -
               (pj.longitude - pi.longitude) * (pk.latitude - pi.latitude)
    }

    // calculateBoundaryTransitionPath used to live here. It routed the drone around the END of
    // an obstacle when the old zone scheme moved it from the near band to the far band. The
    // flight order no longer has bands, so there is no such move to make: a line interrupted by
    // an obstacle is now stepped around and resumed in place, by detourAround inside
    // generateGridSurvey, which is the only obstacle routing left.
}
