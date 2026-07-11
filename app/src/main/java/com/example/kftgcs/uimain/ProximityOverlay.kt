package com.example.kftgcs.uimain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.telemetry.ProximityData
import com.example.kftgcs.telemetry.RadarThresholds
import com.example.kftgcs.telemetry.TerrainData
import com.example.kftgcs.telemetry.proximityColor
import java.util.Locale

// Shared widget styling, matched to the app's existing dark overlays (WaypointListPanel /
// TelemetryOverlay).
private val WidgetBg = Color.Black.copy(alpha = 0.92f)
private val RingColor = Color(0xFF4A5568)
private val Accent = Color(0xFF87CEEB) // sky-blue digital-readout accent used across the app
private val LabelGray = Color.Gray
private const val WIDGET_SIZE_DP = 230

/**
 * Floating map overlay hosting the "Terrain" and "Obstacles" toggle buttons plus their square
 * widgets. Placed by the caller via [modifier] (e.g. `Modifier.align(Alignment.TopStart)`).
 *
 * Toggle state is owned here; each widget renders only while its toggle is ON, so closed widgets
 * never intercept map touch. [terrain] and [proximity] come from `TelemetryState` and stream live.
 */
@Composable
fun ProximityMapOverlay(
    terrain: TerrainData?,
    proximity: ProximityData?,
    thresholds: RadarThresholds,
    modifier: Modifier = Modifier
) {
    var isTerrainWidgetOpen by remember { mutableStateOf(false) }
    var isObstacleWidgetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(
                icon = Icons.Default.Terrain,
                label = "Terrain",
                active = isTerrainWidgetOpen,
                onClick = { isTerrainWidgetOpen = !isTerrainWidgetOpen }
            )
            ToggleButton(
                icon = Icons.Default.Radar,
                label = "Obstacles",
                active = isObstacleWidgetOpen,
                onClick = { isObstacleWidgetOpen = !isObstacleWidgetOpen }
            )
        }

        if (isTerrainWidgetOpen) {
            ProximityWidgetCard { TerrainGaugeView(terrain, thresholds) }
        }
        if (isObstacleWidgetOpen) {
            ProximityWidgetCard { ObstacleRadarView(proximity, thresholds) }
        }
    }
}

/** Labeled 70×56 FAB matching MainPage's FloatingButtons; turns accent when its widget is open. */
@Composable
private fun ToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (active) Accent.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.7f),
        modifier = Modifier.size(width = 70.dp, height = 56.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = if (active) Color.Black else Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Reusable ~230×230 dp semi-transparent dark square popup card. */
@Composable
private fun ProximityWidgetCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WidgetBg,
        shadowElevation = 8.dp,
        modifier = Modifier.size(WIDGET_SIZE_DP.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Obstacle radar (OBSTACLE_DISTANCE 330)
// ---------------------------------------------------------------------------------------------

/**
 * 2D top-down proximity radar: the drone sits at the centre, obstacle sectors are plotted as
 * coloured blips at their measured range (near the centre = close). Nose points up.
 */
@Composable
private fun ObstacleRadarView(
    proximity: ProximityData?,
    thresholds: RadarThresholds,
    modifier: Modifier = Modifier
) {
    val hasData = proximity != null && proximity.distancesM.any { !it.isNaN() }
    Column(modifier = modifier.fillMaxSize()) {
        Text("OBSTACLES", color = LabelGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (!hasData) {
                Text("No data", color = LabelGray, fontSize = 13.sp)
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) { drawRadar(proximity!!, thresholds) }
            }
        }
        proximity?.closestM?.let { closest ->
            Text(
                text = String.format(Locale.US, "closest  %.1f m", closest),
                color = proximityColor(closest, thresholds),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun DrawScope.drawRadar(proximity: ProximityData, thresholds: RadarThresholds) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxR = minOf(size.width, size.height) / 2f * 0.9f
    val maxRange = (proximity.maxDistanceM).takeIf { it > 0f } ?: 1f

    // Range rings.
    for (frac in floatArrayOf(0.5f, 1f)) {
        drawCircle(
            color = RingColor,
            radius = maxR * frac,
            center = center,
            style = Stroke(width = 1.5f)
        )
    }
    // Cross hairs.
    drawLine(RingColor, Offset(center.x - maxR, center.y), Offset(center.x + maxR, center.y), 1f)
    drawLine(RingColor, Offset(center.x, center.y - maxR), Offset(center.x, center.y + maxR), 1f)

    // Obstacle blips: each valid sector -> an arc segment at its measured range.
    val increment = proximity.incrementDeg.takeIf { it != 0f } ?: 1f
    proximity.distancesM.forEachIndexed { i, distM ->
        if (distM.isNaN()) return@forEachIndexed
        val rr = (distM / maxRange).coerceIn(0f, 1f) * maxR
        // MAVLink: clockwise-positive from forward; Compose canvas angles are clockwise-positive
        // from 3 o'clock, so forward (up) == -90°. Centre the wedge on the sector angle.
        val startAngle = -90f + proximity.angleOffsetDeg + i * increment - increment / 2f
        drawArc(
            color = proximityColor(distM, thresholds),
            startAngle = startAngle,
            sweepAngle = increment,
            useCenter = false,
            topLeft = Offset(center.x - rr, center.y - rr),
            size = Size(rr * 2f, rr * 2f),
            style = Stroke(width = 6f)
        )
    }

    // Drone at centre.
    drawCircle(color = Accent, radius = 5f, center = center)
    drawLine(
        color = Accent,
        start = center,
        end = Offset(center.x, center.y - maxR * 0.22f),
        strokeWidth = 3f
    )
}

// ---------------------------------------------------------------------------------------------
// Terrain gauge (DISTANCE_SENSOR 132)
// ---------------------------------------------------------------------------------------------

/** Vertical altitude bar + large digital distance-to-ground readout. */
@Composable
private fun TerrainGaugeView(
    terrain: TerrainData?,
    thresholds: RadarThresholds,
    modifier: Modifier = Modifier
) {
    val valid = terrain?.hasValidReading == true
    val current = terrain?.currentDistanceM ?: 0f
    val maxRange = terrain?.maxDistanceM?.takeIf { it > 0f } ?: 1f
    val fraction = (current / maxRange).coerceIn(0f, 1f)
    val fillColor = if (valid) proximityColor(current, thresholds) else LabelGray

    Column(modifier = modifier.fillMaxSize()) {
        Text("TERRAIN", color = LabelGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Vertical altitude bar (fills from the bottom with the current ground distance).
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A2E33))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(if (valid) fraction else 0f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fillColor)
                )
            }
            Spacer(Modifier.width(14.dp))
            // Digital readout (Cell style: small gray label + large accent value).
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
                Text("DIST TO GROUND", color = LabelGray, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (valid) String.format(Locale.US, "%.1f", current) else "—",
                    color = Accent,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text("meters", color = LabelGray, fontSize = 12.sp)
                if (terrain != null && !terrain.isDownwardFacing) {
                    Spacer(Modifier.height(6.dp))
                    Text("sensor not downward", color = Color.Yellow, fontSize = 10.sp)
                }
            }
        }
    }
}
