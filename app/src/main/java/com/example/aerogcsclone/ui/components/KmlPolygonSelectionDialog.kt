package com.example.aerogcsclone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aerogcsclone.utils.KmlPolygon

/**
 * Dialog for selecting which polygon to use when a KML file contains multiple polygons.
 * Shows the name and point count for each polygon.
 * Tap an item then click "Use Selected" to confirm.
 */
@Composable
fun KmlPolygonSelectionDialog(
    polygons: List<KmlPolygon>,
    onDismiss: () -> Unit,
    onSelectPolygon: (KmlPolygon) -> Unit
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false  // Allow custom width
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)  // 92% of screen width
                .fillMaxHeight(0.8f),  // Max 80% of screen height for more items
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Compact Header - Icon and title in row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Select Polygon",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Found ${polygons.size} polygon(s). Tap to select.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Polygon list - Takes available space
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),  // Takes remaining space, leaving room for buttons
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(polygons) { index, polygon ->
                        val isSelected = selectedIndex == index

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary
                                )
                            } else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isSelected)
                                        Icons.Default.CheckCircle
                                    else
                                        Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = polygon.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${polygon.points.size} points",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons - Fixed at bottom, always visible
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            android.util.Log.d("KmlDialog", "Use Selected clicked, index=$selectedIndex")
                            selectedIndex?.let { index ->
                                val polygon = polygons[index]
                                android.util.Log.d("KmlDialog", "Selected: ${polygon.name}")
                                onSelectPolygon(polygon)
                            }
                        },
                        enabled = selectedIndex != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Use Selected")
                    }
                }
            }
        }
    }
}

