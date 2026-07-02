package com.example.kftgcs.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kftgcs.telemetry.ArmingCheckState

/**
 * Non-dismissible safety popup for accounts whose flight controller still has
 * ARMING_CHECK=0 (allows arming despite failing PreArm checks). Only "Write"
 * or an explicit "Skip for now" / "Later" closes it — outside-tap and back
 * press are blocked since this addresses a known crash cause.
 */
@Composable
fun ArmingCheckSafetyDialog(
    state: ArmingCheckState,
    onWrite: () -> Unit,
    onSkip: () -> Unit,
    onReboot: () -> Unit,
    onLater: () -> Unit
) {
    if (state == ArmingCheckState.HIDDEN) return

    BackHandler(enabled = true) { /* block back-press dismissal */ }

    when (state) {
        ArmingCheckState.PROMPT_WRITE,
        ArmingCheckState.WRITING,
        ArmingCheckState.WRITE_FAILED -> {
            val isWriting = state == ArmingCheckState.WRITING
            AlertDialog(
                onDismissRequest = { /* non-dismissible */ },
                title = {
                    Text(text = "⚠️ Security Parameter Misconfigured", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text("ARMING_CHECK needs to be changed from 0 to 4390 for security purposes.")
                        if (state == ArmingCheckState.WRITE_FAILED) {
                            Text(
                                text = "Failed to write parameter. Please try again.",
                                color = Color(0xFFFF5722)
                            )
                        }
                        if (isWriting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onWrite,
                        enabled = !isWriting,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text(if (isWriting) "Writing..." else "Write", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onSkip, enabled = !isWriting) {
                        Text("Skip for now")
                    }
                }
            )
        }
        ArmingCheckState.PROMPT_REBOOT -> {
            AlertDialog(
                onDismissRequest = { /* non-dismissible */ },
                title = { Text(text = "Reboot Required", fontWeight = FontWeight.Bold) },
                text = {
                    Text("ARMING_CHECK was updated to 4390. Reboot the flight controller now to apply the change?")
                },
                confirmButton = {
                    Button(
                        onClick = onReboot,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text("Reboot", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onLater) {
                        Text("Later")
                    }
                }
            )
        }
        ArmingCheckState.HIDDEN -> Unit
    }
}
