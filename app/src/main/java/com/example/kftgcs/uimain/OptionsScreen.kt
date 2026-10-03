package com.example.kftgcs.uimain

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.kftgcs.telemetry.BatteryFsAction
import com.example.kftgcs.telemetry.LimitFailsafePolicy
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.viewmodel.OptionsViewModel
import java.util.Locale

private val DarkBackground = Color(0xFF23272A)
private val AccentBlue = Color(0xFF87CEEB)
private val BorderGray = Color(0xFF4A5568)
private val SectionBackground = Color(0xFF2C2F33)

private val actionOptions = listOf("HOVER" to "Hover", "RTL" to "RTL", "LAND" to "Land")

// The battery failsafe actions are the drone's own BATT_FS_LOW_ACT / BATT_FS_CRT_ACT, so they offer
// the Smart RTL variants too. "Hover" is None on the drone. Terminate (5) is deliberately not
// selectable, but is displayed if the drone already holds it (see ActionDropdown's fallback label).
private val batteryActionOptions = listOf(
    "HOVER" to "Hover (drone: None)",
    "RTL" to "RTL",
    "LAND" to "Land",
    "SMART_RTL" to "Smart RTL or RTL",
    "SMART_RTL_LAND" to "Smart RTL or Land"
)

// Tank Empty has an extra "Report Only" action: the drone keeps flying and only
// reports that the tank is empty, instead of switching to a safe/stop mode.
private val tankEmptyActionOptions = actionOptions + ("REPORT_ONLY" to "Report Only")

