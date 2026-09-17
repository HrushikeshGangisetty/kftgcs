// Kotlin
package com.example.kftgcs.uimain

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.kftgcs.telemetry.TelemetryState
import com.example.kftgcs.authentication.AuthViewModel
import com.google.maps.android.compose.MapType
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import androidx.compose.ui.text.font.FontWeight
import com.example.kftgcs.telemetry.SharedViewModel
import androidx.compose.runtime.getValue
import com.example.kftgcs.usersettings.UserSettingsViewModel
import androidx.compose.ui.text.style.TextOverflow
import com.example.kftgcs.grid.GridUtils
import com.example.kftgcs.utils.AppStrings
import com.example.kftgcs.ui.components.MissionCompletionDialog
import com.example.kftgcs.ui.components.DroneCameraFeedOverlay

@Composable
fun MainPage(
    telemetryViewModel: SharedViewModel,
    authViewModel: AuthViewModel,
    navController: NavHostController,
    userSettingsViewModel: UserSettingsViewModel
) {
    val telemetryState by telemetryViewModel.telemetryState.collectAsState()
    val context = LocalContext.current

    // Drone path colour from the shared user settings (updates live when changed)
    val userSettings by userSettingsViewModel.settings.collectAsState()
    val dronePathColor = userSettings.dronePathColor

    // Collect area values from ViewModel
    val missionAreaFormatted by telemetryViewModel.missionAreaFormatted.collectAsState()
    val surveyAreaFormatted by telemetryViewModel.surveyAreaFormatted.collectAsState()
    val missionUploaded by telemetryViewModel.missionUploaded.collectAsState()

    // Area enclosed by the mission's waypoints. Once a mission is uploaded this is the area
    // captured at upload time (what the drone will actually fly); before that it tracks the
    // polygon being drawn.
    val waypointAreaToDisplay = if (missionUploaded) {
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

    // Area enclosed by the geofence, shown alongside (never instead of) the waypoint area.
    // Null when there is no fence to measure, which the panel renders as "N/A" rather than a
    // misleading 0. Computed with the same GridUtils helper the map label uses, so the two
    // readouts cannot disagree. remember() keyed on the polygon: this is a geodesic area
    // calculation over every vertex and must not run on every unrelated recomposition.
    val fenceAreaToDisplay = remember(geofencePolygon, geofenceEnabled) {
        if (geofenceEnabled && geofencePolygon.size >= 3) {
            GridUtils.calculateAndFormatPolygonArea(geofencePolygon)
        } else null
    }
    // FC-side home cylinder (FENCE_RADIUS / FENCE_TYPE bit 1) — drawn only when armed.
    val rangeFenceRadius by telemetryViewModel.fenceRadiusMeters.collectAsState()
    val rangeFenceArmed by telemetryViewModel.rangeFenceArmed.collectAsState()
    val obstacles by telemetryViewModel.obstacles.collectAsState()
    val obstacleBoundary by telemetryViewModel.obstacleBoundary.collectAsState()
    val clearDronePathTrigger by telemetryViewModel.clearDronePathTrigger.collectAsState()
    // Trail is ViewModel-owned so it survives navigation away from this screen (a
    // pause/resume) without losing the green sprayed-line history.
    val dronePathPoints by telemetryViewModel.dronePathPoints.collectAsState()

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

    // Collect failsafe alert popup (Battery Failsafe / Fence Breached / Max Altitude)
    val failsafePopup by telemetryViewModel.failsafePopup.collectAsState()

    // Collect vehicle service alert state
    val showServiceAlert by telemetryViewModel.showServiceAlert.collectAsState()

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

    // ===== ADD RESUME POINT FEATURE =====
    val userSelectedFlightMode by telemetryViewModel.userSelectedFlightMode.collectAsState()
    var isAddResumePointMode by remember { mutableStateOf(false) }
    var showResumePointConfirmDialog by remember { mutableStateOf(false) }
    var pendingManualResumeLatLng by remember { mutableStateOf<LatLng?>(null) }
    var manualResumePointPending by remember { mutableStateOf<LatLng?>(null) }   // grey
    var manualResumePointUploaded by remember { mutableStateOf<LatLng?>(null) } // green

    val isAutoModeActive = userSelectedFlightMode == SharedViewModel.UserFlightMode.AUTOMATIC
        && missionUploaded

    // ===== CLEAR MISSION (moved here from the flying-method screen) =====
    // The pilot needs this where the mission actually is - on the map - not behind a
    // navigation step. Same view model calls and the same two dialogs as before; only the
    // button's location changed.
    val missionLoadedOnFc by telemetryViewModel.missionLoadedOnFc.collectAsState()
    val clearMissionState by telemetryViewModel.clearMissionState.collectAsState()
    var showClearMissionConfirm by remember { mutableStateOf(false) }

    // Disabled rather than hidden while armed, so the control is visibly present and its
    // unavailability is explained. clearMissionFromFcConfirmed re-checks both conditions
    // before touching the FC - this is a courtesy, not the safety interlock.
    val clearMissionEnabled = !telemetryState.armed && telemetryState.connected &&
        clearMissionState != SharedViewModel.ClearMissionState.CLEARING

    if (showClearMissionConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMissionConfirm = false },
            title = { Text(AppStrings.clearMission, fontWeight = FontWeight.Bold) },
            text = { Text(AppStrings.clearMissionConfirmMessage) },
            confirmButton = {
                Button(onClick = {
                    showClearMissionConfirm = false
                    telemetryViewModel.clearMissionFromFcConfirmed()
                }) { Text(AppStrings.proceed) }
            },
            dismissButton = {
                Button(onClick = { showClearMissionConfirm = false }) { Text(AppStrings.cancel) }
            }
        )
    }

    // Result acknowledged explicitly rather than auto-dismissed: whether the mission actually
    // left the drone is exactly the thing the pilot must not misread.
    if (clearMissionState == SharedViewModel.ClearMissionState.SUCCESS ||
        clearMissionState == SharedViewModel.ClearMissionState.FAILED
    ) {
        val clearSucceeded = clearMissionState == SharedViewModel.ClearMissionState.SUCCESS
        AlertDialog(
            onDismissRequest = { telemetryViewModel.acknowledgeClearMissionResult() },
            title = { Text(AppStrings.clearMission, fontWeight = FontWeight.Bold) },
            text = {
                Text(if (clearSucceeded) AppStrings.clearMissionSuccess else AppStrings.clearMissionFailed)
            },
            confirmButton = {
                Button(onClick = { telemetryViewModel.acknowledgeClearMissionResult() }) {
                    Text(AppStrings.ok)
                }
            }
        )
    }

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
                // Draw the waypoint-area label (and per-edge dimensions) on the map, the same
                // way PlanScreen does. The geofence already labels its own area here, so
                // without this the main screen showed the fence area and nothing to compare
                // it against. GcsMap gates this on surveyPolygon.size > 2 internally.
                showGridInfo = surveyPolygon.size >= 3,
                rangeFenceRadiusMeters = rangeFenceRadius,
                rangeFenceArmed = rangeFenceArmed,
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
                // Tapping the "+" on an edge adds a corner there, so the fence can go from
                // 4 sides to 5, 6, ... The new corner starts on the edge and is dragged
                // into place with the same handles as every other corner.
                onGeofenceEdgeAddPoint = { edgeIndex, midPoint ->
                    if (edgeIndex in geofencePolygon.indices) {
                        val updatedPolygon = geofencePolygon.toMutableList().apply {
                            add(edgeIndex + 1, midPoint)
                        }
                        telemetryViewModel.updateGeofencePolygonManually(updatedPolygon)
                        selectedGeofencePointIndex = edgeIndex + 1
                        Toast.makeText(
                            context,
                            "Corner added - fence now has ${updatedPolygon.size} sides",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                geofenceAdjustmentEnabled = geofenceEnabled,
                // Obstacle zones for display (no editing on main page)
                obstacles = obstacles,
                obstacleBoundary = obstacleBoundary,
                // Resume point marker - shows "R" where drone paused
                resumePointLocation = resumePointLocation,
                // Manual resume point markers
                manualResumePointPending = manualResumePointPending,
                manualResumePointUploaded = manualResumePointUploaded,
                // Clear drone path trail trigger
                clearDronePathTrigger = clearDronePathTrigger,
                dronePathPoints = dronePathPoints,
                onDronePathPoint = telemetryViewModel::recordDronePathPoint,
                dronePathColor = dronePathColor
            )

            StatusPanel(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                telemetryState = telemetryState,
                waypointAreaFormatted = waypointAreaToDisplay,
                fenceAreaFormatted = fenceAreaToDisplay
            )



            FloatingButtons(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp),
                onToggleMapType = {
                    mapType = if (mapType == MapType.SATELLITE) MapType.NORMAL else MapType.SATELLITE
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
                currentMode = telemetryState.mode,
                showAddResumePointButton = isAutoModeActive,
                isAddResumePointActive = isAddResumePointMode,
                onAddResumePoint = {
                    isAddResumePointMode = !isAddResumePointMode
                },
                showClearMapButton = userSelectedFlightMode == SharedViewModel.UserFlightMode.MANUAL,
                onClearMap = {
                    telemetryViewModel.clearMapLinesOnly()
                    Toast.makeText(context, "Map cleared", Toast.LENGTH_SHORT).show()
                }
            )

            // Top-left column: failsafe alert popup (when active) stacked above the
            // obstacle-avoidance / terrain overlay, so neither ever overlaps the other.
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Failsafe Alert Popup (top-left, disappears after 6 seconds)
                failsafePopup?.let { message ->
                    FailsafeAlertPopup(
                        message = message,
                        onDismiss = { telemetryViewModel.dismissFailsafePopup() }
                    )
                }

                // Obstacle-avoidance / terrain overlay: toggle buttons + floating widgets.
                val radarThresholds by telemetryViewModel.radarThresholds.collectAsState()
                ProximityMapOverlay(
                    terrain = telemetryState.terrainData,
                    proximity = telemetryState.proximityData,
                    thresholds = radarThresholds,
                    onClearMission = { showClearMissionConfirm = true },
                    clearMissionEnabled = clearMissionEnabled
                    // Label left at its short default: AppStrings.clearMission and
                    // clearMissionClearing are full sentences in several languages and would
                    // overflow a 70dp FAB. Progress is shown by the button dimming (it is
                    // disabled while CLEARING) and by the result dialog.
                )
            }

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

            // ===== ADD RESUME POINT - CROSSHAIR OVERLAY =====
            if (isAddResumePointMode) {
                // Semi-transparent dim overlay to indicate placement mode
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                )
                // Crosshair at map center — simple + symbol
                Box(modifier = Modifier.align(Alignment.Center)) {
                    Text(
                        text = "+",
                        color = Color.White,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 48.sp
                    )
                }
                // "Place Here" + Cancel buttons at bottom center
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { isAddResumePointMode = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                    Button(
                        onClick = {
                            pendingManualResumeLatLng = cameraPositionState.position.target
                            showResumePointConfirmDialog = true
                            isAddResumePointMode = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Place Here", color = Color.White)
                    }
                }
            }
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


        // Vehicle service limit alert
        if (showServiceAlert) {
            AlertDialog(
                onDismissRequest = { telemetryViewModel.dismissServiceAlert() },
                title = {
                    Text(
                        text = "⚠️ Service Required",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("Max flights reached. Take drone for servicing.")
                },
                confirmButton = {
                    Button(
                        onClick = { telemetryViewModel.dismissServiceAlert() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text("OK", color = Color.White)
                    }
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

        // === MANUAL ADD RESUME POINT CONFIRMATION DIALOG ===
        if (showResumePointConfirmDialog && pendingManualResumeLatLng != null) {
            val pointLat = pendingManualResumeLatLng!!.latitude
            val pointLng = pendingManualResumeLatLng!!.longitude
            AlertDialog(
                onDismissRequest = {
                    showResumePointConfirmDialog = false
                    pendingManualResumeLatLng = null
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResumePointConfirmDialog = false
                            val resumeLatLng = pendingManualResumeLatLng!!
                            pendingManualResumeLatLng = null
                            // Show grey marker immediately
                            manualResumePointPending = resumeLatLng
                            manualResumePointUploaded = null
                            // Upload in background
                            telemetryViewModel.resumeMissionFromManualPoint(
                                lat = resumeLatLng.latitude,
                                lng = resumeLatLng.longitude,
                                onResult = { success, error ->
                                    if (success) {
                                        manualResumePointPending = null
                                        manualResumePointUploaded = resumeLatLng
                                        Toast.makeText(context, "Resume mission uploaded successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        manualResumePointPending = null
                                        Toast.makeText(context, "Upload failed: ${error ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Yes, Resume From Here", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            showResumePointConfirmDialog = false
                            pendingManualResumeLatLng = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(AppStrings.cancel, color = MaterialTheme.colorScheme.onSecondary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AddLocation,
                            contentDescription = "Resume Point",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Add Resume Point",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            "Do you want the mission to resume from here?",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Lat: ${"%.6f".format(pointLat)}\nLng: ${"%.6f".format(pointLng)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The modified mission will be uploaded to the flight controller in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        // === SET RESUME POINT POPUP ===
        // This dialog appears when mode changes from AUTO to LOITER or BRAKE during a mission
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
    /** Area enclosed by the mission's waypoints — the ground actually being sprayed. */
    waypointAreaFormatted: String,
    /**
     * Area enclosed by the geofence, or null when no fence is set.
     *
     * Shown separately from [waypointAreaFormatted] because the two are never the same
     * number: the fence is a buffer drawn AROUND the mission polygon, so it is always the
     * larger of the two. Collapsing them into one "Area" readout (which is what this panel
     * used to do) left the pilot unable to tell which one they were looking at.
     */
    fenceAreaFormatted: String? = null
) {
    // Shortest (great-circle) distance between the drone and the FC-reported home point.
    // Null whenever either fix is missing, so the readout falls back to "N/A".
    val homeDistanceMeters = remember(
        telemetryState.latitude,
        telemetryState.longitude,
        telemetryState.homeLatitude,
        telemetryState.homeLongitude
    ) {
        val lat = telemetryState.latitude
        val lon = telemetryState.longitude
        val homeLat = telemetryState.homeLatitude
        val homeLon = telemetryState.homeLongitude
        if (lat != null && lon != null && homeLat != null && homeLon != null) {
            SphericalUtil.computeDistanceBetween(LatLng(lat, lon), LatLng(homeLat, homeLon))
        } else null
    }

    Surface(
        modifier = modifier
            // Widened from 560dp when the single "Area" column became two (WP Area + Fence
            // Area). At 560 the top row's five columns fell to ~112dp each and the area
            // figures ellipsized to "5.2..." - which defeats the point of separating them.
            .widthIn(min = 180.dp, max = 660.dp)
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
                // The two areas, always distinguishable. Labelled with the same wording the
                // map labels use, so the panel and the map agree.
                Text(
                    "${AppStrings.wpArea}: $waypointAreaFormatted",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${AppStrings.fenceArea}: ${fenceAreaFormatted ?: "N/A"}",
                    // Yellow to match the geofence's colour on the map and its area label.
                    color = if (fenceAreaFormatted != null) Color(0xFFFFEB3B) else Color.White,
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
                // Distance to ground from the downward terrain rangefinder (DISTANCE_SENSOR 132).
                val obsAltStr = telemetryState.terrainData
                    ?.takeIf { it.hasValidReading }
                    ?.let { "%.1f m".format(it.currentDistanceM) }
                    ?: "N/A"
                Text(
                    "obs-alt: $obsAltStr",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Format mission timer.
                // Falls back to lastMissionElapsedSec so the FINAL time of the mission that just
                // ended stays on the bar (missionElapsedSec is nulled the moment the flight stops).
                // It is cleared only when the next mission starts, so acknowledging the completion
                // dialog no longer blanks the field.
                val timeStr = (telemetryState.missionElapsedSec ?: telemetryState.lastMissionElapsedSec)?.let { sec ->
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
                // Straight-line (great-circle) distance from the drone to the FC's home point.
                val homeDistStr = homeDistanceMeters?.let { d ->
                    if (d < 1000.0) "%.0f m".format(d)
                    else "%.2f km".format(d / 1000.0)
                } ?: "N/A"
                Text(
                    "${AppStrings.homeDistance}: $homeDistStr",
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
    onRefresh: () -> Unit,
    onCameraClick: () -> Unit = {},
    currentMode: String?,
    showAddResumePointButton: Boolean = false,
    isAddResumePointActive: Boolean = false,
    onAddResumePoint: () -> Unit = {},
    showClearMapButton: Boolean = false,
    onClearMap: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

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

        // Add Resume Point Button - only visible in Automatic mode when mission is uploaded
        if (showAddResumePointButton) {
            FloatingActionButton(
                onClick = { onAddResumePoint() },
                containerColor = if (isAddResumePointActive)
                    Color(0xFF4CAF50).copy(alpha = 0.9f)   // green when active
                else
                    Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.size(width = 70.dp, height = 56.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.AddLocation,
                        contentDescription = "Add Resume Point",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Resume\nPoint",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 10.sp
                    )
                }
            }
        }

        // Clear Map Button - only visible in Manual mode
        if (showClearMapButton) {
            FloatingActionButton(
                onClick = { onClearMap() },
                containerColor = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.size(width = 70.dp, height = 56.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.LayersClear,
                        contentDescription = "Clear Map",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Clear\nMap",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 10.sp
                    )
                }
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

/** How long [FailsafeAlertPopup] stays up; must match [SharedViewModel.showFailsafePopup]'s timer. */
private const val FAILSAFE_POPUP_DURATION_MS = 6000

/**
 * Failsafe alert popup — a dark toast-style bar shown at the top-left of the screen for
 * [FAILSAFE_POPUP_DURATION_MS] whenever any RTL-triggering failsafe fires (Communication Link
 * Loss, Tank Empty, Battery Failsafe, Fence Breached, RC Failsafe, ...). A thin progress line
 * along the bottom drains over the same window so the pilot can see how long it'll stay up, and
 * the close button lets them dismiss it early via [onDismiss].
 *
 * Auto-dismiss timing itself lives in [SharedViewModel.showFailsafePopup]; this composable is
 * purely visual, restarting its own drain animation whenever [message] changes.
 */
@Composable
fun FailsafeAlertPopup(message: String, onDismiss: () -> Unit) {
    val progress = remember(message) { Animatable(1f) }
    LaunchedEffect(message) {
        progress.snapTo(1f)
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = FAILSAFE_POPUP_DURATION_MS, easing = LinearEasing)
        )
    }

    Surface(
        modifier = Modifier.wrapContentSize(),
        color = Color(0xFF12181F).copy(alpha = 0.96f), // Dark toast background
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onDismiss)
                )
            }
            // Countdown line: full width at trigger, drains to nothing over the popup's lifetime.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                        .background(Color(0xFFFF9800))
                )
            }
        }
    }
}
