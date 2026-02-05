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
import kotlin.math.sqrt

// Helper function to create larger marker icons for waypoints - easier to interact with
private fun createMediumMarker(hue: Float): BitmapDescriptor {
    // Create a larger bitmap for better touch targets (72px for mobile-friendly interaction)
    val size = 72
    val width = size
    val height = size

    // Create a larger colored circle as marker for easier interaction
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw a colored circle with improved visibility
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = when (hue) {
            BitmapDescriptorFactory.HUE_AZURE -> android.graphics.Color.CYAN
            BitmapDescriptorFactory.HUE_VIOLET -> android.graphics.Color.MAGENTA
            BitmapDescriptorFactory.HUE_GREEN -> android.graphics.Color.GREEN
            BitmapDescriptorFactory.HUE_RED -> android.graphics.Color.RED
            BitmapDescriptorFactory.HUE_ORANGE -> android.graphics.Color.rgb(255, 165, 0)
            BitmapDescriptorFactory.HUE_YELLOW -> android.graphics.Color.YELLOW
            else -> android.graphics.Color.BLUE
        }
        style = android.graphics.Paint.Style.FILL
    }

    canvas.drawCircle(width / 2f, height / 2f, width / 2f - 4, paint)

    // Add a thicker white border for better visibility
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 5f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(width / 2f, height / 2f, width / 2f - 4, paint)

    // Add a thin dark outline for contrast on light backgrounds
    paint.strokeWidth = 2f
    paint.color = android.graphics.Color.DKGRAY
    canvas.drawCircle(width / 2f, height / 2f, width / 2f - 1, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create larger markers with text labels for better interaction
private fun createMarkerWithText(text: String, backgroundColor: Int): BitmapDescriptor {
    val size = 100 // Larger size for better touch targets and visibility
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw the colored circle background
    val circlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = backgroundColor
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, circlePaint)

    // Draw thicker white border for visibility
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, borderPaint)

    // Draw dark outline for contrast
    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, outlinePaint)

    // Draw the text - larger and bolder
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 56f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Add text shadow for better readability
    textPaint.setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)

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

