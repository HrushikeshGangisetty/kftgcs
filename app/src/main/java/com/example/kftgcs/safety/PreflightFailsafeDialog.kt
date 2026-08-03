package com.example.kftgcs.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.kftgcs.telemetry.PreflightFailsafeSummary
import java.util.Locale

/**
 * Pre-arm acknowledgement popup, raised on every connection.
 *
 * Lists the failsafe configuration this vehicle is flying with so the pilot confirms it
 * before takeoff rather than discovering it in the air. Non-dismissible — only OK closes
 * it, and [com.example.kftgcs.telemetry.TelemetryRepository.arm] refuses to arm until
 * that happens. Back press and outside taps are blocked for the same reason.
 */
@Composable
fun PreflightFailsafeDialog(
    summary: PreflightFailsafeSummary?,
    onAcknowledge: () -> Unit
) {
    if (summary == null) return

    BackHandler(enabled = true) { /* block back-press dismissal */ }

    AlertDialog(
        onDismissRequest = { /* non-dismissible */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text(
                text = "⚠️ Confirm Failsafe Settings",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "The drone will fly with these settings. Review them, then press OK to enable arming.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SummaryRow("Low Voltage 1", formatVolts(summary.lowVoltLevel1))
                SummaryRow("Critical Voltage", formatVolts(summary.criticalVoltage))
                SummaryRow("Tank Empty Action", summary.tankEmptyAction)
                SummaryRow("Battery Failsafe Action", summary.batteryFailsafeAction)
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
            ) {
                Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/** One label/value line of the summary, value right-aligned and emphasised. */
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatVolts(v: Float) = String.format(Locale.US, "%.1f V", v)
