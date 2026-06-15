package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ── Palette (matches the dark param-management theme) ─────────────────
private val ScreenBg    = Color(0xFF0D1B4B)
private val TopBarBg     = Color(0xFF1A237E)
private val CardBg       = Color(0xFF16205C)
private val CardBorder   = Color(0xFF2A3A7A)
private val FieldBg      = Color(0xFF0B1442)
private val Accent       = Color(0xFF5B8DEF)
private val AccentLight   = Color(0xFF87CEEB)
private val Amber         = Color(0xFFED8936)
private val GreenOk       = Color(0xFF48BB78)
private val RedErr        = Color(0xFFE57373)
private val TextDim       = Color(0xB3FFFFFF)
private val TextMuted     = Color(0x80FFFFFF)

private fun fmtBrake(value: Float): String {
    if (value == value.toLong().toFloat() && value >= -999999 && value <= 999999)
        return value.toLong().toString()
    return "%.4f".format(value).trimEnd('0').trimEnd('.')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakingSettingsScreen(
    navController: NavController,
    viewModel: BrakingSettingsViewModel
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current

    // User-typed overrides keyed by param name. A param with no entry here simply
    // displays its live on-drone value; an entry that differs is a staged change.
    val editTexts: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }

    // Which params have a valid, changed value staged.
    val changes: Map<String, Float> = remember(editTexts.toMap(), state.values) {
        BrakingSettingsViewModel.paramNames.mapNotNull { name ->
            val current = state.values[name] ?: return@mapNotNull null
            val typed = editTexts[name]?.trim()?.toFloatOrNull() ?: return@mapNotNull null
            if (typed != current) name to typed else null
        }.toMap()
    }

    var showConfirm by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }

    // After a batch write completes, drop overrides for written params (so the
    // field falls back to the confirmed live value) and prompt for a reboot.
    LaunchedEffect(state.writeResults) {
        val results = state.writeResults ?: return@LaunchedEffect
        results.filter { it.success }.forEach { editTexts.remove(it.name) }
        val ok = results.count { it.success }
        val failed = results.count { !it.success }
        if (failed == 0) {
            Toast.makeText(ctx, "✅ $ok parameter(s) written", Toast.LENGTH_SHORT).show()
            showReboot = true
        } else {
            Toast.makeText(ctx, "⚠ $ok written, $failed failed", Toast.LENGTH_LONG).show()
            if (ok > 0) showReboot = true
        }
        viewModel.clearWriteResults()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Braking Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Loiter & PosHold brake tuning",
                            color = AccentLight, fontSize = 11.sp, letterSpacing = 0.3.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchBrakeParams() },
                        enabled = state.isDroneConnected && !state.isLoading
                    ) {
                        Icon(
                            Icons.Filled.Refresh, "Reload",
                            tint = if (state.isDroneConnected && !state.isLoading) Color.White
                            else Color.White.copy(alpha = 0.35f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarBg)
            )
        },
        bottomBar = {
            if (changes.isNotEmpty()) {
                Surface(color = TopBarBg, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${changes.size} unsaved change${if (changes.size > 1) "s" else ""}",
                            color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { focus.clearFocus(); showConfirm = true },
                            enabled = !state.isWriting,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            if (state.isWriting) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Save Changes", fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        containerColor = ScreenBg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.isDroneConnected && state.values.isEmpty() ->
                    InfoCenter("⚠ Drone not connected", "Connect to a drone to read and edit brake parameters.")
                state.isLoading && state.values.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(Modifier.height(16.dp))
                            Text("Reading brake parameters…", color = TextDim, fontSize = 14.sp)
                        }
                    }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "Tune how the aircraft decelerates and holds position. " +
                                    "Edit a value, then tap Save Changes to review and write.",
                                color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp
                            )
                        }
                        items(BrakingSettingsViewModel.paramNames, key = { it }) { name ->
                            val meta = state.metadata[name] ?: ParamMeta()
                            val current = state.values[name]
                            ParamCard(
                                name = name,
                                meta = meta,
                                current = current,
                                editText = editTexts[name] ?: current?.let { fmtBrake(it) } ?: "",
                                isDirty = name in changes,
                                onChange = { editTexts[name] = it }
                            )
                        }
                        state.errorMessage?.let { err ->
                            item {
                                Text(err, color = RedErr, fontSize = 12.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }

    // ── Confirmation dialog ──────────────────────────────────────────
    if (showConfirm) {
        ConfirmChangesDialog(
            changes = changes,
            currentValues = state.values,
            onConfirm = {
                showConfirm = false
                viewModel.writeParams(changes)
            },
            onDismiss = { showConfirm = false }
        )
    }

    // ── Reboot prompt ────────────────────────────────────────────────
    if (showReboot) {
        AlertDialog(
            onDismissRequest = { showReboot = false },
            containerColor = CardBg,
            icon = { Icon(Icons.Filled.RestartAlt, null, tint = AccentLight) },
            title = { Text("Reboot autopilot?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Parameters were written. A reboot may be required for some changes to " +
                        "take full effect and to refresh any dependent parameters.",
                    color = TextDim, fontSize = 13.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReboot = false
                        viewModel.rebootDrone()
                        Toast.makeText(ctx, "🔄 Reboot command sent", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Reboot now") }
            },
            dismissButton = {
                TextButton(onClick = { showReboot = false }) {
                    Text("Later", color = AccentLight)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ── Confirmation dialog listing every staged change ───────────────────
@Composable
private fun ConfirmChangesDialog(
    changes: Map<String, Float>,
    currentValues: Map<String, Float>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = {
            Text(
                "Are you sure you want to change the following parameters?",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp
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
                            .background(FieldBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name, color = AccentLight, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            old?.let { fmtBrake(it) } ?: "—",
                            color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                        Text("  →  ", color = TextMuted, fontSize = 12.sp)
                        Text(
                            fmtBrake(newVal), color = Amber, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("OK, Write")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// ── Single parameter card ─────────────────────────────────────────────
@Composable
private fun ParamCard(
    name: String,
    meta: ParamMeta,
    current: Float?,
    editText: String,
    isDirty: Boolean,
    onChange: (String) -> Unit
) {
    val focus = LocalFocusManager.current
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (isDirty) Amber.copy(alpha = 0.6f) else CardBorder
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    name, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (meta.units.isNotEmpty()) {
                    Text(
                        meta.units, color = AccentLight, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Accent.copy(alpha = 0.18f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            if (meta.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta.description, color = TextDim, fontSize = 12.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (meta.range.isNotEmpty()) {
                        Text("Range: ${meta.range}", color = TextMuted, fontSize = 11.sp)
                    }
                    if (current != null) {
                        Text(
                            "Current: ${fmtBrake(current)}", color = TextMuted, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text("Current: reading…", color = TextMuted, fontSize = 11.sp)
                    }
                }

                // Editable value field
                Box(
                    Modifier
                        .width(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FieldBg)
                        .border(
                            1.dp,
                            if (isDirty) Amber.copy(alpha = 0.8f) else CardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = editText,
                        onValueChange = onChange,
                        enabled = current != null,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (isDirty) Amber else Color.White,
                            fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (isDirty) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(AccentLight),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ── Centered info / empty state ───────────────────────────────────────
@Composable
private fun InfoCenter(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
                lineHeight = 19.sp)
        }
    }
}