// Helper function to create small green "R" marker for resume point
private fun createSmallResumeMarker(): BitmapDescriptor {
    val size = 60 // Small round marker
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw green circle background
    val circlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(76, 175, 80) // Material Green 500
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, circlePaint)

    // Draw white border for visibility
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, borderPaint)

    // Draw dark outline for contrast
    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, outlinePaint)

    // Draw the "R" text
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 36f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Add text shadow for better readability
    textPaint.setShadowLayer(1f, 1f, 1f, android.graphics.Color.BLACK)

    // Center the text vertically
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds("R", 0, 1, textBounds)
    val textY = size / 2f + textBounds.height() / 2f - textBounds.bottom

    canvas.drawText("R", size / 2f, textY, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create RC marker icon for phone GPS location
private fun createRCMarker(): BitmapDescriptor {
    val size = 80 // Size for RC marker
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw outer circle with green color
    val outerPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(76, 175, 80) // Material Green 500
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, outerPaint)

    // Draw white border for visibility
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, borderPaint)

    // Draw dark outline for contrast
    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, outlinePaint)

    // Draw "RC" text
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Add text shadow for better readability
    textPaint.setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)

    // Center the text vertically
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds("RC", 0, 2, textBounds)
    val textY = size / 2f + textBounds.height() / 2f - textBounds.bottom

    canvas.drawText("RC", size / 2f, textY, textPaint)

    // Draw a small pulse ring effect
    val pulsePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(76, 175, 80)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 128
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, pulsePaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create drone icon with directional arrow indicating nose heading
private fun createDroneIconWithArrow(context: android.content.Context): BitmapDescriptor? {
    return runCatching {
        // Load the original drone image
        val droneBmp = BitmapFactory.decodeResource(context.resources, R.drawable.d_image_prev_ui)
        val sizeDp = 64f
        val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)

        // Create a mutable bitmap to draw on
        val resultBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Scale and draw the drone image centered
        val scaledDrone = Bitmap.createScaledBitmap(droneBmp, sizePx, sizePx, true)
        canvas.drawBitmap(scaledDrone, 0f, 0f, null)

        // Draw a prominent arrow pointing upward (north/0°) to indicate the nose direction
        val arrowPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        // Create an equilateral triangle arrow - all sides equal length
        val centerX = sizePx / 2f
        val triangleSize = sizePx * 0.35f  // Size of triangle sides

        // Calculate equilateral triangle dimensions
        // Height = side * sqrt(3)/2, but we'll position it for visual balance
        val height = triangleSize * (sqrt(4.0) / 2.0).toFloat()

        // Position triangle in upper portion of icon for clear direction indication
        val topY = sizePx * 0.15f  // Top vertex position
        val bottomY = topY + height * 0.8f  // Bottom edge (slightly compressed for better look)
        val leftX = centerX - triangleSize / 2f  // Left vertex
        val rightX = centerX + triangleSize / 2f  // Right vertex

        // Define arrow path - equilateral triangle pointing upward
        val arrowPath = android.graphics.Path().apply {
            // Top vertex (nose pointing up/north)
            moveTo(centerX, topY)
            // Bottom left vertex
            lineTo(leftX, bottomY)
            // Bottom right vertex
            lineTo(rightX, bottomY)
            // Close the path back to top vertex
            close()
        }

        // Draw the arrow fill
        canvas.drawPath(arrowPath, arrowPaint)

        // Add white border to arrow for better visibility and definition
        arrowPaint.style = android.graphics.Paint.Style.STROKE
        arrowPaint.color = android.graphics.Color.WHITE
        arrowPaint.strokeWidth = 2.5f
        canvas.drawPath(arrowPath, arrowPaint)

        BitmapDescriptorFactory.fromBitmap(resultBitmap)
    }.getOrNull()
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
    onObstaclePointDrag: (obstacleIndex: Int, pointIndex: Int, newPosition: LatLng) -> Unit = { _, _, _ -> },
    // Enable obstacle editing mode (shows X markers, allows selection)
    obstacleEditingEnabled: Boolean = false,
    // Resume point location - shows "R" marker where drone paused
    resumePointLocation: LatLng? = null,
    // RC Mode - Phone GPS location for RC marker
    phoneLocation: LatLng? = null,
    isRCMode: Boolean = false
) {
    val context = LocalContext.current
    val cameraState = cameraPositionState ?: rememberCameraPositionState()

    val visitedPositions = remember { mutableStateListOf<LatLng>() }

    // Load quadcopter drawable with directional arrow indicator
    val droneIcon = remember {
        createDroneIconWithArrow(context)
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
    val resumeMarker = remember { createSmallResumeMarker() } // Small green "R" for resume point
    val rcMarker = remember { createRCMarker() } // RC marker for phone GPS location

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
                    color = Color.Yellow
                )

                // Fill the polygon area with semi-transparent red
                Polygon(
                    points = geofencePolygon,
                    fillColor = Color.Red.copy(alpha = 0.05f),
                    strokeColor = Color.Yellow,
                    strokeWidth = 4f
                )

                // Add draggable markers for geofence adjustment when enabled
                if (geofenceAdjustmentEnabled) {
                    geofencePolygon.forEachIndexed { index, point ->
                        // Use only index in key to prevent marker recreation during drag
                        key("geofence_vertex_$index") {
                            val markerState = rememberMarkerState(
                                key = "geofence_marker_$index", // Stable key for marker state
                                position = point
                            )

                            // Force update marker position when source point changes (from external update)
                            LaunchedEffect(point) {
                                if (markerState.position != point) {
                                    markerState.position = point
                                }
                            }

                            // Listen to marker position changes for drag events with debounce
                            LaunchedEffect(markerState.position) {
                                // Only trigger callback if position actually differs from source
                                val newPos = markerState.position
                                val hasMoved = kotlin.math.abs(newPos.latitude - point.latitude) > 0.0000001 ||
                                              kotlin.math.abs(newPos.longitude - point.longitude) > 0.0000001
                                if (hasMoved) {
                                    onGeofencePointDrag(index, newPos)
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
                                zIndex = 10f, // Higher z-index for geofence markers to be on top
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

        // ===== OUTER FENCE RENDERING =====
        // Render outer fence 2m away from the normal geofence with yellow background
        if (geofenceEnabled && geofencePolygon.isNotEmpty() && geofencePolygon.size >= 3) {
            val outerFencePolygon = calculateOuterFence(geofencePolygon, 2.0) // 2 meters offset

            if (outerFencePolygon.isNotEmpty()) {
                // Fill the outer fence area with semi-transparent yellow
                Polygon(
                    points = outerFencePolygon,
                    fillColor = Color.Yellow.copy(alpha = 0.05f),
                    strokeColor = Color.Red,
                    strokeWidth = 3f
                )

                // Draw the outer fence boundary outline
                val closedOuterFence = outerFencePolygon + outerFencePolygon.first()
                Polyline(
                    points = closedOuterFence,
                    width = 3f,
                    color = Color.Red
                )
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

                // Always show distance labels on each edge (like boundary area)
                obstaclePoints.forEachIndexed { pointIndex, point ->
                    val nextIndex = (pointIndex + 1) % obstaclePoints.size
                    val nextPoint = obstaclePoints[nextIndex]

                    // Calculate distance between consecutive points
                    val distance = SphericalUtil.computeDistanceBetween(point, nextPoint)
                    val distanceText = if (distance >= 1000) {
                        String.format(Locale.US, "%.1fkm", distance / 1000)
                    } else {
                        String.format(Locale.US, "%.1fm", distance)
                    }

                    // Calculate midpoint for label placement
                    val midLat = (point.latitude + nextPoint.latitude) / 2
                    val midLon = (point.longitude + nextPoint.longitude) / 2
                    val midPoint = LatLng(midLat, midLon)

                    // Create a custom marker with distance text
                    val distanceMarkerIcon = remember(distanceText, obstacleIndex, pointIndex) {
                        createObstacleDistanceLabel(distanceText)
                    }

                    Marker(
                        state = MarkerState(position = midPoint),
                        title = "Edge ${pointIndex + 1}: $distanceText",
                        icon = distanceMarkerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        flat = true,
                        zIndex = 5f
                    )
                }

                // Add clickable/draggable markers at obstacle vertices when editing is enabled
                // Always show draggable vertex markers for all obstacles (not just selected) when obstacleEditingEnabled is true
                if (obstacleEditingEnabled) {
                    obstaclePoints.forEachIndexed { pointIndex, point ->
                        key("obstacle_${obstacleIndex}_${pointIndex}") {
                            val markerState = rememberMarkerState(
                                key = "obs_${obstacleIndex}_${point.latitude}_${point.longitude}",
                                position = point
                            )

                            // Force update marker position when source point changes (from external update)
                            LaunchedEffect(point) {
                                if (markerState.position != point) {
                                    markerState.position = point
                                }
                            }

                            LaunchedEffect(markerState.position) {
                                // Only trigger callback if position actually differs from source
                                val newPos = markerState.position
                                val hasMoved = kotlin.math.abs(newPos.latitude - point.latitude) > 0.0000001 ||
                                              kotlin.math.abs(newPos.longitude - point.longitude) > 0.0000001
                                if (hasMoved) {
                                    onObstaclePointDrag(obstacleIndex, pointIndex, newPos)
                                }
                            }

                            // Use different icon for selected obstacle vertices
                            val markerIcon = if (selectedObstacleIndex == obstacleIndex) {
                                mediumYellowMarker // Selected obstacle - Yellow
                            } else {
                                mediumRedMarker // Default - Red for obstacles
                            }

                            Marker(
                                state = markerState,
                                title = "Obs${obstacleIndex + 1}-P${pointIndex + 1}",
                                icon = markerIcon,
                                anchor = Offset(0.5f, 0.5f),
                                draggable = true,
                                zIndex = if (selectedObstacleIndex == obstacleIndex) 12f else 8f, // Selected obstacles on top
                                onClick = {
                                    onObstacleClick(obstacleIndex)
                                    true
                                }
                            )
                        }
                    }
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
            // Draw polyline connecting all waypoints
            if (points.size > 1) {
                // Use size and hash of points to trigger recomposition when waypoints change
                key("waypoint_polyline_${points.size}_${points.hashCode()}") {
                    Polyline(points = points.toList(), width = 8f, color = Color.Blue) // Thicker for better visibility
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
                Polyline(points = closedPolygon, width = 6f, color = Color.Magenta) // Thicker for better visibility

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
                Polyline(points = surveyPolygon, width = 6f, color = Color.Magenta) // Thicker for better visibility
            }
        }

        // Grid lines (red when drawing survey plan, or gray when in split mode)
        gridLines.forEach { line ->
            if (line.size >= 2) {
                Polyline(
                    points = line,
                    width = 4f, // Thicker for better visibility
                    color = if (splitPlanMode) Color.Gray.copy(alpha = 0.5f) else Color.Red
                )
            }
        }

        // Helper function to check if a line segment intersects with an obstacle polygon
        fun lineIntersectsObstacle(start: LatLng, end: LatLng, obstacle: List<LatLng>): Boolean {
            if (obstacle.size < 3) return false

            // Sample points along the line and check if any are inside the obstacle
            val numSamples = 20
            for (i in 1 until numSamples) {
                val t = i.toDouble() / numSamples
                val lat = start.latitude + t * (end.latitude - start.latitude)
                val lng = start.longitude + t * (end.longitude - start.longitude)

                // Simple point-in-polygon test (ray casting)
                var inside = false
                var j = obstacle.size - 1
                for (k in obstacle.indices) {
                    val xi = obstacle[k].longitude
                    val yi = obstacle[k].latitude
                    val xj = obstacle[j].longitude
                    val yj = obstacle[j].latitude

                    val intersect = ((yi > lat) != (yj > lat)) &&
                            (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)

                    if (intersect) inside = !inside
                    j = k
                }

                if (inside) return true
            }
            return false
        }

        // Draw connecting lines between grid waypoints (turn lines between survey lines)
        // These connect the end of one survey line to the start of the next
        // Skip lines that would cross obstacles
        if (gridWaypoints.size >= 2 && !splitPlanMode) {
            for (i in 0 until gridWaypoints.size - 1) {
                // Connect consecutive waypoints
                val start = gridWaypoints[i]
                val end = gridWaypoints[i + 1]
                // Check if this is a turn line (not part of a survey line)
                // Survey lines are between even-odd pairs (0-1, 2-3, 4-5, etc.)
                // Turn lines are between odd-even pairs (1-2, 3-4, 5-6, etc.)
                if (i % 2 == 1) { // Turn line (connecting line between survey lines)
                    // Check if this turn line crosses any obstacle
                    val crossesObstacle = obstacles.any { obstacle ->
                        lineIntersectsObstacle(start, end, obstacle)
                    }

                    if (!crossesObstacle) {
                        Polyline(
                            points = listOf(start, end),
                            width = 3f,
                            color = Color.Red.copy(alpha = 0.7f)
                        )
                    }
                    // If it crosses an obstacle, don't draw the line (drone will fly around)
                }
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

            // Draw connecting lines for split plan waypoints
            if (splitGridWaypoints.size >= 2) {
                for (i in 0 until splitGridWaypoints.size - 1) {
                    val start = splitGridWaypoints[i]
                    val end = splitGridWaypoints[i + 1]
                    // Turn lines are between odd-even pairs (1-2, 3-4, 5-6, etc.)
                    if (i % 2 == 1) {
                        // Check if this turn line crosses any obstacle
                        val crossesObstacle = obstacles.any { obstacle ->
                            lineIntersectsObstacle(start, end, obstacle)
                        }

                        if (!crossesObstacle) {
                            Polyline(
                                points = listOf(start, end),
                                width = 3f,
                                color = Color.Yellow.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // Grid waypoints (first: S/green with text, last: E/red with text, others: orange)
        // In split plan mode, show original waypoints as gray/dimmed, and split waypoints highlighted
        if (gridWaypoints.isNotEmpty()) {
            if (splitPlanMode && splitGridWaypoints.isNotEmpty()) {
                // In split mode: Don't show dimmed markers to reduce clutter
                // Grid lines already show the full pattern

                // Show split waypoints with only start/end markers
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
                        // No intermediate markers
                    }
                }
            } else {
                // Normal mode: show only Start and End markers, no intermediate waypoints
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
                        // No intermediate markers - just use grid lines
                    }
                }
            }
        }

        // Green polyline showing the drone's traveled path
        if (visitedPositions.size > 1) {
            Polyline(points = visitedPositions.toList(), width = 6f, color = Color.Green)
        }

        // ===== RESUME POINT MARKER =====
        // Show green "R" marker at the location where drone paused (LOITER mode)
        if (resumePointLocation != null) {
            Marker(
                state = MarkerState(position = resumePointLocation),
                title = "Resume Point",
                snippet = "Drone paused here",
                icon = resumeMarker,
                anchor = Offset(0.5f, 0.5f)
            )
        }

        // ===== RC MODE MARKER =====
        // Show RC marker at phone's GPS location when in RC mode
        if (isRCMode && phoneLocation != null) {
            Marker(
                state = MarkerState(position = phoneLocation),
                title = "RC (Phone GPS)",
                snippet = "Your current location",
                icon = rcMarker,
                anchor = Offset(0.5f, 0.5f),
                zIndex = 10f // Keep RC marker on top
            )
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

/**
 * Calculate an outer fence polygon that is offset by a specified distance (in meters)
 * from the inner geofence polygon. The outer fence will be placed OUTSIDE the geofence.
 */
private fun calculateOuterFence(innerPolygon: List<LatLng>, offsetMeters: Double): List<LatLng> {
    if (innerPolygon.size < 3) return emptyList()

    val outerPoints = mutableListOf<LatLng>()
    val n = innerPolygon.size

    // Determine if polygon is clockwise or counter-clockwise
    // Using the shoelace formula to calculate signed area
    var signedArea = 0.0
    for (i in 0 until n) {
        val j = (i + 1) % n
        signedArea += innerPolygon[i].longitude * innerPolygon[j].latitude
        signedArea -= innerPolygon[j].longitude * innerPolygon[i].latitude
    }
    // If signedArea > 0, polygon is counter-clockwise; if < 0, clockwise
    val isClockwise = signedArea < 0

    // For each point in the polygon, calculate the offset point
    for (i in innerPolygon.indices) {
        val prev = innerPolygon[(i - 1 + n) % n]
        val curr = innerPolygon[i]
        val next = innerPolygon[(i + 1) % n]

        // Calculate the heading from prev to curr
        val heading1 = SphericalUtil.computeHeading(prev, curr)
        // Calculate the heading from curr to next
        val heading2 = SphericalUtil.computeHeading(curr, next)

        // Calculate perpendicular headings
        // For OUTWARD offset: use -90 for clockwise, +90 for counter-clockwise
        val perpOffset = if (isClockwise) -90.0 else 90.0
        val perpHeading1 = heading1 + perpOffset
        val perpHeading2 = heading2 + perpOffset

        // Calculate the average perpendicular heading for smooth corners
        // Handle angle wrapping properly
        val avgPerpHeading = averageAngles(perpHeading1, perpHeading2)

        // Calculate the offset point using the average perpendicular heading
        val offsetPoint = SphericalUtil.computeOffset(curr, offsetMeters, avgPerpHeading)
        outerPoints.add(offsetPoint)
    }

    return outerPoints
}

/**
 * Calculate the average of two angles, handling the wrap-around at 360 degrees
 */
private fun averageAngles(angle1: Double, angle2: Double): Double {
    // Normalize angles to 0-360 range
    val a1 = ((angle1 % 360) + 360) % 360
    val a2 = ((angle2 % 360) + 360) % 360

    // Calculate the difference
    var diff = a2 - a1
    if (diff > 180) diff -= 360
    if (diff < -180) diff += 360

    // Average is a1 + half the difference
    val avg = a1 + diff / 2

    // Normalize result
    return ((avg % 360) + 360) % 360
}

/**
 * Create a bitmap descriptor with distance label for obstacle edges
 */
private fun createDistanceLabel(text: String): BitmapDescriptor {
    val width = 120
    val height = 40
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw background with rounded corners
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb(200, 50, 50, 50) // Semi-transparent dark gray
        style = android.graphics.Paint.Style.FILL
    }
    val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawRoundRect(rect, 8f, 8f, bgPaint)

    // Draw border
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

    // Draw text
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 28f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Calculate text position (centered)
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(text, 0, text.length, textBounds)
    val x = width / 2f
    val y = height / 2f + textBounds.height() / 2f

    canvas.drawText(text, x, y, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/**
 * Create a bitmap descriptor with distance label for obstacle edges (red themed)
 * Similar to createSmallLabelMarker but with red background for obstacles
 */
private fun createObstacleDistanceLabel(text: String): BitmapDescriptor {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 24f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    // Measure text width
    val textBounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, textBounds)

    val paddingH = 12
    val paddingV = 8
    val width = textBounds.width() + paddingH * 2
    val height = textBounds.height() + paddingV * 2

    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(40), height.coerceAtLeast(26), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw rounded rectangle background (dark red/maroon)
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb(230, 180, 40, 40) // Semi-transparent red
        style = android.graphics.Paint.Style.FILL
    }
    val rect = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawRoundRect(rect, 6f, 6f, bgPaint)

    // Draw border (lighter red)
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(255, 120, 120)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

    // Draw text in white
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val textY = bitmap.height / 2f + textBounds.height() / 2f - textBounds.bottom
    canvas.drawText(text, bitmap.width / 2f, textY, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

