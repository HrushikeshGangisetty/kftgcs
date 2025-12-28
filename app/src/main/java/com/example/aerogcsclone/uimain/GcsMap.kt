package com.example.aerogcsclone.uimain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.aerogcsclone.R
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.*
import com.google.maps.android.SphericalUtil
import java.util.Locale

// Helper function to create medium-sized marker icons for waypoints
private fun createMediumMarker(hue: Float): BitmapDescriptor {
    // Create a medium-sized bitmap (50% of original marker size)
    val scale = 0.5f
    val width = (64 * scale).toInt() // Default marker is ~64px
    val height = (64 * scale).toInt()

    // Create a medium-sized colored circle as marker
    val mediumBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mediumBitmap)

    // Draw a medium-sized colored circle
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = when (hue) {
            BitmapDescriptorFactory.HUE_AZURE -> android.graphics.Color.CYAN
            BitmapDescriptorFactory.HUE_VIOLET -> android.graphics.Color.MAGENTA
            BitmapDescriptorFactory.HUE_GREEN -> android.graphics.Color.GREEN
            BitmapDescriptorFactory.HUE_RED -> android.graphics.Color.RED
            BitmapDescriptorFactory.HUE_ORANGE -> android.graphics.Color.rgb(255, 165, 0)
            else -> android.graphics.Color.BLUE
        }
        style = android.graphics.Paint.Style.FILL
    }

    canvas.drawCircle(width / 2f, height / 2f, width / 2f - 1, paint)

    // Add a border for visibility
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(width / 2f, height / 2f, width / 2f - 2, paint)

    return BitmapDescriptorFactory.fromBitmap(mediumBitmap)
}

