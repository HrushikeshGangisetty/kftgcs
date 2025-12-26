package com.example.aerogcsclone.uimain

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.lifecycle.viewmodel.compose.viewModel
//import com.example.aerogcsclone.Telemetry.SharedViewModel
import com.example.aerogcsclone.authentication.AuthViewModel
import com.example.aerogcsclone.database.GridParameters
import com.example.aerogcsclone.ui.components.SaveMissionDialog
import com.example.aerogcsclone.ui.components.MissionChoiceDialog
import com.example.aerogcsclone.ui.components.TemplateSelectionDialog
import com.example.aerogcsclone.viewmodel.MissionTemplateViewModel
import com.google.android.gms.maps.model.LatLng
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.MavFrame
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.MapType
import com.example.aerogcsclone.navigation.Screen
import kotlinx.coroutines.launch
import com.google.android.gms.maps.CameraUpdateFactory
import com.example.aerogcsclone.grid.*
import com.example.aerogcsclone.telemetry.SharedViewModel
import java.util.Locale
import com.example.aerogcsclone.utils.AppStrings

@Suppress("UnusedMaterial3ScaffoldPaddingParameter", "UNUSED_PARAMETER")
@Composable
fun PlanScreen(
    telemetryViewModel: SharedViewModel,
    authViewModel: AuthViewModel,
    navController: NavHostController,
    missionTemplateViewModel: MissionTemplateViewModel = viewModel()
) {
    val telemetryState by telemetryViewModel.telemetryState.collectAsState()
    val missionTemplateUiState by missionTemplateViewModel.uiState.collectAsState()
    val templates by missionTemplateViewModel.templates.collectAsState(initial = emptyList())
    val fenceRadius by telemetryViewModel.fenceRadius.collectAsState()
    val geofenceEnabled by telemetryViewModel.geofenceEnabled.collectAsState()
    val geofencePolygon by telemetryViewModel.geofencePolygon.collectAsState()
    val context = LocalContext.current
    val uploadProgress by telemetryViewModel.missionUploadProgress.collectAsState()

    // State management
    var isGridSurveyMode by remember { mutableStateOf(false) }
    var showGridControls by remember { mutableStateOf(false) }
    var mapType by remember { mutableStateOf(MapType.SATELLITE) }

    // Mission template dialog states
    var showMissionChoiceDialog by remember { mutableStateOf(true) }
    var showSaveMissionDialog by remember { mutableStateOf(false) }
    var showTemplateSelectionDialog by remember { mutableStateOf(false) }
    var hasStartedPlanning by remember { mutableStateOf(false) }

    // Plan save/edit state - controls whether plan can be edited and uploaded
    var isPlanSaved by remember { mutableStateOf(false) }  // True after saving, false during editing

    // Grid survey parameters
    var lineSpacing by remember { mutableStateOf(3f) }
    var gridAngle by remember { mutableStateOf(0f) }
    var surveySpeed by remember { mutableStateOf(10f) }
    var surveyAltitude by remember { mutableStateOf(60f) }
    var holdNosePosition by remember { mutableStateOf(false) }
    var autoSpray by remember { mutableStateOf(false) }
    var indentation by remember { mutableStateOf(1f) }  // Safe zone padding in meters (1-10m, 0.5 step)

    // Grid state
    var surveyPolygon by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var gridResult by remember { mutableStateOf<GridSurveyResult?>(null) }
    val gridGenerator = remember { GridGenerator() }
    // Local-only geofence polygon used while planning (do not publish until upload)
    var localGeofencePolygon by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    // Camera and waypoint state
    val cameraPositionState = rememberCameraPositionState()
    val points = remember { mutableStateListOf<LatLng>() }
    val waypoints = remember { mutableStateListOf<MissionItemInt>() }
    val coroutineScope = rememberCoroutineScope()

    // Selected waypoint tracking for deletion
    var selectedWaypointIndex by remember { mutableStateOf<Int?>(null) }

    // Selected polygon point tracking for deletion and dragging
    var selectedPolygonPointIndex by remember { mutableStateOf<Int?>(null) }

    // ===== SPLIT PLAN STATE =====
    // Split Plan mode controls
    var isSplitPlanMode by remember { mutableStateOf(false) }
    // Slider range: 0f to 1f representing 0% to 100%
    var splitStartPercent by remember { mutableStateOf(0f) }
    var splitEndPercent by remember { mutableStateOf(1f) }
    // Original grid result backup (to preserve original mission)
    var originalGridResult by remember { mutableStateOf<GridSurveyResult?>(null) }
    // Split grid result for upload (generated from slider selection)
    var splitGridResult by remember { mutableStateOf<GridSurveyResult?>(null) }

    // Selected geofence point tracking for adjustment
    var selectedGeofencePointIndex by remember { mutableStateOf<Int?>(null) }

    // Waypoint list panel state
    var showWaypointList by remember { mutableStateOf(false) }

    // Center map once when screen opens
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(telemetryState.latitude, telemetryState.longitude) {
        val lat = telemetryState.latitude
        val lon = telemetryState.longitude
        if (!centeredOnce && lat != null && lon != null) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
            centeredOnce = true
        }
    }

    // Helper functions - moved before LaunchedEffect that uses them
    fun buildMissionItemFromLatLng(
        latLng: LatLng,
        seq: Int,
        isTakeoff: Boolean = false,
        alt: Float = 10f
    ): MissionItemInt {
        return MissionItemInt(
            targetSystem = 0u,
            targetComponent = 0u,
            seq = seq.toUShort(),
            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
            command = if (isTakeoff) MavEnumValue.of(MavCmd.NAV_TAKEOFF) else MavEnumValue.of(MavCmd.NAV_WAYPOINT),
            current = 0u,
            autocontinue = 1u,
            param1 = 0f, param2 = 0f, param3 = 0f, param4 = 0f,
            x = (latLng.latitude * 1E7).toInt(),
            y = (latLng.longitude * 1E7).toInt(),
            z = alt
        )
    }

    fun regenerateGrid() {
        if (surveyPolygon.size >= 3) {
            val params = GridSurveyParams(
                lineSpacing = lineSpacing,
                gridAngle = gridAngle,
                speed = surveySpeed,
                altitude = surveyAltitude,
                includeSpeedCommands = true,
                indentation = indentation
            )
            gridResult = gridGenerator.generateGridSurvey(surveyPolygon, params)

            // Do NOT update SharedViewModel here: keep planning state local to PlanScreen
            // SharedViewModel will be updated only after the user uploads the mission

            // Recompute local geofence polygon for preview while planning
            if (geofenceEnabled) {
                val allWaypoints = mutableListOf<LatLng>()
                // prefer grid waypoints for buffer, fall back to survey polygon
                gridResult?.let { res -> allWaypoints.addAll(res.waypoints.map { it.position }) }
                if (allWaypoints.isEmpty()) allWaypoints.addAll(surveyPolygon)
                if (allWaypoints.isNotEmpty()) {
                    val bufferDistance = fenceRadius.toDouble()
                    localGeofencePolygon = com.example.aerogcsclone.utils.GeofenceUtils.generatePolygonBuffer(allWaypoints, bufferDistance)
                } else {
                    localGeofencePolygon = emptyList()
                }
            }
        }
    }

    /**
     * Generate a split grid result based on start and end percentages.
     * Maps percentage (0-100%) to grid line indices.
     * The split operates strictly along grid lines, not by waypoint count or distance.
     *
     * CRITICAL: Re-indexes waypoints so lineIndex starts from 0 for proper mission upload.
     * Also updates isLineStart/isLineEnd flags for the new subset.
     */
    fun generateSplitGridResult(
        sourceGrid: GridSurveyResult,
        startPercent: Float,
        endPercent: Float
    ): GridSurveyResult {
        if (sourceGrid.gridLines.isEmpty() || sourceGrid.waypoints.isEmpty()) {
            android.util.Log.w("SplitPlan", "Empty source grid - returning as-is")
            return sourceGrid
        }

        val totalLines = sourceGrid.numLines
        if (totalLines == 0) {
            android.util.Log.w("SplitPlan", "Zero total lines - returning source grid")
            return sourceGrid
        }

        // Map percentage to grid line index
        // startPercent=0% means start from first grid line (index 0)
        // endPercent=100% means include all grid lines up to the last one
        val startLineIndex = (startPercent * (totalLines - 1)).toInt().coerceIn(0, totalLines - 1)
        val endLineIndex = (endPercent * (totalLines - 1)).toInt().coerceIn(0, totalLines - 1)

        android.util.Log.d("SplitPlan", "Split calculation: totalLines=$totalLines, startPercent=$startPercent, endPercent=$endPercent")
        android.util.Log.d("SplitPlan", "Line indices: startLineIndex=$startLineIndex, endLineIndex=$endLineIndex")

        // Ensure valid range
        if (startLineIndex > endLineIndex) {
            android.util.Log.w("SplitPlan", "Invalid range: startLineIndex > endLineIndex")
            return GridSurveyResult(
                waypoints = emptyList(),
                gridLines = emptyList(),
                totalDistance = 0.0,
                estimatedTime = 0.0,
                numLines = 0,
                polygonArea = sourceGrid.polygonArea
            )
        }

        // Filter grid lines within the selected range
        val selectedGridLines = sourceGrid.gridLines.filterIndexed { index, _ ->
            index in startLineIndex..endLineIndex
        }

        // Filter waypoints that belong to the selected grid lines
        // Each grid line has 2 waypoints (start and end)
        val filteredWaypoints = sourceGrid.waypoints.filter { wp ->
            wp.lineIndex in startLineIndex..endLineIndex
        }

        android.util.Log.d("SplitPlan", "Filtered: ${selectedGridLines.size} lines, ${filteredWaypoints.size} waypoints")

        // CRITICAL: Re-index waypoints so lineIndex starts from 0
        // This ensures proper mission upload with correct line start/end detection
        val reindexedWaypoints = filteredWaypoints.map { wp ->
            val newLineIndex = wp.lineIndex - startLineIndex
            wp.copy(lineIndex = newLineIndex)
        }

        // Log first few reindexed waypoints for debugging
        reindexedWaypoints.take(6).forEachIndexed { idx, wp ->
            android.util.Log.d("SplitPlan", "WP[$idx]: lineIndex=${wp.lineIndex}, isStart=${wp.isLineStart}, isEnd=${wp.isLineEnd}")
        }

        // Recalculate total distance for selected waypoints
        var totalDistance = 0.0
        for (i in 0 until reindexedWaypoints.size - 1) {
            totalDistance += GridUtils.haversineDistance(
                reindexedWaypoints[i].position,
                reindexedWaypoints[i + 1].position
            )
        }

        // Calculate estimated time based on speed
        val estimatedTime = if (surveySpeed > 0) totalDistance / surveySpeed else 0.0

        android.util.Log.i("SplitPlan", "✓ Generated split: ${reindexedWaypoints.size} waypoints, ${selectedGridLines.size} lines, ${String.format("%.1f", totalDistance)}m")

        return GridSurveyResult(
            waypoints = reindexedWaypoints,
            gridLines = selectedGridLines,
            totalDistance = totalDistance,
            estimatedTime = estimatedTime,
            numLines = selectedGridLines.size,
            polygonArea = sourceGrid.polygonArea
        )
    }

    // Handle mission template UI state changes
    LaunchedEffect(missionTemplateUiState) {
        missionTemplateUiState.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            missionTemplateViewModel.clearMessages()
        }

        missionTemplateUiState.successMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            missionTemplateViewModel.clearMessages()
            if (showSaveMissionDialog) {
                showSaveMissionDialog = false
                isPlanSaved = true  // Lock the plan after successful save
            }
        }

        missionTemplateUiState.selectedTemplate?.let { template ->
            // Load template data into current state
            points.clear()
            waypoints.clear()

            if (template.isGridSurvey && template.gridParameters != null) {
                isGridSurveyMode = true
                showGridControls = true

                val gridParams = template.gridParameters
                lineSpacing = gridParams.lineSpacing
                gridAngle = gridParams.gridAngle
                surveySpeed = gridParams.surveySpeed
                surveyAltitude = gridParams.surveyAltitude
                surveyPolygon = gridParams.surveyPolygon

                if (surveyPolygon.size >= 3) {
                    regenerateGrid()
                }
            } else {
                isGridSurveyMode = false
                showGridControls = false
                points.addAll(template.waypointPositions)
                waypoints.addAll(template.waypoints)
            }

            // Center map on first waypoint if available
            if (template.waypointPositions.isNotEmpty()) {
                val firstPoint = template.waypointPositions.first()
                cameraPositionState.move(
                    CameraUpdateFactory.newLatLngZoom(firstPoint, 16f)
                )
            }

            missionTemplateViewModel.clearSelectedTemplate()
            isPlanSaved = true  // Loaded template is already saved, user can upload or edit
            Toast.makeText(context, "Template '${template.plotName}' loaded successfully", Toast.LENGTH_SHORT).show()
        }
    }

    // Map click handler
    val onMapClick: (LatLng) -> Unit = { latLng ->
        if (isPlanSaved) {
            // Plan is saved - no editing allowed until "Edit Plan" is clicked
            Toast.makeText(context, AppStrings.planSavedEditRequired, Toast.LENGTH_SHORT).show()
        } else if (isGridSurveyMode) {
            // Grid mode: clicking on map adds polygon points
            surveyPolygon = surveyPolygon + latLng
            if (surveyPolygon.size >= 3) {
                regenerateGrid()
            }
        } else {
            // Regular waypoint mode: Disable map click for adding waypoints
            // User must use "Add Point" button instead
            // Map click does nothing in waypoint mode now
        }
    }

    // Update grid when parameters change
    LaunchedEffect(lineSpacing, gridAngle, surveySpeed, surveyAltitude, indentation, surveyPolygon) {
        if (isGridSurveyMode) {
            regenerateGrid()
        }
    }

    // Update split grid when sliders change (Split Plan mode)
    LaunchedEffect(isSplitPlanMode, splitStartPercent, splitEndPercent, originalGridResult) {
        if (isSplitPlanMode && originalGridResult != null) {
            splitGridResult = generateSplitGridResult(originalGridResult!!, splitStartPercent, splitEndPercent)
        }
    }

    Scaffold(
        floatingActionButton = {
            // Bottom right - Delete waypoint button only (hidden when plan is saved)
            if (hasStartedPlanning && !isPlanSaved) {
                FloatingActionButton(
                    onClick = {
                        if (isGridSurveyMode) {
                            // Handle deletion of selected polygon point
                            selectedPolygonPointIndex?.let { index ->
                                if (index in surveyPolygon.indices) {
                                    surveyPolygon = surveyPolygon.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    selectedPolygonPointIndex = null // Clear selection after deletion
                                    if (surveyPolygon.size >= 3) {
                                        regenerateGrid()
                                    } else {
                                        gridResult = null
                                    }
                                    // update local geofence after deleting a point
                                    if (geofenceEnabled) {
                                        val allWaypoints = gridResult?.waypoints?.map { it.position } ?: surveyPolygon
                                        localGeofencePolygon = if (allWaypoints.isNotEmpty()) {
                                            com.example.aerogcsclone.utils.GeofenceUtils.generatePolygonBuffer(allWaypoints, fenceRadius.toDouble())
                                        } else {
                                            emptyList()
                                        }
                                    }
                                }
                            } ?: run {
                                // If no polygon point is selected, show a toast message
                                Toast.makeText(context, "Please select a polygon point to delete", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if (waypoints.isNotEmpty()) {
                                // Handle deletion of selected waypoint
                                selectedWaypointIndex?.let { index ->
                                    if (index in waypoints.indices) {
                                        waypoints.removeAt(index)
                                        points.removeAt(index)
                                        selectedWaypointIndex = null // Clear selection after deletion
                                        // update local geofence after deleting a point
                                        if (geofenceEnabled) {
                                            if (points.isNotEmpty()) {
                                                localGeofencePolygon = com.example.aerogcsclone.utils.GeofenceUtils.generatePolygonBuffer(points.toList(), fenceRadius.toDouble())
                                            } else {
                                                localGeofencePolygon = emptyList()
                                            }
                                        }
                                    }
                                } ?: run {
                                    // If no waypoint is selected, show a toast message
                                    Toast.makeText(context, "Please select a waypoint to delete", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = if (isGridSurveyMode) "Delete Polygon Point" else "Delete Waypoint")
                }
            }
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Map
            GcsMap(
                telemetryState = telemetryState,
                points = if (isGridSurveyMode) emptyList() else points,
                onMapClick = onMapClick,
                cameraPositionState = cameraPositionState,
                mapType = mapType,
                autoCenter = false,
                surveyPolygon = if (isGridSurveyMode) surveyPolygon else emptyList(),
                gridLines = gridResult?.gridLines?.map { pair -> listOf(pair.first, pair.second) } ?: emptyList(),
                gridWaypoints = gridResult?.waypoints?.map { it.position } ?: emptyList(),
                heading = telemetryState.heading,
                // Split Plan visual feedback parameters
                splitPlanMode = isSplitPlanMode,
                splitGridLines = if (isSplitPlanMode && splitGridResult != null) {
                    splitGridResult!!.gridLines.map { pair -> listOf(pair.first, pair.second) }
                } else emptyList(),
                splitGridWaypoints = if (isSplitPlanMode && splitGridResult != null) {
                    splitGridResult!!.waypoints.map { it.position }
                } else emptyList(),
                geofencePolygon = if (hasStartedPlanning) localGeofencePolygon else geofencePolygon,
                geofenceEnabled = geofenceEnabled,
                // Handle waypoint dragging (disabled when plan is saved)
                onWaypointDrag = { index, newPosition ->
                    if (!isPlanSaved && index in points.indices) {
                        // Update the waypoint position
                        points[index] = newPosition
                        // Update the mission item
                        val updatedItem = waypoints[index].copy(
                            x = (newPosition.latitude * 1E7).toInt(),
                            y = (newPosition.longitude * 1E7).toInt()
                        )
                        waypoints[index] = updatedItem

                        // Update local geofence if enabled
                        if (geofenceEnabled && points.isNotEmpty()) {
                            localGeofencePolygon = com.example.aerogcsclone.utils.GeofenceUtils.generatePolygonBuffer(
                                points.toList(),
                                fenceRadius.toDouble()
                            )
                        }
                    }
                },
                // Handle waypoint selection
                selectedWaypointIndex = selectedWaypointIndex,
                onWaypointClick = { index ->
                    selectedWaypointIndex = index
                    selectedPolygonPointIndex = null // Clear polygon selection
                },
                // Handle polygon point dragging (disabled when plan is saved)
                onPolygonPointDrag = { index, newPosition ->
                    if (!isPlanSaved && index in surveyPolygon.indices) {
                        // Update the polygon point position
                        surveyPolygon = surveyPolygon.toMutableList().apply {
                            this[index] = newPosition
                        }
                        // Regenerate the grid with the updated polygon
                        if (surveyPolygon.size >= 3) {
                            regenerateGrid()
                        }
                    }
                },
                // Handle polygon point selection
                selectedPolygonPointIndex = selectedPolygonPointIndex,
                onPolygonPointClick = { index ->
                    selectedPolygonPointIndex = index
                    selectedWaypointIndex = null // Clear waypoint selection
                    selectedGeofencePointIndex = null // Clear geofence selection
                },
                // Handle geofence point dragging
                onGeofencePointDrag = { index, newPosition ->
                    if (hasStartedPlanning) {
                        // Update the local geofence polygon
                        if (index in localGeofencePolygon.indices) {
                            localGeofencePolygon = localGeofencePolygon.toMutableList().apply {
                                this[index] = newPosition
                            }
                        }
                    } else {
                        // Update the shared view model geofence polygon
                        if (index in geofencePolygon.indices) {
                            val updatedPolygon = geofencePolygon.toMutableList().apply {
                                this[index] = newPosition
                            }
                            telemetryViewModel.updateGeofencePolygonManually(updatedPolygon)
                        }
                    }
                },
                // Handle geofence point selection
                selectedGeofencePointIndex = selectedGeofencePointIndex,
                onGeofencePointClick = { index ->
                    selectedGeofencePointIndex = index
                    selectedWaypointIndex = null // Clear waypoint selection
                    selectedPolygonPointIndex = null // Clear polygon selection
                    Toast.makeText(context, "Geofence point ${index + 1} selected - drag to adjust", Toast.LENGTH_SHORT).show()
                },
                // Enable geofence adjustment when geofence is enabled
                geofenceAdjustmentEnabled = geofenceEnabled,
                // Show area and dimensions for grid survey mode
                showGridInfo = isGridSurveyMode && surveyPolygon.size >= 3
            )

            // Geofence adjustment helper text
            if (geofenceEnabled && (if (hasStartedPlanning) localGeofencePolygon else geofencePolygon).isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        text = "💡 ${AppStrings.geofence}: Tap orange markers to select, then drag to adjust boundary",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Status indicator
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Text(
                    "Connected: ${telemetryState.connected}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "FCU detected: ${telemetryState.fcuDetected}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Mode: ${if (isGridSurveyMode) "Grid Survey" else "Waypoints"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGridSurveyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isGridSurveyMode) FontWeight.Bold else FontWeight.Normal
                )
            }

            // Top right button column - new organized layout
            if (hasStartedPlanning) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 90.dp), // Moved up from 120dp to 90dp
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // === EDITING STATE: Show Add Point and Save Mission buttons ===
                    if (!isPlanSaved) {
                        // Add Point button with text and transparent background
                        ElevatedButton(
                            onClick = {
                                val center = cameraPositionState.position.target
                                if (isGridSurveyMode) {
                                    surveyPolygon = surveyPolygon + center
                                    if (surveyPolygon.size >= 3) regenerateGrid()
                                } else {
                                    val seq = waypoints.size
                                    val item = buildMissionItemFromLatLng(center, seq, seq == 0)
                                    points.add(center)
                                    waypoints.add(item)
                                }
                            },
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.7f), // Keep transparent
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(
                                defaultElevation = 6.dp
                            ),
                            modifier = Modifier.widthIn(min = 120.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(AppStrings.addPoint)
                        }

                        // Save Mission button - only shown when NOT saved and there's content to save
                        val hasContent = if (isGridSurveyMode) surveyPolygon.size >= 3 else points.isNotEmpty()
                        ElevatedButton(
                            onClick = { showSaveMissionDialog = true },
                            enabled = hasContent,
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color(0xFF2E2E2E), // Dark gray background
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF2E2E2E).copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(
                                defaultElevation = 6.dp
                            ),
                            modifier = Modifier.widthIn(min = 120.dp)
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(AppStrings.saveMission)
                        }
                    }

                    // === SAVED STATE: Show Edit Plan and Upload Mission buttons ===
                    if (isPlanSaved) {
                        // Edit Plan button - allows user to go back to editing mode
                        ElevatedButton(
                            onClick = {
                                isPlanSaved = false
                                Toast.makeText(context, AppStrings.planUnlocked, Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color(0xFF4A4A4A), // Medium gray background
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(
                                defaultElevation = 6.dp
                            ),
                            modifier = Modifier.widthIn(min = 120.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(AppStrings.editPlan)
                        }

                        // Split Plan button - only shown for grid survey missions after plan is saved
                        if (isGridSurveyMode && gridResult != null && gridResult!!.gridLines.isNotEmpty()) {
                            ElevatedButton(
                                onClick = {
                                    // Enter Split Plan mode
                                    originalGridResult = gridResult
                                    splitStartPercent = 0f
                                    splitEndPercent = 1f
                                    splitGridResult = gridResult
                                    isSplitPlanMode = true
                                },
                                colors = ButtonDefaults.elevatedButtonColors(
                                    containerColor = Color(0xFFFF9800), // Orange background
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.elevatedButtonElevation(
                                    defaultElevation = 6.dp
                                ),
                                modifier = Modifier.widthIn(min = 120.dp)
                            ) {
                                Icon(
                                    Icons.Default.CallSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(AppStrings.splitPlanBtn)
                            }
                        }

                        // Upload Mission button - only shown after plan is saved
                        ElevatedButton(
                            onClick = {
                                val homeLat = telemetryState.latitude ?: 0.0
                                val homeLon = telemetryState.longitude ?: 0.0

                                if (isGridSurveyMode && gridResult != null) {
                                    // Use split grid result if in split plan mode, otherwise use original
                                    val gridToUpload = if (isSplitPlanMode && splitGridResult != null && splitGridResult!!.waypoints.isNotEmpty()) {
                                        splitGridResult!!
                                    } else {
                                        gridResult!!
                                    }

                                    // Grid survey mission upload
                                    val homePosition = LatLng(homeLat, homeLon)
                                    val currentHeading = telemetryState.heading ?: 0f
                                    val fcuSystemId = telemetryViewModel.getFcuSystemId()
                                    val fcuComponentId = telemetryViewModel.getFcuComponentId()
                                    val builtMission = GridMissionConverter.convertToMissionItems(
                                        gridResult = gridToUpload,
                                        homePosition = homePosition,
                                        holdNosePosition = holdNosePosition,
                                        initialYaw = currentHeading,
                                        autoSpray = autoSpray,
                                        fcuSystemId = fcuSystemId,
                                        fcuComponentId = fcuComponentId
                                    )

                                    telemetryViewModel.uploadMission(builtMission) { success, error ->
                                        if (success) {
                                            Toast.makeText(context, AppStrings.gridMissionUploaded, Toast.LENGTH_SHORT).show()

                                            // If Hold Nose Position is enabled, set up yaw hold
                                            if (holdNosePosition) {
                                                coroutineScope.launch {
                                                    // Set WP_YAW_BEHAVIOR = 0 to prevent auto yaw changes
                                                    telemetryViewModel.setWpYawBehavior(0)
                                                    // Enable yaw hold and capture current yaw
                                                    telemetryViewModel.enableYawHold()
                                                    Toast.makeText(context, "Yaw locked at ${currentHeading.toInt()}°", Toast.LENGTH_SHORT).show()
                                                }
                                            }

                                            // Publish planning points and grid/survey data to SharedViewModel only after successful upload
                                            // Use the grid that was actually uploaded (split or original)
                                            val publishedPoints = gridToUpload.waypoints.map { it.position }
                                            telemetryViewModel.setPlanningWaypoints(publishedPoints)
                                            telemetryViewModel.setSurveyPolygon(surveyPolygon)
                                            telemetryViewModel.setGridWaypoints(gridToUpload.waypoints.map { it.position })
                                            telemetryViewModel.setGridLines(gridToUpload.gridLines)

                                            // Reset split plan mode after successful upload
                                            if (isSplitPlanMode) {
                                                isSplitPlanMode = false
                                                splitGridResult = null
                                                originalGridResult = null
                                            }

                                            coroutineScope.launch { telemetryViewModel.readMissionFromFcu() }
                                            navController.navigate(Screen.Main.route) {
                                                popUpTo(Screen.Plan.route) { inclusive = true }
                                            }
                                        } else {
                                            Toast.makeText(context, error ?: AppStrings.failedToUploadGrid, Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                 } else if (points.isNotEmpty()) {
                                     // Regular waypoint mission upload
                                     val builtMission = mutableListOf<MissionItemInt>()
                                     val homeAlt = telemetryState.altitudeMsl ?: 10f
                                     val fcuSystemId = telemetryViewModel.getFcuSystemId()
                                     val fcuComponentId = telemetryViewModel.getFcuComponentId()

                                    // CRITICAL FIX: Correct MAVLink mission structure for ArduPilot
                                    // seq: 0 = HOME position (NAV_WAYPOINT with current=1)
                                    // seq: 1 = TAKEOFF
                                    // seq: 2+ = Mission waypoints

                                    // Sequence 0: Home position as NAV_WAYPOINT (current=1)
                                    builtMission.add(
                                        MissionItemInt(
                                            targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = 0u,
                                            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                                            command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                                            current = 1u, // MUST be 1 for first item (home)
                                            autocontinue = 1u,
                                            param1 = 0f, // Hold time
                                            param2 = 0f, // Acceptance radius
                                            param3 = 0f, // Pass through waypoint
                                            param4 = 0f, // Yaw
                                            x = (homeLat * 1E7).toInt(),
                                            y = (homeLon * 1E7).toInt(),
                                            z = 0f  // Home altitude (relative)
                                        )
                                    )

                                    // Sequence 1: Takeoff command
                                    builtMission.add(
                                        MissionItemInt(
                                            targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = 1u,
                                            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                                            command = MavEnumValue.of(MavCmd.NAV_TAKEOFF),
                                            current = 0u, autocontinue = 1u,
                                            param1 = 0f, // Pitch angle
                                            param2 = 0f, param3 = 0f,
                                            param4 = 0f, // Yaw angle
                                            x = (homeLat * 1E7).toInt(),
                                            y = (homeLon * 1E7).toInt(),
                                            z = 10f  // Takeoff altitude
                                        )
                                    )

                                    // Sequence 2+: User-defined waypoints
                                    points.forEachIndexed { idx, latLng ->
                                        val seq = idx + 2  // Start from seq=2 (0=home, 1=takeoff)
                                        val isLast = idx == points.lastIndex
                                        builtMission.add(
                                            MissionItemInt(
                                                targetSystem = fcuSystemId, targetComponent = fcuComponentId, seq = seq.toUShort(),
                                                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                                                command = if (isLast) MavEnumValue.of(MavCmd.NAV_LAND) else MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                                                current = 0u, autocontinue = 1u,
                                                param1 = 0f, // Loiter time (for waypoint)
                                                param2 = 0f, param3 = 0f, param4 = 0f,
                                                x = (latLng.latitude * 1E7).toInt(),
                                                y = (latLng.longitude * 1E7).toInt(),
                                                z = 10f  // Waypoint altitude
                                            )
                                        )
                                    }

                                    telemetryViewModel.uploadMission(builtMission) { success, error ->
                                         if (success) {
                                             Toast.makeText(context, AppStrings.missionUploadedSuccess, Toast.LENGTH_SHORT).show()
                                             // Publish planning points to SharedViewModel only after successful upload
                                             telemetryViewModel.setPlanningWaypoints(points.toList())
                                             coroutineScope.launch { telemetryViewModel.readMissionFromFcu() }
                                             navController.navigate(Screen.Main.route) {
                                                 popUpTo(Screen.Plan.route) { inclusive = true }
                                             }
                                         } else {
                                             Toast.makeText(context, error ?: AppStrings.failedToUploadMission, Toast.LENGTH_SHORT).show()
                                         }
                                     }
                                } else {
                                    Toast.makeText(context, AppStrings.noWaypointsToUpload, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = if (isGridSurveyMode) gridResult?.waypoints?.isNotEmpty() == true else points.isNotEmpty(),
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color(0xFF1A1A1A), // Darker background
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(
                                defaultElevation = 6.dp
                            ),
                            modifier = Modifier.widthIn(min = 120.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(AppStrings.uploadMissionBtn)
                        }
                    }
                }
            }

            // Crosshair
            if (hasStartedPlanning) {
                Box(
                    modifier = Modifier.size(36.dp).align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Crosshair",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Top left arrow back icon
            if (hasStartedPlanning) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 16.dp)
                ) {
                    @Suppress("DEPRECATION")
                    IconButton(onClick = {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(Screen.Plan.route) { inclusive = true }
                        }
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Left sidebar buttons
            if (hasStartedPlanning) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 72.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            if (isPlanSaved) {
                                Toast.makeText(context, AppStrings.planSavedEditRequired, Toast.LENGTH_SHORT).show()
                            } else {
                                isGridSurveyMode = !isGridSurveyMode
                                showGridControls = isGridSurveyMode
                                // Don't clear data when toggling modes - preserve user's work
                            }
                        },
                        containerColor = if (isGridSurveyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Grid Survey Mode",
                            tint = if (isGridSurveyMode) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // NEW: Waypoint List button
                    FloatingActionButton(
                        onClick = { showWaypointList = !showWaypointList },
                        containerColor = if (showWaypointList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = "Waypoint List",
                            tint = if (showWaypointList) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    FloatingActionButton(
                        onClick = {
                            val lat = telemetryState.latitude
                            val lon = telemetryState.longitude
                            if (lat != null && lon != null) {
                                cameraPositionState.move(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f)
                                )
                            } else {
                                Toast.makeText(context, "No GPS location available", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on Drone")
                    }

                    FloatingActionButton(
                        onClick = {
                            mapType = if (mapType == MapType.SATELLITE) MapType.NORMAL else MapType.SATELLITE
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = "Toggle Map Type")
                    }
                }
            }

            // Grid controls - restored original design with statistics
            if (showGridControls && hasStartedPlanning) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 96.dp)
                        .fillMaxWidth(0.40f) // decreased more from 0.44f to 0.40f
                        .fillMaxHeight(0.82f) // increased from 0.75f to 0.82f
                        .widthIn(min = 280.dp) // reduced min width from 300.dp to 280.dp
                        .heightIn(min = 360.dp), // increased min height from 320.dp to 360.dp
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.92f)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // Header with title and close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Grid Survey Parameters",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { showGridControls = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close Panel",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Show locked state indicator when plan is saved
                        if (isPlanSaved) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF4A4A4A)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Plan is saved. Click 'Edit Plan' to modify.",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        // Line Spacing
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Line Spacing", color = if (isPlanSaved) Color.Gray else Color.White, modifier = Modifier.weight(1f))
                                Text("${String.format(Locale.US, "%.1f", lineSpacing)} m", color = if (isPlanSaved) Color.Gray else Color.White, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { if (!isPlanSaved) lineSpacing = (lineSpacing - 0.1f).coerceAtLeast(3f) },
                                    enabled = !isPlanSaved,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = if (isPlanSaved) Color.Gray else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = lineSpacing,
                                    onValueChange = { if (!isPlanSaved) lineSpacing = it },
                                    enabled = !isPlanSaved,
                                    valueRange = 3f..5f,
                                    steps = 19,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (isPlanSaved) Color.Gray else MaterialTheme.colorScheme.primary,
                                        activeTrackColor = if (isPlanSaved) Color.Gray else MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray,
                                        disabledThumbColor = Color.Gray,
                                        disabledActiveTrackColor = Color.Gray
                                    )
                                )
                                IconButton(
                                    onClick = { if (!isPlanSaved) lineSpacing = (lineSpacing + 0.1f).coerceAtMost(5f) },
                                    enabled = !isPlanSaved,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Grid Angle
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Grid Angle", color = Color.White, modifier = Modifier.weight(1f))
                                Text("${gridAngle.toInt()}°", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { gridAngle = (gridAngle - 5f).coerceAtLeast(0f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = gridAngle,
                                    onValueChange = { gridAngle = it },
                                    valueRange = 0f..180f,
                                    steps = 35,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                IconButton(
                                    onClick = { gridAngle = (gridAngle + 5f).coerceAtMost(180f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Survey Speed
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Speed", color = Color.White, modifier = Modifier.weight(1f))
                                Text("${surveySpeed.toInt()} m/s", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { surveySpeed = (surveySpeed - 1f).coerceAtLeast(1f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = surveySpeed,
                                    onValueChange = { surveySpeed = it },
                                    valueRange = 1f..20f,
                                    steps = 40,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                IconButton(
                                    onClick = { surveySpeed = (surveySpeed + 1f).coerceAtMost(20f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Survey Altitude
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Altitude", color = Color.White, modifier = Modifier.weight(1f))
                                Text("${surveyAltitude.toInt()} m", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { surveyAltitude = (surveyAltitude - 1f).coerceAtLeast(1f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = surveyAltitude,
                                    onValueChange = { surveyAltitude = it },
                                    valueRange = 1f..30f,
                                    steps = 60,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                IconButton(
                                    onClick = { surveyAltitude = (surveyAltitude + 1f).coerceAtMost(30f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Indentation (Safe Zone Padding)
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Indentation", color = Color.White, modifier = Modifier.weight(1f))
                                Text("${String.format(Locale.US, "%.1f", indentation)} m", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { indentation = (indentation - 0.5f).coerceAtLeast(1f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = indentation,
                                    onValueChange = { indentation = it },
                                    valueRange = 1f..10f,
                                    steps = 17,  // (10-1)/0.5 - 1 = 17 steps for 0.5m increments
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                IconButton(
                                    onClick = { indentation = (indentation + 0.5f).coerceAtMost(10f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                "Safe zone padding from polygon boundary",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        // Hold Nose Position
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Hold Nose Position", color = Color.White, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = holdNosePosition,
                                    onCheckedChange = { holdNosePosition = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color.Green, // Green when ON
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.Red // Red when OFF
                                    )
                                )
                            }
                            Text(
                                "Nose will hold current position during survey lines",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Auto Spray
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Auto Spray", color = Color.White, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = autoSpray,
                                    onCheckedChange = { autoSpray = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color.Green, // Green when ON
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.Red // Red when OFF
                                    )
                                )
                            }
                            Text(
                                "Sprayer will turn ON during survey lines",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        HorizontalDivider(color = Color.Gray, thickness = 1.dp)

                        // Geofence Toggle (moved above buffer distance as requested)
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Enable Geofence",
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold
                                )
                                Switch(
                                    checked = geofenceEnabled,
                                    onCheckedChange = { telemetryViewModel.setGeofenceEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color.Green, // Green when ON
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.Red // Red when OFF
                                    )
                                )
                            }
                            if (geofenceEnabled) {
                                Text(
                                    "Polygon fence active around mission plan",
                                    color = Color.Green,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            } else {
                                Text(
                                    "Geofence disabled",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        // Buffer Distance Slider (moved below toggle as requested)
                        if (geofenceEnabled) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Buffer Distance", color = Color.White, modifier = Modifier.weight(1f))
                                    Text("${fenceRadius.toInt()} m", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = fenceRadius,
                                    onValueChange = { telemetryViewModel.setFenceRadius(it) },
                                    valueRange = -4f..50f, // Changed minimum from 0 to -4 for testing boundary crossing
                                    steps = 50, // Updated steps to match new range
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Green,
                                        activeTrackColor = Color.Green,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                Text(
                                    "Adjust polygon buffer distance around mission plan",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        gridResult?.let { result ->
                            HorizontalDivider(color = Color.Gray, thickness = 1.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Grid Statistics:", color = Color.White, fontWeight = FontWeight.Bold)
                                Text("Waypoints: ${result.waypoints.size}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Text("Lines: ${result.numLines}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Text("Distance: ${String.format(Locale.US, "%.1f", result.totalDistance / 1000)}km", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Text("Time: ${String.format(Locale.US, "%.1f", result.estimatedTime / 60)}min", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Text("Area: ${result.polygonArea}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Waypoint List Panel with Drag-and-Drop Reordering
            if (showWaypointList && hasStartedPlanning && !isGridSurveyMode && points.isNotEmpty()) {
                WaypointListPanel(
                    waypoints = points.toList(),
                    onReorder = { fromIndex, toIndex ->
                        // Reorder both points and waypoints lists
                        if (fromIndex in points.indices && toIndex in points.indices) {
                            val movedPoint = points.removeAt(fromIndex)
                            points.add(toIndex, movedPoint)

                            val movedWaypoint = waypoints.removeAt(fromIndex)
                            waypoints.add(toIndex, movedWaypoint)

                            // Renumber waypoints automatically (seq numbers)
                            waypoints.forEachIndexed { index, waypoint ->
                                waypoints[index] = waypoint.copy(seq = index.toUShort())
                            }

                            Toast.makeText(context, AppStrings.waypointReordered, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onWaypointClick = { index ->
                        selectedWaypointIndex = index
                        // Center map on selected waypoint
                        if (index in points.indices) {
                            cameraPositionState.move(
                                CameraUpdateFactory.newLatLngZoom(points[index], 18f)
                            )
                        }
                    },
                    onClose = { showWaypointList = false },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .fillMaxWidth(0.35f)
                        .fillMaxHeight(0.70f)
                )
            }

            // ===== SPLIT PLAN UI PANEL =====
            // Compact dual-ended range slider for selecting grid line range
            if (isSplitPlanMode && hasStartedPlanning && isGridSurveyMode && originalGridResult != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp, start = 80.dp, end = 80.dp)
                        .fillMaxWidth(0.6f)
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.92f),
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Calculate grid line indices based on percentages
                        val totalLines = originalGridResult!!.numLines
                        val startLineIndex = (splitStartPercent * (totalLines - 1)).toInt().coerceIn(0, totalLines - 1)
                        val endLineIndex = (splitEndPercent * (totalLines - 1)).toInt().coerceIn(0, totalLines - 1)
                        val selectedLines = if (endLineIndex >= startLineIndex) endLineIndex - startLineIndex + 1 else 0

                        // Header row with title, stats and close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CallSplit,
                                    contentDescription = null,
                                    tint = Color(0xFFFF9800),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    AppStrings.splitPlanTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Selected lines count in center
                            Text(
                                "$selectedLines / $totalLines lines",
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    isSplitPlanMode = false
                                    splitGridResult = null
                                    originalGridResult = null
                                    splitStartPercent = 0f
                                    splitEndPercent = 1f
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Compact sliders row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Start value
                            Text(
                                "${startLineIndex + 1}",
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            // Combined slider area
                            Column(modifier = Modifier.weight(1f)) {
                                // Start Slider
                                Slider(
                                    value = splitStartPercent,
                                    onValueChange = { newValue ->
                                        splitStartPercent = newValue.coerceAtMost(splitEndPercent - 0.01f).coerceAtLeast(0f)
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth().height(24.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF4CAF50),
                                        activeTrackColor = Color(0xFF4CAF50),
                                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                                    )
                                )
                                // End Slider
                                Slider(
                                    value = splitEndPercent,
                                    onValueChange = { newValue ->
                                        splitEndPercent = newValue.coerceAtLeast(splitStartPercent + 0.01f).coerceAtMost(1f)
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth().height(24.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFF44336),
                                        activeTrackColor = Color(0xFFF44336),
                                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            // End value
                            Text(
                                "${endLineIndex + 1}",
                                color = Color(0xFFF44336),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Compact stats and action row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stats
                            splitGridResult?.let { split ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "${split.waypoints.size} pts",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "${String.format(Locale.US, "%.1f", split.totalDistance / 1000)}km",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "${String.format(Locale.US, "%.0f", split.estimatedTime / 60)}min",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Apply button
                            Button(
                                onClick = {
                                    Toast.makeText(context, AppStrings.splitApplied, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF9800)
                                ),
                                enabled = splitGridResult != null && splitGridResult!!.waypoints.isNotEmpty()
                            ) {
                                Text(
                                    AppStrings.applySplit,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Dialogs
            if (showMissionChoiceDialog && !hasStartedPlanning) {
                MissionChoiceDialog(
                    onDismiss = {
                        showMissionChoiceDialog = false
                        hasStartedPlanning = true
                    },
                    onLoadExisting = {
                        showMissionChoiceDialog = false
                        showTemplateSelectionDialog = true
                    },
                    onCreateNew = {
                        showMissionChoiceDialog = false
                        hasStartedPlanning = true
                    },
                    hasTemplates = templates.isNotEmpty()
                )
            }

            if (showTemplateSelectionDialog) {
                TemplateSelectionDialog(
                    templates = templates,
                    onDismiss = {
                        showTemplateSelectionDialog = false
                        hasStartedPlanning = true
                    },
                    onSelectTemplate = { template ->
                        showTemplateSelectionDialog = false
                        hasStartedPlanning = true
                        missionTemplateViewModel.loadTemplate(template.id)
                    },
                    isLoading = missionTemplateUiState.isLoading
                )
            }

            if (showSaveMissionDialog) {
                SaveMissionDialog(
                    onDismiss = { showSaveMissionDialog = false },
                    onSave = { projectName, plotName ->
                        val currentGridParams = if (isGridSurveyMode) {
                            GridParameters(
                                lineSpacing = lineSpacing,
                                gridAngle = gridAngle,
                                surveySpeed = surveySpeed,
                                surveyAltitude = surveyAltitude,
                                surveyPolygon = surveyPolygon
                            )
                        } else null

                        val waypointsToSave = if (isGridSurveyMode && gridResult != null) {
                            gridResult!!.waypoints.mapIndexed { index, gridWaypoint ->
                                buildMissionItemFromLatLng(
                                    gridWaypoint.position,
                                    index,
                                    index == 0,
                                    gridWaypoint.altitude
                                )
                            }
                        } else {
                            waypoints.toList()
                        }

                        val positionsToSave = if (isGridSurveyMode && gridResult != null) {
                            gridResult!!.waypoints.map { it.position }
                        } else {
                            points.toList()
                        }

                        missionTemplateViewModel.saveTemplate(
                            projectName = projectName,
                            plotName = plotName,
                            waypoints = waypointsToSave,
                            waypointPositions = positionsToSave,
                            isGridSurvey = isGridSurveyMode,
                            gridParameters = currentGridParams
                        )
                    },
                    isLoading = missionTemplateUiState.isLoading
                )
            }
        }
    }

    // Mission Upload Progress Dialog
    uploadProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Prevent dismissing during upload */ },
            title = {
                Text(
                    text = "Uploading Mission",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Stage indicator
                    Text(
                        text = progress.stage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (progress.stage) {
                            "Complete" -> Color.Green
                            "Failed", "Error" -> Color.Red
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )

                    // Progress bar
                    LinearProgressIndicator(
                        progress = progress.percentage / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = when (progress.stage) {
                            "Complete" -> Color.Green
                            "Failed", "Error" -> Color.Red
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )

                    // Progress text
                    Text(
                        text = "${progress.percentage}%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )

                    // Current item / total items
                    Text(
                        text = "${progress.currentItem} / ${progress.totalItems} waypoints",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Progress message
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    // Loading indicator (except for Complete/Failed/Error)
                    if (progress.stage !in listOf("Complete", "Failed", "Error")) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(40.dp)
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                // No confirm button during upload
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Recompute local geofence whenever enable flag or radius or planning sets change
    LaunchedEffect(geofenceEnabled, fenceRadius, gridResult, points, surveyPolygon) {
        if (!geofenceEnabled) {
            localGeofencePolygon = emptyList()
        } else {
            val allWaypoints = mutableListOf<LatLng>()
            if (isGridSurveyMode) {
                gridResult?.let { allWaypoints.addAll(it.waypoints.map { w -> w.position }) }
                if (allWaypoints.isEmpty()) allWaypoints.addAll(surveyPolygon)
            } else {
                allWaypoints.addAll(points)
            }
            if (allWaypoints.isNotEmpty()) {
                localGeofencePolygon = com.example.aerogcsclone.utils.GeofenceUtils.generatePolygonBuffer(allWaypoints, fenceRadius.toDouble())
            } else {
                localGeofencePolygon = emptyList()
            }
        }
    }
}
