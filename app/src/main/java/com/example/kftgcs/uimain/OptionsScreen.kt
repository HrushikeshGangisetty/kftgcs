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
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.viewmodel.OptionsViewModel

private val DarkBackground = Color(0xFF23272A)
private val AccentBlue = Color(0xFF87CEEB)
private val BorderGray = Color(0xFF4A5568)
private val SectionBackground = Color(0xFF2C2F33)

private val actionOptions = listOf("HOVER" to "Hover", "RTL" to "RTL", "LAND" to "Land")

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

            // Section: Tank Empty
            SectionCard(title = "Tank Empty") {
                ActionRadioGroup(
                    selected = options.tankEmptyAction,
                    onSelectionChanged = { viewModel.updateTankEmptyAction(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Low Voltage Alerts
            SectionCard(title = "Low Voltage Alerts") {
                // Level 1
                VoltageTextField(
                    label = "Level 1 Threshold (V)",
                    value = options.lowVoltLevel1,
                    onValueChange = { viewModel.updateLowVoltLevel1(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Action: Alert only (popup + TTS every 5 sec)",
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )

                // Level 2
                VoltageTextField(
                    label = "Level 2 Threshold (V)",
                    value = options.lowVoltLevel2,
                    onValueChange = { viewModel.updateLowVoltLevel2(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Level 2 Action Dropdown
                ActionDropdown(
                    label = "Level 2 Action",
                    selected = options.lowVoltLevel2Action,
                    onSelectionChanged = { viewModel.updateLowVoltLevel2Action(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save Button
            Button(
                onClick = { viewModel.saveAndSync(sharedViewModel) },
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
                    color = if (status.contains("Failed")) Color(0xFFFF6B6B) else Color(0xFF90EE90),
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
private fun ActionRadioGroup(
    selected: String,
    onSelectionChanged: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        actionOptions.forEach { (value, label) ->
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

@Composable
private fun VoltageTextField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

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
    onSelectionChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = actionOptions.firstOrNull { it.first == selected }?.second ?: selected

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
            actionOptions.forEach { (value, displayText) ->
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
