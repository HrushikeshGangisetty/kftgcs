package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ── Light palette matching the spraying-configuration mockup ──────────
private val SprayScreenBg  = Color(0xFFEFF3F8)
private val SprayTopBar     = Color(0xFF1A237E)
private val SprayCard       = Color.White
private val SprayBorder     = Color(0xFFD9E1EC)
private val SprayFieldBorder = Color(0xFFCBD5E1)
private val SprayTitle      = Color(0xFF29A8E0)
private val SprayBlue        = Color(0xFF29A8E0)
private val SprayLabel       = Color(0xFF6B7280)
private val SprayValueText    = Color(0xFF1A202C)
private val SprayHint         = Color(0xFFB0B8C4)
private val SprayAmber        = Color(0xFFED8936)
private val SprayGreen        = Color(0xFF38A169)
private val SprayRed          = Color(0xFFE53935)

private fun fmtSpray(value: Float): String {
    if (value == value.toLong().toFloat() && value >= -999999 && value <= 999999)
        return value.toLong().toString()
    return "%.4f".format(value).trimEnd('0').trimEnd('.')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpraySettingsScreen(
    navController: NavController,
    viewModel: SpraySettingsViewModel
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current

    // User-typed overrides keyed by param name; absent = show the live value.
    val editTexts: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }

    fun displayText(param: String): String =
        editTexts[param] ?: state.values[param]?.let { fmtSpray(it) } ?: ""

    // Valid, changed values staged for writing.
    val changes: Map<String, Float> = remember(editTexts.toMap(), state.values) {
        SpraySettingsViewModel.fields.mapNotNull { f ->
            val current = state.values[f.param] ?: return@mapNotNull null
            val typed = editTexts[f.param]?.trim()?.toFloatOrNull() ?: return@mapNotNull null
            if (typed != current) f.param to typed else null
        }.toMap()
    }

    var showConfirm by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }

    LaunchedEffect(state.writeResults) {
        val results = state.writeResults ?: return@LaunchedEffect
        results.filter { it.success }.forEach { editTexts.remove(it.name) }
        val ok = results.count { it.success }
        val failed = results.count { !it.success }
        if (failed == 0) {
            Toast.makeText(ctx, "✅ $ok parameter(s) updated", Toast.LENGTH_SHORT).show()
            showReboot = true
        } else {
            Toast.makeText(ctx, "⚠ $ok updated, $failed failed", Toast.LENGTH_LONG).show()
            if (ok > 0) showReboot = true
        }
        viewModel.clearWriteResults()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spray Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchSprayParams() },
                        enabled = state.isDroneConnected && !state.isLoading
                    ) {
                        Icon(
                            Icons.Filled.Refresh, "Reload",
                            tint = if (state.isDroneConnected && !state.isLoading) Color.White
                            else Color.White.copy(alpha = 0.35f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SprayTopBar)
            )
        },
        containerColor = SprayScreenBg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.isDroneConnected && state.values.isEmpty() ->
                    InfoCenterSpray("⚠ Drone not connected", "Connect to a drone to read and edit spraying parameters.")
                state.isLoading && state.values.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SprayBlue)
                            Spacer(Modifier.height(16.dp))
                            Text("Reading spray parameters…", color = SprayLabel, fontSize = 14.sp)
                        }
                    }
                else -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            "SPRAYING CONFIGURATION",
                            color = SprayTitle, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(16.dp))

                        // Section 1: Enable / RC Switch / BRD PWM Count
                        SectionCard(
                            SpraySettingsViewModel.fields.subList(0, 3),
                            state, { displayText(it) }, changes
                        ) { p, v -> editTexts[p] = v }

                        Spacer(Modifier.height(14.dp))

                        // Section 2: Servo9 (AUX1) function + PWM min/max
                        SectionCard(
                            SpraySettingsViewModel.fields.subList(3, 6),
                            state, { displayText(it) }, changes
                        ) { p, v -> editTexts[p] = v }

                        Spacer(Modifier.height(14.dp))

                        // Section 3: Servo10 (AUX2) function + PWM min/max
                        SectionCard(
                            SpraySettingsViewModel.fields.subList(6, 9),
                            state, { displayText(it) }, changes
                        ) { p, v -> editTexts[p] = v }

                        state.errorMessage?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = SprayRed, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(20.dp))

                        // Update button (mockup: bottom-right)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = { focus.clearFocus(); showConfirm = true },
                                enabled = changes.isNotEmpty() && !state.isWriting,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SprayBlue,
                                    disabledContainerColor = SprayBlue.copy(alpha = 0.4f)
                                ),
                                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 12.dp)
                            ) {
                                if (state.isWriting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Update", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        if (changes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${changes.size} unsaved change${if (changes.size > 1) "s" else ""}",
                                color = SprayAmber, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // ── Confirmation dialog ──────────────────────────────────────────
    if (showConfirm) {
        ConfirmSprayDialog(
            changes = changes,
            currentValues = state.values,
            onConfirm = { showConfirm = false; viewModel.writeParams(changes) },
            onDismiss = { showConfirm = false }
        )
    }

    // ── Reboot prompt ────────────────────────────────────────────────
    if (showReboot) {
        AlertDialog(
            onDismissRequest = { showReboot = false },
            containerColor = SprayCard,
            icon = { Icon(Icons.Filled.RestartAlt, null, tint = SprayBlue) },
            title = { Text("Reboot autopilot?", color = SprayValueText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Parameters were updated. A reboot may be required for some changes (servo " +
                        "functions, PWM ranges) to take full effect.",
                    color = SprayLabel, fontSize = 13.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReboot = false
                        viewModel.rebootDrone()
                        Toast.makeText(ctx, "🔄 Reboot command sent", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SprayBlue)
                ) { Text("Reboot now") }
            },
            dismissButton = {
                TextButton(onClick = { showReboot = false }) { Text("Later", color = SprayBlue) }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ── A white card holding up to 3 fields in a row (wraps on narrow screens) ──
@Composable
private fun SectionCard(
    fields: List<SprayField>,
    state: SpraySettingsState,
    displayText: (String) -> String,
    changes: Map<String, Float>,
    onChange: (String, String) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SprayCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SprayBorder),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            fields.forEach { f ->
                Box(Modifier.weight(1f)) {
                    SprayFieldView(
                        field = f,
                        value = displayText(f.param),
                        isLoaded = state.values.containsKey(f.param),
                        isDirty = f.param in changes,
                        onChange = { onChange(f.param, it) }
                    )
                }
            }
        }
    }
}

// ── One labelled field: dropdown for enums, numeric stepper otherwise ──
@Composable
private fun SprayFieldView(
    field: SprayField,
    value: String,
    isLoaded: Boolean,
    isDirty: Boolean,
    onChange: (String) -> Unit
) {
    val focus = LocalFocusManager.current

    Column {
        Text(field.label, color = SprayLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))

        if (field.options.isNotEmpty()) {
            // Dropdown (e.g. Enable Spraying)
            EnumDropdown(field.options, value, isLoaded) { onChange(it) }
        } else {
            // Numeric text field with up/down steppers
            val borderColor = if (isDirty) SprayAmber else SprayFieldBorder
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .background(Color.White),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { new ->
                        // Allow digits, one optional leading minus, and a decimal point
                        if (new.isEmpty() || new.matches(Regex("-?\\d*\\.?\\d*"))) onChange(new)
                    },
                    enabled = isLoaded,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (isDirty) SprayAmber else SprayValueText,
                        fontSize = 16.sp,
                        fontWeight = if (isDirty) FontWeight.Bold else FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(SprayBlue),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                )
                // Steppers
                Column {
                    StepperButton(Icons.Filled.KeyboardArrowUp, enabled = isLoaded) {
                        val cur = value.trim().toFloatOrNull() ?: field.min
                        onChange(fmtSpray((cur + 1f).coerceIn(field.min, field.max)))
                    }
                    StepperButton(Icons.Filled.KeyboardArrowDown, enabled = isLoaded) {
                        val cur = value.trim().toFloatOrNull() ?: field.min
                        onChange(fmtSpray((cur - 1f).coerceIn(field.min, field.max)))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "[Min ${fmtSpray(field.min)} - Max ${fmtSpray(field.max)}]",
            color = SprayHint, fontSize = 11.sp
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(width = 34.dp, height = 24.dp)
            .background(Color(0xFFF1F4F8))
            .border(0.5.dp, SprayFieldBorder)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = SprayLabel, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EnumDropdown(
    options: Map<Int, String>,
    value: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentInt = value.trim().toFloatOrNull()?.toInt()
    val label = options[currentInt] ?: value.ifBlank { "—" }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, SprayFieldBorder, RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = SprayValueText, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null, tint = SprayLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, color = SprayValueText) },
                    onClick = { expanded = false; onSelect(key.toString()) }
                )
            }
        }
    }
}

// ── Confirmation dialog listing every staged change ───────────────────
@Composable
private fun ConfirmSprayDialog(
    changes: Map<String, Float>,
    currentValues: Map<String, Float>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SprayCard,
        title = {
            Text(
                "Are you sure you want to change the following parameters?",
                color = SprayValueText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp
            )
        },
        text = {
            Column {
                changes.forEach { (name, newVal) ->
                    val old = currentValues[name]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SprayScreenBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name, color = SprayBlue, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            old?.let { fmtSpray(it) } ?: "—",
                            color = SprayLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                        Text("  →  ", color = SprayLabel, fontSize = 12.sp)
                        Text(
                            fmtSpray(newVal), color = SprayAmber, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = SprayGreen)) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("OK, Update")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SprayLabel) } },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun InfoCenterSpray(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = SprayValueText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle, color = SprayLabel, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 19.sp
            )
        }
    }
}
