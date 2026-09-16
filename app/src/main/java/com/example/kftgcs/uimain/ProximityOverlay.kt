package com.example.kftgcs.uimain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.telemetry.ProximityData
import com.example.kftgcs.telemetry.RadarThresholds
import com.example.kftgcs.telemetry.TerrainData
import com.example.kftgcs.telemetry.proximityColor
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// Shared widget styling, matched to the app's existing dark overlays (WaypointListPanel /
// TelemetryOverlay).
private val WidgetBg = Color.Black.copy(alpha = 0.92f)
private val RingColor = Color(0xFF4A5568)
private val Accent = Color(0xFF87CEEB) // sky-blue digital-readout accent used across the app
private val LabelGray = Color.Gray
private val RingLabelGreen = Color(0xFF4CD964) // Mission-Planner-style green range-ring labels
private const val WIDGET_SIZE_DP = 230
private const val OBSTACLE_WIDGET_SIZE_DP = 290 // obstacle radar runs a little larger than terrain
private const val RADAR_RADIUS_FACTOR = 0.70f   // bullseye radius as a fraction of the half-canvas

// Fixed obstacle-radar range rings (metres). Non-uniform: 2 m steps to 10 m, then 5 m steps to 20 m.
// Only the rings within the current zoom's full-scale range are drawn.
private val ObstacleRangeRingsM = listOf(2f, 4f, 6f, 8f, 10f, 15f, 20f)

// Zoom stops = the radar's full-scale range in metres, ordered zoomed-out (20 m) -> zoomed-in (4 m).
// Rings and blips both scale against the selected value, so closer obstacles read larger as you zoom.
private val ObstacleZoomStopsM = listOf(20f, 10f, 6f, 4f)

/**
 * Range-ring set for the current full-scale [maxRange]. Zoomed-in ranges expose finer, in-between
 * rings (1 m steps) so the closer view isn't just 2 m apart; the 20 m default keeps the coarse set.
 */
private fun obstacleRingsForRange(maxRange: Float): List<Float> = when {
    maxRange <= 4f -> listOf(1f, 2f, 3f, 4f)
    maxRange <= 6f -> listOf(1f, 2f, 3f, 4f, 5f, 6f)
    maxRange <= 10f -> listOf(2f, 4f, 6f, 8f, 10f)
    else -> ObstacleRangeRingsM
}

/**
 * Floating map overlay hosting the "Terrain" and "Obstacles" toggle buttons plus their square
 * widgets. Placed by the caller via [modifier] (e.g. `Modifier.align(Alignment.TopStart)`).
 *
 * Toggle state is owned here; each widget renders only while its toggle is ON, so closed widgets
 * never intercept map touch. [terrain] and [proximity] come from `TelemetryState` and stream live.
 *
 * The buttons stack VERTICALLY. They used to sit side by side, but a third control (Clear
 * Mission) would have pushed the row into the map's pan area on a phone-width screen; a column
 * keeps every control against the left edge and leaves the map clear.
 *
 * Clear Mission is optional: pass [onClearMission] to show it. Left null (the default) the
 * button is not composed at all, so this overlay stays usable anywhere the action makes no
 * sense. The caller owns the confirm/result dialogs and the enablement rule — this composable
 * only draws the button.
 */
@Composable
fun ProximityMapOverlay(
    terrain: TerrainData?,
    proximity: ProximityData?,
    thresholds: RadarThresholds,
    modifier: Modifier = Modifier,
    onClearMission: (() -> Unit)? = null,
    clearMissionEnabled: Boolean = false,
    // Two short words, not AppStrings.clearMission: that is a full phrase in several of the
    // supported languages and will not fit a 70dp FAB. The confirm dialog carries the
    // translated wording.
    clearMissionLabel: String = "Clear\nMission"
) {
    var isTerrainWidgetOpen by remember { mutableStateOf(false) }
    var isObstacleWidgetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

        if (onClearMission != null) {
            ToggleButton(
                icon = Icons.Default.DeleteSweep,
                label = clearMissionLabel,
                // Never "active": this is an action, not a toggle. Red rather than the accent
                // colour so it does not read as a third view to switch on.
                active = false,
                enabled = clearMissionEnabled,
                containerColor = ClearMissionRed.copy(alpha = 0.75f),
                onClick = onClearMission
            )
        }

        if (isTerrainWidgetOpen) {
            ProximityWidgetCard { TerrainGaugeView(terrain, thresholds) }
        }
        if (isObstacleWidgetOpen) {
            ProximityWidgetCard(sizeDp = OBSTACLE_WIDGET_SIZE_DP) {
                ObstacleRadarView(proximity, thresholds)
            }
        }
    }
}

