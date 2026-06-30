package com.example.kftgcs.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.kftgcs.R
import com.example.kftgcs.loganalysis.model.ReplayFrame
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlin.math.cos
import kotlin.math.sin

private val ScreenBg = Color(0xFF23272A)
private val GridLine = Color(0xFF34383D)
private val PathColor = Color(0xFF87CEEB)
private val MarkerColor = Color(0xFFE53935)
private val Accent = Color(0xFF87CEEB)

/**
 * Hybrid flight-path map. Uses Google Maps when the device is online (rich satellite/terrain context),
 * and automatically falls back to a self-contained [ReplayMapCanvas] grid when offline — so the path is
 * always visible in the field. A Map/Grid toggle lets the user switch manually.
 *
 * The path polyline is static; only the drone marker moves, driven by [frameProvider]. The toggle and
 * mode state are independent of the playback index, so switching modes never disturbs playback.
 */
@Composable
fun ReplayMap(
    path: List<LatLng>,
    bounds: LatLngBounds?,
    frameProvider: () -> ReplayFrame,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (path.isEmpty() || bounds == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ScreenBg),
            contentAlignment = Alignment.Center
        ) {
            Text("No GPS position recorded in this log", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    var useMap by remember { mutableStateOf(isOnline(context)) }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        if (useMap) {
            GoogleMapReplay(path, bounds, frameProvider)
        } else {
            ReplayMapCanvas(path, bounds, frameProvider)
        }

        // Map/Grid toggle overlay (top-right).
        IconButton(
            onClick = { useMap = !useMap },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(40.dp)
                .background(ScreenBg.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
        ) {
            Icon(
                imageVector = if (useMap) Icons.Filled.GridOn else Icons.Filled.Map,
                contentDescription = if (useMap) "Switch to offline grid" else "Switch to map",
                tint = Accent
            )
        }
    }
}

@Composable
private fun GoogleMapReplay(
    path: List<LatLng>,
    bounds: LatLngBounds,
    frameProvider: () -> ReplayFrame
) {
    val cameraState = rememberCameraPositionState()
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        properties = MapProperties(mapType = MapType.SATELLITE),
        uiSettings = MapUiSettings(zoomControlsEnabled = false),
        onMapLoaded = {
            runCatching {
                cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            }
        }
    ) {
        Polyline(points = path, width = 8f, color = PathColor)

        val frame = frameProvider()
        val pos = safeLatLng(frame, path.first())
        val markerState = rememberMarkerState(position = pos)
        markerState.position = pos

        val droneIcon = rememberDroneIcon()
        Marker(
            state = markerState,
            icon = droneIcon,
            rotation = frame.yaw.takeIf { !it.isNaN() }?.toFloat() ?: 0f,
            anchor = Offset(0.5f, 0.5f),
            flat = true,
            title = "Drone"
        )
    }
}

/**
 * Offline fallback: a pure-Canvas 2D map. Projects lat/lng to screen via the flight bounding box
 * (equirectangular, longitude aspect-corrected by cos(latitude)) and draws the trail + a heading-aware
 * drone marker. The current frame is read inside the [DrawScope], so it redraws without recomposing.
 */
@Composable
private fun ReplayMapCanvas(
    path: List<LatLng>,
    bounds: LatLngBounds,
    frameProvider: () -> ReplayFrame
) {
    val minLat = bounds.southwest.latitude
    val minLng = bounds.southwest.longitude
    val maxLat = bounds.northeast.latitude
    val maxLng = bounds.northeast.longitude
    val midLat = (minLat + maxLat) / 2.0
    val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(1e-6)

    // World extents (aspect-corrected). Guard degenerate (single-point) flights.
    val worldW = ((maxLng - minLng) * cosLat).coerceAtLeast(1e-9)
    val worldH = (maxLat - minLat).coerceAtLeast(1e-9)

    fun worldX(lng: Double) = (lng - minLng) * cosLat
    fun worldY(lat: Double) = (lat - minLat)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        // Background grid (the "blank grid" offline look).
        val step = size.minDimension / 8f
        var gx = 0f
        while (gx <= size.width) {
            drawLine(GridLine, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
            gx += step
        }
        var gy = 0f
        while (gy <= size.height) {
            drawLine(GridLine, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            gy += step
        }

        // Fit the world box into 90% of the canvas, centred, north-up (flip Y).
        val scale = minOf(size.width * 0.9f / worldW.toFloat(), size.height * 0.9f / worldH.toFloat())
        val drawW = worldW.toFloat() * scale
        val drawH = worldH.toFloat() * scale
        val offX = (size.width - drawW) / 2f
        val offY = (size.height - drawH) / 2f
        fun project(lat: Double, lng: Double): Offset {
            val x = offX + worldX(lng).toFloat() * scale
            val y = size.height - (offY + worldY(lat).toFloat() * scale)
            return Offset(x, y)
        }

        // Flight path.
        if (path.size >= 2) {
            val trail = Path()
            val first = project(path[0].latitude, path[0].longitude)
            trail.moveTo(first.x, first.y)
            for (i in 1 until path.size) {
                val p = project(path[i].latitude, path[i].longitude)
                trail.lineTo(p.x, p.y)
            }
            drawPath(trail, color = PathColor, style = Stroke(width = 3f))
        }

        // Current drone position + heading.
        val frame = frameProvider()
        val pos = safeLatLng(frame, path.first())
        val marker = project(pos.latitude, pos.longitude)
        val yaw = frame.yaw.takeIf { !it.isNaN() }?.toFloat() ?: 0f
        val yawRad = Math.toRadians(yaw.toDouble())
        val len = 16f
        // North-up heading vector (yaw measured clockwise from north).
        val hx = (sin(yawRad)).toFloat() * len
        val hy = (-cos(yawRad)).toFloat() * len
        drawLine(MarkerColor, marker, Offset(marker.x + hx, marker.y + hy), strokeWidth = 3f)
        drawCircle(MarkerColor, radius = 7f, center = marker)
    }
}

/** Render the drone vector drawable to a [BitmapDescriptor]; null falls back to the default marker. */
@Composable
private fun rememberDroneIcon(): BitmapDescriptor? {
    val context = LocalContext.current
    return remember {
        runCatching {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_drone) ?: return@runCatching null
            val px = 84
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bmp)
        }.getOrNull()
    }
}

/** A valid [LatLng] for [frame], or [fallback] when the frame has no usable fix (NaN / null island). */
private fun safeLatLng(frame: ReplayFrame, fallback: LatLng): LatLng {
    val lat = frame.lat
    val lng = frame.lng
    val valid = !lat.isNaN() && !lng.isNaN() && (lat != 0.0 || lng != 0.0)
    return if (valid) LatLng(lat, lng) else fallback
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
