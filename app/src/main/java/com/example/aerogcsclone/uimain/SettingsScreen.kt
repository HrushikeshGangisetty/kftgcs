package com.example.aerogcsclone.uimain

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
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun SettingsScreen(navController: NavHostController) {
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

            // 1. IMU Calibrations
            NumberedButton(
                number = 1,
                icon = Icons.Filled.Speed,
                title = "IMU Calibrations",
                onClick = { navController.navigate("accelerometer_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 2. Compass Calibration
            NumberedButton(
                number = 2,
                icon = Icons.Filled.Explore,
                title = "Compass Calibration",
                onClick = { navController.navigate("compass_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 3. Barometer Calibration
            NumberedButton(
                number = 3,
                icon = Icons.Filled.Thermostat,
                title = "Barometer Calibration",
                onClick = { navController.navigate("barometer_calibration") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 4. Spraying System
            NumberedButton(
                number = 4,
                icon = Icons.Filled.Opacity,
                title = "Spraying System",
                onClick = { navController.navigate("spraying_system") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 5. Remote Controller
            NumberedButton(
                number = 5,
                icon = Icons.Filled.Gamepad,
                title = "Remote Controller",
                onClick = { navController.navigate("remote_controller") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 6. Aircraft
            NumberedButton(
                number = 6,
                icon = Icons.Filled.Flight,
                title = "Aircraft",
                onClick = { navController.navigate("aircraft") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 7. RangeFinder Settings
            NumberedButton(
                number = 7,
                icon = Icons.Filled.GpsFixed,
                title = "RangeFinder Settings",
                onClick = { navController.navigate("rangefinder_settings") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 8. About App
            NumberedButton(
                number = 8,
                icon = Icons.Filled.Info,
                title = "About App",
                onClick = { navController.navigate("about_app") },
                height = buttonHeight
            )

            Spacer(modifier = Modifier.height(buttonSpacing))

            // 9. Security
            NumberedButton(
                number = 9,
                icon = Icons.Filled.Security,
                title = "Security",
                onClick = { navController.navigate("security") },
                height = buttonHeight
            )
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
