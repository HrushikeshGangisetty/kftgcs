package com.example.aerogcsclone.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Initial dialog for selecting mission type: Grid or Waypoint
 * Responsive layout that adapts to different screen sizes
 */
@Composable
fun MissionTypeSelectionDialog(
    onDismiss: () -> Unit,
    onSelectGrid: () -> Unit,
    onSelectWaypoint: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // Calculate responsive sizes based on screen dimensions
    val isSmallScreen = screenWidth < 360.dp || screenHeight < 500.dp
    val buttonWidth = if (isSmallScreen) 110.dp else 140.dp
    val buttonHeight = if (isSmallScreen) 70.dp else 80.dp
    val iconSize = if (isSmallScreen) 20.dp else 24.dp
    val horizontalPadding = if (isSmallScreen) 12.dp else 16.dp
    val contentPadding = if (isSmallScreen) 16.dp else 24.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.9f)
                .padding(horizontal = horizontalPadding, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Icon(
                    imageVector = Icons.Default.FlightTakeoff,
                    contentDescription = null,
                    modifier = Modifier.size(if (isSmallScreen) 28.dp else 36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(if (isSmallScreen) 8.dp else 12.dp))

                Text(
                    text = "Select Mission Type",
                    style = if (isSmallScreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(if (isSmallScreen) 12.dp else 16.dp))

                // Horizontal button layout with flexible sizing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Grid Mission Button
                    Button(
                        onClick = onSelectGrid,
                        modifier = Modifier
                            .widthIn(min = buttonWidth)
                            .height(buttonHeight),
                        contentPadding = PaddingValues(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Grid",
                                fontWeight = FontWeight.Bold,
                                style = if (isSmallScreen) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Auto survey",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(if (isSmallScreen) 8.dp else 12.dp))

                    // Waypoint Mission Button
                    OutlinedButton(
                        onClick = onSelectWaypoint,
                        modifier = Modifier
                            .widthIn(min = buttonWidth)
                            .height(buttonHeight),
                        contentPadding = PaddingValues(8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Waypoint",
                                fontWeight = FontWeight.Bold,
                                style = if (isSmallScreen) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isSmallScreen) 8.dp else 12.dp))

                // Cancel button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

