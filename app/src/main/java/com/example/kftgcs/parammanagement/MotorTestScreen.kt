package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────────────
// Palette  (matches the rest of the Param Management screens)
// ─────────────────────────────────────────────────────────────────────────────
private val MtBg          = Color(0xFF0D1B4B)
private val MtTopBar      = Color(0xFF1A237E)
private val MtCard        = Color(0xFF1E2D6B)
private val MtAccent      = Color(0xFF3A6BD5)
private val MtStop        = Color(0xFFE53935)
private val MtSequence    = Color(0xFFF57C00)
private val MtSuccess     = Color(0xFF38A169)
private val MtWarning     = Color(0xFFFFC107)
private val MtTextW       = Color.White
private val MtTextMuted   = Color.White.copy(alpha = 0.55f)
private val MtFieldBg     = Color.White.copy(alpha = 0.08f)
private val MtMotorBtn    = Color(0xFF1565C0)

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotorTestScreen(
    navController: NavController,
    viewModel: MotorTestViewModel
) {
    val state   by viewModel.state.collectAsState()
    val ctx     = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // ── Local UI state ────────────────────────────────────────────────────────

    // Throttle % input field (default 5, range 1–19 to stay safe)
    var throttleText  by remember { mutableStateOf("5") }
    // Duration input field (default 2s, range 1–60s)
    var durationText  by remember { mutableStateOf("2") }

    // SpinArm dialog
    var showSpinArmDialog   by remember { mutableStateOf(false) }
    var spinArmInputText    by remember { mutableStateOf("") }

    // SpinMin dialog — driven by ViewModel state (requires reading current param)
    var spinMinInputText    by remember { mutableStateOf("") }

    // Derived safe ints (fallback to defaults on parse failure)
    val throttleInt = throttleText.toIntOrNull()?.coerceIn(1, 99) ?: 5
    val durationInt = durationText.toIntOrNull()?.coerceIn(1, 60) ?: 2

    // Controls gating: disable while loading layout or not connected
    val controlsEnabled = state.isDroneConnected && !state.isLoadingLayout

    // ── Auto-fetch on connect ─────────────────────────────────────────────────
    LaunchedEffect(state.isDroneConnected) {
        if (state.isDroneConnected && state.motorMax == 0 && !state.isLoadingLayout) {
            viewModel.fetchLayout()
        }
    }

    // ── Toast for status / error messages ────────────────────────────────────
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(ctx, "⚠ $it", Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
    }

    // ── Sync SpinMin dialog input with ViewModel suggestion ───────────────────
    LaunchedEffect(state.spinMinDialogSuggestion) {
        state.spinMinDialogSuggestion?.let { spinMinInputText = it.toString() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dialogs
    // ─────────────────────────────────────────────────────────────────────────

    // MOT_SPIN_ARM dialog
    if (showSpinArmDialog) {
        MtInputDialog(
            title       = "Set MOT_SPIN_ARM",
            label       = "Arm throttle % (deadzone + 2 %)",
            inputText   = spinArmInputText,
            onInputChange = { spinArmInputText = it },
            onConfirm   = {
                val v = spinArmInputText.toIntOrNull()
                if (v != null) viewModel.setSpinArm(v)
                else Toast.makeText(ctx, "Enter a valid integer", Toast.LENGTH_SHORT).show()
                showSpinArmDialog = false
            },
            onDismiss   = { showSpinArmDialog = false }
        )
    }

    // MOT_SPIN_MIN dialog — shown when ViewModel has loaded the suggestion
    if (state.spinMinDialogSuggestion != null) {
        MtInputDialog(
            title       = "Set MOT_SPIN_MIN",
            label       = "Min spin throttle % (arm min + 3 %)",
            inputText   = spinMinInputText,
            onInputChange = { spinMinInputText = it },
            onConfirm   = {
                val v = spinMinInputText.toIntOrNull()
                if (v != null) viewModel.setSpinMin(v)
                else {
                    Toast.makeText(ctx, "Enter a valid integer", Toast.LENGTH_SHORT).show()
                    viewModel.dismissSpinMinDialog()
                }
            },
            onDismiss   = { viewModel.dismissSpinMinDialog() }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main scaffold
    // ─────────────────────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Motor Test", color = MtTextW, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MtTextW)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MtTopBar)
            )
        },
        containerColor = MtBg
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 1. Frame Info card ────────────────────────────────────────────
            MtCard(title = "Frame Info") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(state.frameClassText, color = MtTextW, fontSize = 13.sp)
                    Text(state.frameTypeText,  color = MtTextMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick  = { viewModel.fetchLayout() },
                        enabled  = state.isDroneConnected && !state.isLoadingLayout,
                        colors   = ButtonDefaults.buttonColors(containerColor = MtAccent),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh Layout", fontSize = 12.sp)
                    }
                    if (state.isLoadingLayout) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color    = MtAccent,
                            strokeWidth = 2.dp
                        )
                    }
                }
                if (!state.isDroneConnected) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ Not connected to drone",
                        color    = MtWarning,
                        fontSize = 12.sp
                    )
                }
            }

            // ── 2. Test settings card ─────────────────────────────────────────
            MtCard(title = "Test Settings") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MtNumberField(
                        label    = "Throttle %",
                        value    = throttleText,
                        hint     = "1–99",
                        modifier = Modifier.weight(1f),
                        onChange = { throttleText = it }
                    )
                    MtNumberField(
                        label    = "Duration (s)",
                        value    = durationText,
                        hint     = "1–60",
                        modifier = Modifier.weight(1f),
                        onChange = { durationText = it }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ Keep throttle ≤ 5 % for benchtop safety. Propellers will spin!",
                    color    = MtWarning,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }

            // ── 3. Individual motor buttons card ──────────────────────────────
            MtCard(title = "Individual Motors") {
                if (state.motorMax <= 0) {
                    Text(
                        "Tap \"Refresh Layout\" to detect the number of motors on your frame.",
                        color    = MtTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                } else {
                    // Lay out motor buttons in rows of 2
                    val motorIndices = (1..state.motorMax).toList()
                    motorIndices.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { motorIndex ->
                                val label = ('A' + (motorIndex - 1)).toString()
                                Button(
                                    onClick  = {
                                        viewModel.testMotor(motorIndex, throttleInt, durationInt)
                                    },
                                    enabled  = controlsEnabled,
                                    colors   = ButtonDefaults.buttonColors(containerColor = MtMotorBtn),
                                    shape    = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                ) {
                                    Text(
                                        "Motor $label",
                                        fontSize   = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            // If odd number of motors, fill the empty slot
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // ── 4. Master controls card ───────────────────────────────────────
            MtCard(title = "Master Controls") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Test All
                    Button(
                        onClick  = { viewModel.testAllMotors(throttleInt, durationInt) },
                        enabled  = controlsEnabled && state.motorMax > 0,
                        colors   = ButtonDefaults.buttonColors(containerColor = MtAccent),
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Test All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // Stop All
                    Button(
                        onClick  = { viewModel.stopAllMotors() },
                        enabled  = state.isDroneConnected,
                        colors   = ButtonDefaults.buttonColors(containerColor = MtStop),
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Stop All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Test Sequence — full width
                Button(
                    onClick  = { viewModel.testSequence(throttleInt, durationInt) },
                    enabled  = controlsEnabled && state.motorMax > 0,
                    colors   = ButtonDefaults.buttonColors(containerColor = MtSequence),
                    shape    = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        "Test Sequence (Motor A → ${
                            if (state.motorMax > 0) ('A' + (state.motorMax - 1)).toString() else "?"
                        })",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sequence sends one command; ArduPilot runs each motor for the set duration.",
                    color    = MtTextMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }

            // ── 5. Parameter settings card ────────────────────────────────────
            MtCard(title = "Parameter Settings") {
                Text(
                    "⚠ Throttle must be < 20 % to write spin parameters (Mission Planner safety rule).",
                    color    = MtWarning,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // MOT_SPIN_ARM
                    Button(
                        onClick  = {
                            if (throttleInt >= 20) {
                                Toast.makeText(ctx, "Throttle % above 20 — too high", Toast.LENGTH_SHORT).show()
                            } else {
                                spinArmInputText = (throttleInt + 2).toString()
                                showSpinArmDialog = true
                            }
                        },
                        enabled  = controlsEnabled,
                        colors   = ButtonDefaults.buttonColors(containerColor = MtAccent),
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("MOT_SPIN_ARM", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // MOT_SPIN_MIN
                    Button(
                        onClick  = { viewModel.prepareSpinMinDialog(throttleInt) },
                        enabled  = controlsEnabled,
                        colors   = ButtonDefaults.buttonColors(containerColor = MtAccent),
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("MOT_SPIN_MIN", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "ARM suggestion = throttle% + 2 %  |  MIN suggestion = current MIN + 3 %",
                    color    = MtTextMuted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        uriHandler.openUri(
                            "https://ardupilot.org/copter/docs/connect-escs-and-motors.html#motor-order-diagrams"
                        )
                    }
                ) {
                    Text(
                        "Motor Order Diagrams ↗",
                        color    = MtAccent.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable composables
// ─────────────────────────────────────────────────────────────────────────────

/** Titled card with consistent styling. */
@Composable
private fun MtCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = MtCard)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                title,
                color      = MtTextW,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(bottom = 10.dp)
            )
            content()
        }
    }
}

/** Integer input field styled for dark background. */
@Composable
private fun MtNumberField(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(label, color = MtTextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = { new ->
                // Accept only digit characters; limit to 3 chars (max "100")
                if (new.all { it.isDigit() } && new.length <= 3) onChange(new)
            },
            placeholder   = { Text(hint, color = MtTextMuted, fontSize = 12.sp) },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle     = LocalTextStyle.current.copy(color = MtTextW, fontSize = 14.sp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = MtAccent,
                unfocusedBorderColor = MtTextMuted,
                cursorColor          = MtAccent,
                focusedContainerColor   = MtFieldBg,
                unfocusedContainerColor = MtFieldBg
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )
    }
}

/** AlertDialog with a single text input, used for SpinArm / SpinMin. */
@Composable
private fun MtInputDialog(
    title: String,
    label: String,
    inputText: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MtCard,
        title = {
            Text(title, color = MtTextW, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(label, color = MtTextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { new ->
                        if (new.all { it.isDigit() } && new.length <= 3) onInputChange(new)
                    },
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle     = LocalTextStyle.current.copy(color = MtTextW),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = MtAccent,
                        unfocusedBorderColor    = MtTextMuted,
                        cursorColor             = MtAccent,
                        focusedContainerColor   = MtFieldBg,
                        unfocusedContainerColor = MtFieldBg
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Value written as float: ${(inputText.toIntOrNull() ?: 0) / 100.0f}",
                    color = MtTextMuted, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Set", color = MtAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MtTextMuted)
            }
        }
    )
}
