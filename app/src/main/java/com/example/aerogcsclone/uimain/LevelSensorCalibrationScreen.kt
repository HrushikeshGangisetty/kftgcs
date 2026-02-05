package com.example.aerogcsclone.uimain

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.aerogcsclone.telemetry.SharedViewModel
import kotlinx.coroutines.launch

@Composable
fun LevelSensorCalibrationScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel = viewModel()
) {
    val telemetryState by sharedViewModel.telemetryState.collectAsState()
    val sprayTelemetry = telemetryState.sprayTelemetry
    val scope = rememberCoroutineScope()

    // Current readings
    val currentVoltageMv = sprayTelemetry.tankVoltageMv ?: 0
    val currentLevelPercent = sprayTelemetry.tankLevelPercent

    // Editable calibration values (initialized from current state)
    var emptyVoltageMv by remember { mutableStateOf(sprayTelemetry.levelSensorEmptyMv.toString()) }
    var fullVoltageMv by remember { mutableStateOf(sprayTelemetry.levelSensorFullMv.toString()) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Level Sensor Calibration",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            HorizontalDivider(
                color = Color(0xFF87CEEB),
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            // Instructions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2C3E50)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Info",
                            tint = Color(0xFF87CEEB),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Calibration Instructions",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "1",
                        text = "Empty the tank completely and note the voltage reading below"
                    )
                    InstructionStep(
                        number = "2",
                        text = "Enter the empty tank voltage in the 'Empty Voltage' field"
                    )
                    InstructionStep(
                        number = "3",
                        text = "Fill the tank completely and note the voltage reading"
                    )
                    InstructionStep(
                        number = "4",
                        text = "Enter the full tank voltage in the 'Full Voltage' field"
                    )
                    InstructionStep(
                        number = "5",
                        text = "Tap 'Save Calibration' to apply the new settings"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "⚠️ Note: Your sensor has BATT3_MONITOR = 25 (analog voltage), so the app calculates tank level from voltage using linear interpolation.",
                        color = Color(0xFFFF9800),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Current Readings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF34495E)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Current Readings",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    ReadingRow(
                        label = "Tank Voltage",
                        value = "${currentVoltageMv / 1000f} V",
                        sublabel = "($currentVoltageMv mV)"
                    )

                    currentLevelPercent?.let {
                        ReadingRow(
                            label = "Calculated Level",
                            value = "$it%",
                            sublabel = "(based on current calibration)"
                        )
                    }

                    HorizontalDivider(
                        color = Color(0xFF4A5568),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Text(
                        text = "Current Calibration",
                        color = Color(0xFFB0BEC5),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ReadingRow(
                        label = "Empty Voltage",
                        value = "${sprayTelemetry.levelSensorEmptyMv / 1000f} V",
                        sublabel = "(${sprayTelemetry.levelSensorEmptyMv} mV)"
                    )

                    ReadingRow(
                        label = "Full Voltage",
                        value = "${sprayTelemetry.levelSensorFullMv / 1000f} V",
                        sublabel = "(${sprayTelemetry.levelSensorFullMv} mV)"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Calibration Input Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2C3E50)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Set Calibration Values",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Empty Voltage Input
                    Text(
                        text = "Empty Tank Voltage (mV)",
                        color = Color(0xFFB0BEC5),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    OutlinedTextField(
                        value = emptyVoltageMv,
                        onValueChange = { emptyVoltageMv = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF87CEEB),
                            unfocusedBorderColor = Color(0xFF4A5568),
                            cursorColor = Color(0xFF87CEEB)
                        ),
                        placeholder = {
                            Text("e.g., 10000", color = Color(0xFF6B7280))
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Full Voltage Input
                    Text(
                        text = "Full Tank Voltage (mV)",
                        color = Color(0xFFB0BEC5),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    OutlinedTextField(
                        value = fullVoltageMv,
                        onValueChange = { fullVoltageMv = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF87CEEB),
                            unfocusedBorderColor = Color(0xFF4A5568),
                            cursorColor = Color(0xFF87CEEB)
                        ),
                        placeholder = {
                            Text("e.g., 45000", color = Color(0xFF6B7280))
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Quick Set Buttons
                    Text(
                        text = "Quick Set Current Voltage",
                        color = Color(0xFFB0BEC5),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { emptyVoltageMv = currentVoltageMv.toString() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A5568)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = "Set Empty",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set as Empty")
                        }

                        Button(
                            onClick = { fullVoltageMv = currentVoltageMv.toString() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A5568)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Set Full",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set as Full")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val emptyMv = emptyVoltageMv.toIntOrNull()
                            val fullMv = fullVoltageMv.toIntOrNull()

                            if (emptyMv == null || fullMv == null) {
                                return@launch
                            }

                            if (emptyMv >= fullMv) {
                                return@launch
                            }

                            // Update the calibration values in the state
                            sharedViewModel.updateLevelSensorCalibration(emptyMv, fullMv)

                            showSaveSuccess = true

                            // Hide success message after 3 seconds
                            kotlinx.coroutines.delay(3000)
                            showSaveSuccess = false
                        } catch (e: Exception) {
                            // Error saving calibration
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Save",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save Calibration",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showSaveSuccess) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Success",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Calibration saved successfully!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Help Text
            Text(
                text = "💡 Tip: For best results, perform calibration with the tank at room temperature. The voltage reading should stabilize after a few seconds.",
                color = Color(0xFF87CEEB),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF87CEEB), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReadingRow(
    label: String,
    value: String,
    sublabel: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = label,
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp
            )
            sublabel?.let {
                Text(
                    text = it,
                    color = Color(0xFF6B7280),
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = value,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