@Composable
fun OptionsScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel,
    viewModel: OptionsViewModel = viewModel()
) {
    val context = LocalContext.current
    val options by viewModel.options.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val isLoadingFromDrone by viewModel.isLoadingFromDrone.collectAsState()
    val loadStatus by viewModel.loadStatus.collectAsState()
    val fenceAction by sharedViewModel.fenceAction.collectAsState()
    val fenceRadius by sharedViewModel.fenceRadiusMeters.collectAsState()
    val fenceMargin by sharedViewModel.fenceMargin.collectAsState()
    val fenceType by sharedViewModel.fenceTypeBits.collectAsState()
    val fenceEnable by sharedViewModel.fenceEnable.collectAsState()
    val fcAltitudeMax by sharedViewModel.fcAltitudeMax.collectAsState()

   // On first open, read voltage parameters from the flight controller
    LaunchedEffect(Unit) {
        viewModel.loadFromDrone(sharedViewModel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Failsafe Settings",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    // Refresh button — re-read voltage params from FC
                    IconButton(
                        onClick = { viewModel.loadFromDrone(sharedViewModel) },
                        enabled = !isLoadingFromDrone,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh from Drone",
                            tint = if (isLoadingFromDrone) Color.Gray else AccentBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(
                        onClick = { navController.navigate("main") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Go to Home",
                            tint = AccentBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = AccentBlue,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            // Loading indicator when reading from drone
            if (isLoadingFromDrone) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = AccentBlue,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Reading parameters from drone...",
                        color = Color(0xFFB0B0B0),
                        fontSize = 14.sp
                    )
                }
            }

            // Load status feedback
            loadStatus?.let { status ->
                Text(
                    text = status,
                    color = if (status.contains("✓")) Color(0xFF90EE90) else Color(0xFFFFD700),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Section: Mission Completion
            SectionCard(title = "Mission Completion") {
                ActionRadioGroup(
                    selected = options.missionCompletionAction,
                    onSelectionChanged = { viewModel.updateMissionCompletionAction(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Tank Empty — separate action for Manual flight vs Auto missions
            SectionCard(title = "Tank Empty") {
                SubLabel("Manual Mode")
                ActionRadioGroup(
                    selected = options.tankEmptyActionManual,
                    onSelectionChanged = { viewModel.updateTankEmptyActionManual(it) },
                    options = tankEmptyActionOptions
                )

                Spacer(modifier = Modifier.height(16.dp))

                SubLabel("Auto Mode")
                ActionRadioGroup(
                    selected = options.tankEmptyActionAuto,
                    onSelectionChanged = { viewModel.updateTankEmptyActionAuto(it) },
                    options = tankEmptyActionOptions
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Low Voltage Alerts
            SectionCard(title = "Low Voltage Alerts") {
                // Level 1
                NumericTextField(
                    label = "Level 1 Threshold (V)",
                    value = options.lowVoltLevel1,
                    onValueChange = { viewModel.updateLowVoltLevel1(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Level 1 Action Dropdown — BATT_FS_LOW_ACT. The drone's own setting is the
                // source of truth: this shows what it holds and Save & Sync writes it back.
                ActionDropdown(
                    label = "Level 1 Action — BATT_FS_LOW_ACT",
                    selected = options.lowVoltLevel1Action,
                    onSelectionChanged = { viewModel.updateLowVoltLevel1Action(it) },
                    options = batteryActionOptions
                )

                Text(
                    text = "The tablet always alerts (popup + TTS every few seconds); the action above is what the drone itself does.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
                )

                // Level 2
                NumericTextField(
                    label = "Level 2 Threshold (V)",
                    value = options.lowVoltLevel2,
                    onValueChange = { viewModel.updateLowVoltLevel2(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Level 2 Action Dropdown — BATT_FS_CRT_ACT
                ActionDropdown(
                    label = "Level 2 Action — BATT_FS_CRT_ACT",
                    selected = options.lowVoltLevel2Action,
                    onSelectionChanged = { viewModel.updateLowVoltLevel2Action(it) },
                    options = batteryActionOptions
                )

                Text(
                    text = "Read from the drone when this screen opens (refresh to re-read) and written to it by Save & Sync. " +
                        "The tablet takes the same action, so the drone and tablet agree.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Altitude Limit — mirrors the FC's FENCE_ALT_MAX parameter
            SectionCard(title = "Maximum Altitude") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Enable GCS altitude monitoring",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Switch(
                        checked = options.maxAltitudeEnabled,
                        onCheckedChange = { viewModel.updateMaxAltitudeEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentBlue,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = BorderGray
                        )
                    )
                }

                NumericTextField(
                    label = "Max Altitude (m above home) — FENCE_ALT_MAX",
                    value = options.maxAltitude,
                    onValueChange = { viewModel.updateMaxAltitude(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "The GCS altitude action triggers at the first fresh position reading at or above " +
                        "FENCE_ALT_MAX. Approach warnings do not change this limit. " +
                        "Aircraft momentum and telemetry delay can carry it beyond the boundary.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )
                Text(
                    text = "Confirmed altitude: ${fcAltitudeMax?.let { "$it m above home" } ?: "Not confirmed"}\n" +
                        "Action at both limits — FENCE_ACTION: ${fenceAction?.pilotLabel ?: "Not confirmed"}",
                    color = Color.White,
                    fontSize = 14.sp
                )

            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionCard(title = "Max Range — Drone Parameters") {
                val rangeActionAt = LimitFailsafePolicy.rangeActionThreshold(fenceRadius, fenceMargin)
                val hasValidMargin = LimitFailsafePolicy.validRangeMargin(fenceRadius, fenceMargin)
                Text(
                    text = "FENCE_RADIUS: ${fenceRadius?.let { "$it m from home" } ?: "Not confirmed"}\n" +
                        "FENCE_MARGIN: ${fenceMargin?.let { "$it m" } ?: "Not confirmed"}\n" +
                        "GCS range action at: ${rangeActionAt?.let { "$it m from home" } ?: "Not confirmed"}" +
                        (if (rangeActionAt != null && !hasValidMargin) " (margin unavailable/invalid; using radius)" else "") + "\n" +
                        "FENCE_ACTION: ${fenceAction?.pilotLabel ?: "Not confirmed"}\n" +
                        "FENCE_ENABLE: ${fenceEnable?.toString() ?: "Not confirmed"}; " +
                        "FENCE_TYPE: ${fenceType?.toString() ?: "Not confirmed"}",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "The altitude switch controls GCS monitoring only; it does not change onboard fences. " +
                        "GCS range monitoring acts at FENCE_RADIUS minus FENCE_MARGIN while connected and armed, " +
                        "using fresh position telemetry and a confirmed action. " +
                        "A zero margin acts at the radius; an unread or invalid margin also falls back to the radius. " +
                        "Onboard fence enforcement also requires FENCE_ENABLE = 1 and the appropriate " +
                        "FENCE_TYPE bits (altitude: 1, circle: 2). These settings are read only here.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save Button
            Button(
                onClick = { viewModel.saveAndSync(sharedViewModel) },
                enabled = !isLoadingFromDrone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Save & Sync to Drone",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Sync status feedback
            syncStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    color = if (status.contains("Failed") || status.contains("Not saved")) Color(0xFFFF6B6B) else Color(0xFF90EE90),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SectionBackground, RoundedCornerShape(12.dp))
            .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = AccentBlue,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFFB0B0B0),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
    )
}

@Composable
private fun ActionRadioGroup(
    selected: String,
    onSelectionChanged: (String) -> Unit,
    options: List<Pair<String, String>> = actionOptions
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectionChanged(value) }
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelectionChanged(value) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentBlue,
                        unselectedColor = Color.Gray
                    )
                )
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/** Decimal entry used for both the voltage thresholds and the altitude ceiling. */
@Composable
private fun NumericTextField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    // Not keyed on `value`: every valid keystroke pushes a new value up to the ViewModel, and a
    // remember(value) key threw away what was being typed and re-seeded the field from the parsed
    // number — so "42" became "4" -> "4.0" -> "4.02". The text is only re-seeded when `value`
    // changes to something the current text does NOT already represent (e.g. a load from drone).
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toFloatOrNull() != value) text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toFloatOrNull()?.let { onValueChange(it) }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = BorderGray,
            focusedLabelColor = AccentBlue,
            unfocusedLabelColor = Color.Gray,
            cursorColor = AccentBlue
        ),
        shape = RoundedCornerShape(8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropdown(
    label: String,
    selected: String,
    onSelectionChanged: (String) -> Unit,
    options: List<Pair<String, String>> = actionOptions
) {
    var expanded by remember { mutableStateOf(false) }
    // A value the drone holds that is not in the menu (e.g. TERMINATE) still shows truthfully.
    val displayLabel = options.firstOrNull { it.first == selected }?.second
        ?: BatteryFsAction.label(selected)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = BorderGray,
                focusedLabelColor = AccentBlue,
                unfocusedLabelColor = Color.Gray
            ),
            shape = RoundedCornerShape(8.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SectionBackground)
        ) {
            options.forEach { (value, displayText) ->
                DropdownMenuItem(
                    text = { Text(displayText, color = Color.White) },
                    onClick = {
                        onSelectionChanged(value)
                        expanded = false
                    },
                    modifier = Modifier.background(
                        if (selected == value) Color(0xFF3A3F44) else Color.Transparent
                    )
                )
            }
        }
    }
}
