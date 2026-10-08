package com.example.kftgcs.aquaculture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.kftgcs.aquaculture.model.PondPoint
import com.example.kftgcs.aquaculture.repository.AquacultureMissionRepository
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.uimain.GcsMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun AquacultureScreen(viewModel: AquacultureViewModel, telemetry: SharedViewModel,
                      onBack: () -> Unit, onFlightView: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val vehicle by telemetry.telemetryState.collectAsState()
    val progress by telemetry.missionUploadProgress.collectAsState()
    val repository = remember(telemetry) { AquacultureMissionRepository(telemetry) }
    val initialLocation = remember { LatLng(vehicle.latitude ?: 17.0, vehicle.longitude ?: 78.0) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(initialLocation, 17f) }
    var showMission by rememberSaveable { mutableStateOf(false) }
    var showCalculationInfo by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    BackHandler { if (!state.uploading) onBack() }

    val hasBoundary = state.boundary.isNotEmpty()
    LaunchedEffect(hasBoundary, state.plan, mapSize) {
        if (hasBoundary) {
            if (state.boundary.size >= 3 && mapSize.width > 300 && mapSize.height > 300) {
                val bounds = LatLngBounds.builder().apply {
                    state.boundary.forEach { include(LatLng(it.latitude, it.longitude)) }
                }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, mapSize.width, mapSize.height, 120))
            } else {
                val point = state.boundary.first()
                camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 17f))
            }
        }
    }
    val selectedLoad = state.plan?.loads?.get(state.selectedLoad)
    val lapPoints = selectedLoad?.mission?.waypoints?.filter { it.passIndex == state.selectedPass }
        ?.map { LatLng(it.position.latitude, it.position.longitude) } ?: emptyList()
    val homeAvailable = vehicle.homeLatitude?.isFinite() == true && vehicle.homeLongitude?.isFinite() == true &&
        !(vehicle.homeLatitude == 0.0 && vehicle.homeLongitude == 0.0)
    val uploadEnabled = vehicle.connected && vehicle.fcuDetected && !vehicle.armed && !vehicle.isMissionActive && homeAvailable

    AquacultureTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = onBack, enabled = !state.uploading) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Icon(Icons.Outlined.Water, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Aquaculture", style = MaterialTheme.typography.titleLarge)
                    Text("/", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(AquacultureIcons.Prawn, "Prawn culture", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Prawn", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("Mission planner", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                BoxWithConstraints(Modifier.weight(1f)) {
                    val panelWidth = if (maxWidth >= 900.dp) 400.dp else 340.dp
                    val mapContent: @Composable () -> Unit = {
                        Box(Modifier.fillMaxSize().onSizeChanged { mapSize = it }.testTag("aquaculture_map")) {
                            GcsMap(telemetryState = vehicle, cameraPositionState = camera, autoCenter = false, mapType = MapType.SATELLITE,
                                surveyPolygon = state.boundary.map { LatLng(it.latitude, it.longitude) }, boundaryWaypoints = lapPoints,
                                polygonEditingEnabled = state.editingBoundary && !state.uploading,
                                compactBoundaryMarkers = true,
                                onMapClick = { if (!state.uploading) viewModel.addVertex(PondPoint(it.latitude, it.longitude)) },
                                onPolygonPointDrag = { index, point -> if (!state.uploading) viewModel.moveVertex(index, PondPoint(point.latitude, point.longitude)) },
                                onPolygonPointClick = viewModel::selectVertex, selectedPolygonPointIndex = state.selectedVertex)
                            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (state.editingBoundary) Icons.Outlined.EditLocationAlt else Icons.Outlined.Route, null,
                                        Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Column {
                                        Text(if (state.editingBoundary) "Draw pond boundary" else "Boundary path", style = MaterialTheme.typography.labelLarge)
                                        Text("${state.boundary.size} vertices" + (state.plan?.let { "  ·  ${it.totalPasses} passes  ·  ${state.fields[AquacultureField.INDENTATION]} m inset" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    val formContent: @Composable () -> Unit = {
                        Surface(Modifier.fillMaxSize().testTag("aquaculture_planning_panel"), color = MaterialTheme.colorScheme.background) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val chipColors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary)
                                    FilterChip(selected = !showMission, onClick = { showMission = false }, label = { Text("Setup") },
                                        leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(22.dp)) }, colors = chipColors, modifier = Modifier.weight(1f))
                                    FilterChip(selected = showMission, onClick = { showMission = true }, label = { Text("Mission") },
                                        leadingIcon = { Icon(Icons.Outlined.Route, null, Modifier.size(22.dp)) }, colors = chipColors, modifier = Modifier.weight(1f))
                                }
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (!showMission) {
                                        AquacultureConfiguration(state, viewModel) {
                                            val lat = initialLocation.latitude
                                            val lon = initialLocation.longitude
                                            viewModel.setBoundary(listOf(PondPoint(lat, lon), PondPoint(lat, lon + 0.001),
                                                PondPoint(lat + 0.0008, lon + 0.001), PondPoint(lat + 0.0008, lon)))
                                        }
                                    } else {
                                        if (state.summary == null) AquacultureSection("Mission review", Icons.Outlined.Route,
                                            "Set up the pond and culture inputs, then calculate the feeding requirement.") {
                                            OutlinedButton(onClick = { showMission = false }, modifier = Modifier.fillMaxWidth()) {
                                                ButtonIcon(Icons.Outlined.Tune); Text("Open setup")
                                            }
                                        }
                                        AquacultureSummary(state, viewModel::generate)
                                        AquacultureMissionSummary(state, viewModel::selectLoad, viewModel::selectPass, onUpload = {
                                            val plan = state.plan
                                            val loadIndex = state.selectedLoad
                                            if (plan != null && viewModel.beginUpload()) repository.upload(plan, loadIndex) { success, error ->
                                                viewModel.finishUpload(loadIndex, success, error)
                                            }
                                        }, uploadEnabled = uploadEnabled, progress = if (state.uploading) progress?.message else null,
                                            onFlightView = onFlightView)
                                    }
                                    state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                                    Spacer(Modifier.height(6.dp))
                                }
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { showCalculationInfo = true }) { Icon(Icons.Outlined.Info, "Calculation information") }
                                    Button(onClick = { viewModel.calculate(); if (viewModel.state.value.summary != null) showMission = true },
                                        enabled = !state.uploading, modifier = Modifier.weight(1f)) {
                                        ButtonIcon(Icons.Outlined.Calculate); Text("Calculate & review")
                                    }
                                }
                            }
                        }
                    }
                    if (maxWidth >= 600.dp) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(panelWidth).fillMaxHeight()) { formContent() }
                            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            Box(Modifier.weight(1f).fillMaxHeight()) { mapContent() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { formContent() }
                            Box(Modifier.fillMaxWidth().height(220.dp)) { mapContent() }
                        }
                    }
                }
            }
        }
        if (showCalculationInfo) AlertDialog(onDismissRequest = { showCalculationInfo = false },
            icon = { Icon(Icons.Outlined.Info, null) }, title = { Text("Calculation information") },
            text = { Text("Feed estimates use an interim culture-day table and operator-entered biomass. The formulas are not scientifically validated. Uploads contain flight commands only; feed discharge is simulated.") },
            confirmButton = { TextButton(onClick = { showCalculationInfo = false }) { Text("Close") } })
    }
}
