package com.example.kftgcs.uimain

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Waypoint list panel with long-press drag-and-drop reordering
 * - Long-press the DOT icon for 2 seconds to start dragging
 * - Drag to reorder waypoints
 * - Automatically renumbers waypoints
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaypointListPanel(
    waypoints: List<LatLng>,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onWaypointClick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Per-waypoint altitude (m), aligned to `waypoints`. Shown in each row.
    altitudes: List<Float> = emptyList()
) {
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.92f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxHeight()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Waypoints (${waypoints.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Waypoint list with drag-and-drop
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = waypoints,
                    key = { index, _ -> index }
                ) { index, latLng ->
                    val isDragging = draggedIndex == index
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 2.dp,
                        label = "elevation"
                    )

                    WaypointRow(
                        index = index,
                        latLng = latLng,
                        altitude = altitudes.getOrNull(index),
                        isDragging = isDragging,
                        elevation = elevation,
                        onWaypointClick = { onWaypointClick(index) },
                        onDragStart = {
                            scope.launch {
                                // Wait for 2 seconds before starting drag
                                delay(2000)
                                draggedIndex = index
                                targetIndex = index
                            }
                        },
                        onDragEnd = {
                            draggedIndex?.let { from ->
                                targetIndex?.let { to ->
                                    if (from != to) {
                                        onReorder(from, to)
                                    }
                                }
                            }
                            draggedIndex = null
                            targetIndex = null
                        },
                        onDragCancel = {
                            draggedIndex = null
                            targetIndex = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WaypointRow(
    index: Int,
    latLng: LatLng,
    altitude: Float?,
    isDragging: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    onWaypointClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var isLongPressing by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation)
            .zIndex(if (isDragging) 1f else 0f),
        shape = RoundedCornerShape(8.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color(0xFF2E2E2E)
        },
        onClick = onWaypointClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Waypoint info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "WP${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    if (altitude != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "▲ ${altitude.toInt()} m",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64B5F6),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "Lat: ${String.format("%.6f", latLng.latitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    text = "Lng: ${String.format("%.6f", latLng.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Right side: Drag handle (DOT icon)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // Start long-press timer
                                longPressJob = scope.launch {
                                    isLongPressing = true
                                    delay(2000) // 2 seconds
                                    if (isLongPressing) {
                                        onDragStart()
                                    }
                                }
                            },
                            onDrag = { _, _ ->
                                // Dragging - do nothing, handled by parent
                            },
                            onDragEnd = {
                                isLongPressing = false
                                longPressJob?.cancel()
                                onDragEnd()
                            },
                            onDragCancel = {
                                isLongPressing = false
                                longPressJob?.cancel()
                                onDragCancel()
                            }
                        )
                    }
                    .background(
                        color = if (isLongPressing) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Drag Handle",
                    tint = if (isLongPressing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Gray
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    // Visual feedback during long-press
    if (isLongPressing) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

