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
    geofenceAdjustmentEnabled: Boolean = false
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

    // Markers with text labels for grid waypoints
    val startMarker = remember { createMarkerWithText("S", android.graphics.Color.GREEN) }
    val endMarker = remember { createMarkerWithText("E", android.graphics.Color.RED) }

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
                        key(index) { // Use key to ensure proper recomposition per marker
                            val markerState = rememberMarkerState(position = point)

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

        // Drone marker using quadcopter image; centered via anchor Offset(0.5f, 0.5f)
        if (lat != null && lon != null) {
            Marker(
                state = MarkerState(position = LatLng(lat, lon)),
                title = "Drone",
                icon = droneIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                anchor = Offset(0.5f, 0.5f),
                rotation = heading ?: 0f
            )
        }

        // Regular waypoint markers and planned route (blue)
        if (points.isNotEmpty() && surveyPolygon.isEmpty()) {
            points.forEachIndexed { index, point ->
                key(index) { // Use key to ensure proper recomposition per marker
                    val markerState = rememberMarkerState(position = point)

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
                key(index) { // Use key to ensure proper recomposition per marker
                    val markerState = rememberMarkerState(position = point)

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
            } else if (surveyPolygon.size == 2) {
                Polyline(points = surveyPolygon, width = 3f, color = Color.Magenta)
            }
        }

        // Grid lines (green)
        gridLines.forEach { line ->
            if (line.size >= 2) {
                Polyline(
                    points = line,
                    width = 2f,
                    color = Color.Green
                )
            }
        }

        // Grid waypoints (first: S/green with text, last: E/red with text, others: orange)
        if (gridWaypoints.isNotEmpty()) {
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