// Helper function to create markers with text labels
private fun createMarkerWithText(text: String, backgroundColor: Int): BitmapDescriptor {
    val size = 80 // Larger size to accommodate text
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw the colored circle background
    val circlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = backgroundColor
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, circlePaint)

    // Draw white border
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, borderPaint)

    // Draw the text
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 48f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Center the text vertically
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(text, 0, text.length, textBounds)
    val textY = size / 2f + textBounds.height() / 2f - textBounds.bottom

    canvas.drawText(text, size / 2f, textY, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create small rounded label for area/dimension display
private fun createSmallLabelMarker(text: String, backgroundColor: Int = android.graphics.Color.WHITE): BitmapDescriptor {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 28f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    // Measure text width
    val textBounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, textBounds)

    val paddingH = 16
    val paddingV = 10
    val width = textBounds.width() + paddingH * 2
    val height = textBounds.height() + paddingV * 2

    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(40), height.coerceAtLeast(30), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw rounded rectangle background
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = backgroundColor
        style = android.graphics.Paint.Style.FILL
    }
    val rect = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawRoundRect(rect, 8f, 8f, bgPaint)

    // Draw border
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

    // Draw text
    paint.color = android.graphics.Color.BLACK
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val textY = bitmap.height / 2f + textBounds.height() / 2f - textBounds.bottom
    canvas.drawText(text, bitmap.width / 2f, textY, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@Composable
fun GcsMap(
    telemetryState: TelemetryState,
    points: List<LatLng> = emptyList(),
    onMapClick: (LatLng) -> Unit = {},
    cameraPositionState: CameraPositionState? = null,
    mapType: MapType = MapType.NORMAL,
    autoCenter: Boolean = true,
    // Grid survey parameters
    surveyPolygon: List<LatLng> = emptyList(),
    gridLines: List<List<LatLng>> = emptyList(),
    gridWaypoints: List<LatLng> = emptyList(),
    heading: Float? = null,
    // Split Plan parameters - for visual feedback
    splitPlanMode: Boolean = false,
    splitGridLines: List<List<LatLng>> = emptyList(),
    splitGridWaypoints: List<LatLng> = emptyList(),
    // Geofence parameters - now using polygon instead of circle
    geofencePolygon: List<LatLng> = emptyList(),
    geofenceEnabled: Boolean = false,
    // Waypoint drag callback
    onWaypointDrag: (index: Int, newPosition: LatLng) -> Unit = { _, _ -> },
    // Waypoint selection
    selectedWaypointIndex: Int? = null,
    onWaypointClick: (index: Int) -> Unit = {},
    // Polygon point drag callback
    onPolygonPointDrag: (index: Int, newPosition: LatLng) -> Unit = { _, _ -> },
    // Polygon point selection
    selectedPolygonPointIndex: Int? = null,
    onPolygonPointClick: (index: Int) -> Unit = {},
    // Geofence point drag callback
    onGeofencePointDrag: (index: Int, newPosition: LatLng) -> Unit = { _, _ -> },
    // Geofence point selection
    selectedGeofencePointIndex: Int? = null,
    onGeofencePointClick: (index: Int) -> Unit = {},
    // Geofence adjustment mode
    geofenceAdjustmentEnabled: Boolean = false,
    // Show grid info (area at center, dimensions on edges)
    showGridInfo: Boolean = false,
    // Obstacle zones - list of polygons representing no-fly zones
    obstacles: List<List<LatLng>> = emptyList(),
    // Obstacle editing mode
    isAddingObstacle: Boolean = false,
    currentObstaclePoints: List<LatLng> = emptyList(),
    // Obstacle selection for editing
    selectedObstacleIndex: Int? = null,
    onObstacleClick: (obstacleIndex: Int) -> Unit = {},
    // Obstacle point drag callback
    onObstaclePointDrag: (obstacleIndex: Int, pointIndex: Int, newPosition: LatLng) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val cameraState = cameraPositionState ?: rememberCameraPositionState()

    val visitedPositions = remember { mutableStateListOf<LatLng>() }

    // Load quadcopter drawable from res/drawable and scale to dp-based size
    val droneIcon = remember {
        runCatching {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.d_image_prev_ui)
            val sizeDp = 64f
            val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val scaled = Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
            BitmapDescriptorFactory.fromBitmap(scaled)
        }.getOrNull()
    }

    // Create medium-sized marker icons for waypoints (50% of default size)
    val mediumBlueMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_AZURE) }
    val mediumVioletMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_VIOLET) }
    val mediumOrangeMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_ORANGE) }
    val mediumYellowMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_YELLOW) } // For selected waypoint
    val mediumRedMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_RED) } // For obstacles

    // Markers with text labels for grid waypoints
    val startMarker = remember { createMarkerWithText("S", android.graphics.Color.GREEN) }
    val endMarker = remember { createMarkerWithText("E", android.graphics.Color.RED) }
    val obstacleXMarker = remember { createMarkerWithText("X", android.graphics.Color.RED) } // For obstacle centroid

    val lat = telemetryState.latitude
    val lon = telemetryState.longitude
    if (autoCenter && lat != null && lon != null) {
        cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
    }

    LaunchedEffect(lat, lon) {
        if (lat != null && lon != null) {
            val pos = LatLng(lat, lon)
            if (visitedPositions.isEmpty() || visitedPositions.last() != pos) {
                visitedPositions.add(pos)
                val maxLen = 2000
                if (visitedPositions.size > maxLen) {
                    val removeCount = visitedPositions.size - maxLen
                    repeat(removeCount) { visitedPositions.removeAt(0) }
                }
            }
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        properties = MapProperties(mapType = mapType),
        uiSettings = MapUiSettings(zoomControlsEnabled = false), // Disable zoom controls
        onMapClick = { latLng -> onMapClick(latLng) }
    ) {
        // Polygon geofence boundary overlay (replaces circular fence)
        if (geofenceEnabled && geofencePolygon.isNotEmpty()) {
            // Draw the polygon boundary
            if (geofencePolygon.size >= 3) {
                // Close the polygon by connecting last point to first
                val closedPolygon = geofencePolygon + geofencePolygon.first()
                Polyline(
                    points = closedPolygon,
                    width = 4f,
                    color = Color.Red
                )

                // Fill the polygon area with semi-transparent red
                Polygon(
                    points = geofencePolygon,
                    fillColor = Color.Red.copy(alpha = 0.2f),
                    strokeColor = Color.Red,
                    strokeWidth = 4f
                )

                // Add draggable markers for geofence adjustment when enabled
                if (geofenceAdjustmentEnabled) {
                    geofencePolygon.forEachIndexed { index, point ->
                        key("geofence_$index") { // Unique key per geofence point
                            // Use point coordinates as key to update marker only when polygon actually changes
                            val markerState = rememberMarkerState(
                                key = "gf_${point.latitude}_${point.longitude}",
                                position = point
                            )

                            // Listen to marker position changes for drag events
                            LaunchedEffect(markerState.position) {
                                if (markerState.position != point) {
                                    onGeofencePointDrag(index, markerState.position)
                                }
                            }

                            // Determine the marker icon based on selection state
                            val markerIcon = if (selectedGeofencePointIndex == index) {
                                mediumYellowMarker // Selected geofence point - Yellow
                            } else {
                                mediumOrangeMarker // Default - Orange/Red for geofence
                            }

                            Marker(
                                state = markerState,
                                title = "GF${index + 1}",
                                icon = markerIcon,
                                anchor = Offset(0.5f, 0.5f),
                                draggable = true,  // Enable dragging
                                onClick = {
                                    // Marker clicked, can be dragged now
                                    onGeofencePointClick(index) // Handle geofence point click
                                    true
                                }
                            )
                        }
                    }
                }
            }
        }

        // ===== OBSTACLE ZONES RENDERING =====
        // Render saved obstacle zones as red semi-transparent polygons
        obstacles.forEachIndexed { obstacleIndex, obstaclePoints ->
            if (obstaclePoints.size >= 3) {
                // Fill the obstacle area with semi-transparent red
                Polygon(
                    points = obstaclePoints,
                    fillColor = Color.Red.copy(alpha = 0.35f),
                    strokeColor = Color.Red,
                    strokeWidth = 3f
                )

                // Draw obstacle boundary outline
                val closedObstacle = obstaclePoints + obstaclePoints.first()
                Polyline(
                    points = closedObstacle,
                    width = 3f,
                    color = Color.Red
                )

                // Add clickable/draggable markers at obstacle vertices when selected
                if (selectedObstacleIndex == obstacleIndex) {
                    obstaclePoints.forEachIndexed { pointIndex, point ->
                        key("obstacle_${obstacleIndex}_${pointIndex}") {
                            val markerState = rememberMarkerState(
                                key = "obs_${obstacleIndex}_${point.latitude}_${point.longitude}",
                                position = point
                            )

                            LaunchedEffect(markerState.position) {
                                if (markerState.position != point) {
                                    onObstaclePointDrag(obstacleIndex, pointIndex, markerState.position)
                                }
                            }

                            Marker(
                                state = markerState,
                                title = "Obs${obstacleIndex + 1}-P${pointIndex + 1}",
                                icon = mediumRedMarker,
                                anchor = Offset(0.5f, 0.5f),
                                draggable = true,
                                onClick = {
                                    onObstacleClick(obstacleIndex)
                                    true
                                }
                            )
                        }
                    }
                } else {
                    // Just show a single click marker at centroid when not selected
                    val centroidLat = obstaclePoints.map { it.latitude }.average()
                    val centroidLon = obstaclePoints.map { it.longitude }.average()
                    val centroid = LatLng(centroidLat, centroidLon)

                    Marker(
                        state = MarkerState(position = centroid),
                        title = "Obstacle ${obstacleIndex + 1}",
                        icon = obstacleXMarker,
                        anchor = Offset(0.5f, 0.5f),
                        onClick = {
                            onObstacleClick(obstacleIndex)
                            true
                        }
                    )
                }
            }
        }

        // Render obstacle being currently drawn (in progress)
        if (isAddingObstacle && currentObstaclePoints.isNotEmpty()) {
            // Draw the points added so far
            currentObstaclePoints.forEachIndexed { index, point ->
                Marker(
                    state = MarkerState(position = point),
                    title = "New Obs P${index + 1}",
                    icon = mediumRedMarker,
                    anchor = Offset(0.5f, 0.5f)
                )
            }

            // Draw lines connecting the points
            if (currentObstaclePoints.size >= 2) {
                Polyline(
                    points = currentObstaclePoints,
                    width = 3f,
                    color = Color.Red.copy(alpha = 0.7f)
                )
            }

            // If we have 3+ points, show the closing line preview (dashed effect via alpha)
            if (currentObstaclePoints.size >= 3) {
                Polyline(
                    points = listOf(currentObstaclePoints.last(), currentObstaclePoints.first()),
                    width = 2f,
                    color = Color.Red.copy(alpha = 0.4f)
                )

                // Show semi-transparent fill preview
                Polygon(
                    points = currentObstaclePoints,
                    fillColor = Color.Red.copy(alpha = 0.2f),
                    strokeColor = Color.Transparent,
                    strokeWidth = 0f
                )
            }
        }

        // Drone marker using quadcopter image; centered via anchor Offset(0.5f, 0.5f)
        if (lat != null && lon != null) {
            // Create a unique key that changes when heading changes significantly (every 1 degree)
            // This forces the marker to recreate and apply new rotation
            val headingKey = remember(heading) {
                (heading ?: 0f).toInt()
            }

            key(headingKey) {
                val droneMarkerState = rememberMarkerState(
                    key = "drone_marker_$headingKey",
                    position = LatLng(lat, lon)
                )

                // Update marker position when lat/lon changes
                LaunchedEffect(lat, lon) {
                    droneMarkerState.position = LatLng(lat, lon)
                }

                // The rotation is passed directly to the Marker
                Marker(
                    state = droneMarkerState,
                    title = "Drone",
                    icon = droneIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                    anchor = Offset(0.5f, 0.5f),
                    rotation = heading ?: 0f,
                    flat = true  // Make the marker flat on the map so rotation works correctly
                )
            }
        }

        // Regular waypoint markers and planned route (blue)
        if (points.isNotEmpty() && surveyPolygon.isEmpty()) {
            points.forEachIndexed { index, point ->
                key("waypoint_$index") { // Unique key per waypoint
                    // Use point coordinates as key to update marker only when waypoint actually changes
                    val markerState = rememberMarkerState(
                        key = "wp_${point.latitude}_${point.longitude}",
                        position = point
                    )

                    // Listen to marker position changes for drag events
                    LaunchedEffect(markerState.position) {
                        if (markerState.position != point) {
                            onWaypointDrag(index, markerState.position)
                        }
                    }

                    // Determine the marker icon based on selection state
                    val markerIcon = if (selectedWaypointIndex == index) {
                        mediumYellowMarker // Selected waypoint - Yellow
                    } else {
                        mediumBlueMarker // Default - Blue
                    }

                    Marker(
                        state = markerState,
                        title = "WP ${index + 1}",
                        icon = markerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        draggable = true,  // Enable dragging
                        onClick = {
                            // Marker clicked, can be dragged now
                            onWaypointClick(index) // Handle waypoint click
                            true
                        }
                    )
                }
            }
            if (points.size > 1) {
                key(points) {
                    Polyline(points = points, width = 4f, color = Color.Blue)
                }
            }
        }

        // Survey polygon outline (purple) - Now draggable!
        if (surveyPolygon.isNotEmpty()) {
            surveyPolygon.forEachIndexed { index, point ->
                key("polygon_$index") { // Unique key per polygon point
                    // Use point coordinates as key to update marker only when polygon actually changes
                    val markerState = rememberMarkerState(
                        key = "poly_${point.latitude}_${point.longitude}",
                        position = point
                    )

                    // Listen to marker position changes for drag events
                    LaunchedEffect(markerState.position) {
                        if (markerState.position != point) {
                            onPolygonPointDrag(index, markerState.position)
                        }
                    }

                    // Determine the marker icon based on selection state
                    val markerIcon = if (selectedPolygonPointIndex == index) {
                        mediumYellowMarker // Selected polygon point - Yellow
                    } else {
                        mediumVioletMarker // Default - Purple
                    }

                    Marker(
                        state = markerState,
                        title = "P${index + 1}",
                        icon = markerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        draggable = true,  // Enable dragging
                        onClick = {
                            // Marker clicked, can be dragged now
                            onPolygonPointClick(index) // Handle polygon point click
                            true
                        }
                    )
                }
            }

            if (surveyPolygon.size > 2) {
                // Close the polygon by connecting last point to first
                val closedPolygon = surveyPolygon + surveyPolygon.first()
                Polyline(points = closedPolygon, width = 3f, color = Color.Magenta)

                // Show area and dimensions when enabled
                if (showGridInfo) {
                    // Calculate area in acres
                    val areaInSqMeters = SphericalUtil.computeArea(surveyPolygon)
                    val areaInSqFeet = areaInSqMeters * 10.7639
                    val areaInAcres = areaInSqFeet / 43560.0
                    val areaText = String.format(Locale.US, "%.2f acres", areaInAcres)

                    // Calculate centroid of the polygon for area label
                    val centroidLat = surveyPolygon.map { it.latitude }.average()
                    val centroidLon = surveyPolygon.map { it.longitude }.average()
                    val centroid = LatLng(centroidLat, centroidLon)

                    // Create area label marker
                    val areaLabelIcon = remember(areaText) {
                        createSmallLabelMarker(areaText)
                    }

                    Marker(
                        state = MarkerState(position = centroid),
                        title = "Area: $areaText",
                        icon = areaLabelIcon,
                        anchor = Offset(0.5f, 0.5f)
                    )

                    // Display edge dimensions for each side of the polygon
                    surveyPolygon.forEachIndexed { index, point ->
                        val nextIndex = (index + 1) % surveyPolygon.size
                        val nextPoint = surveyPolygon[nextIndex]

                        // Calculate distance between consecutive points
                        val distanceMeters = SphericalUtil.computeDistanceBetween(point, nextPoint)
                        val distanceText = if (distanceMeters >= 1000) {
                            String.format(Locale.US, "%.1f km", distanceMeters / 1000)
                        } else {
                            String.format(Locale.US, "%.0f m", distanceMeters)
                        }

                        // Position the label at the midpoint of the edge
                        val midLat = (point.latitude + nextPoint.latitude) / 2
                        val midLon = (point.longitude + nextPoint.longitude) / 2
                        val midPoint = LatLng(midLat, midLon)

                        // Create dimension label marker
                        val dimLabelIcon = remember(distanceText, index) {
                            createSmallLabelMarker(distanceText)
                        }

                        Marker(
                            state = MarkerState(position = midPoint),
                            title = "Edge ${index + 1}: $distanceText",
                            icon = dimLabelIcon,
                            anchor = Offset(0.5f, 0.5f)
                        )
                    }
                }
            } else if (surveyPolygon.size == 2) {
                Polyline(points = surveyPolygon, width = 3f, color = Color.Magenta)
            }
        }

        // Grid lines (green for original, or gray when in split mode)
        gridLines.forEach { line ->
            if (line.size >= 2) {
                Polyline(
                    points = line,
                    width = 2f,
                    color = if (splitPlanMode) Color.Gray.copy(alpha = 0.5f) else Color.Green
                )
            }
        }

        // Split Plan: Highlight selected grid lines in yellow
        if (splitPlanMode && splitGridLines.isNotEmpty()) {
            splitGridLines.forEach { line ->
                if (line.size >= 2) {
                    Polyline(
                        points = line,
                        width = 4f,  // Thicker line for selected portion
                        color = Color.Yellow
                    )
                }
            }
        }

        // Grid waypoints (first: S/green with text, last: E/red with text, others: orange)
        // In split plan mode, show original waypoints as gray/dimmed, and split waypoints highlighted
        if (gridWaypoints.isNotEmpty()) {
            if (splitPlanMode && splitGridWaypoints.isNotEmpty()) {
                // In split mode: show original waypoints as dimmed (no start/end markers)
                gridWaypoints.forEachIndexed { index, waypoint ->
                    // Show all original waypoints as small gray markers
                    Marker(
                        state = MarkerState(position = waypoint),
                        title = "G${index + 1}",
                        icon = mediumOrangeMarker,
                        anchor = Offset(0.5f, 0.5f),
                        alpha = 0.3f // Dimmed
                    )
                }

                // Now show split waypoints with proper start/end markers
                splitGridWaypoints.forEachIndexed { index, waypoint ->
                    val isFirst = index == 0
                    val isLast = index == splitGridWaypoints.lastIndex

                    when {
                        isFirst -> {
                            // Start marker - Green with "S" text for split start
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "Split Start",
                                icon = startMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                        isLast -> {
                            // End marker - Red with "E" text for split end
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "Split End",
                                icon = endMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                        else -> {
                            // Intermediate waypoints - Yellow (highlighted)
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "S${index + 1}",
                                icon = mediumYellowMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                    }
                }
            } else {
                // Normal mode: show regular waypoints
                gridWaypoints.forEachIndexed { index, waypoint ->
                    val isFirst = index == 0
                    val isLast = index == gridWaypoints.lastIndex

                    when {
                        isFirst -> {
                            // Start marker - Green with "S" text
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "Start",
                                icon = startMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                        isLast -> {
                            // End marker - Red with "E" text
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "End",
                                icon = endMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                        else -> {
                            // Intermediate waypoints - Orange
                            Marker(
                                state = MarkerState(position = waypoint),
                                title = "G${index + 1}",
                                icon = mediumOrangeMarker,
                                anchor = Offset(0.5f, 0.5f)
                            )
                        }
                    }
                }
            }
        }

        // Red polyline showing the drone's traveled path
        if (visitedPositions.size > 1) {
            Polyline(points = visitedPositions.toList(), width = 6f, color = Color.Red)
        }

        // Optional grid overlay for regular waypoints
        if (points.size >= 4 && surveyPolygon.isEmpty()) {
            val lats = points.map { it.latitude }
            val lons = points.map { it.longitude }
            val minLat = lats.minOrNull() ?: 0.0
            val maxLat = lats.maxOrNull() ?: 0.0
            val minLon = lons.minOrNull() ?: 0.0
            val maxLon = lons.maxOrNull() ?: 0.0

            val latSteps = listOf(minLat, (minLat + maxLat) / 2.0, maxLat)
            val lonSteps = listOf(minLon, (minLon + maxLon) / 2.0, maxLon)

            lonSteps.forEach { lonVal ->
                val line = listOf(LatLng(minLat, lonVal), LatLng(maxLat, lonVal))
                Polyline(points = line, width = 2f, color = Color.Gray)
            }
            latSteps.forEach { latVal ->
                val line = listOf(LatLng(latVal, minLon), LatLng(latVal, maxLon))
                Polyline(points = line, width = 2f, color = Color.Gray)
            }
        }
    }
}
