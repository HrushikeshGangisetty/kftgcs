package com.example.kftgcs.uiconnection

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.telemetry.ConnectionType
import com.example.kftgcs.telemetry.PairedDevice
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.AppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun ConnectionPage(navController: NavController, viewModel: SharedViewModel) {
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
                navController.navigate(Screen.SelectMethod.route) {
                    popUpTo(Screen.Connection.route) { inclusive = true }
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
                    errorMessage = AppStrings.connectionTimedOut
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
        ConnectionType.BLUETOOTH -> viewModel.selectedDevice.value != null
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
                                ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
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

            val tabs = listOf(AppStrings.tcp, AppStrings.bluetooth)
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
                ConnectionType.BLUETOOTH -> BluetoothConnectionContent(viewModel)
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
            AlertDialog(
                onDismissRequest = { showPopup = false },
                title = { Text(AppStrings.connectionFailed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = { Text(errorMessage) },
                confirmButton = {
                    Button(onClick = { showPopup = false }) { Text(AppStrings.ok) }
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