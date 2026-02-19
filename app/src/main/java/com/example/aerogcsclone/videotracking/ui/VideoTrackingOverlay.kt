package com.example.aerogcsclone.videotracking.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aerogcsclone.videotracking.*

/**
 * Video Tracking Overlay — renders tracking visual feedback on top of the video feed.
 *
 * Mirrors MissionPlanner's RenderFrame overlay:
 * - Red ellipse for point tracking
 * - Red rectangle for region tracking
 * - Status indicators (TRACKING / SEARCHING / LOST)
 * - Gimbal angle readout
 * - Camera info display
 *
 * Also handles user touch interaction:
 * - Single tap → point tracking
 * - Drag → rectangle tracking
 * - Long press → move gimbal to location
 */
@Composable
fun VideoTrackingOverlay(
    trackingState: CameraTrackingState,
    onTap: (Float, Float) -> Unit,
    onDragComplete: (Float, Float, Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier,
    showControls: Boolean = true
) {
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentOffset by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        // Touch interaction layer + tracking visualization
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                val normX = offset.x / canvasSize.width
                                val normY = offset.y / canvasSize.height
                                onTap(normX, normY)
                            }
                        },
                        onLongPress = { offset ->
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                val normX = offset.x / canvasSize.width
                                val normY = offset.y / canvasSize.height
                                onLongPress(normX, normY)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStartOffset = offset
                            dragCurrentOffset = offset
                            isDragging = true
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrentOffset = change.position
                        },
                        onDragEnd = {
                            val start = dragStartOffset
                            val end = dragCurrentOffset
                            if (start != null && end != null && canvasSize.width > 0 && canvasSize.height > 0) {
                                // Only count as rectangle drag if movement > 20px
                                val dx = kotlin.math.abs(end.x - start.x)
                                val dy = kotlin.math.abs(end.y - start.y)
                                if (dx > 20 || dy > 20) {
                                    onDragComplete(
                                        start.x / canvasSize.width,
                                        start.y / canvasSize.height,
                                        end.x / canvasSize.width,
                                        end.y / canvasSize.height
                                    )
                                }
                            }
                            isDragging = false
                            dragStartOffset = null
                            dragCurrentOffset = null
                        },
                        onDragCancel = {
                            isDragging = false
                            dragStartOffset = null
                            dragCurrentOffset = null
                        }
                    )
                }
        ) {
            canvasSize = size
            val w = size.width
            val h = size.height

            val trackingStatus = trackingState.trackingImageStatus

            // ── Draw active tracking indicator ──
            if (trackingStatus.trackingStatus == TrackingStatus.ACTIVE) {
                when (trackingStatus.trackingMode) {
                    TrackingMode.POINT -> {
                        // Draw red ellipse at tracked point (like MissionPlanner)
                        val cx = trackingStatus.pointX * w
                        val cy = trackingStatus.pointY * h
                        val radius = (trackingStatus.radius * w).coerceIn(10f, w / 4f)

                        // Outer circle
                        drawCircle(
                            color = Color.Red,
                            center = Offset(cx, cy),
                            radius = radius,
                            style = Stroke(width = 3f)
                        )
                        // Inner crosshair
                        val crossSize = radius * 0.4f
                        drawLine(
                            color = Color.Red,
                            start = Offset(cx - crossSize, cy),
                            end = Offset(cx + crossSize, cy),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color.Red,
                            start = Offset(cx, cy - crossSize),
                            end = Offset(cx, cy + crossSize),
                            strokeWidth = 2f
                        )
                        // Center dot
                        drawCircle(
                            color = Color.Red,
                            center = Offset(cx, cy),
                            radius = 4f
                        )
                    }

                    TrackingMode.RECTANGLE -> {
                        // Draw red rectangle around tracked region (like MissionPlanner)
                        val left = trackingStatus.rectTopLeftX * w
                        val top = trackingStatus.rectTopLeftY * h
                        val right = trackingStatus.rectBottomRightX * w
                        val bottom = trackingStatus.rectBottomRightY * h

                        drawRect(
                            color = Color.Red,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 3f)
                        )
                        // Corner brackets for better visibility
                        val bracketLen = minOf(right - left, bottom - top) * 0.2f
                        // Top-left corner
                        drawLine(Color.Red, Offset(left, top), Offset(left + bracketLen, top), 4f)
                        drawLine(Color.Red, Offset(left, top), Offset(left, top + bracketLen), 4f)
                        // Top-right corner
                        drawLine(Color.Red, Offset(right, top), Offset(right - bracketLen, top), 4f)
                        drawLine(Color.Red, Offset(right, top), Offset(right, top + bracketLen), 4f)
                        // Bottom-left corner
                        drawLine(Color.Red, Offset(left, bottom), Offset(left + bracketLen, bottom), 4f)
                        drawLine(Color.Red, Offset(left, bottom), Offset(left, bottom - bracketLen), 4f)
                        // Bottom-right corner
                        drawLine(Color.Red, Offset(right, bottom), Offset(right - bracketLen, bottom), 4f)
                        drawLine(Color.Red, Offset(right, bottom), Offset(right, bottom - bracketLen), 4f)
                    }

                    TrackingMode.NONE -> { /* No tracking indicator */ }
                }
            }

            // ── Draw drag selection rectangle (while user is dragging) ──
            if (isDragging) {
                val start = dragStartOffset
                val current = dragCurrentOffset
                if (start != null && current != null) {
                    val left = minOf(start.x, current.x)
                    val top = minOf(start.y, current.y)
                    val right = maxOf(start.x, current.x)
                    val bottom = maxOf(start.y, current.y)

                    drawRect(
                        color = Color.Yellow.copy(alpha = 0.3f),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top)
                    )
                    drawRect(
                        color = Color.Yellow,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 2f)
                    )
                }
            }

            // ── Center crosshair (always visible) ──
            val centerX = w / 2
            val centerY = h / 2
            val crossLen = 15f
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(centerX - crossLen, centerY),
                end = Offset(centerX + crossLen, centerY),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(centerX, centerY - crossLen),
                end = Offset(centerX, centerY + crossLen),
                strokeWidth = 1f
            )
        }

        // ── Status badges ──
        if (showControls) {
            // Tracking status indicator (top-left)
            TrackingStatusBadge(
                trackingStatus = trackingState.trackingImageStatus,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )

            // Camera info badge (top-right)
            if (trackingState.cameraDetected) {
                CameraInfoBadge(
                    cameraInfo = trackingState.cameraInfo,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            // Gimbal angles (bottom-left)
            if (trackingState.gimbalState.isAvailable) {
                GimbalInfoBadge(
                    gimbalState = trackingState.gimbalState,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }

            // Stop tracking button (bottom-right, when tracking active)
            if (trackingState.isTrackingActive) {
                IconButton(
                    onClick = onStopTracking,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            Color.Red.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop Tracking",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // GPS target indicator (bottom-center)
            if (trackingState.trackingTargetGps != null) {
                val gps = trackingState.trackingTargetGps
                Text(
                    text = "📍 %.5f, %.5f".format(gps.latitude, gps.longitude),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  STATUS BADGE COMPOSABLES
// ═══════════════════════════════════════════════════════════════

@Composable
private fun TrackingStatusBadge(
    trackingStatus: TrackingImageStatus,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = when (trackingStatus.trackingStatus) {
        TrackingStatus.ACTIVE -> "TRACKING" to Color(0xFF4CAF50)
        TrackingStatus.ERROR -> "LOST" to Color(0xFFF44336)
        TrackingStatus.IDLE -> "READY" to Color(0xFF9E9E9E)
    }

    val modeText = when (trackingStatus.trackingMode) {
        TrackingMode.POINT -> "Point"
        TrackingMode.RECTANGLE -> "Region"
        TrackingMode.NONE -> ""
    }

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Status indicator dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, RoundedCornerShape(4.dp))
        )
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        if (modeText.isNotEmpty()) {
            Text(
                text = "• $modeText",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun CameraInfoBadge(
    cameraInfo: CameraInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = buildString {
                if (cameraInfo.modelName.isNotBlank()) append(cameraInfo.modelName)
                else append("Camera")
                if (cameraInfo.capabilities.hasTrackingPoint) append(" [T]")
            },
            color = Color.White,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun GimbalInfoBadge(
    gimbalState: GimbalState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ControlCamera,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "P:%.0f° Y:%.0f°".format(gimbalState.pitchDeg, gimbalState.yawDeg),
            color = Color.White,
            fontSize = 9.sp
        )
    }
}

