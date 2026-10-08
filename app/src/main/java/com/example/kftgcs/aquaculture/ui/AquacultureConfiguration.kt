package com.example.kftgcs.aquaculture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CultureDayDropdown(day: String, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(value = "Day $day", onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("Culture day") }, leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(22.dp)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("culture_day_picker"))
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)) {
            (1..120).forEach { value ->
                DropdownMenuItem(text = { Text("Day $value", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onSelect(value); expanded = false }, modifier = Modifier.testTag("culture_day_$value"))
            }
        }
    }
}

@Composable
private fun AquacultureInputField(state: AquacultureUiState, field: AquacultureField,
                                  onChange: (AquacultureField, String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = state.fields.getValue(field), onValueChange = { onChange(field, it) },
        label = { Text(field.label, style = MaterialTheme.typography.bodySmall) }, enabled = !state.uploading,
        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = if (field == AquacultureField.HOPPER) {
            { Icon(Icons.Outlined.Inventory2, null, Modifier.size(22.dp)) }
        } else null,
        supportingText = if (field == AquacultureField.HOPPER) {
            { Text("Capacity per refill. Saved for future missions.") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = if (field == AquacultureField.HOPPER) modifier.testTag("hopper_capacity") else modifier)
}

@Composable
private fun FieldPair(state: AquacultureUiState, first: AquacultureField, second: AquacultureField,
                      onChange: (AquacultureField, String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AquacultureInputField(state, first, onChange, Modifier.weight(1f))
        AquacultureInputField(state, second, onChange, Modifier.weight(1f))
    }
}

@Composable
internal fun AquacultureConfiguration(state: AquacultureUiState, viewModel: AquacultureViewModel,
                                      onSamplePond: () -> Unit) {
    AquacultureSection("Pond boundary", Icons.Outlined.Polyline,
        "Tap pond corners in order. Select a vertex to edit it.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = viewModel::toggleBoundaryEditing, enabled = !state.uploading, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                ButtonIcon(if (state.editingBoundary) Icons.Outlined.Lock else Icons.Outlined.Edit)
                Text(if (state.editingBoundary) "Lock" else "Edit")
            }
            OutlinedButton(onClick = viewModel::deleteSelectedVertex,
                enabled = state.selectedVertex != null && !state.uploading && state.editingBoundary,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                ButtonIcon(Icons.Outlined.DeleteOutline); Text("Delete")
            }
            IconButton(onClick = { viewModel.setBoundary(emptyList()) }, enabled = !state.uploading) {
                Icon(Icons.Outlined.RestartAlt, "Clear boundary")
            }
        }
        OutlinedButton(onClick = onSamplePond, enabled = !state.uploading, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(Icons.Outlined.AddLocationAlt); Text("Load sample pond")
        }
        AquacultureInputField(state, AquacultureField.AREA, viewModel::updateField, Modifier.fillMaxWidth())
    }
    AquacultureSection("Culture", AquacultureIcons.Prawn) {
        CultureDayDropdown(state.fields.getValue(AquacultureField.CULTURE_DAY), !state.uploading, viewModel::setCultureDay)
        FieldPair(state, AquacultureField.COUNT, AquacultureField.BIOMASS, viewModel::updateField)
    }
    AquacultureSection("Feeding", Icons.Outlined.Scale) {
        AquacultureInputField(state, AquacultureField.HOPPER, viewModel::updateField, Modifier.fillMaxWidth())
        AquacultureInputField(state, AquacultureField.SESSIONS, viewModel::updateField, Modifier.fillMaxWidth())
        AquacultureInputField(state, AquacultureField.DISCHARGE, viewModel::updateField, Modifier.fillMaxWidth())
        AquacultureInputField(state, AquacultureField.FEED_RATE, viewModel::updateField, Modifier.fillMaxWidth())
        Text("Leave the rate blank to use the culture-day table.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AquacultureSection("Flight settings", Icons.Outlined.Tune) {
        FieldPair(state, AquacultureField.ALTITUDE, AquacultureField.SPEED, viewModel::updateField)
        FieldPair(state, AquacultureField.SPACING, AquacultureField.INDENTATION, viewModel::updateField)
        Text("The flight path stays at least 3 m inside the pond boundary.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
