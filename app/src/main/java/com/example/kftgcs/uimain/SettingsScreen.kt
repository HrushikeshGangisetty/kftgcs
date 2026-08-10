package com.example.kftgcs.uimain

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.kftgcs.BuildConfig
import com.example.kftgcs.telemetry.ConnectionType
import com.example.kftgcs.telemetry.SharedViewModel

// The SVD white-label build ships for DGCA inspection: the pilot must not be able to
// re-tune the failsafe/battery configuration, so "Options" and "Battery" are hidden and
// those values come from the flight controller's own parameters instead.
private val isSvdFlavor = BuildConfig.FLAVOR == "svd"

private class SettingsEntry(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit
)

@Composable
fun SettingsScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel
) {
    val context = LocalContext.current
    val telemetry by sharedViewModel.telemetryState.collectAsState()
    // "Analyze Log" pulls DataFlash logs over USB serial, so it only makes sense — and is only
    // shown — when a USB link is actually live.
    val usbConnected = telemetry.connected &&
        sharedViewModel.connectionType.value == ConnectionType.USB
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A)) // dark grey background
            .padding(24.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Header row with title and home icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                // Home icon button
                IconButton(
                    onClick = { navController.navigate("main") },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Go to Home",
                        tint = Color(0xFF87CEEB), // light blue
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Light blue horizontal line separating the title from the rest of the content
            HorizontalDivider(
                color = Color(0xFF87CEEB), // light blue
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            // Single column layout with numbered buttons
            val buttonHeight = 70.dp
            val buttonSpacing = 12.dp

            // Built as a list so the entries hidden on some builds (Options/Battery on
            // SVD, Analyze Log without USB) don't leave gaps in the numbering.
            val entries = buildList {
                add(SettingsEntry(Icons.Filled.Speed, "IMU Calibrations") {
                    navController.navigate("accelerometer_calibration")
                })
                add(SettingsEntry(Icons.Filled.Explore, "Compass Calibration") {
                    navController.navigate("compass_calibration")
                })
                add(SettingsEntry(Icons.Filled.Thermostat, "Barometer Calibration") {
                    navController.navigate("barometer_calibration")
                })
                add(SettingsEntry(Icons.Filled.Opacity, "Spraying System") {
                    navController.navigate("spraying_system")
                })
                add(SettingsEntry(Icons.Filled.Gamepad, "Remote Controller") {
                    navController.navigate("remote_controller")
                })
                add(SettingsEntry(Icons.Filled.Policy, "Privacy Policy") {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://sreenijagangadari.github.io/pavamanGCS-privacy-policy/".toUri()
                    )
                    context.startActivity(intent)
                })

                // Options (failsafe settings) and Battery (BATT_MONITOR setup) are
                // omitted on SVD — see [isSvdFlavor].
                if (!isSvdFlavor) {
                    add(SettingsEntry(Icons.Filled.Settings, "Options") {
                        navController.navigate("options")
                    })
                    add(SettingsEntry(Icons.Filled.BatteryChargingFull, "Battery") {
                        navController.navigate("battery_monitor_settings")
                    })
                }

                add(SettingsEntry(Icons.Filled.Person, "User Settings") {
                    navController.navigate("user_settings")
                })
                // Sensor Settings (proximity-radar thresholds)
                add(SettingsEntry(Icons.Filled.Radar, "Sensor Settings") {
                    navController.navigate("sensor_settings")
                })

                // Analyze Log — visible only on an active USB connection
                if (usbConnected) {
                    add(SettingsEntry(Icons.Filled.Analytics, "Analyze Log") {
                        navController.navigate("analyze_log")
                    })
                }
            }

            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(buttonSpacing))
                }
                NumberedButton(
                    number = index + 1,
                    icon = entry.icon,
                    title = entry.title,
                    onClick = entry.onClick,
                    height = buttonHeight
                )
            }

        }
    }
}

@Composable
private fun NumberedButton(
    number: Int,
    icon: ImageVector,
    title: String,
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
        border = BorderStroke(1.dp, Color(0xFF4A5568)), // darker gray border
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
                    .size(32.dp)
                    .background(Color(0xFF87CEEB), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Icon
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
