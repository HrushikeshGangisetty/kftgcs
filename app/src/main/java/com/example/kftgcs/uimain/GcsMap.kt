package com.example.kftgcs.uimain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.kftgcs.R
import com.example.kftgcs.location.rememberPhoneLocation
import com.example.kftgcs.telemetry.DronePathPoint
import com.example.kftgcs.telemetry.TelemetryState
import com.example.kftgcs.telemetry.SharedViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.example.kftgcs.grid.GridGenerator
import com.example.kftgcs.grid.GridUtils
import com.google.maps.android.compose.*
import com.google.maps.android.SphericalUtil
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.Locale

// Helper constant for lemon yellow color (255, 244, 79)
private val LEMON_YELLOW = Color(red = 255f / 255f, green = 244f / 255f, blue = 79f / 255f)

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
            BitmapDescriptorFactory.HUE_ORANGE -> android.graphics.Color.YELLOW
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

// Helper function to create a marker with lemon yellow color (255, 244, 79) for selected markers
private fun createLemonYellowMarker(): BitmapDescriptor {
    val size = 72
    val width = size
    val height = size

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw a colored circle with lemon yellow color (255, 244, 79)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(255, 244, 79) // Lemon yellow
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
private fun createMarkerWithText(
    text: String,
    backgroundColor: Int,
    textColor: Int = android.graphics.Color.WHITE
): BitmapDescriptor {
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

    // Draw the text - larger and bolder. Scale down for longer labels (e.g. "120")
    // so multi-digit altitudes still fit inside the circle.
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = textColor
        textSize = when {
            text.length <= 2 -> 56f
            text.length == 3 -> 42f
            else -> 34f
        }
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

// Helper function to create small rounded label for area/dimension display.
//
// [bottomSpacerPx] adds transparent space below the pill. Anchored at (0.5, 1.0) that lifts the
// visible label off the marker position by exactly that many pixels - which is how the geofence
// edge lengths stay clear of the "+" buttons sitting on the same midpoints. Baking the gap into
// the bitmap keeps the anchor inside the [0,1] range the Maps SDK documents.
private fun createSmallLabelMarker(
    text: String,
    backgroundColor: Int = android.graphics.Color.WHITE,
    bottomSpacerPx: Int = 0
): BitmapDescriptor {
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
    val width = (textBounds.width() + paddingH * 2).coerceAtLeast(40)
    val labelHeight = (textBounds.height() + paddingV * 2).coerceAtLeast(30)

    val bitmap = Bitmap.createBitmap(width, labelHeight + bottomSpacerPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw rounded rectangle background (only over the label itself, not the spacer)
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = backgroundColor
        style = android.graphics.Paint.Style.FILL
    }
    val rect = android.graphics.RectF(0f, 0f, width.toFloat(), labelHeight.toFloat())
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
    val textY = labelHeight / 2f + textBounds.height() / 2f - textBounds.bottom
    canvas.drawText(text, width / 2f, textY, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create the "+" button drawn at the midpoint of every geofence edge.
// Tapping one splits that edge, so a 4-sided fence can be reshaped into a 5- or 6-sided one.
private fun createAddVertexMarker(): BitmapDescriptor {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = center - 5f

    // Green reads as "add" and stands clear of the lemon-yellow fence line it sits on.
    val fillPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(76, 175, 80) // Material Green 500
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, fillPaint)

    // White border for visibility over satellite imagery
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    // Thin dark outline for contrast on light backgrounds
    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawCircle(center, center, radius + 2f, outlinePaint)

    // The plus glyph
    val plusPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val arm = size * 0.20f
    canvas.drawLine(center - arm, center, center + arm, center, plusPaint)
    canvas.drawLine(center, center - arm, center, center + arm, plusPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create small grey "R" marker for manual resume point (pending upload)
private fun createSmallResumeMarkerGrey(): BitmapDescriptor {
    val size = 60
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val circlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(158, 158, 158) // Material Grey 500
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, circlePaint)

    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, borderPaint)

    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, outlinePaint)

    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 36f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    textPaint.setShadowLayer(1f, 1f, 1f, android.graphics.Color.BLACK)
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds("R", 0, 1, textBounds)
    val textY = size / 2f + textBounds.height() / 2f - textBounds.bottom
    canvas.drawText("R", size / 2f, textY, textPaint)

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
    val size = 40 // Halved from 80 — the marker was overpowering the map
    // Every stroke width, text size and inset below is expressed relative to `size` so the
    // whole marker scales as one piece. They used to be hardcoded absolutes, which meant
    // shrinking the bitmap alone left a 4px border and 32px text on a 40px circle.
    val scale = size / 80f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val borderInset = 4f * scale
    val outlineInset = 1f * scale

    // Draw outer circle with green color
    val outerPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(76, 175, 80) // Material Green 500
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - borderInset, outerPaint)

    // Draw white border for visibility
    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f * scale
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - borderInset, borderPaint)

    // Draw dark outline for contrast
    val outlinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f * scale
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - outlineInset, outlinePaint)

    // Draw "RC" text
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 32f * scale
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Add text shadow for better readability
    textPaint.setShadowLayer(2f * scale, 1f * scale, 1f * scale, android.graphics.Color.BLACK)

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
        strokeWidth = 2f * scale
        alpha = 128
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - outlineInset, pulsePaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Helper function to create drone icon with directional arrow indicating nose heading
private fun createDroneIconWithArrow(context: android.content.Context): BitmapDescriptor? {
    return runCatching {
        // Load the original drone image
        val droneBmp = BitmapFactory.decodeResource(context.resources, R.drawable.d_image_prev_ui)
        val sizeDp = 24f // Reduced from 64f to make drone smaller
        val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(16)

        // Create a mutable bitmap to draw on
        val resultBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Scale and draw the drone image centered, tinted red
        val scaledDrone = Bitmap.createScaledBitmap(droneBmp, sizePx, sizePx, true)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                android.graphics.Color.RED,
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
        canvas.drawBitmap(scaledDrone, 0f, 0f, paint)

        // No arrow - just return the drone icon
        BitmapDescriptorFactory.fromBitmap(resultBitmap)
    }.getOrNull()
}

