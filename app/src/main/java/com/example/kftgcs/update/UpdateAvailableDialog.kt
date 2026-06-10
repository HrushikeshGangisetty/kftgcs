package com.example.kftgcs.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.kftgcs.utils.AppStrings

private val PrimaryBlue = Color(0xFF2196F3)

/**
 * Popup shown when Google Play reports a newer version is available.
 * Always dismissible ("Later"); "Update" hands off to the Play FLEXIBLE flow.
 *
 * Mirrors the Dialog { Card { Column } } style used by MissionCompletionDialog.
 */
@Composable
fun UpdateAvailableDialog(
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    UpdatePromptScaffold(
        title = AppStrings.updateAvailableTitle,
        message = AppStrings.updateAvailableMessage,
        confirmLabel = AppStrings.updateNow,
        onConfirm = onUpdate,
        onDismiss = onLater
    )
}

/**
 * Popup shown once a FLEXIBLE update has finished downloading in the background.
 * "Restart" installs it (restarts the app); "Later" leaves it for next time.
 */
@Composable
fun UpdateDownloadedDialog(
    onRestart: () -> Unit,
    onLater: () -> Unit
) {
    UpdatePromptScaffold(
        title = AppStrings.updateDownloadedTitle,
        message = AppStrings.updateDownloadedMessage,
        confirmLabel = AppStrings.restartToInstall,
        onConfirm = onRestart,
        onDismiss = onLater
    )
}

@Composable
private fun UpdatePromptScaffold(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = AppStrings.later,
                            color = Color.Gray,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.size(8.dp))

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = confirmLabel,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}
