package com.example.kftgcs.uiconnection

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.example.kftgcs.telemetry.connections.UdpDiagnostics
import com.example.kftgcs.telemetry.connections.UdpPortScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.telemetry.ConnectionType
import com.example.kftgcs.telemetry.PairedDevice
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.telemetry.UsbDeviceInfo
import com.example.kftgcs.utils.AppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun ConnectionPage(
    navController: NavController,
    viewModel: SharedViewModel,
    /** Where to navigate after a successful connection. Defaults to SelectMethod. */
    destinationRoute: String = Screen.SelectMethod.route,
    /** Route to pop up to (inclusive) when navigating away. Defaults to Connection. */
    popUpToRoute: String = Screen.Connection.route
) {
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var showPopup by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val connectionType by viewModel.connectionType

    // When the page is shown, get the paired Bluetooth devices
    LaunchedEffect(Unit) {
        viewModel.refreshPairedDevices(context)
    }

    // React to connection state changes from the ViewModel
    LaunchedEffect(viewModel) {
        viewModel.isConnected.collectLatest { isConnected ->
            if (isConnected) {
                isConnecting = false
                connectionJob?.cancel()
                navController.navigate(destinationRoute) {
                    popUpTo(popUpToRoute) { inclusive = true }
                }
            }
        }
    }

    fun startConnection() {
        isConnecting = true
        errorMessage = ""
        connectionJob?.cancel() // Cancel any previous job
        connectionJob = coroutineScope.launch {
            try {
                viewModel.connect() // Ask the ViewModel to connect

                // Set a timeout for the connection attempt
                delay(10000) // 10-second timeout

                // If we are still in a 'connecting' state after the timeout, it failed.
                if (isConnecting) {
                    isConnecting = false
                    // For UDP, a bare "timed out" tells the user nothing — and the app ships on the
                    // RC where no logcat is available. Substitute the stage-specific verdict
                    // recorded by the transport (bind failed / no packets / no MAVLink parsed).
                    errorMessage = if (connectionType == ConnectionType.UDP && UdpDiagnostics.hasData()) {
                        UdpDiagnostics.verdict()
                    } else {
                        AppStrings.connectionTimedOut
                    }
                    showPopup = true
                    viewModel.cancelConnection() // Clean up the failed attempt
                    // Announce connection failure via TTS
                    viewModel.announceConnectionFailed()
                }
            } catch (e: Exception) {
                // 🔥 CRITICAL FIX: Do NOT treat CancellationException as an error.
                // When connection succeeds, the LaunchedEffect cancels connectionJob (the timeout).
                // This throws CancellationException inside delay(10000), which was being caught here
                // and incorrectly treated as a connection error — showing "k0 cancelled" popup and
                // calling cancelConnection() which set repo = null, killing the working connection.
                if (e is kotlinx.coroutines.CancellationException) {
                    // Normal cancellation (connection succeeded or user cancelled) - just rethrow
                    throw e
                }
                isConnecting = false
                errorMessage = "Connection error: ${e.message ?: "Unknown error"}"
                showPopup = true
                try {
                    viewModel.cancelConnection()
                } catch (_: Exception) { }
                viewModel.announceConnectionFailed()
            }
        }
    }

    fun cancelConnection() {
        connectionJob?.cancel()
        isConnecting = false
        errorMessage = ""
        coroutineScope.launch {
            viewModel.cancelConnection()
        }
    }

    val isConnectEnabled = !isConnecting && when (connectionType) {
        ConnectionType.TCP -> viewModel.ipAddress.value.isNotBlank() && viewModel.port.value.isNotBlank()
        ConnectionType.UDP -> viewModel.udpLocalPort.value.toIntOrNull()?.let { it in 1..65535 } == true &&
            isPlausibleHost(viewModel.udpRemoteHost.value) &&
            (viewModel.udpRemoteHost.value.isBlank() ||
                viewModel.udpRemotePort.value.toIntOrNull()?.let { it in 1..65535 } == true) &&
            !isUdpSelfTarget(
                viewModel.udpLocalPort.value,
                viewModel.udpRemoteHost.value,
                viewModel.udpRemotePort.value
            )
        ConnectionType.BLUETOOTH -> viewModel.selectedDevice.value != null
        ConnectionType.USB -> viewModel.selectedUsbDevice.value != null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E27),
                        Color(0xFF131722),
                        Color(0xFF0F1419)
                    )
                )
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Modern Header (styled similar to LogsScreen)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2196F3), Color(0xFF1976D2))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (connectionType) {
                                ConnectionType.TCP -> Icons.Default.Cloud
                                ConnectionType.UDP -> Icons.Default.Lan
                                ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
                                ConnectionType.USB -> Icons.Default.Usb
                            },
                            contentDescription = "Connection",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            AppStrings.connectionTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            AppStrings.connectionType,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }

                // Spacer to keep header compact; no action buttons here to avoid logic changes
                Spacer(modifier = Modifier.width(8.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            val tabs = listOf(AppStrings.tcp, AppStrings.udp, AppStrings.bluetooth, AppStrings.usb)
            TabRow(
                selectedTabIndex = connectionType.ordinal,
                containerColor = Color(0xFF1E293B).copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(12.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = connectionType.ordinal == index,
                        onClick = { viewModel.onConnectionTypeChange(ConnectionType.entries[index]) },
                        text = { Text(title, color = Color.White) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (connectionType) {
                ConnectionType.TCP -> TcpConnectionContent(viewModel)
                ConnectionType.UDP -> UdpConnectionContent(viewModel, isConnecting)
                ConnectionType.BLUETOOTH -> BluetoothConnectionContent(viewModel)
                ConnectionType.USB -> UsbConnectionContent(viewModel)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Styled Connect button
                Button(
                    onClick = { startConnection() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = isConnectEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(8.dp, RoundedCornerShape(26.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = if (isConnectEnabled) listOf(Color(0xFF1E88E5), Color(0xFF1565C0)) else listOf(Color.Gray.copy(alpha = 0.3f), Color.Gray.copy(alpha = 0.3f))
                                ), RoundedCornerShape(26.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(AppStrings.connect, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Styled Cancel button
                Button(
                    onClick = { cancelConnection() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = isConnecting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(6.dp, RoundedCornerShape(26.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = if (isConnecting) listOf(Color(0xFFEF4444), Color(0xFFDC2626)) else listOf(Color(0xFF374151), Color(0xFF1F2937))
                                ), RoundedCornerShape(26.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(AppStrings.cancel, color = Color.White, fontSize = 16.sp)
                    }
                }
            }


            if (errorMessage.isNotEmpty() && !showPopup) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5252).copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SignalWifiOff, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = errorMessage, color = Color(0xFFFF5252), modifier = Modifier.weight(1f))
                        TextButton(onClick = { errorMessage = "" }) { Text("OK", color = Color(0xFFFF5252)) }
                    }
                }
            }
        }

        if (showPopup) {
            val clipboard = LocalClipboardManager.current
            // Keyed on errorMessage, not on showPopup: showPopup is always true inside this branch,
            // so keying on it would keep the FIRST failure's snapshot for every later failure.
            var copied by remember(errorMessage) { mutableStateOf(false) }
            // Snapshot the details when the dialog opens: cancelConnection() has already torn the
            // socket down by now, but UdpDiagnostics is a plain singleton so the counters remain.
            val udpDetails = remember(errorMessage) {
                if (connectionType == ConnectionType.UDP && UdpDiagnostics.hasData()) {
                    UdpDiagnostics.details()
                } else {
                    null
                }
            }

            AlertDialog(
                onDismissRequest = { showPopup = false },
                title = { Text(AppStrings.connectionFailed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = {
                    // Scrollable: the RC's screen is short and the diagnostics block was being
                    // clipped after the first line — hiding the "remote :" row, which is exactly
                    // the line needed to tell listen-only mode from a seeded-peer attempt.
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(errorMessage)
                        if (udpDetails != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                AppStrings.udpDiagnostics,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Selectable so the customer can long-press to copy even if the button
                            // is missed, and monospaced so the columns line up in a screenshot.
                            SelectionContainer {
                                Text(
                                    udpDetails,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Color.Black.copy(alpha = 0.25f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showPopup = false }) { Text(AppStrings.ok) }
                },
                dismissButton = if (udpDetails != null) {
                    {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString("$errorMessage\n\n$udpDetails"))
                            copied = true
                        }) {
                            Text(if (copied) AppStrings.copied else AppStrings.copy)
                        }
                    }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
fun TcpConnectionContent(viewModel: SharedViewModel) {
    val ipAddress by viewModel.ipAddress
    val port by viewModel.port

    OutlinedTextField(
        value = ipAddress,
        onValueChange = { viewModel.onIpAddressChange(it) },
        label = { Text(AppStrings.ipAddress, color = Color.White) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = Color.White)
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = port,
        onValueChange = { viewModel.onPortChange(it) },
        label = { Text(AppStrings.port, color = Color.White) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = Color.White)
    )
}

@Composable
fun UdpConnectionContent(viewModel: SharedViewModel, isConnecting: Boolean = false) {
    val localPort by viewModel.udpLocalPort
    val remoteHost by viewModel.udpRemoteHost
    val remotePort by viewModel.udpRemotePort
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var scanSummary by remember { mutableStateOf<String?>(null) }

    // Local port + Scan sit on one row: on an RC the port map varies by firmware, so finding the
    // live port matters more than typing one in.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = localPort,
            onValueChange = { viewModel.onUdpLocalPortChange(it.filter { c -> c.isDigit() }) },
            label = { Text(AppStrings.udpLocalPort, color = Color.White) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(color = Color.White)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Button(
            onClick = {
                scanning = true
                scanSummary = null
                scope.launch {
                    // Binding sockets blocks; keep it off the main thread.
                    val results = withContext(Dispatchers.IO) { UdpPortScanner.scan() }
                    scanSummary = UdpPortScanner.summarize(results)
                    // Apply ONLY the local port.
                    //
                    // An earlier version also filled Remote Host/Port from the discovered peer.
                    // That was wrong and actively harmful: every Scan re-populated Remote Host,
                    // silently putting the app back into seed-and-probe mode and undoing
                    // listen-only — so a user who cleared the field and then scanned was tested in
                    // the very mode we were trying to avoid. QGroundControl and Mission Planner
                    // never derive a send target this way; they learn the peer from the first
                    // received datagram at runtime, which PeerAddress.observe() already does.
                    UdpPortScanner.bestResult(results)?.let { best ->
                        viewModel.onUdpLocalPortChange(best.port.toString())
                    }
                    scanning = false
                }
            },
            // Disabled while a connection attempt is live: the transport already holds the local
            // port, so scanning now would report our own socket as "in use by another app".
            enabled = !scanning && !isConnecting,
            modifier = Modifier.height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00796B),
                contentColor = Color.White
            )
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text(AppStrings.scan)
            }
        }
    }

    // One-tap reset to the configuration the vendor documents and that both QGroundControl and
    // Mission Planner use: bind the port, transmit nothing, learn the peer from the first packet.
    // A button rather than an instruction because a stale Remote Host silently re-enables the
    // seed-and-probe path, and asking the user to clear a field by hand keeps not working.
    if (remoteHost.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                viewModel.onUdpRemoteHostChange("")
                scanSummary = null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(AppStrings.udpUseListenOnly, color = Color(0xFF4FC3F7))
        }
    }

    if (scanning || scanSummary != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            scanSummary ?: AppStrings.scanning,
            color = Color.White.copy(alpha = 0.75f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = remoteHost,
        onValueChange = { viewModel.onUdpRemoteHostChange(it) },
        label = { Text(AppStrings.udpRemoteHostOptional, color = Color.White) },
        placeholder = { Text("127.0.0.1", color = Color.White.copy(alpha = 0.35f)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = remoteHost.isNotBlank() && !isPlausibleHost(remoteHost),
        supportingText = {
            if (remoteHost.isNotBlank() && !isPlausibleHost(remoteHost)) {
                Text(AppStrings.udpInvalidHost, color = Color(0xFFFF5252))
            } else {
                // UDP is connectionless: binding always succeeds, so a wrong port shows up only as
                // a timeout. Say so up front instead of letting the user guess.
                Text(AppStrings.udpListenHint, color = Color.White.copy(alpha = 0.5f))
            }
        },
        textStyle = LocalTextStyle.current.copy(color = Color.White)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Separate remote port, matching QGC's layout, so an RC router's two ports (e.g. listen on
    // 14550, send to 14551) map one-to-one instead of being squeezed into a host:port string.
    OutlinedTextField(
        value = remotePort,
        onValueChange = { viewModel.onUdpRemotePortChange(it.filter { c -> c.isDigit() }) },
        label = { Text(AppStrings.udpRemotePort, color = Color.White) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = remoteHost.isNotBlank(),
        isError = remoteHost.isNotBlank() &&
            (remotePort.toIntOrNull()?.let { it in 1..65535 } != true ||
                isUdpSelfTarget(localPort, remoteHost, remotePort)),
        supportingText = {
            if (isUdpSelfTarget(localPort, remoteHost, remotePort)) {
                Text(AppStrings.udpSameAsLocalPort, color = Color(0xFFFF5252))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(color = Color.White)
    )
}

/**
 * True when the remote endpoint points back at our own listen socket (loopback on the same port).
 *
 * This is the configuration that produced the customer's "bound: yes, packets in: 0": the peer is
 * seeded to 127.0.0.1 on the port we just bound, so the heartbeat probe is delivered straight back
 * to us instead of to the RC's router. Blocking it in the UI turns a 10-second timeout into an
 * immediately visible mistake.
 */
private fun isUdpSelfTarget(localPort: String, remoteHost: String, remotePort: String): Boolean {
    val host = remoteHost.trim()
    if (host.isBlank()) return false
    val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) ||
        host == "::1" || host == "[::1]"
    if (!isLoopback) return false
    val lp = localPort.toIntOrNull() ?: return false
    val rp = remotePort.toIntOrNull() ?: return false
    return lp == rp
}

/**
 * Cheap sanity check for the optional remote host field: an IPv4 literal, a bracketed IPv6 literal,
 * or a hostname, each with an optional `:port` suffix. Real resolution still happens on connect —
 * this only catches obvious typos before the 10-second timeout.
 */
private fun isPlausibleHost(input: String): Boolean {
    val value = input.trim()
    if (value.isEmpty()) return true

    // Split an optional :port, taking care not to break IPv6 literals.
    val hostPart: String
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        if (end < 0) return false
        hostPart = value.substring(1, end)
    } else {
        val idx = value.lastIndexOf(':')
        hostPart = if (idx > 0 && value.count { it == ':' } == 1) {
            val port = value.substring(idx + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            value.substring(0, idx)
        } else {
            value
        }
    }

    if (hostPart.isEmpty()) return false
    // IPv6 literal
    if (hostPart.contains(':')) return hostPart.all { it.isDigit() || it in "abcdefABCDEF:" }
    // IPv4 literal
    if (hostPart.all { it.isDigit() || it == '.' }) {
        val octets = hostPart.split('.')
        return octets.size == 4 && octets.all { o -> o.isNotEmpty() && o.toIntOrNull()?.let { it in 0..255 } == true }
    }
    // Hostname
    return hostPart.all { it.isLetterOrDigit() || it == '-' || it == '.' } &&
        !hostPart.startsWith('-') && !hostPart.endsWith('-')
}

@Composable
fun BluetoothConnectionContent(viewModel: SharedViewModel) {
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice
    val context = LocalContext.current

    if (pairedDevices.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(AppStrings.noDevicesPaired, color = Color.White)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            items(pairedDevices) { device ->
                DeviceRow(
                    device = device,
                    isSelected = device.address == selectedDevice?.address,
                    onClick = { viewModel.onDeviceSelected(device) }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = { viewModel.refreshPairedDevices(context) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00796B),
            contentColor = Color.White
        )
    ) {
        Text("Refresh Devices")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbConnectionContent(viewModel: SharedViewModel) {
    val usbDevices by viewModel.usbDevices.collectAsState()
    val selectedUsbDevice by viewModel.selectedUsbDevice
    val baudRate by viewModel.baudRate
    val context = LocalContext.current
    var permissionMessage by remember { mutableStateOf("") }

    // Populate the device list as soon as the USB tab is shown.
    LaunchedEffect(Unit) {
        viewModel.refreshUsbDevices(context)
    }

    if (usbDevices.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(AppStrings.noUsbDevices, color = Color.White)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            items(usbDevices) { device ->
                UsbDeviceRow(
                    device = device,
                    isSelected = device.id == selectedUsbDevice?.id,
                    onClick = {
                        viewModel.onUsbDeviceSelected(device)
                        permissionMessage = ""
                        // USB devices need a per-device runtime grant before we can open them.
                        viewModel.requestUsbPermission(context, device.device) { granted ->
                            if (!granted) {
                                // Clear the selection so Connect stays disabled until granted.
                                if (viewModel.selectedUsbDevice.value?.id == device.id) {
                                    viewModel.clearUsbSelection()
                                }
                                permissionMessage = "USB permission denied for ${device.name}"
                            }
                        }
                    }
                )
            }
        }
    }

    if (permissionMessage.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(permissionMessage, color = Color(0xFFFF5252), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Baud rate selector — 115200 is the ArduPilot/SiK default.
    val baudOptions = listOf(57600, 115200, 921600)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = baudRate.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(AppStrings.baudRate, color = Color.White) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            baudOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        viewModel.onBaudRateChange(option)
                        expanded = false
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = { viewModel.refreshUsbDevices(context) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00796B),
            contentColor = Color.White
        )
    ) {
        Text("Refresh Devices")
    }
}

@Composable
fun UsbDeviceRow(device: UsbDeviceInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(device.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(device.id, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun DeviceRow(device: PairedDevice, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(device.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(device.address, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}