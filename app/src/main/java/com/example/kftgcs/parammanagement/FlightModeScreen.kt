package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────
// Palette (mirrors the app-wide dark theme)
// ─────────────────────────────────────────────────────────────────────
private val FmBg        = Color(0xFF0D1B4B)
private val FmTopBar    = Color(0xFF1A237E)
private val FmCard      = Color(0xFF1E2D6B)
private val FmCardAlt   = Color(0xFF162356)
private val FmAccent    = Color(0xFF3A6BD5)
private val FmAccentLt  = Color(0xFF87CEEB)
private val FmGreen     = Color(0xFF38A169)
private val FmRed       = Color(0xFFE53935)
private val FmAmber     = Color(0xFFED8936)
private val FmTextW     = Color.White
private val FmTextMuted = Color.White.copy(alpha = 0.55f)
private val FmDivider   = Color.White.copy(alpha = 0.12f)

// ─────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightModeScreen(
    navController: NavController,
    viewModel: FlightModeViewModel
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            Toast.makeText(ctx, "✅ $it", Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(ctx, "⚠ $it", Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Flight Modes", color = FmTextW, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "FLTMODE1–6 → RC switch mapping",
                            color = FmAccentLt, fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FmTextW)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadFromDrone() },
                        enabled = state.isDroneConnected && !state.isLoading
                    ) {
                        Icon(
                            Icons.Filled.Download, "Read from drone",
                            tint = if (state.isDroneConnected && !state.isLoading) FmTextW
                            else FmTextW.copy(alpha = 0.35f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FmTopBar)
            )
        },
        containerColor = FmBg
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Info banner ──────────────────────────────────────────
            InfoBanner()

            // ── Loading indicator ────────────────────────────────────
            if (state.isLoading) {
                LoadingRow()
            }

            // ── Not connected warning ────────────────────────────────
            if (!state.isDroneConnected) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = FmRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Drone not connected. Connect first to read/write flight modes.",
                            color = FmRed, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }

            // ── Prompt to load ───────────────────────────────────────
            if (!state.isLoading && state.modes.all { it == null } && state.isDroneConnected) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = FmCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.FlightTakeoff, null, tint = FmAccentLt, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Tap ↓ to read current flight modes from drone",
                            color = FmTextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.loadFromDrone() },
                            colors = ButtonDefaults.buttonColors(containerColor = FmAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Read from Drone")
                        }
                    }
                }
            }

            // ── 6 slot cards ─────────────────────────────────────────
            if (state.modes.any { it != null } || !state.isDroneConnected) {
                repeat(6) { idx ->
                    FlightModeSlotCard(
                        slotNumber = idx + 1,
                        pwmLabel = SLOT_PWM_LABELS[idx],
                        currentModeKey = state.modes[idx],
                        isSaving = state.isSaving && state.savingSlot == idx,
                        onSave = { modeKey -> viewModel.saveSlot(idx, modeKey) },
                        cardColor = if (idx % 2 == 0) FmCard else FmCardAlt
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Info banner
// ─────────────────────────────────────────────────────────────────────
@Composable
private fun InfoBanner() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = FmAccent.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("RC Channel Switch → Flight Mode", color = FmAccentLt,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "ArduCopter reads a single RC channel (typically Ch5 or Ch6) and maps " +
                "PWM ranges to 6 flight mode slots. Use the dropdowns below to assign a " +
                "mode to each slot, then tap Save.",
                color = FmTextW.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 15.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Loading row
// ─────────────────────────────────────────────────────────────────────
@Composable
private fun LoadingRow() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = FmCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = FmAccentLt,
                strokeWidth = 2.5.dp
            )
            Text("Reading flight modes from drone…", color = FmTextW, fontSize = 13.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Per-slot card
// ─────────────────────────────────────────────────────────────────────
@Composable
private fun FlightModeSlotCard(
    slotNumber: Int,
    pwmLabel: String,
    currentModeKey: Int?,
    isSaving: Boolean,
    onSave: (Int) -> Unit,
    cardColor: Color
) {
    // local selection — initialised once loaded
    var selectedKey by remember(currentModeKey) {
        mutableStateOf(currentModeKey ?: ALLOWED_FLIGHT_MODES.first().key)
    }
    val isDirty = currentModeKey != null && selectedKey != currentModeKey

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // ── Slot header ───────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Slot badge
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FmAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$slotNumber", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Flight Mode $slotNumber",
                        color = FmTextW, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Text(pwmLabel, color = FmAccentLt, fontSize = 11.sp)
                }
                // Status dot
                if (currentModeKey != null) {
                    val dotColor = if (isDirty) FmAmber else FmGreen
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(dotColor)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = FmDivider)
            Spacer(Modifier.height(12.dp))

            // ── Dropdown row ──────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    FlightModeDropdown(
                        selectedKey = selectedKey,
                        onSelect = { selectedKey = it }
                    )
                }

                // Save button — only when dirty
                if (isDirty && !isSaving) {
                    Button(
                        onClick = { onSave(selectedKey) },
                        colors = ButtonDefaults.buttonColors(containerColor = FmGreen),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = FmAccentLt,
                        strokeWidth = 2.5.dp
                    )
                }
            }

            // ── "Unsaved" hint ────────────────────────────────────────
            if (isDirty && !isSaving) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Unsaved change — tap Save to write to drone",
                    color = FmAmber, fontSize = 10.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Mode dropdown (restricted to allowed modes)
// ─────────────────────────────────────────────────────────────────────
@Composable
private fun FlightModeDropdown(
    selectedKey: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = ALLOWED_FLIGHT_MODES.find { it.key == selectedKey }?.label
        ?: "Mode $selectedKey"

    Box {
        // Trigger row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, FmAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedLabel,
                color = FmTextW, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = FmAccentLt)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF1A237E), RoundedCornerShape(12.dp))
                .border(0.5.dp, FmAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            ALLOWED_FLIGHT_MODES.forEach { mode ->
                val isSel = mode.key == selectedKey
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${mode.key}",
                                fontSize = 11.sp,
                                color = if (isSel) Color.White else FmAccentLt,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSel) FmAccent else FmAccent.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                mode.label, fontSize = 13.sp,
                                color = if (isSel) FmAccentLt else FmTextW,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    },
                    onClick = {
                        onSelect(mode.key)
                        expanded = false
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) FmAccent.copy(alpha = 0.18f) else Color.Transparent)
                )
            }
        }

        // Full-width click target to open
        Surface(
            modifier = Modifier.matchParentSize(),
            color = Color.Transparent,
            onClick = { expanded = !expanded }
        ) {}
    }
}








