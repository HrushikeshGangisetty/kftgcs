package com.example.aerogcsclone.uimain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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

    // Set nav bar gradient colors based on connection status
    // Top nav bar color palette — use the provided three-color theme (UI-only)
    val navBarColors = if (telemetryState.connected) {
        listOf(
            Color(0xFF0A0E27),
            Color(0xFF1A1F3A),
            Color(0xFF0F1419)
        )
    } else {
        // Disconnected gradient (requested): #BF280A, #E84223, #8F0E00
        listOf(
            Color(0xFFBF280A),
            Color(0xFFE84223),
            Color(0xFF8F0E00)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Removed .statusBarsPadding() to allow navbar to be drawn under status bar
            .height(IntrinsicSize.Min)
            .background(
                brush = Brush.horizontalGradient(
                    colors = navBarColors
                )
            )
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
                    contentDescription = "Menu",
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
                            text = { Text("Plan Mission", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Map,
                                    contentDescription = "Plan Mission",
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
                            text = { Text("Plot Templates", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FileCopy,
                                    contentDescription = "Plot Templates",
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
                            text = { Text("Reconnect", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reconnect",
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
                contentDescription = "Home",
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
                    text = "Pavaman Aviation",
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
                    contentDescription = "Spray",
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
                        contentDescription = "Geofence",
                        tint = if (geofenceEnabled) Color.Green else Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        if (geofenceEnabled) "ON" else "OFF",
                        color = if (geofenceEnabled) Color.Green else Color.White,
                        fontSize = 9.sp
                    )
                }
                DividerBlock()
                InfoBlock(Icons.Default.BatteryFull, "${telemetryState.batteryPercent ?: "N/A"}%")
                DividerBlock()
                InfoBlock(Icons.Default.Gamepad, "100%")
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
                    listOf(displayMode, if (telemetryState.armed) "Armed" else "Disarmed")
                )
                DividerBlock()

                // Notification Icon
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint = Color.White,
                    modifier = Modifier.clickable { onToggleNotificationPanel() }
                )
                Spacer(modifier = Modifier.width(16.dp))

                // Kebab menu
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White,
                        modifier = Modifier.clickable { kebabMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = kebabMenuExpanded,
                        onDismissRequest = { kebabMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Logs") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = "Logs",
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
                            text = { Text("Settings") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
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
                            text = { Text("Disconnect") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = "Disconnect",
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
                            text = { Text("Logout") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Logout,
                                    contentDescription = "Logout",
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
                            "Geofence Settings",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        Divider(color = Color.White.copy(alpha = 0.3f))

                        // Geofence Enable/Disable Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Enable Geofence",
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
                                "Polygon fence active around mission plan",
                                color = Color.Green,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            Text(
                                "Geofence disabled",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Buffer Distance Slider (only shown when geofence is enabled)
                        if (geofenceEnabled) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Buffer Distance", color = Color.White, modifier = Modifier.weight(1f))
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
                                    "Adjust polygon buffer distance around mission plan",
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
                            "Spray Settings",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        Divider(color = Color.White.copy(alpha = 0.3f))

                        // Spray Enable/Disable Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Enable Spray",
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
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

                        // Status text based on spray state
                        if (sprayEnabled) {
                            Text(
                                "Spray active",
                                color = Color.Green,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            Text(
                                "Spray inactive",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Spray Rate Slider (only shown when spray is enabled)
                        if (sprayEnabled) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Spray Rate", color = Color.White, modifier = Modifier.weight(1f))
                                    Text("${sprayRate.toInt()} %", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = sprayRate,
                                    onValueChange = { telemetryViewModel.setSprayRate(it) },
                                    valueRange = 0f..100f, // 0% to 100%
                                    steps = 100, // 101 steps for 0-100 range
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Green,
                                        activeTrackColor = Color.Green,
                                        inactiveTrackColor = Color.Gray
                                    )
                                )
                                Text(
                                    "Adjust spray intensity",
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
    }
}

@Composable
fun ConnectionStatusWidget(isConnected: Boolean) {
    val statusColor = if (isConnected) Color.Green else Color.Red
    val statusText = if (isConnected) "Connected" else "Disconnected"

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
