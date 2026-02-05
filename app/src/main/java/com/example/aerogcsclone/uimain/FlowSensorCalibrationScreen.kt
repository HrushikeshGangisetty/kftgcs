package com.example.aerogcsclone.uimain

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.aerogcsclone.telemetry.SharedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FlowCalibrationState {
    IDLE,
    CALIBRATING,
    COMPLETED,
    ERROR
}

@Composable
fun FlowSensorCalibrationScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel = viewModel()
) {
    val telemetryState by sharedViewModel.telemetryState.collectAsState()
    val sprayTelemetry = telemetryState.sprayTelemetry
    val scope = rememberCoroutineScope()

    // Calibration state
    var calibrationState by remember { mutableStateOf(FlowCalibrationState.IDLE) }
    var selectedVolumeLiters by remember { mutableStateOf(2.0f) }
    var startFlowReading by remember { mutableStateOf(0f) }
    var elapsedTimeSeconds by remember { mutableStateOf(0) }
    var calculatedCalibrationFactor by remember { mutableStateOf<Float?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Timer for elapsed time
    LaunchedEffect(calibrationState) {
        if (calibrationState == FlowCalibrationState.CALIBRATING) {
            elapsedTimeSeconds = 0
            while (calibrationState == FlowCalibrationState.CALIBRATING) {
                delay(1000)
                elapsedTimeSeconds++
            }
        }
    }

    // Current flow readings
    val currentFlowRate = sprayTelemetry.flowRateLiterPerMin ?: 0f
    val currentConsumed = sprayTelemetry.consumedLiters ?: 0f

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
                    text = "Flow Sensor Calibration",
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
                        text = "Fill the spray tank with the exact amount of liquid you will measure"
                    )
                    InstructionStep(
                        number = "2",
                        text = "Select the volume amount from the dropdown (1L, 2L, 4L, or 6L)"
                    )
                    InstructionStep(
                        number = "3",
                        text = "Click 'Start Calibration' to begin dispensing the liquid"
                    )
                    InstructionStep(
                        number = "4",
                        text = "Wait for the selected amount to be completely dispensed"
                    )
                    InstructionStep(
                        number = "5",
                        text = "Click 'Stop Calibration' when the tank is empty"
                    )
                    InstructionStep(
                        number = "6",
                        text = "The calibration factor will be calculated and saved automatically"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "⚠️ Important: Make sure the spray system is properly configured (BATT2_MONITOR = 11) before calibration.",
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
                        label = "Flow Rate",
                        value = String.format("%.2f L/min", currentFlowRate),
                        valueColor = if (calibrationState == FlowCalibrationState.CALIBRATING) Color(0xFF4CAF50) else Color.White
                    )

                    ReadingRow(
                        label = "Total Consumed",
                        value = String.format("%.2f L", currentConsumed),
                        valueColor = Color.White
                    )

                    if (calibrationState == FlowCalibrationState.CALIBRATING) {
                        ReadingRow(
                            label = "Elapsed Time",
                            value = "${elapsedTimeSeconds}s",
                            valueColor = Color(0xFF87CEEB)
                        )

                        val consumedDuringCal = currentConsumed - startFlowReading
                        ReadingRow(
                            label = "Dispensed in This Calibration",
                            value = String.format("%.2f L", consumedDuringCal),
                            valueColor = Color(0xFFFFEB3B)
                        )
                    }

                    HorizontalDivider(
                        color = Color(0xFF4A5568),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Text(
                        text = "System Configuration",
                        color = Color(0xFFB0BEC5),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ReadingRow(
                        label = "Spray System",
                        value = if (sprayTelemetry.sprayEnabled) "ENABLED" else "DISABLED",
                        valueColor = if (sprayTelemetry.sprayEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )

                    sprayTelemetry.batt2AmpPerVolt?.let {
                        ReadingRow(
                            label = "Current Calibration Factor",
                            value = String.format("%.4f", it),
                            valueColor = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Volume Selection Card
            if (calibrationState == FlowCalibrationState.IDLE || calibrationState == FlowCalibrationState.COMPLETED) {
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
                            text = "Select Liquid Volume",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Text(
                            text = "Choose the exact amount of liquid you will dispense:",
                            color = Color(0xFFB0BEC5),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Volume options in a grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VolumeOptionButton(
                                volume = 1.0f,
                                selected = selectedVolumeLiters == 1.0f,
                                onClick = { selectedVolumeLiters = 1.0f },
                                modifier = Modifier.weight(1f)
                            )
                            VolumeOptionButton(
                                volume = 2.0f,
                                selected = selectedVolumeLiters == 2.0f,
                                onClick = { selectedVolumeLiters = 2.0f },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VolumeOptionButton(
                                volume = 4.0f,
                                selected = selectedVolumeLiters == 4.0f,
                                onClick = { selectedVolumeLiters = 4.0f },
                                modifier = Modifier.weight(1f)
                            )
                            VolumeOptionButton(
                                volume = 6.0f,
                                selected = selectedVolumeLiters == 6.0f,
                                onClick = { selectedVolumeLiters = 6.0f },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Calibration Status Card
            if (calibrationState == FlowCalibrationState.CALIBRATING) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1565C0)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Calibration in Progress",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Dispensing ${selectedVolumeLiters}L of liquid...",
                                color = Color(0xFFE3F2FD),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Completed Status Card
            if (calibrationState == FlowCalibrationState.COMPLETED && calculatedCalibrationFactor != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50)
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
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Success",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Calibration Completed!",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "New calibration factor calculated",
                                    color = Color(0xFFE8F5E9),
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.3f),
                            thickness = 1.dp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Calculated Calibration Factor:",
                            color = Color(0xFFE8F5E9),
                            fontSize = 14.sp
                        )
                        Text(
                            text = String.format("%.6f", calculatedCalibrationFactor),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "This value has been saved as the new BATT2_AMP_PERVLT parameter.",
                            color = Color(0xFFE8F5E9),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Error Status Card
            if (calibrationState == FlowCalibrationState.ERROR && errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD32F2F)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = "Error",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Calibration Failed",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = errorMessage!!,
                                color = Color(0xFFFFCDD2),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Control Buttons
            if (calibrationState == FlowCalibrationState.IDLE || calibrationState == FlowCalibrationState.COMPLETED) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                // Record starting flow reading
                                startFlowReading = currentConsumed

                                // Enable spray system (set RC7 to high PWM value ~2000)
                                sharedViewModel.controlSpray(true)

                                calibrationState = FlowCalibrationState.CALIBRATING
                                errorMessage = null
                                calculatedCalibrationFactor = null
                            } catch (e: Exception) {
                                errorMessage = "Failed to start calibration: ${e.message}"
                                calibrationState = FlowCalibrationState.ERROR
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
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Start",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Calibration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (calibrationState == FlowCalibrationState.CALIBRATING) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                // Disable spray system (set RC7 to low PWM value ~1000)
                                sharedViewModel.controlSpray(false)

                                // Calculate the calibration factor
                                val endFlowReading = currentConsumed
                                val actualDispensed = endFlowReading - startFlowReading

                                if (actualDispensed <= 0) {
                                    errorMessage = "No liquid was dispensed. Please check the spray system."
                                    calibrationState = FlowCalibrationState.ERROR
                                    return@launch
                                }

                                // Calculate calibration factor
                                // New factor = (Expected volume / Actual volume) × Current factor
                                val currentFactor = sprayTelemetry.batt2AmpPerVolt ?: 1.0f
                                val newFactor = (selectedVolumeLiters / actualDispensed) * currentFactor

                                calculatedCalibrationFactor = newFactor

                                // Save the calibration factor
                                sharedViewModel.updateFlowSensorCalibration(newFactor)

                                calibrationState = FlowCalibrationState.COMPLETED
                            } catch (e: Exception) {
                                errorMessage = "Failed to stop calibration: ${e.message}"
                                calibrationState = FlowCalibrationState.ERROR
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Calibration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Help Text
            Text(
                text = "💡 Tip: For accurate calibration, use a measuring container to verify the exact volume dispensed matches your selected amount.",
                color = Color(0xFF87CEEB),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun VolumeOptionButton(
    volume: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1976D2) else Color(0xFF4A5568)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = "${volume.toInt()}L",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
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
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

