package com.example.kftgcs.uimain

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kftgcs.telemetry.SharedViewModel

@Composable
fun SprayCalibrationScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel = viewModel()
) {
    val telemetryState by sharedViewModel.telemetryState.collectAsState()
    val sprayTelemetry = telemetryState.sprayTelemetry

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
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
                    text = "Spray Calibration",
                    color = Color.White,
                    fontSize = 32.sp,
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

            // Current Configuration Status
            ConfigurationStatusCard(
                sprayEnabled = sprayTelemetry.sprayEnabled,
                rc7Value = sprayTelemetry.rc7Value,
                sprayRcChannel = sprayTelemetry.sprayRcChannel,
                configurationValid = sprayTelemetry.configurationValid,
                configurationError = sprayTelemetry.configurationError,
                tankLevel = sprayTelemetry.tankLevelPercent,
                tankVoltage = sprayTelemetry.tankVoltageMv,
                flowRate = sprayTelemetry.formattedFlowRate
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Calibration Options
            Text(
                text = "Calibration Tools",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val buttonHeight = 80.dp
            val buttonSpacing = 12.dp

            // 1. Flow Sensor Calibration (Bucket Test)
            CalibrationButton(
                number = 1,
                icon = Icons.Filled.WaterDrop,
                title = "Flow Sensor Calibration",
                subtitle = "Bucket test to calibrate BATT2_AMP_PERVLT",
                onClick = { navController.navigate("flow_sensor_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 2. Level Sensor Calibration
            CalibrationButton(
                number = 2,
                icon = Icons.Filled.Opacity,
                title = "Level Sensor Calibration",
                subtitle = "Set empty/full voltage ranges",
                onClick = { navController.navigate("level_sensor_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 3. Pump Calibration
            CalibrationButton(
                number = 3,
                icon = Icons.Filled.Build,
                title = "Pump Calibration",
                subtitle = "Test and configure pump PWM (Servo9)",
                onClick = { navController.navigate("pump_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 4. Spray System Test
            CalibrationButton(
                number = 4,
                icon = Icons.Filled.Science,
                title = "Spray System Test",
                subtitle = "Run full system diagnostics",
                onClick = { navController.navigate("spray_system_test") },
                height = buttonHeight
            )
        }
    }
}

@Composable
private fun ConfigurationStatusCard(
    sprayEnabled: Boolean,
    rc7Value: Int?,
    sprayRcChannel: Int,
    configurationValid: Boolean,
    configurationError: String?,
    tankLevel: Int?,
    tankVoltage: Int?,
    flowRate: String?
) {
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
                text = "Current Status",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Spray Status
            StatusRow(
                label = "Spray System",
                value = if (sprayEnabled) "ENABLED" else "DISABLED",
                valueColor = if (sprayEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )

            rc7Value?.let {
                StatusRow(
                    label = "RC$sprayRcChannel PWM",
                    value = "$it",
                    valueColor = Color.White
                )
            }

            HorizontalDivider(
                color = Color(0xFF4A5568),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Configuration Status
            StatusRow(
                label = "Configuration",
                value = if (configurationValid) "✓ VALID" else "✗ INVALID",
                valueColor = if (configurationValid) Color(0xFF4CAF50) else Color(0xFFF44336)
            )

            configurationError?.let {
                Text(
                    text = it,
                    color = Color(0xFFF44336),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(
                color = Color(0xFF4A5568),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Tank Level
            tankLevel?.let {
                StatusRow(
                    label = "Tank Level",
                    value = "$it%",
                    valueColor = when {
                        it > 50 -> Color(0xFF4CAF50)
                        it > 20 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }
                )
            }

            tankVoltage?.let {
                StatusRow(
                    label = "Tank Voltage",
                    value = "${it / 1000f} V",
                    valueColor = Color.White
                )
            }

            // Flow Rate
            flowRate?.let {
                StatusRow(
                    label = "Flow Rate",
                    value = it,
                    valueColor = Color.White
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CalibrationButton(
    number: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0xFF4A5568)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Number badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF87CEEB), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    color = Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Icon
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color(0xFF87CEEB),
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Title and subtitle
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFFB0BEC5)
                )
            }
        }
    }
}