@Composable
fun GcsMap(
    telemetryState: TelemetryState,
    points: List<LatLng> = emptyList(),
    // Per-waypoint altitude (m), aligned to `points`. Shown on each waypoint marker.
    waypointAltitudes: List<Float> = emptyList(),
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
    // Home-centred range cylinder as configured on the FC (FENCE_RADIUS), and whether the
    // FC actually has it armed (FENCE_TYPE bit 1). Null radius / false = don't draw it.
    rangeFenceRadiusMeters: Float? = null,
    rangeFenceArmed: Boolean = false,
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
    // Tapping the "+" on the midpoint of geofence edge [edgeIndex] (the edge running from
    // vertex edgeIndex to the next one). Insert [midPoint] at edgeIndex + 1 to add a side.
    onGeofenceEdgeAddPoint: (edgeIndex: Int, midPoint: LatLng) -> Unit = { _, _ -> },
    // Geofence adjustment mode
    geofenceAdjustmentEnabled: Boolean = false,
    // Show grid info (area at center, dimensions on edges)
    showGridInfo: Boolean = false,
    // Obstacle zones - list of polygons representing no-fly zones
    obstacles: List<List<LatLng>> = emptyList(),
    // Clearance held around each obstacle (metres). Drives the shaded buffer ring drawn
    // around every obstacle, so the map shows the whole area the drone actually avoids.
    // Clamped to GridGenerator.MIN_OBSTACLE_BUFFER_M, matching the planner.
    obstacleBoundary: Float = GridGenerator.MIN_OBSTACLE_BUFFER_M.toFloat(),
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
    // Explicit position for the RC marker (the pilot's phone). Normally left null: the map
    // then falls back to the live phone GPS so the RC marker is always on screen.
    phoneLocation: LatLng? = null,
    // Manual resume point (grey = uploading, green = uploaded)
    manualResumePointPending: LatLng? = null,
    manualResumePointUploaded: LatLng? = null,
    // Trigger to clear the local drone path trail (increment to clear)
    clearDronePathTrigger: Int = 0,
    // The flown trail, hoisted into SharedViewModel so it survives this composable leaving
    // composition (navigating between MainPage and PlanScreen, e.g. during a pause/resume).
    // Defaults keep the map usable in previews and in any caller that doesn't track a trail.
    dronePathPoints: List<DronePathPoint> = emptyList(),
    onDronePathPoint: (LatLng, Boolean) -> Unit = { _, _ -> },
    // User-customizable drone path line color (non-spraying segments)
    dronePathColor: Color = Color.Red
) {
    val context = LocalContext.current
    val cameraState = cameraPositionState ?: rememberCameraPositionState()

    // Pilot ("RC") position from the phone's own GPS. Tracked for as long as a map is on
    // screen, so the RC marker is drawn on every map without the caller wiring anything up.
    val livePhoneLocation = rememberPhoneLocation()
    val rcLocation = phoneLocation ?: livePhoneLocation

    // --- Deferred map loading to avoid "referer is null" race condition ---
    // The Maps SDK sometimes starts rendering before its internal HTTP client
    // is ready, causing tile loading failures. We defer rendering slightly
    // and use a key to force remount if the map fails to load tiles.
    var isMapReady by remember { mutableStateOf(false) }
    var mapLoadAttempt by remember { mutableIntStateOf(0) }
    var mapRendered by remember { mutableStateOf(false) }

    // Small delay before showing the map to let the Maps SDK fully initialize
    LaunchedEffect(mapLoadAttempt) {
        isMapReady = false
        delay(150L) // Brief delay to let SDK HTTP client stabilize
        isMapReady = true
    }

    // If the map hasn't reported loaded after a timeout, force a remount (max 2 retries)
    LaunchedEffect(mapLoadAttempt, isMapReady) {
        if (isMapReady && !mapRendered && mapLoadAttempt < 2) {
            delay(8000L) // Wait 8 seconds for map tiles to load
            if (!mapRendered) {
                Timber.w("Map tiles did not load after attempt ${mapLoadAttempt + 1}, retrying...")
                mapLoadAttempt++
                mapRendered = false
            }
        }
    }

    // The trail itself is owned by SharedViewModel (see [dronePathPoints]); this composable
    // only reports new samples and draws what it is given. It deliberately keeps no local
    // copy — that local copy was the bug: it died with the composable on navigation, so a
    // pause/resume erased every green sprayed line already flown.
    //
    // Clearing is likewise the ViewModel's job, driven by clearDronePathTrigger at the call
    // site, so an explicit "Clear Map" still empties the trail.
    val visitedPathPoints = dronePathPoints

    // Load quadcopter drawable with directional arrow indicator
    val droneIcon = remember {
        createDroneIconWithArrow(context)
    }

    // Create medium-sized marker icons for waypoints (50% of default size)
    // (Waypoint markers now render an altitude label via createMarkerWithText.)
    val mediumVioletMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_VIOLET) }
    val mediumOrangeMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_ORANGE) }
    val mediumYellowMarker = remember { createLemonYellowMarker() } // For selected waypoint - Lemon yellow (255, 244, 79)
    val mediumRedMarker = remember { createMediumMarker(BitmapDescriptorFactory.HUE_RED) } // For obstacles

    // Markers with text labels for grid waypoints
    val startMarker = remember { createMarkerWithText("S", android.graphics.Color.GREEN) }
    val endMarker = remember { createMarkerWithText("E", android.graphics.Color.RED) }
    val resumeMarker = remember { createSmallResumeMarker() }
    val resumeMarkerGrey = remember { createSmallResumeMarkerGrey() }
    val rcMarker = remember { createRCMarker() }
    val addVertexMarker = remember { createAddVertexMarker() }

    val lat = telemetryState.latitude
    val lon = telemetryState.longitude
    if (autoCenter && lat != null && lon != null) {
        cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
    }

    // Use sprayActive which is TRUE when either RC7 is enabled OR flow > 0 (AUTO mission spray)
    LaunchedEffect(lat, lon, telemetryState.sprayTelemetry.sprayActive) {
        if (lat != null && lon != null) {
            val pos = LatLng(lat, lon)

            // Determine if drone is actively spraying
            // sprayActive is TRUE when RC7 is enabled, flow > 0, or AUTO mode spray detected
            // This ensures green spray lines during AUTO missions even with brief flow sensor gaps
            val isSpraying = telemetryState.sprayTelemetry.sprayActive

            // Hand the sample to the ViewModel, which de-duplicates against the last point
            // and enforces the length cap. Recording a point when the SPRAY STATUS flips
            // (not just when the position moves) is what creates the boundary between a red
            // segment and a green one.
            onDronePathPoint(pos, isSpraying)
        }
    }

    // Use key() to force remount when mapLoadAttempt changes (retry mechanism)
    key(mapLoadAttempt) {
        if (isMapReady) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(mapType = mapType),
                uiSettings = MapUiSettings(zoomControlsEnabled = false), // Disable zoom controls
                onMapClick = { latLng -> onMapClick(latLng) },
                onMapLoaded = {
                    mapRendered = true
                    Timber.d("Map tiles loaded successfully (attempt ${mapLoadAttempt + 1})")
                }
            ) {
        // Max range boundary — the FC's home-centred cylinder fence (FENCE_RADIUS).
        // Drawn from the radius actually read off the vehicle, and only when the FC has the
        // circle bit armed, so the ring on the map is never a promise the FC isn't keeping.
        val maxRangeHomeLat = telemetryState.homeLatitude
        val maxRangeHomeLon = telemetryState.homeLongitude
        val rangeRadius = rangeFenceRadiusMeters
        if (rangeFenceArmed && rangeRadius != null && rangeRadius > 0f &&
            maxRangeHomeLat != null && maxRangeHomeLon != null) {
            Circle(
                center = LatLng(maxRangeHomeLat, maxRangeHomeLon),
                radius = rangeRadius.toDouble(),
                strokeColor = Color(0xFFFF6D00), // Orange
                strokeWidth = 4f,
                fillColor = Color(0xFFFF6D00).copy(alpha = 0.04f),
                zIndex = 0f
            )
        }

        // Polygon geofence boundary overlay (replaces circular fence)
        if (geofenceEnabled && geofencePolygon.isNotEmpty()) {
            // Draw the polygon boundary
            if (geofencePolygon.size >= 3) {
                // Close the polygon by connecting last point to first
                val closedPolygon = geofencePolygon + geofencePolygon.first()
                Polyline(
                    points = closedPolygon,
                    width = 4f,
                    color = LEMON_YELLOW // Lemon yellow boundary
                )

                // Fill the polygon area with semi-transparent red
                Polygon(
                    points = geofencePolygon,
                    fillColor = Color.Red.copy(alpha = 0.05f),
                    strokeColor = LEMON_YELLOW, // Lemon yellow
                    strokeWidth = 4f,
                    zIndex = 1f // Above base overlays but still below the mission boundary/labels
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

                    // "+" button on the midpoint of every edge. Tapping one splits that edge,
                    // so the pilot can turn the default 4-sided fence into a 5- or 6-sided one
                    // and then drag the new corner into place. Edge `index` runs from vertex
                    // `index` to the next vertex, so the new corner goes in at index + 1 —
                    // which keeps the polygon's winding order intact (and, for the closing
                    // edge, appends to the end).
                    geofencePolygon.forEachIndexed { index, point ->
                        val nextPoint = geofencePolygon[(index + 1) % geofencePolygon.size]
                        val edgeMidPoint = LatLng(
                            (point.latitude + nextPoint.latitude) / 2,
                            (point.longitude + nextPoint.longitude) / 2
                        )

                        key("geofence_edge_add_$index") {
                            Marker(
                                state = MarkerState(position = edgeMidPoint),
                                title = "Add fence corner",
                                snippet = "Tap to split edge ${index + 1}",
                                icon = addVertexMarker,
                                anchor = Offset(0.5f, 0.5f),
                                zIndex = 9f, // Above the edge labels, below the corner markers
                                onClick = {
                                    onGeofenceEdgeAddPoint(index, edgeMidPoint)
                                    true
                                }
                            )
                        }
                    }
                }

                // ===== GEOFENCE MEASUREMENTS =====
                // Add distance labels along geofence edges and area in the center
                if (geofencePolygon.size >= 3) {
                    // Calculate and display area in the center of the geofence
                    val areaInSqMeters = SphericalUtil.computeArea(geofencePolygon)
                    val areaInSqFeet = areaInSqMeters * 10.7639
                    val areaInAcres = areaInSqFeet / 43560.0
                    val areaText = when {
                        areaInAcres >= 1.0 -> String.format(Locale.US, "%.2f acres", areaInAcres)
                        areaInSqMeters >= 10000 -> String.format(Locale.US, "%.2f ha", areaInSqMeters / 10000.0)
                        else -> String.format(Locale.US, "%.0f m²", areaInSqMeters)
                    }

                    // ═══ Where the fence's area label goes ═══
                    //
                    // NOT at the fence centroid. The fence is a buffer drawn AROUND the mission
                    // polygon, so the two shapes share a centroid to within a metre or two — put
                    // both area labels there and they land on top of each other. The previous
                    // code worked around that by anchoring this one 1.6x above its point, which
                    // only converted the overlap into two badges stacked in mid-field, neither
                    // of them visibly belonging to the boundary it measured.
                    //
                    // Instead, put it in the RING: the band between the mission polygon's edge
                    // and the fence's edge, which is ground only the fence encloses. Directly
                    // above the mission polygon's top edge, halfway out to the fence's own top
                    // edge. The label then sits inside the shape it is measuring and outside the
                    // other one, so which number belongs to which boundary needs no explaining.
                    //
                    // With no mission polygon drawn there is no ring to aim for, so it falls back
                    // to halfway between the fence centroid and the fence's top — still clearly
                    // within the fence, and nothing to collide with.
                    val centroidLat = geofencePolygon.map { it.latitude }.average()
                    val centroidLon = geofencePolygon.map { it.longitude }.average()
                    val fenceTopLat = geofencePolygon.maxOf { it.latitude }
                    val innerTopLat = if (surveyPolygon.size >= 3) {
                        surveyPolygon.maxOf { it.latitude }.coerceIn(centroidLat, fenceTopLat)
                    } else {
                        centroidLat
                    }
                    val labelPosition = LatLng((innerTopLat + fenceTopLat) / 2, centroidLon)

                    // Create area label marker with yellow background to match geofence color
                    val areaLabelIcon = remember(areaText) {
                        createSmallLabelMarker(areaText, android.graphics.Color.rgb(255, 235, 59)) // Yellow background
                    }

                    Marker(
                        state = MarkerState(position = labelPosition),
                        title = "Geofence Area: $areaText",
                        icon = areaLabelIcon,
                        // Centred on its point now that it has a place of its own; the old 1.6x
                        // vertical offset existed only to dodge the mission label.
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 8f // Below geofence markers but above other elements
                    )

                    // Display distance measurements for each edge of the geofence
                    geofencePolygon.forEachIndexed { index, point ->
                        val nextIndex = (index + 1) % geofencePolygon.size
                        val nextPoint = geofencePolygon[nextIndex]

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

                        // While the fence is editable an "add corner" button sits on this same
                        // midpoint, so lift the length label clear of it. The "+" is 64px tall
                        // and centred, reaching 32px above the point; 44px of spacer leaves a
                        // small gap above that.
                        val labelLiftPx = if (geofenceAdjustmentEnabled) 44 else 0

                        // Create distance label marker with white background for contrast
                        val distanceLabelIcon = remember(distanceText, index, labelLiftPx) {
                            createSmallLabelMarker(distanceText, android.graphics.Color.WHITE, labelLiftPx)
                        }

                        Marker(
                            state = MarkerState(position = midPoint),
                            title = "Edge ${index + 1}: $distanceText",
                            icon = distanceLabelIcon,
                            // Bottom-anchored when lifted, so the transparent spacer baked into
                            // the bitmap becomes the gap above the "+".
                            anchor = if (geofenceAdjustmentEnabled) Offset(0.5f, 1.0f) else Offset(0.5f, 0.5f),
                            zIndex = 7f // Below area marker and geofence markers
                        )
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
                // The cleared zone the drone actually holds around the obstacle, drawn
                // beneath the obstacle itself. This is the same geometry the grid planner
                // splits spray lines against, so what is shaded here is what is flown --
                // the obstacle the pilot drew is only the inner core of the real no-fly area.
                val bufferZone = remember(obstaclePoints, obstacleBoundary) {
                    GridGenerator.obstacleBufferZone(obstaclePoints, obstacleBoundary.toDouble())
                }
                if (bufferZone != null && bufferZone.size >= 3) {
                    Polygon(
                        points = bufferZone,
                        fillColor = Color(0xFFFF9800).copy(alpha = 0.18f),
                        strokeColor = Color(0xFFFF9800).copy(alpha = 0.7f),
                        strokeWidth = 2f,
                        strokePattern = listOf(Dash(16f), Gap(10f)),
                        zIndex = -1f
                    )
                }

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

                // Area of the obstacle, in the middle of it.
                //
                // Sits alongside the per-edge lengths already drawn below: the edges say how
                // big each side is, this says how much ground the thing actually covers, which
                // is the number that matters when deciding whether it is worth marking at all.
                //
                // Uses formatAreaCompact rather than the field's acres formatter — an obstacle
                // this size is a rounding error in acres (a 6 x 7 m shed is "0.01 acres"), so
                // small shapes report square metres instead.
                val obstacleAreaText = remember(obstaclePoints) {
                    GridUtils.formatAreaCompact(obstaclePoints)
                }
                val obstacleAreaCenter = remember(obstaclePoints) {
                    GridUtils.calculatePolygonCenter(obstaclePoints)
                }
                val obstacleAreaIcon = remember(obstacleAreaText, obstacleIndex) {
                    createObstacleDistanceLabel(obstacleAreaText, emphasis = true)
                }
                Marker(
                    state = MarkerState(position = obstacleAreaCenter),
                    title = "Obstacle ${obstacleIndex + 1}: $obstacleAreaText",
                    icon = obstacleAreaIcon,
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    // Above the edge labels (5f): if a short edge's label overlaps the middle
                    // of a small obstacle, the area is the one worth reading.
                    zIndex = 6f
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
                    icon = droneIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
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

                    // Determine selection state and this waypoint's altitude label
                    val isSelected = selectedWaypointIndex == index
                    val altValue = waypointAltitudes.getOrNull(index)
                    val altLabel = altValue?.let { "${it.toInt()}" } ?: "${index + 1}"

                    // Marker shows the per-waypoint altitude number (MissionPlanner-style).
                    // Selected waypoint = yellow with dark text; default = blue with white text.
                    val markerIcon = remember(altLabel, isSelected) {
                        createMarkerWithText(
                            altLabel,
                            if (isSelected) android.graphics.Color.rgb(255, 244, 79) // Lemon yellow
                            else android.graphics.Color.rgb(33, 150, 243), // Blue
                            if (isSelected) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        )
                    }

                    Marker(
                        state = markerState,
                        title = "WP ${index + 1}",
                        snippet = altValue?.let { "Altitude: ${it.toInt()} m (tap to edit)" },
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
                        zIndex = 11f, // Above geofence corner markers so mission vertices stay reachable
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
                // zIndex above the geofence fill/outer-fence polygons so the mission boundary
                // stays visible instead of being painted over when the geofence is enabled.
                Polyline(points = closedPolygon, width = 6f, color = LEMON_YELLOW, zIndex = 5f) // Lemon yellow boundary

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

                    // Stays at the mission polygon's own centroid — this label is already
                    // inside the boundary it measures. The fence's label is the one that moved,
                    // out into the ring between the two shapes, so these no longer compete for
                    // the same pixels (see the geofence area label above).
                    Marker(
                        state = MarkerState(position = centroid),
                        title = "Area: $areaText",
                        icon = areaLabelIcon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 8.5f
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
                            anchor = Offset(0.5f, 0.5f),
                            zIndex = 6f // Above the geofence fill/outer fence so mission edges stay legible
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
                        color = LEMON_YELLOW // Lemon yellow
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
                                color = LEMON_YELLOW.copy(alpha = 0.7f) // Lemon yellow
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

        // Spray-aware polylines showing the drone's traveled path
        // Green for sprayed areas, Red for non-sprayed areas
        if (visitedPathPoints.size > 1) {
            // Group consecutive points by spray status to create segments
            val segments = mutableListOf<Pair<List<LatLng>, Boolean>>() // (points, isSpraying)
            var currentSegment = mutableListOf<LatLng>()
            var currentSprayStatus: Boolean? = null

            visitedPathPoints.forEach { pathPoint ->
                if (currentSprayStatus == null || currentSprayStatus != pathPoint.isSpraying) {
                    // Start a new segment
                    if (currentSegment.isNotEmpty() && currentSprayStatus != null) {
                        // Add the previous segment
                        segments.add(Pair(currentSegment.toList(), currentSprayStatus!!))
                    }
                    // Start new segment with current point
                    currentSegment = mutableListOf(pathPoint.position)
                    currentSprayStatus = pathPoint.isSpraying
                } else {
                    // Continue current segment
                    currentSegment.add(pathPoint.position)
                }
            }

            // Add the final segment
            if (currentSegment.isNotEmpty() && currentSprayStatus != null) {
                segments.add(Pair(currentSegment.toList(), currentSprayStatus!!))
            }

            // Draw polylines for each segment
    segments.forEach { (points, isSpraying) ->
        if (points.size > 1) {
            Polyline(
                points = points,
                width = 7.5f,
                color = if (isSpraying) Color.Green else dronePathColor
            )
        }
    }
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

        // ===== MANUAL RESUME POINT MARKERS =====
        // Grey "R" = user placed but upload still in progress
        if (manualResumePointPending != null) {
            Marker(
                state = MarkerState(position = manualResumePointPending),
                title = "Resume Point (uploading…)",
                snippet = "Uploading modified mission",
                icon = resumeMarkerGrey,
                anchor = Offset(0.5f, 0.5f),
                zIndex = 11f
            )
        }
        // Green "R" = uploaded successfully
        if (manualResumePointUploaded != null) {
            Marker(
                state = MarkerState(position = manualResumePointUploaded),
                title = "Resume Point (ready)",
                snippet = "Mission uploaded — ready to resume",
                icon = resumeMarker,
                anchor = Offset(0.5f, 0.5f),
                zIndex = 11f
            )
        }

        // ===== RC MARKER =====
        // The pilot's own position, from the phone's GPS. Always shown once a fix exists —
        // the same way the drone marker is always shown once the drone reports a fix.
        rcLocation?.let { rcPosition ->
            val rcMarkerState = rememberMarkerState(
                key = "rc_marker",
                position = rcPosition
            )
            LaunchedEffect(rcPosition) {
                rcMarkerState.position = rcPosition
            }
            Marker(
                state = rcMarkerState,
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
        } // end if (isMapReady)
    } // end key(mapLoadAttempt)
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
/**
 * Red pill label used on obstacles: edge lengths, and the area in the middle.
 *
 * [emphasis] makes the pill larger and darker. The area label uses it so that it does not read
 * as just another edge length sitting near the centre of the shape.
 */
private fun createObstacleDistanceLabel(text: String, emphasis: Boolean = false): BitmapDescriptor {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = if (emphasis) 30f else 24f
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
        color = if (emphasis) {
            android.graphics.Color.argb(240, 120, 20, 20)
        } else {
            android.graphics.Color.argb(230, 180, 40, 40) // Semi-transparent red
        }
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