/** Destructive-action red, matching the Clear Mission button on the flying-method screen. */
private val ClearMissionRed = Color(0xFFEF5350)

/**
 * Labeled 70×56 FAB matching MainPage's FloatingButtons; turns accent when its widget is open.
 *
 * [enabled] exists for the action buttons (Clear Mission), which must stay VISIBLE but inert
 * when the action is unavailable — hiding them would leave the pilot hunting for a control
 * that is simply disabled. A disabled button dims and ignores taps.
 *
 * [containerColor] overrides the inactive background, for actions that should not read as a
 * view toggle. It is ignored while [active] is true.
 */
@Composable
private fun ToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color? = null
) {
    val background = when {
        active -> Accent.copy(alpha = 0.85f)
        containerColor != null -> containerColor
        else -> Color.Black.copy(alpha = 0.7f)
    }
    val contentColor = if (active) Color.Black else Color.White
    // Dim the whole button rather than just the text, so "unavailable" reads at a glance in
    // sunlight without changing the layout.
    val alpha = if (enabled) 1f else 0.4f

    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = background.copy(alpha = background.alpha * alpha),
        modifier = Modifier.size(width = 70.dp, height = 56.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor.copy(alpha = alpha),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = contentColor.copy(alpha = alpha),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                lineHeight = 10.sp
            )
        }
    }
}

