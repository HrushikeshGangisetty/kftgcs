package com.example.aerogcsclone.uimain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.NavHostController
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.authentication.AuthViewModel
import com.example.aerogcsclone.navigation.Screen
import com.example.aerogcsclone.telemetry.SharedViewModel
import android.util.Log
import com.example.aerogcsclone.utils.AppStrings

@Composable
fun TopNavBar(
    telemetryState: TelemetryState,
    authViewModel: AuthViewModel,
    navController: NavHostController,
    onToggleNotificationPanel: () -> Unit,
    telemetryViewModel: SharedViewModel, // Added SharedViewModel parameter
    modifier: Modifier = Modifier // added modifier parameter with default
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var kebabMenuExpanded by remember { mutableStateOf(false) }
    var showGeofenceSlider by remember { mutableStateOf(false) } // Added geofence slider state
    var showSpraySlider by remember { mutableStateOf(false) } // Added spray slider state

    val coroutineScope = rememberCoroutineScope()

    // Collect geofence state from viewmodel
    val geofenceEnabled by telemetryViewModel.geofenceEnabled.collectAsState()
    val fenceRadius by telemetryViewModel.fenceRadius.collectAsState()

    // Collect spray state from viewmodel
    val sprayEnabled by telemetryViewModel.sprayEnabled.collectAsState()
    val sprayRate by telemetryViewModel.sprayRate.collectAsState()

    // Remember the mode to prevent flickering due to recomposition
    val displayMode by remember(telemetryState.mode) {
        derivedStateOf {
            val mode = telemetryState.mode ?: "N/A"
            Log.d("TopNavBar", "Display mode updated: $mode (from telemetryState.mode: ${telemetryState.mode})")
            mode
        }
    }

    // Log RC battery percentage updates for verification
    LaunchedEffect(telemetryState.rcBatteryPercent) {
        Log.i("RCBattery", "🎮 TopNavBar: RC Battery display updated to ${telemetryState.rcBatteryPercent ?: "N/A"}%")
    }

    // Set nav bar color based on connection status - solid colors
    // Dark green when connected, Red when disconnected
    val navBarColor = if (telemetryState.connected) {
        Color(0xFF2E7D32) // Dark green color for connected state (Material Green 800)
    } else {
        Color(0xFFE53935) // Red color for disconnected state
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Removed .statusBarsPadding() to allow navbar to be drawn under status bar
            .height(IntrinsicSize.Min)
            .background(color = navBarColor)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hamburger menu
            Box {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = AppStrings.menu,
                    tint = Color.White,
                    modifier = Modifier.clickable { menuExpanded = true }
                )
                if (menuExpanded) {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .width(140.dp)
                            .background(Color(0xFF23232B).copy(alpha = 0.85f))
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppStrings.planMission, color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Map,
                                    contentDescription = AppStrings.planMission,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                navController.navigate(Screen.Plan.route)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.plotTemplates, color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FileCopy,
                                    contentDescription = AppStrings.plotTemplates,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                navController.navigate(Screen.PlotTemplates.route)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.reconnect, color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = AppStrings.reconnect,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (telemetryState.connected) {
                                    telemetryViewModel.connect()
                                } else {
                                    navController.navigate(Screen.Connection.route)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Home icon
            Icon(
                Icons.Default.Home,
                contentDescription = AppStrings.home,
                tint = Color.White,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.SelectMethod.route)
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Title only - flight mode is shown in telemetry section on the right
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = AppStrings.pavamanAviation,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 25.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Status & telemetry
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusWidget(isConnected = telemetryState.connected)
                DividerBlock()
                // Spray icon
                Icon(
                    Icons.Default.Shower,
                    contentDescription = AppStrings.spray,
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { showSpraySlider = !showSpraySlider } // Make spray icon clickable
                )
                DividerBlock()
                // Clickable geofence icon
                Column(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { showGeofenceSlider = !showGeofenceSlider },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Fence,
                        contentDescription = AppStrings.geofence,
                        tint = if (geofenceEnabled) Color.Green else Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        if (geofenceEnabled) AppStrings.on else AppStrings.off,
                        color = if (geofenceEnabled) Color.Green else Color.White,
                        fontSize = 9.sp
                    )
                }
                DividerBlock()
                InfoBlock(Icons.Default.BatteryFull, "${telemetryState.batteryPercent ?: "N/A"}%")
                DividerBlock()
                InfoBlock(Icons.Default.Gamepad, telemetryState.rcBatteryPercent?.let { "$it%" } ?: "--%")
                DividerBlock()
                InfoBlockGroup(
                    Icons.Default.Bolt,
                    listOf(
                        "${telemetryState.voltage ?: "N/A"} V",
                        "${telemetryState.currentA ?: "N/A"} A"
                    )
                )
                DividerBlock()
                InfoBlockGroup(
                    Icons.Default.SatelliteAlt,
                    listOf(
                        "${telemetryState.sats ?: "N/A"} sats",
                        "${telemetryState.hdop ?: "N/A"} hdop"
                    )
                )
                DividerBlock()
                InfoBlockGroup(
                    Icons.Default.Sync,
                    listOf(displayMode, if (telemetryState.armed) AppStrings.armed else AppStrings.disarmed)
                )
                DividerBlock()

                // Notification Icon
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = AppStrings.notifications,
                    tint = Color.White,
                    modifier = Modifier.clickable { onToggleNotificationPanel() }
                )
                Spacer(modifier = Modifier.width(16.dp))

                // Kebab menu
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = AppStrings.more,
                        tint = Color.White,
                        modifier = Modifier.clickable { kebabMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = kebabMenuExpanded,
                        onDismissRequest = { kebabMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppStrings.logs) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.List,
                                    contentDescription = AppStrings.logs,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                kebabMenuExpanded = false
                                navController.navigate(Screen.Logs.route)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.settings) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = AppStrings.settings,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                kebabMenuExpanded = false
                                navController.navigate(Screen.Settings.route)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.disconnect) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = AppStrings.disconnect,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                kebabMenuExpanded = false
                                // Disconnect from flight controller
                                navController.navigate(Screen.Connection.route)
                                // Launch coroutine to disconnect
                                coroutineScope.launch {
                                    telemetryViewModel.cancelConnection()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.language) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = AppStrings.language,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                kebabMenuExpanded = false
                                navController.navigate(Screen.LanguageSelection.route)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.logout) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = AppStrings.logout,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                kebabMenuExpanded = false
                                authViewModel.signout()
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(0)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Geofence slider popup
        if (showGeofenceSlider) {
            Popup(
                onDismissRequest = { showGeofenceSlider = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF23232B).copy(alpha = 0.9f),
                    modifier = Modifier
                        .padding(16.dp)
                        .width(300.dp) // Fixed width for better layout
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            AppStrings.geofenceSettings,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f))

                        // Geofence Enable/Disable Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                AppStrings.enableGeofence,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = geofenceEnabled,
                                onCheckedChange = { telemetryViewModel.setGeofenceEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color.Green, // Green when ON
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color.Red // Red when OFF
                                )
                            )
                        }

                        // Status text based on geofence state
                        if (geofenceEnabled) {
                            Text(
                                AppStrings.polygonFenceActive,
                                color = Color.Green,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            Text(
                                AppStrings.geofenceDisabled,
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Buffer Distance Slider (only shown when geofence is enabled)
                        if (geofenceEnabled) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(AppStrings.bufferDistance, color = Color.White, modifier = Modifier.weight(1f))
                                    Text("${fenceRadius.toInt()} m", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = fenceRadius,
                                    onValueChange = { telemetryViewModel.setFenceRadius(it) },
                                    valueRange = -4f..50f, // Same range as PlanScreen
                                    steps = 50, // Same steps as PlanScreen
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Green,
                                        activeTrackColor = Color.Green,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                Text(
                                    AppStrings.adjustPolygonBuffer,
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Spray rate slider popup
        if (showSpraySlider) {
            Popup(
                onDismissRequest = { showSpraySlider = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF23232B).copy(alpha = 0.9f),
                    modifier = Modifier
                        .padding(16.dp)
                        .width(300.dp) // Fixed width for better layout
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            AppStrings.spraySettings,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        Divider(color = Color.White.copy(alpha = 0.3f))

                        // Spray Rate Slider - Always visible
                        // PWM mapping: OFF=1000, 10%=1100, 50%=1500, 100%=2000
                        // Uses DO_SET_SERVO (SERVO7) by default for direct servo control
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(AppStrings.sprayRate, color = Color.White, modifier = Modifier.weight(1f))
                                Text("${sprayRate.toInt()} %", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = sprayRate,
                                onValueChange = { newRate ->
                                    // Snap to nearest 10%
                                    val snappedRate = (Math.round(newRate / 10f) * 10f).coerceIn(10f, 100f)
                                    telemetryViewModel.setSprayRate(snappedRate)
                                },
                                valueRange = 10f..100f, // 10% to 100% (minimum 10%)
                                steps = 8, // 9 positions: 10%, 20%, 30%, 40%, 50%, 60%, 70%, 80%, 90%, 100%
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = if (sprayEnabled) Color.Green else Color.Gray,
                                    activeTrackColor = if (sprayEnabled) Color.Green else Color.Gray,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                            Text(
                                AppStrings.adjustSprayIntensity,
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            // PWM info text
                            Text(
                                "PWM: ${(1000 + (sprayRate.toInt() / 100f * 1000f)).toInt()}",
                                color = Color.Gray.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Divider(color = Color.White.copy(alpha = 0.3f))

                        // Spray Enable/Disable Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    AppStrings.enableSpray,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (sprayEnabled) AppStrings.sprayActive else AppStrings.sprayInactive,
                                    color = if (sprayEnabled) Color.Green else Color.Red,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = sprayEnabled,
                                onCheckedChange = { telemetryViewModel.setSprayEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color.Green, // Green when ON
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color.Red // Red when OFF
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusWidget(isConnected: Boolean) {
    val statusColor = if (isConnected) Color.Green else Color.Red
    val statusText = if (isConnected) AppStrings.connected else AppStrings.disconnected

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(statusText, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun DividerBlock() {
    Box(
        modifier = Modifier
            .padding(horizontal = 7.dp)
            .width(1.dp)
            .height(22.dp) // slightly smaller height
            .background(Color.White.copy(alpha = 0.7f))
    )
}

@Composable
fun InfoBlock(icon: ImageVector, value: String) {
    Column(
        modifier = Modifier.padding(horizontal = 4.dp), // slightly less padding
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) // just a little smaller
        Spacer(modifier = Modifier.height(1.dp))
        Text(value, color = Color.White, fontSize = 9.sp) // just a little smaller
    }
}

@Composable
fun InfoBlockGroup(icon: ImageVector, values: List<String>) {
    Column(
        modifier = Modifier.padding(horizontal = 4.dp), // slightly less padding
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) // just a little smaller
        Spacer(modifier = Modifier.height(1.dp))
        values.forEach { value ->
            Text(value, color = Color.White, fontSize = 9.sp) // just a little smaller
        }
    }
}
