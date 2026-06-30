package com.example.kftgcs.ui.analyzelog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.telemetry.LogEntryInfo
import com.example.kftgcs.telemetry.SharedViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)
private val CardBorder = Color(0xFF4A5568)

/**
 * Analyze Log screen — lists the DataFlash logs on the connected flight controller and lets the
 * user download one over the active USB serial link. The downloaded raw `.bin` is cached in the
 * app's internal cache directory (handled by [AnalyzeLogViewModel]); analysis is out of scope.
 */
@Composable
fun AnalyzeLogScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel
) {
    val vm: AnalyzeLogViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()

    // SAF picker for a local `.bin` fallback. Mime is */* because .bin has no standard Android type.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) vm.importLocalLog(uri)
    }
    val onImport: () -> Unit = { importLauncher.launch("*/*") }

    // Kick off log discovery once when the screen is first shown.
    LaunchedEffect(Unit) {
        vm.loadLogs(sharedViewModel.repository)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Analyze Log",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            HorizontalDivider(
                color = Accent,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            when (val state = uiState) {
                is AnalyzeLogUiState.Loading -> CenteredStatus(
                    spinner = true,
                    text = "Reading logs from flight controller…"
                )

                is AnalyzeLogUiState.ListReady -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (state.logs.isEmpty()) {
                                CenteredStatus(
                                    spinner = false,
                                    text = "No logs found on the flight controller."
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(state.logs, key = { it.id }) { log ->
                                        LogRow(
                                            log = log,
                                            onClick = { vm.downloadLog(sharedViewModel.repository, log) }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        AccentButton(text = "Import Local Log", onClick = onImport)
                    }
                }

                is AnalyzeLogUiState.Copying -> CenteredStatus(
                    spinner = true,
                    text = "Importing local log…"
                )

                is AnalyzeLogUiState.Downloading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Downloading log #${state.log.id} (${formatBytes(state.log.sizeBytes)})",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress = { state.percent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = Accent,
                            trackColor = CardBorder
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "${(state.percent * 100).toInt()}%",
                            color = Accent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is AnalyzeLogUiState.Downloaded -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "File Ready",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.log?.let { "Log #${it.id} • ${formatBytes(state.file.length())}" }
                                ?: "Imported log • ${formatBytes(state.file.length())}",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.file.absolutePath,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        AccentButton(text = "Analyze") {
                            navController.navigate(Screen.LogAnalysis.createRoute(state.file.absolutePath))
                        }
                        Spacer(Modifier.height(12.dp))
                        AccentButton(text = "Back to list") {
                            vm.loadLogs(sharedViewModel.repository)
                        }
                    }
                }

                is AnalyzeLogUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            color = Color(0xFFE57373),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(24.dp))
                        AccentButton(text = "Retry") {
                            vm.loadLogs(sharedViewModel.repository)
                        }
                        Spacer(Modifier.height(12.dp))
                        AccentButton(text = "Import Local Log", onClick = onImport)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: LogEntryInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ID badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Accent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = log.id.toString(),
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Log #${log.id}",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${formatBytes(log.sizeBytes)}  •  ${formatTimestamp(log.timeUtcSec)}",
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun CenteredStatus(spinner: Boolean, text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (spinner) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(20.dp))
        }
        Text(text = text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Accent),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 16.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTimestamp(timeUtcSec: Long): String =
    if (timeUtcSec <= 0L) {
        "No timestamp"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timeUtcSec * 1000L))
    }
