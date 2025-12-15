package com.example.aerogcsclone.uimain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.widget.Toast
import com.example.aerogcsclone.navigation.Screen

@Composable
fun SecurityScreen(navController: NavHostController) {
    val context = LocalContext.current
    // PIN dialog visibility and input state
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var savedPin by remember { mutableStateOf(loadSavedPin(context)) }
    // Simple security settings screen with a couple of toggles as examples
    var requireAuth by remember { mutableStateOf(false) }
    var lockOnBackground by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF87CEEB)
                    )
                }

                Text(
                    text = "Security",
                    color = Color.White,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 4-digit PIN section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "4-digit PIN", color = Color.White)
                    Text(
                        text = if (savedPin.isNullOrEmpty()) "Not set" else "•••• (set)",
                        color = Color.Gray
                    )
                }
                Button(onClick = { showPinDialog = true }) {
                    Text(text = if (savedPin.isNullOrEmpty()) "Set PIN" else "Change PIN")
                }
            }

            // PIN input dialog
            if (showPinDialog) {
                AlertDialog(
                    onDismissRequest = { showPinDialog = false; pinInput = "" },
                    title = { Text(text = "Set 4-digit PIN") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { new ->
                                    // Allow only digits and limit to 4 characters
                                    val filtered = new.filter { it.isDigit() }.take(4)
                                    pinInput = filtered
                                    // Auto-save and close dialog immediately when 4 digits entered
                                    if (filtered.length == 4) {
                                        savePin(context, filtered)
                                        savedPin = filtered
                                        Toast.makeText(context, "PIN set", Toast.LENGTH_SHORT).show()
                                        showPinDialog = false
                                        pinInput = ""
                                    }
                                },
                                placeholder = { Text(text = "Enter 4-digit PIN") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "PIN will be saved automatically when you confirm.", color = Color.Gray)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (pinInput.length == 4) {
                                savePin(context, pinInput)
                                savedPin = pinInput
                                Toast.makeText(context, "PIN set", Toast.LENGTH_SHORT).show()
                                showPinDialog = false
                                pinInput = ""
                            } else {
                                Toast.makeText(context, "Please enter a 4-digit PIN", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("Confirm")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPinDialog = false; pinInput = "" }) { Text("Cancel") }
                    }
                )
            }

            // Example toggle: require authentication to resume mission
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Require authentication to resume", color = Color.White)
                    Text(text = "Requires PIN or biometric to resume sensitive actions", color = Color.Gray)
                }
                Switch(checked = requireAuth, onCheckedChange = { requireAuth = it })
            }

            // Example toggle: lock on background
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Lock when app in background", color = Color.White)
                    Text(text = "Automatically lock when app is backgrounded", color = Color.Gray)
                }
                Switch(checked = lockOnBackground, onCheckedChange = { lockOnBackground = it })
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { navController.navigate(Screen.Settings.route) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(text = "Back to Settings", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

private fun savePin(context: Context, pin: String) {
    val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("pin", pin).apply()
}

private fun loadSavedPin(context: Context): String? {
    val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
    return prefs.getString("pin", null)
}
