package com.example.kftgcs.aquaculture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kftgcs.aquaculture.mission.PondGeometry
import com.example.kftgcs.aquaculture.model.PondBoundary
import java.util.Locale

internal fun formatNumber(value: Double, decimals: Int = 2): String = String.format(Locale.US, "%.$decimals" + "f", value)

@Composable
internal fun AquacultureSummary(state: AquacultureUiState, onGenerate: () -> Unit) {
    val feed = state.summary ?: return
    val mappedArea = PondGeometry.areaSquareMeters(PondBoundary(state.boundary)) / 4046.8564224
    AquacultureSection("Feeding summary", Icons.Outlined.Scale) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Culture", "Prawn · day ${state.fields[AquacultureField.CULTURE_DAY]}", Modifier.weight(1f))
            AquacultureMetric("Pond area", "${formatNumber(mappedArea)} acres", Modifier.weight(1f))
        }
        state.fields[AquacultureField.AREA]?.takeIf { it.isNotBlank() }?.let {
            Text("Entered area: $it acres", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Count / kg", state.fields.getValue(AquacultureField.COUNT), Modifier.weight(1f))
            AquacultureMetric("Prawn weight", "≈ ${formatNumber(feed.averagePrawnWeightGrams, 1)} g", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Biomass estimate", "${state.fields[AquacultureField.BIOMASS]} kg", Modifier.weight(1f))
            AquacultureMetric("Feed rate", "${formatNumber(feed.feedRatePercent, 1)}%", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Daily feed", "${formatNumber(feed.dailyFeedKg)} kg", Modifier.weight(1f))
            AquacultureMetric("Sessions / day", state.fields.getValue(AquacultureField.SESSIONS), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Hopper capacity", "${state.fields[AquacultureField.HOPPER]} kg", Modifier.weight(1f))
            AquacultureMetric("Discharge rate", "${state.fields[AquacultureField.DISCHARGE]} kg/s", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Feed / session", "${formatNumber(feed.feedPerSessionKg)} kg", Modifier.weight(1f))
            AquacultureMetric("Required loads", feed.requiredLoads.toString(), Modifier.weight(1f))
        }
        Button(onClick = onGenerate, enabled = !state.uploading, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(Icons.Outlined.Route); Text("Generate mission")
        }
    }
}

@Composable
internal fun AquacultureMissionSummary(state: AquacultureUiState, onSelectLoad: (Int) -> Unit,
                                      onSelectPass: (Int) -> Unit, onUpload: () -> Unit,
                                      uploadEnabled: Boolean, progress: String?, onFlightView: () -> Unit) {
    val plan = state.plan ?: return
    val load = plan.loads[state.selectedLoad]
    AquacultureSection("Boundary mission", Icons.Outlined.Route) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Waypoints", plan.totalWaypoints.toString(), Modifier.weight(1f))
            AquacultureMetric("Boundary passes", plan.totalPasses.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Flight distance", "${formatNumber(plan.distanceMeters, 0)} m", Modifier.weight(1f))
            AquacultureMetric("Flight time", "${formatNumber(plan.flightSeconds / 60, 1)} min", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        MissionPager("Load ${load.loadNumber} of ${plan.loads.size}", "load", state.selectedLoad > 0,
            state.selectedLoad < plan.loads.lastIndex, !state.uploading,
            { onSelectLoad(state.selectedLoad - 1) }, { onSelectLoad(state.selectedLoad + 1) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Load feed", "${formatNumber(load.feedKg)} kg", Modifier.weight(1f))
            AquacultureMetric("Feed / pass", "${formatNumber(load.feedKgPerPass)} kg", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Speed", "${formatNumber(load.mission.parameters.targetSpeedMetersPerSecond)} m/s", Modifier.weight(1f))
            AquacultureMetric("Discharge / pass", "${formatNumber(load.mission.parameters.feedReleaseSecondsPerPass, 1)} s", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AquacultureMetric("Altitude", "${formatNumber(load.mission.parameters.altitudeMeters, 0)} m", Modifier.weight(1f))
            AquacultureMetric("Boundary inset", "${formatNumber(load.mission.parameters.indentationMeters, 1)} m", Modifier.weight(1f))
        }
        MissionPager("Lap ${state.selectedPass + 1} of ${load.mission.parameters.passes}", "lap", state.selectedPass > 0,
            state.selectedPass < load.mission.parameters.passes - 1, !state.uploading,
            { onSelectPass(state.selectedPass - 1) }, { onSelectPass(state.selectedPass + 1) })
        Text("Map shows the selected lap. Estimates exclude launch, return and refill.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onUpload, enabled = uploadEnabled && !state.uploading, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(Icons.Outlined.CloudUpload)
            Text(if (state.uploading) "Uploading…" else "Upload load ${load.loadNumber}")
        }
        Text(if (!uploadEnabled && !state.uploading) "Connect a disarmed vehicle with a home position to upload."
            else "Flight-only upload · return and refill between loads.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.uploadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        if (state.uploadedLoad != null) OutlinedButton(onClick = onFlightView, enabled = !state.uploading, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(Icons.Outlined.FlightTakeoff); Text("Flight view")
        }
    }
}

@Composable
private fun MissionPager(label: String, kind: String, previousEnabled: Boolean, nextEnabled: Boolean,
                          enabled: Boolean, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = previous, enabled = enabled && previousEnabled) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous $kind", Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = next, enabled = enabled && nextEnabled) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next $kind", Modifier.size(24.dp))
        }
    }
}
