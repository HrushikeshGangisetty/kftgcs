package com.example.kftgcs.loganalysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.kftgcs.loganalysis.model.DiagnosticFlag
import com.example.kftgcs.loganalysis.model.DiagnosticSeverity
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.ui.replay.ReplaySession
import java.io.File
import java.util.Locale

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)
private val CardBorder = Color(0xFF4A5568)
private val CriticalColor = Color(0xFFE57373)
private val WarningColor = Color(0xFFFFB74D)
private val InfoColor = Color(0xFF90CAF9)
private val OkColor = Color(0xFF4CAF50)

/**
 * Offline log analysis screen — runs the streaming DataFlash parser + crash diagnostic engine over a
 * downloaded `.bin` file (path in [binFilePath]) and renders the resulting [DiagnosticFlag]s. The
 * decimated replay timeline is also computed and held in the ViewModel state for a future map-replay
 * UI; here we only note its frame count.
 */
@Composable
fun LogAnalysisScreen(
    navController: NavHostController,
    binFilePath: String
) {
    val vm: LogAnalysisViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(binFilePath) {
        vm.analyze(File(binFilePath))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Log Analysis",
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
                is LogAnalysisUiState.Loading ->
                    CenteredStatus(spinner = true, text = "Preparing…")

                is LogAnalysisUiState.Parsing -> ParsingView(state.percent)

                is LogAnalysisUiState.AnalysisComplete -> AnalysisResults(
                    state = state,
                    onReplay = {
                        ReplaySession.set(
                            frames = state.replayTimeline,
                            sourceFilePath = binFilePath,
                            sourceLabel = File(binFilePath).name
                        )
                        navController.navigate(Screen.LogReplay.route)
                    },
                    onShareLog = { shareLogFile(context, File(binFilePath)) }
                )

                is LogAnalysisUiState.Error ->
                    CenteredStatus(spinner = false, text = state.message, textColor = CriticalColor)
            }
        }
    }
}

@Composable
private fun ParsingView(percent: Float?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Analyzing flight log…",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(20.dp))
        if (percent == null) {
            CircularProgressIndicator(color = Accent)
        } else {
            LinearProgressIndicator(
                progress = { percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Accent,
                trackColor = CardBorder
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${(percent * 100).toInt()}%",
                color = Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AnalysisResults(
    state: LogAnalysisUiState.AnalysisComplete,
    onReplay: () -> Unit,
    onShareLog: () -> Unit
) {
    val diagnostics = state.diagnostics
    Column(modifier = Modifier.fillMaxSize()) {
        // Summary line.
        Text(
            text = if (diagnostics.isEmpty()) {
                "No anomalies detected"
            } else {
                "${diagnostics.size} finding${if (diagnostics.size == 1) "" else "s"}"
            },
            color = if (diagnostics.isEmpty()) OkColor else Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = buildString {
                append("${state.replayTimeline.size} replay frames")
                if (state.truncated) append("  •  log truncated (ends abruptly)")
            },
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            if (state.replayTimeline.isNotEmpty()) {
                OutlinedAccentButton(
                    text = "Replay flight",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onReplay
                )
            }
            OutlinedAccentButton(
                text = "Share log",
                icon = Icons.Filled.Share,
                onClick = onShareLog
            )
        }

        if (diagnostics.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = OkColor,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "The diagnostic engine found no crash signatures in this log.",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(diagnostics) { flag -> DiagnosticCard(flag) }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(flag: DiagnosticFlag) {
    val (icon, color) = severityVisual(flag.severity)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = flag.title,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatTime(flag.timeUs),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = flag.description,
            color = Color.White,
            fontSize = 14.sp
        )
        if (flag.metrics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            flag.metrics.forEach { (k, v) ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(text = "$k: ", color = Color.Gray, fontSize = 13.sp)
                    Text(
                        text = v,
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlinedAccentButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Accent),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Accent)
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = Color.White, fontSize = 16.sp)
    }
}

private fun severityVisual(severity: DiagnosticSeverity): Pair<ImageVector, Color> =
    when (severity) {
        DiagnosticSeverity.CRITICAL -> Icons.Filled.Error to CriticalColor
        DiagnosticSeverity.WARNING -> Icons.Filled.Warning to WarningColor
        DiagnosticSeverity.INFO -> Icons.Filled.Info to InfoColor
    }

@Composable
private fun CenteredStatus(
    spinner: Boolean,
    text: String,
    textColor: Color = Color.White
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (spinner) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(20.dp))
        }
        Text(text = text, color = textColor, fontSize = 16.sp)
    }
}

/** Format a `TimeUS` (microseconds since boot) as `mm:ss.mmm` for display. */
private fun formatTime(timeUs: Long): String {
    val totalMs = timeUs / 1000
    val minutes = totalMs / 60000
    val seconds = (totalMs % 60000) / 1000
    val millis = totalMs % 1000
    return String.format(Locale.US, "T+%02d:%02d.%03d", minutes, seconds, millis)
}
