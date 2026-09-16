package com.example.kftgcs.uimain

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
import java.util.Locale
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
import com.example.kftgcs.telemetry.TelemetryState
import com.example.kftgcs.authentication.AuthViewModel
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.AppStrings

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
    val context = androidx.compose.ui.platform.LocalContext.current

    // Collect geofence state from viewmodel
    val geofenceEnabled by telemetryViewModel.geofenceEnabled.collectAsState()
    val fenceRadius by telemetryViewModel.fenceRadius.collectAsState()

    // Collect user selected flight mode to conditionally show Plan Mission menu item
    val userFlightMode by telemetryViewModel.userSelectedFlightMode.collectAsState()

    // Collect spray rate from viewmodel (spray enabled status comes from RC7 in telemetryState)
    val sprayRate by telemetryViewModel.sprayRate.collectAsState()

    // What the pump will actually do at the current groundspeed, plus the vehicle's sprayer master
    // switch — the slider on its own does not tell the pilot either (see sprayEffectiveOutputPct).
    val sprayEffectiveOutput by telemetryViewModel.sprayEffectiveOutputPct.collectAsState()
    val sprayEnableParam by telemetryViewModel.sprayEnableParam.collectAsState()

    // Pump mode (AUTO = speed-scaled SPRAY_PUMP_RATE, MANUAL = direct SPRAY_PUMP_PCT duty) and
    // whether this firmware defines the manual params at all.
    val sprayManualMode by telemetryViewModel.sprayManualMode.collectAsState()
    val sprayManualPct by telemetryViewModel.sprayManualPct.collectAsState()
    val sprayManualSupported by telemetryViewModel.sprayManualParamsSupported.collectAsState()

    // Remember the mode to prevent flickering due to recomposition
    val displayMode by remember(telemetryState.mode) {
        derivedStateOf {
            telemetryState.mode ?: "N/A"
        }
    }

    // Set nav bar color based on connection status - solid colors
    // Light green when connected, Red when disconnected
    val navBarColor = if (telemetryState.connected) {
        Color(0xFF4CAF50) // Light green color for connected state (Material Green 500)
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
                        // Only show Plan Mission menu item when user selected Automatic mode
                        if (userFlightMode == SharedViewModel.UserFlightMode.AUTOMATIC) {
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
                        }
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
                InfoBlockGroup(
                    Icons.Default.Bolt,
                    listOf(
                        "${telemetryState.voltage?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"} V",
                        "${telemetryState.currentA?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"} A"
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
                                authViewModel.signout(context)
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
                                    valueRange = 1f..50f, // Minimum 1m as requested, max 50m
                                    steps = 48, // 1m increments from 1 to 50
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
            // Get RC-switch-based spray enabled status from telemetry. The channel is whichever
            // one has RCx_OPTION = 15 (Sprayer), so label it with the resolved number rather than
            // assuming RC7.
            val rc7SprayEnabled = telemetryState.sprayTelemetry.sprayEnabled
            val rc7Value = telemetryState.sprayTelemetry.rc7Value
            val sprayChannelLabel = "RC${telemetryState.sprayTelemetry.sprayRcChannel}"

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

                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f))

                        // Sprayer switch status display
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$sprayChannelLabel Status:",
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (rc7SprayEnabled) "ENABLED" else "DISABLED",
                                color = if (rc7SprayEnabled) Color.Green else Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "$sprayChannelLabel PWM: ${rc7Value ?: "N/A"}",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f))

                        // Pump mode toggle. NOTE: this is the FC's pump-output MODE, unrelated to
                        // PlanScreen's "Auto Spray" switch (which embeds DO_SPRAYER items into a
                        // planned mission). Disabled when the firmware has no manual-pump params.
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(AppStrings.sprayPumpMode, color = Color.White, modifier = Modifier.weight(1f))
                                Text(
                                    if (sprayManualMode) AppStrings.sprayModeManual else AppStrings.sprayModeAuto,
                                    color = if (sprayManualSupported == false) Color.Gray else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Switch(
                                    checked = sprayManualMode,
                                    onCheckedChange = { telemetryViewModel.setSprayManualMode(it) },
                                    enabled = sprayManualSupported != false,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF1E88E5), // Blue = manual
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.Green      // Green = auto
                                    )
                                )
                            }
                            if (sprayManualSupported == false) {
                                Text(
                                    AppStrings.sprayManualUnsupported,
                                    color = Color(0xFFFF6D00),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        // Spray slider. In AUTO it writes SPRAY_PUMP_RATE (% per 1 m/s, 10-100
                        // step 10); in MANUAL it writes SPRAY_PUMP_PCT (direct duty, 0-100 step 5
                        // — finer because a duty cycle is set directly, and 0 means pump off).
                        // Slider is always functional regardless of the sprayer switch status.
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (sprayManualMode) AppStrings.sprayManualDuty else AppStrings.sprayRate,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${if (sprayManualMode) sprayManualPct.toInt() else sprayRate.toInt()} %",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = if (sprayManualMode) sprayManualPct else sprayRate,
                                onValueChange = { raw ->
                                    if (sprayManualMode) {
                                        // Snap to nearest 5%
                                        telemetryViewModel.setSprayManualPct(
                                            (Math.round(raw / 5f) * 5f).coerceIn(0f, 100f)
                                        )
                                    } else {
                                        // Snap to nearest 10%
                                        telemetryViewModel.setSprayRate(
                                            (Math.round(raw / 10f) * 10f).coerceIn(10f, 100f)
                                        )
                                    }
                                },
                                valueRange = if (sprayManualMode) 0f..100f else 10f..100f,
                                steps = if (sprayManualMode) 19 else 8, // 21 vs 9 positions
                                modifier = Modifier.fillMaxWidth(),
                                enabled = true, // Always enabled
                                colors = SliderDefaults.colors(
                                    thumbColor = if (sprayManualMode) Color(0xFF1E88E5) else Color.Green,
                                    activeTrackColor = if (sprayManualMode) Color(0xFF1E88E5) else Color.Green,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                            // Status text
                            Text(
                                if (sprayManualMode) AppStrings.sprayManualHint else AppStrings.adjustSprayIntensity,
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            // Effective pump output. In AUTO the slider is % per 1 m/s, so its
                            // number is NOT the pump percentage in flight — showing the computed
                            // value is what makes a saturated pump (every position above ~25 at
                            // 4 m/s) visible instead of looking like the app failed to write the
                            // rate. In MANUAL the two agree by definition, so the saturation
                            // warning would be noise and is suppressed.
                            Text(
                                text = sprayEffectiveOutput?.let { pct ->
                                    val saturated = pct >= 100f && !sprayManualMode
                                    "Pump now: ${pct.toInt()}%" + if (saturated) " (max — slider has no effect at this speed)" else ""
                                } ?: "Pump now: — (no groundspeed)",
                                color = sprayEffectiveOutput.let { pct ->
                                    when {
                                        pct == null -> Color.Gray
                                        pct >= 100f && !sprayManualMode -> Color(0xFFFFA000)
                                        else -> Color.LightGray
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            // A vehicle with SPRAY_ENABLE = 0 accepts and acks every rate write
                            // while spraying nothing, which is indistinguishable from a broken
                            // write unless we say so.
                            if (sprayEnableParam == false) {
                                Text(
                                    "⚠ SPRAY_ENABLE = 0 on the vehicle — rate changes will have no effect",
                                    color = Color(0xFFFF6D00),
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
    Row(
        modifier = Modifier.padding(horizontal = 4.dp), // slightly less padding
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) // just a little smaller
        Spacer(modifier = Modifier.width(3.dp))
        Text(value, color = Color.White, fontSize = 9.sp) // just a little smaller
    }
}

@Composable
fun InfoBlockGroup(icon: ImageVector, values: List<String>) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp), // slightly less padding
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) // just a little smaller
        Spacer(modifier = Modifier.width(3.dp))
        Column(horizontalAlignment = Alignment.Start) {
            values.forEach { value ->
                Text(value, color = Color.White, fontSize = 9.sp) // just a little smaller
            }
        }
    }
}
