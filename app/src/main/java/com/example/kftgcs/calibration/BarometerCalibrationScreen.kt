package com.example.kftgcs.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.utils.AppStrings

@Composable
fun BarometerCalibrationScreen(
    viewModel: BarometerCalibrationViewModel,
    navController: NavController
) {
    val uiState by viewModel.uiState.collectAsState()

    // Completion popup, matching the pattern the other calibration screens use.
    // The TTS announcement is fired from the ViewModel alongside this flag.
    if (uiState.showCompletionDialog) {
        val success = uiState.completionSuccess
        AlertDialog(
            onDismissRequest = { viewModel.dismissCompletionDialog() },
            icon = {
                Icon(
                    imageVector = if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = if (success) AppStrings.barometerCalibrationCompleteTitle
                    else AppStrings.barometerCalibrationFailedTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (success) AppStrings.barometerCalibrationCompleteMessage
                    else AppStrings.barometerCalibrationFailedMessage,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissCompletionDialog() }) {
                    Text(AppStrings.ok)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A)) // Matching dark theme
    ) {
        // Top navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2C2F33))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { navController.navigate(Screen.Settings.route) }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = "Barometer Calibration",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = { navController.navigate("main") }
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Go to Home",
                    tint = Color(0xFF87CEEB),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 72.dp, start = 24.dp, end = 24.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large icon at the top
            Card(
                modifier = Modifier
                    .size(120.dp)
                    .shadow(8.dp, RoundedCornerShape(60.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF87CEEB)),
                shape = RoundedCornerShape(60.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Thermostat,
                        contentDescription = "Barometer",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Instructions card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2F33)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Calibration Instructions",
                        color = Color(0xFF87CEEB),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        text = "• Place the drone on a flat, stable surface\n• Ensure there is no wind or movement\n• Keep the drone stationary during calibration",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        lineHeight = 24.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusIndicatorCard(
                    icon = Icons.Filled.Landscape,
                    label = "Flat Surface",
                    isGood = uiState.isFlatSurface
                )

                StatusIndicatorCard(
                    icon = Icons.Filled.Air,
                    label = "Wind",
                    isGood = uiState.isWindGood
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Warning messages
            if (!uiState.isFlatSurface || !uiState.isWindGood) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B6B).copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            if (!uiState.isFlatSurface) {
                                Text(
                                    text = "Place the drone on a flat surface",
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (!uiState.isWindGood) {
                                Text(
                                    text = "Wind conditions are not suitable",
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Progress section
            if (uiState.isCalibrating) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2F33)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Calibrating...",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LinearProgressIndicator(
                            progress = { uiState.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp),
                            color = Color(0xFF87CEEB),
                            trackColor = Color(0xFF4A5568),
                        )

                        Text(
                            text = "${uiState.progress}%",
                            color = Color(0xFF87CEEB),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.stopCalibration() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B6B)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Stop Calibration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                val isEnabled = uiState.isConnected && uiState.isFlatSurface && uiState.isWindGood
                Button(
                    onClick = { viewModel.startCalibration() },
                    enabled = isEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .shadow(if (isEnabled) 4.dp else 0.dp, RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF87CEEB),
                        disabledContainerColor = Color(0xFF4A5568)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Start Calibration",
                        color = if (isEnabled) Color.Black else Color.Gray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Status text
            if (uiState.statusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2F33)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = uiState.statusText,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Connection status
            if (!uiState.isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "⚠ Drone not connected",
                    color = Color(0xFFFFAA00),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun StatusIndicatorCard(
    icon: ImageVector,
    label: String,
    isGood: Boolean
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isGood) Color(0xFF2C2F33) else Color(0xFF3D2424)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isGood) Color(0xFF87CEEB) else Color(0xFFFF6B6B),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Icon(
                imageVector = if (isGood) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = if (isGood) "Good" else "Bad",
                tint = if (isGood) Color(0xFF4CAF50) else Color(0xFFFF6B6B),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