/** Reusable semi-transparent dark square popup card; [sizeDp] is its side length in dp. */
@Composable
private fun ProximityWidgetCard(sizeDp: Int = WIDGET_SIZE_DP, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WidgetBg,
        shadowElevation = 8.dp,
        modifier = Modifier.size(sizeDp.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Obstacle radar (forward-facing DISTANCE_SENSOR 132, orientation NONE)
// ---------------------------------------------------------------------------------------------

/**
 * 2D top-down proximity radar: the drone sits at the centre. The forward rangefinder reports a
 * single obstacle distance, drawn as one coloured wedge locked to the 0° (forward, up) sector at
 * its measured range (near the centre = close). Nose points up.
 */
@Composable
private fun ObstacleRadarView(
    proximity: ProximityData?,
    thresholds: RadarThresholds,
    modifier: Modifier = Modifier
) {
    val forwardDist = proximity?.forwardDistanceM
    val hasData = forwardDist != null
    val textMeasurer = rememberTextMeasurer()
    // Zoom: index into ObstacleZoomStopsM. 0 = fully zoomed out (20 m); higher = zoomed in.
    var zoomIndex by remember { mutableStateOf(0) }
    val maxRange = ObstacleZoomStopsM[zoomIndex]
    val rings = obstacleRingsForRange(maxRange)
    // Recenter: pin the drone to the bottom so the whole widget is spent on the forward view.
    var pinnedToBottom by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        Text("OBSTACLES", color = LabelGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    // Pinch-to-zoom: accumulate the pinch factor across a gesture and step the zoom
                    // level when it crosses a threshold. Pinch out (zoom > 1) tightens the range
                    // (zoom in); pinch in widens it. Mirrors the +/- buttons' range limits.
                    var accum = 1f
                    detectTransformGestures { _, _, zoom, _ ->
                        accum *= zoom
                        when {
                            accum > 1.2f -> {
                                if (zoomIndex < ObstacleZoomStopsM.lastIndex) zoomIndex++
                                accum = 1f
                            }
                            accum < 1f / 1.2f -> {
                                if (zoomIndex > 0) zoomIndex--
                                accum = 1f
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Grid (rings + heading spokes + MAV icon) always renders so the widget reads as a
            // live radar even with no target; the forward blip layers on top only when valid.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val geom = radarGeometry(forwardOnly = pinnedToBottom)
                drawPolarGrid(geom, maxRange, rings, textMeasurer)
                if (proximity != null) drawForwardBlip(geom, proximity, thresholds, maxRange)
                drawDroneIcon(geom.center)
            }
            if (!hasData) {
                // In forward-only mode the drone sits at the bottom, so surface the state up top.
                Text(
                    text = "No Target",
                    color = LabelGray,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(if (pinnedToBottom) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(vertical = 2.dp)
                )
            }
            // Recenter toggle: pin the drone to the bottom (forward-only) or return it to centre.
            ZoomButton(
                icon = if (pinnedToBottom) Icons.Default.VerticalAlignCenter
                else Icons.Default.VerticalAlignBottom,
                enabled = true,
                onClick = { pinnedToBottom = !pinnedToBottom },
                description = if (pinnedToBottom) "Center drone" else "Move drone to bottom",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
            )
            // Zoom controls: "+" tightens the full-scale range (zoom in), "-" widens it (zoom out).
            ZoomControls(
                canZoomIn = zoomIndex < ObstacleZoomStopsM.lastIndex,
                canZoomOut = zoomIndex > 0,
                onZoomIn = { if (zoomIndex < ObstacleZoomStopsM.lastIndex) zoomIndex++ },
                onZoomOut = { if (zoomIndex > 0) zoomIndex-- },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
        forwardDist?.let { dist ->
            Text(
                text = String.format(Locale.US, "forward  %.1f m", dist),
                color = proximityColor(dist, thresholds),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Vertical +/- zoom control overlaid on the radar; each button disables at its range limit. */
@Composable
private fun ZoomControls(
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ZoomButton(Icons.Default.Add, enabled = canZoomIn, onClick = onZoomIn, description = "Zoom in")
        ZoomButton(Icons.Default.Remove, enabled = canZoomOut, onClick = onZoomOut, description = "Zoom out")
    }
}

/** Compact 28dp square icon button used by [ZoomControls]; dims when [enabled] is false. */
@Composable
private fun ZoomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier
            .size(28.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Where the drone sits and how big the bullseye is; [forwardOnly] pins it to a bottom-up fan. */
private class RadarGeometry(val center: Offset, val maxR: Float, val forwardOnly: Boolean)

/**
 * Centred bullseye, or — when [forwardOnly] — the drone pinned near the bottom so the forward view
 * uses the full height. Forward-only intentionally lets the radius exceed the half-width (rings clip
 * at the sides) to trade the unused rear space for more forward range resolution.
 */
private fun DrawScope.radarGeometry(forwardOnly: Boolean): RadarGeometry =
    if (forwardOnly) {
        val cx = size.width / 2f
        val cy = size.height * 0.95f
        RadarGeometry(Offset(cx, cy), cy * 0.88f, forwardOnly = true)
    } else {
        val center = Offset(size.width / 2f, size.height / 2f)
        RadarGeometry(center, minOf(size.width, size.height) / 2f * RADAR_RADIUS_FACTOR, forwardOnly = false)
    }

/**
 * Mission-Planner-style polar grid: concentric range rings (green distance labels) plus heading
 * spokes. Centred mode draws full rings and a labelled 45° compass (180° unlabeled). Forward-only
 * mode draws top-half arcs and an unlabelled forward fan so a single forward sensor gets the whole
 * widget. Nose (0°) is straight up in both.
 */
private fun DrawScope.drawPolarGrid(
    geom: RadarGeometry,
    maxRange: Float,
    rings: List<Float>,
    textMeasurer: TextMeasurer
) {
    val center = geom.center
    val maxR = geom.maxR

    // Range rings within the current full-scale range.
    for (dist in rings) {
        if (dist > maxRange) continue
        val radius = (dist / maxRange).coerceIn(0f, 1f) * maxR
        if (radius <= 0f) continue
        if (geom.forwardOnly) {
            // Top-half arc only (the forward fan); 180°..360° passes through 270° (straight up).
            drawArc(
                color = RingColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 2f)
            )
        } else {
            drawCircle(color = RingColor, radius = radius, center = center, style = Stroke(width = 2f))
        }
        val layout = textMeasurer.measure(
            text = "${dist.toInt()}m",
            style = TextStyle(color = RingLabelGreen, fontSize = 8.sp)
        )
        drawText(textLayoutResult = layout, topLeft = Offset(center.x + 2f, center.y - radius - layout.size.height))
    }

    // Heading spokes: forward-only draws a symmetric forward fan; centred draws the full compass.
    val spokeDegs = if (geom.forwardOnly) {
        listOf(-90f, -45f, 0f, 45f, 90f)
    } else {
        listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
    }
    for (deg in spokeDegs) {
        val rad = Math.toRadians((deg - 90).toDouble())
        drawLine(
            color = RingColor,
            start = center,
            end = Offset(center.x + maxR * cos(rad).toFloat(), center.y + maxR * sin(rad).toFloat()),
            strokeWidth = 1f
        )
    }

    // Spoke labels only in centred mode; the forward fan stays clean (0° would clip at the top edge).
    if (!geom.forwardOnly) {
        val spokeLabels = linkedMapOf(
            0f to "0°", 45f to "45°", 90f to "90°", 135f to "135°",
            225f to "225°", 270f to "270°", 315f to "315°"
        )
        spokeLabels.forEach { (deg, label) ->
            val rad = Math.toRadians((deg - 90).toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(color = LabelGray, fontSize = 8.sp)
            )
            val labelR = maxR + 9f
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    center.x + labelR * dx - layout.size.width / 2f,
                    center.y + labelR * dy - layout.size.height / 2f
                )
            )
        }
    }
}

/**
 * Draws the single forward obstacle reading as one colour-coded wedge locked to the 0° (forward)
 * sector — a 30°-wide slice spanning 345°..15° at the top of the bullseye — at its measured range.
 * An invalid / out-of-range reading ([ProximityData.forwardDistanceM] null) draws nothing, leaving
 * the clean grid and the "No Target" state.
 */
private fun DrawScope.drawForwardBlip(
    geom: RadarGeometry,
    proximity: ProximityData,
    thresholds: RadarThresholds,
    maxRange: Float
) {
    val distM = proximity.forwardDistanceM ?: return
    val center = geom.center
    val maxR = geom.maxR
    // Scale against the current full-scale (zoom) range so the blip lines up with the rings.
    val rr = (distM / maxRange).coerceIn(0f, 1f) * maxR
    if (rr <= 0f) return
    val color = proximityColor(distM, thresholds)
    // Compose canvas angles are clockwise-positive from 3 o'clock, so forward (up) == -90°.
    // A 30°-wide wedge centred on forward runs 345°..15°, i.e. start at -105° and sweep 30°.
    val startAngle = -105f
    val sweepAngle = 30f
    val topLeft = Offset(center.x - rr, center.y - rr)
    val arcSize = Size(rr * 2f, rr * 2f)
    drawArc(
        color = color.copy(alpha = 0.45f),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = true,
        topLeft = topLeft,
        size = arcSize,
        style = Fill
    )
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 3f)
    )
}

/** Minimal nose-up quadcopter glyph (X-frame arms + body) marking the MAV at the radar's centre. */
private fun DrawScope.drawDroneIcon(center: Offset) {
    val armLength = minOf(size.width, size.height) * 0.05f
    val armAngles = floatArrayOf(45f, 135f, 225f, 315f)
    for (deg in armAngles) {
        val rad = Math.toRadians(deg.toDouble())
        val tip = Offset(
            center.x + armLength * cos(rad).toFloat(),
            center.y + armLength * sin(rad).toFloat()
        )
        drawLine(color = Accent, start = center, end = tip, strokeWidth = 2.5f)
        drawCircle(color = Accent, radius = armLength * 0.22f, center = tip)
    }
    drawCircle(color = Color.White, radius = armLength * 0.3f, center = center)

    // Nose triangle, pointing straight up.
    val noseLen = armLength * 0.9f
    val noseHalfWidth = armLength * 0.32f
    val nosePath = Path().apply {
        moveTo(center.x, center.y - noseLen)
        lineTo(center.x - noseHalfWidth, center.y - noseLen * 0.35f)
        lineTo(center.x + noseHalfWidth, center.y - noseLen * 0.35f)
        close()
    }
    drawPath(nosePath, color = Accent)
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
