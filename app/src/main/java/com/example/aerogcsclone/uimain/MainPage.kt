// Kotlin
package com.example.aerogcsclone.uimain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
//import com.example.aerogcsclone.Telemetry.SharedViewModel
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.authentication.AuthViewModel
import com.google.maps.android.compose.MapType
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import androidx.compose.ui.text.font.FontWeight
import com.example.aerogcsclone.telemetry.SharedViewModel
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import com.example.aerogcsclone.utils.AppStrings
import com.example.aerogcsclone.ui.components.MissionCompletionDialog
import com.example.aerogcsclone.ui.components.DroneCameraFeedOverlay
import kotlinx.coroutines.flow.debounce

@Composable
fun MainPage(
    telemetryViewModel: SharedViewModel,
    authViewModel: AuthViewModel,
    navController: NavHostController
) {
    val telemetryState by telemetryViewModel.telemetryState.collectAsState()
    val context = LocalContext.current

    // Collect area values from ViewModel
    val missionAreaFormatted by telemetryViewModel.missionAreaFormatted.collectAsState()
    val surveyAreaFormatted by telemetryViewModel.surveyAreaFormatted.collectAsState()
    val missionUploaded by telemetryViewModel.missionUploaded.collectAsState()

    // Decide which area string to show in the status panel
    val areaToDisplay = if (missionUploaded) {
        if (missionAreaFormatted.isNotBlank()) missionAreaFormatted else "N/A"
    } else {
        if (surveyAreaFormatted.isNotBlank()) surveyAreaFormatted else "N/A"
    }

    // Collect uploaded waypoints for display
    val uploadedWaypoints by telemetryViewModel.uploadedWaypoints.collectAsState()
    val surveyPolygon by telemetryViewModel.surveyPolygon.collectAsState()
    val gridLines by telemetryViewModel.gridLines.collectAsState()
    val gridWaypoints by telemetryViewModel.gridWaypoints.collectAsState()
    val geofenceEnabled by telemetryViewModel.geofenceEnabled.collectAsState()
    val geofencePolygon by telemetryViewModel.geofencePolygon.collectAsState()
    val obstacles by telemetryViewModel.obstacles.collectAsState()

    // Selected geofence point tracking for adjustment
    var selectedGeofencePointIndex by remember { mutableStateOf<Int?>(null) }

    // Map camera state controlled from parent so refresh can move it
    val cameraPositionState = rememberCameraPositionState()

    // Map type state
    var mapType by remember { mutableStateOf(MapType.SATELLITE) }

    // Ensure we center the map once when MainPage opens if we have telemetry
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(telemetryState.latitude, telemetryState.longitude) {
        val lat = telemetryState.latitude
        val lon = telemetryState.longitude
        if (!centeredOnce && lat != null && lon != null) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
            centeredOnce = true
        }
    }

    val notifications by telemetryViewModel.notifications.collectAsState()
    val isNotificationPanelVisible by telemetryViewModel.isNotificationPanelVisible.collectAsState()

    // Collect spray status popup
    val sprayStatusPopup by telemetryViewModel.sprayStatusPopup.collectAsState()

    // Collect resume point location for displaying "R" marker on map
    val resumePointLocation by telemetryViewModel.resumePointLocation.collectAsState()

    // Collect "Set Resume Point Here" popup state
    val showSetResumePointPopup by telemetryViewModel.showAddResumeHerePopup.collectAsState()
    val resumePointWaypoint by telemetryViewModel.resumePointWaypoint.collectAsState()

    // Resume Mission dialog states
    var showResumeWarningDialog by remember { mutableStateOf(false) }
    var showResumeWaypointDialog by remember { mutableStateOf(false) }
    var showResumeProgressDialog by remember { mutableStateOf(false) }

    // Use mutableStateOf without remember key - let it update freely
    var resumeWaypointNumber by remember { mutableStateOf(
        telemetryState.pausedAtWaypoint
            ?: telemetryState.currentWaypoint
            ?: 1
    )}

    // Always update resumeWaypointNumber when pausedAtWaypoint or currentWaypoint changes
    LaunchedEffect(telemetryState.pausedAtWaypoint, telemetryState.currentWaypoint) {
        val newWaypoint = telemetryState.pausedAtWaypoint
            ?: telemetryState.currentWaypoint
            ?: 1
        resumeWaypointNumber = newWaypoint
    }

    var resumeProgressMessage by remember { mutableStateOf("Initializing...") }

    // Mission Completion Dialog state
    val showMissionCompletionDialog by telemetryViewModel.showMissionCompletionDialog.collectAsState()
    val missionCompletionData by telemetryViewModel.missionCompletionData.collectAsState()
    val currentProjectName by telemetryViewModel.currentProjectName.collectAsState()
    val currentPlotName by telemetryViewModel.currentPlotName.collectAsState()
    val currentCropType by telemetryViewModel.currentCropType.collectAsState()

    // Drone Camera Feed state
    var showCameraFeed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Pass uploadedWaypoints to GcsMap for blue markers/lines
            GcsMap(
                telemetryState = telemetryState,
                points = uploadedWaypoints,
                surveyPolygon = surveyPolygon,
                gridLines = gridLines.map { listOf(it.first, it.second) },
                gridWaypoints = gridWaypoints,
                mapType = mapType,
                cameraPositionState = cameraPositionState,
                autoCenter = false,
                heading = telemetryState.heading,
                geofencePolygon = geofencePolygon,
                geofenceEnabled = geofenceEnabled,
                // Geofence adjustment parameters
                onGeofencePointDrag = { index, newPosition ->
                    // Update the geofence polygon when user drags a vertex
                    if (index in geofencePolygon.indices) {
                        val updatedPolygon = geofencePolygon.toMutableList().apply {
                            this[index] = newPosition
                        }
                        telemetryViewModel.updateGeofencePolygonManually(updatedPolygon)
                    }
                },
                selectedGeofencePointIndex = selectedGeofencePointIndex,
                onGeofencePointClick = { index ->
                    selectedGeofencePointIndex = index
                    Toast.makeText(context, "Geofence point ${index + 1} selected - drag to adjust", Toast.LENGTH_SHORT).show()
                },
                geofenceAdjustmentEnabled = geofenceEnabled,
                // Obstacle zones for display (no editing on main page)
                obstacles = obstacles,
                // Resume point marker - shows "R" where drone paused
                resumePointLocation = resumePointLocation
            )

            StatusPanel(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                telemetryState = telemetryState,
                areaFormatted = areaToDisplay
            )



            FloatingButtons(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp),
                onToggleMapType = {
                    mapType = if (mapType == MapType.SATELLITE) MapType.NORMAL else MapType.SATELLITE
                },
                onStartMission = {
                    telemetryViewModel.startMission { success, error ->
                        if (success) {
                            Toast.makeText(context, "Mission start sent", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, error ?: "Failed to start mission", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRefresh = {
                    val lat = telemetryState.latitude
                    val lon = telemetryState.longitude
                    if (lat != null && lon != null) {
                        cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
                    } else {
                        Toast.makeText(context, "No GPS location available", Toast.LENGTH_SHORT).show()
                    }
                },
                onCameraClick = {
                    showCameraFeed = !showCameraFeed
                },
                currentMode = telemetryState.mode
            )

            if (isNotificationPanelVisible) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    NotificationPanel(notifications = notifications)
                }
            }

            // Spray Status Popup (appears at top center)
            sprayStatusPopup?.let { message ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    SprayStatusPopup(message = message)
                }
            }

            // Drone Camera Feed Overlay (Picture-in-Picture at bottom-end, expandable)
            // With MissionPlanner-like video tracking integration
            val cameraTrackingState by telemetryViewModel.cameraTrackingState.collectAsState()
            DroneCameraFeedOverlay(
                isVisible = showCameraFeed,
                onDismiss = { showCameraFeed = false },
                modifier = Modifier.matchParentSize(),
                videoStreamUrl = null, // Set to drone camera stream URL when available (e.g., RTSP/HTTP)
                isConnected = telemetryState.connected,
                // Video tracking integration
                trackingState = cameraTrackingState,
                telemetryState = telemetryState,
                onVideoTap = { x, y -> telemetryViewModel.onVideoTap(x, y) },
                onVideoDragComplete = { x1, y1, x2, y2 -> telemetryViewModel.onVideoDragComplete(x1, y1, x2, y2) },
                onVideoLongPress = { x, y -> telemetryViewModel.onVideoLongPress(x, y) },
                onStopTracking = { telemetryViewModel.stopVideoTracking() },
                onGimbalNudge = { pitch, yaw -> telemetryViewModel.nudgeGimbal(pitch, yaw) },
                onGimbalClearROI = { telemetryViewModel.clearGimbalROI() }
            )

            // TopNavBar removed - it's already handled in AppNavGraph.kt
        }

        // Mission Complete - NO POPUP, notification is sufficient
        // Track mission completion for logging purposes only
        var lastHandledCompletionTime by remember { mutableStateOf(0L) }

        LaunchedEffect(telemetryState.missionCompleted, telemetryState.lastMissionElapsedSec) {
            val currentCompletionTime = telemetryState.lastMissionElapsedSec ?: 0L
            val isNewCompletion = currentCompletionTime != lastHandledCompletionTime && currentCompletionTime > 0L

            if (telemetryState.missionCompleted && isNewCompletion) {
                val missionTime = telemetryState.lastMissionElapsedSec
                val missionDistance = telemetryState.totalDistanceMeters
                val litresConsumed = telemetryState.sprayTelemetry.consumedLiters

                lastHandledCompletionTime = currentCompletionTime
                // No popup - notification already shown by UnifiedFlightTracker
            }
        }

        // Mission Completion Dialog - shows when mission ends with project/plot name inputs
        if (showMissionCompletionDialog) {
            MissionCompletionDialog(
                totalTime = missionCompletionData.totalTime,
                totalAcres = missionCompletionData.totalAcres,
                sprayedAcres = missionCompletionData.sprayedAcres,
                consumedLitres = missionCompletionData.consumedLitres,
                initialProjectName = currentProjectName,
                initialPlotName = currentPlotName,
                initialCropType = currentCropType,
                onDismiss = {
                    telemetryViewModel.dismissMissionCompletionDialog()
                },
                onSave = { projectName, plotName, cropType ->
                    telemetryViewModel.saveMissionCompletionData(projectName, plotName, cropType)
                    Toast.makeText(context, "Mission data saved", Toast.LENGTH_SHORT).show()
                }
            )
        }


        // Resume Mission Warning Dialog (Step 1)
        if (showResumeWarningDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showResumeWarningDialog = false
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResumeWarningDialog = false
                            showResumeWaypointDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text(AppStrings.continueTxt, color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showResumeWarningDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(AppStrings.cancel, color = MaterialTheme.colorScheme.onSecondary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = AppStrings.warning,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(AppStrings.resumeMissionWarning, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column {
                        Text(
                            AppStrings.resumeWarningMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            AppStrings.resumeFilterMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            )
        }

        // Resume Mission Waypoint Selection Dialog (Step 2)
        if (showResumeWaypointDialog) {
            // Use key() to force recomposition with fresh state when dialog opens
            key(showResumeWaypointDialog, telemetryState.pausedAtWaypoint, telemetryState.currentWaypoint) {
                // Get the current waypoint to resume from
                val defaultWaypoint = telemetryState.pausedAtWaypoint
                    ?: telemetryState.currentWaypoint
                    ?: 1

                var waypointInput by remember { mutableStateOf(defaultWaypoint.toString()) }

                AlertDialog(
                onDismissRequest = { showResumeWaypointDialog = false },
                confirmButton = {
                    Button(
                        onClick = {
                            val waypointNum = waypointInput.toIntOrNull() ?: defaultWaypoint
                            showResumeWaypointDialog = false
                            showResumeProgressDialog = true

                            // Start resume mission process
                            telemetryViewModel.resumeMissionComplete(
                                resumeWaypointNumber = waypointNum,
                                resetHomeCoords = false,
                                onProgress = { progress ->
                                    resumeProgressMessage = progress
                                },
                                onResult = { success, error ->
                                    showResumeProgressDialog = false
                                    if (!success) {
                                        Toast.makeText(
                                            context,
                                            "${AppStrings.resumeFailed}: ${error ?: AppStrings.unknownError}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(AppStrings.resume, color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showResumeWaypointDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(AppStrings.cancel, color = MaterialTheme.colorScheme.onSecondary)
                    }
                },
                title = {
                    Text(AppStrings.resumeMissionAt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            AppStrings.enterWaypointNumber,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = waypointInput,
                            onValueChange = { waypointInput = it },
                            label = { Text(AppStrings.waypoint) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${AppStrings.defaultWaypoint} $defaultWaypoint ${AppStrings.lastAutoWaypoint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            }
        }

        // Resume Mission Progress Dialog (Step 3)
        if (showResumeProgressDialog) {
            AlertDialog(
                onDismissRequest = { /* Cannot dismiss during operation */ },
                confirmButton = { },
                title = {
                    Text(AppStrings.resumingMission, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            resumeProgressMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            )
        }

        // === SET RESUME POINT POPUP ===
        // This dialog appears when mode changes from AUTO to LOITER during a mission
        if (showSetResumePointPopup) {
            AlertDialog(
                onDismissRequest = {
                    telemetryViewModel.cancelSetResumePoint()
                },
                confirmButton = {
                    Button(
                        onClick = {
                            telemetryViewModel.confirmSetResumePoint()
                            Toast.makeText(
                                context,
                                "Resume point set at waypoint ${resumePointWaypoint ?: "?"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)) // Green
                    ) {
                        Text("OK", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            telemetryViewModel.cancelSetResumePoint()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(AppStrings.cancel, color = MaterialTheme.colorScheme.onSecondary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "Resume Point",
                            tint = Color(0xFF4CAF50), // Green
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Set Resume Point",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            "Do you want to set resume point here?",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Waypoint: ${resumePointWaypoint ?: "Unknown"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Click OK to mark this location as resume point.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}


@Composable
fun StatusPanel(
    modifier: Modifier = Modifier,
    telemetryState: TelemetryState,
    areaFormatted: String
) {
    Surface(
        modifier = modifier
            .widthIn(min = 180.dp, max = 480.dp)
            .heightIn(min = 48.dp, max = 74.dp),
        color = Color.Black.copy(alpha = 0.22f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Format altitude with proper null handling
                val formattedAltitude = telemetryState.altitudeRelative?.let { "%.1f m".format(it) } ?: "--"
                Text(
                    "${AppStrings.alt}: $formattedAltitude",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${AppStrings.speedLabel}: ${telemetryState.formattedGroundspeed ?: "N/A"}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${AppStrings.area}: ${areaFormatted}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${AppStrings.flow}: ${telemetryState.sprayTelemetry.formattedFlowRate ?: "N/A"}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Show waypoint info - display pause state or current waypoint
                val waypointText = if (telemetryState.missionPaused) {
                    "⏸ ${AppStrings.wp} ${telemetryState.pausedAtWaypoint ?: "?"}"
                } else if (telemetryState.currentWaypoint != null) {
                    "${AppStrings.wp}: ${telemetryState.currentWaypoint}"
                } else {
                    "${AppStrings.wp}: N/A"
                }
                Text(
                    waypointText,
                    color = if (telemetryState.missionPaused) Color.Yellow else Color.White,
                    fontSize = 11.sp,
                    fontWeight = if (telemetryState.missionPaused) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Format mission timer
                val timeStr = telemetryState.missionElapsedSec?.let { sec ->
                    val m = (sec % 3600) / 60
                    val s = sec % 60
                    "%02d:%02d".format(m, s)
                } ?: "N/A"
                Text(
                    "${AppStrings.time}: $timeStr",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Format total distance
                val distStr = telemetryState.totalDistanceMeters?.let { dist ->
                    if (dist < 1000f) "%.0f m".format(dist)
                    else "%.2f km".format(dist / 1000f)
                } ?: "N/A"
                Text(
                    "${AppStrings.distance}: $distStr",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${AppStrings.consumed}: ${telemetryState.sprayTelemetry.formattedConsumed ?: "N/A"}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FloatingButtons(
    modifier: Modifier = Modifier,
    onToggleMapType: () -> Unit,
    onStartMission: () -> Unit,
    onRefresh: () -> Unit,
    onCameraClick: () -> Unit = {},
    currentMode: String?
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Start Mission Button
        FloatingActionButton(
            onClick = { onStartMission() },
            containerColor = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.size(width = 70.dp, height = 56.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = AppStrings.start,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AppStrings.start,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }


        // Recenter Button
        FloatingActionButton(
            onClick = { onRefresh() },
            containerColor = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.size(width = 70.dp, height = 56.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = AppStrings.recenter,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AppStrings.recenter,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Toggle Map Type Button
        FloatingActionButton(
            onClick = { onToggleMapType() },
            containerColor = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.size(width = 70.dp, height = 56.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = AppStrings.mapType,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AppStrings.mapType,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Camera Feed Button
        FloatingActionButton(
            onClick = { onCameraClick() },
            containerColor = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.size(width = 70.dp, height = 56.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = AppStrings.camera,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AppStrings.camera,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun SprayStatusPopup(message: String) {
    Surface(
        modifier = Modifier
            .wrapContentSize()
            .padding(horizontal = 16.dp),
        color = Color(0xFF4CAF50).copy(alpha = 0.9f), // Green background with transparency
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (message.contains("Enabled", ignoreCase = true))
                    Icons.Default.CheckCircle
                else
                    Icons.Default.Info,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
