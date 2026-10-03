package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────
// Palette
// ─────────────────────────────────────────────────────────────────────
private val BmBg        = Color(0xFF0D1B4B)
private val BmTopBar    = Color(0xFF1A237E)
private val BmCard      = Color(0xFF1E2D6B)
private val BmAccent    = Color(0xFF3A6BD5)
private val BmAccentLt  = Color(0xFF87CEEB)
private val BmGreen     = Color(0xFF38A169)
private val BmTextW     = Color.White
private val BmTextMuted = Color.White.copy(alpha = 0.55f)
private val BmFieldBg   = Color.White.copy(alpha = 0.08f)

// ─────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryMonitorScreen(
    navController: NavController,
    viewModel: BatteryMonitorViewModel
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current

    // Auto-load params from drone on connect
    LaunchedEffect(state.isDroneConnected) {
        if (state.isDroneConnected && state.loadedVoltMult == null && !state.isLoading) {
            viewModel.loadFromDrone()
        }
    }

    // Toast feedback
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            Toast.makeText(ctx, "✅ $it", Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(ctx, "⚠ $it", Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Battery", color = BmTextW, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BmTextW)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BmTopBar)
            )
        },
        containerColor = BmBg
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Voltage input ──────────────────────────────────────────
            LabeledNumberField(
                label = "Measured Battery Voltage",
                suffix = "V",
                value = state.measuredVoltageInput,
                onValueChange = viewModel::updateMeasuredVoltageInput,
                keyboardType = KeyboardType.Decimal,
                helper = "Read with a multimeter at the battery terminals"
            )

            // ── Calculated new BATT_VOLT_MULT ─────────────────────────
            val calc = state.calculatedVoltageDivider
            CalculatedCard("Calculated BATT_VOLT_MULT", calc)

            // ── Save button ───────────────────────────────────────────
            SaveButton(
                text = "Save BATT_VOLT_MULT to Drone",
                isSaving = state.isSaving,
                enabled = state.isDroneConnected && !state.isSaving && !state.isLoading && calc != null,
                onClick = {
                    viewModel.saveToDrone(
                        monitor     = null,
                        capacityMah = null,
                        voltMult    = calc,
                        ampPerVolt  = null,
                        voltPin     = null,
                        currPin     = null
                    )
                }
            )

            // ── Hint when drone not connected ─────────────────────────
            if (!state.isDroneConnected) {
                Text(
                    "⚠ Connect to drone to enable saving",
                    color = BmTextMuted, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // ── Hint when calc not yet possible ───────────────────────
            if (state.isDroneConnected && calc == null && state.measuredVoltageInput.isNotBlank()) {
                Text(
                    "Waiting for live voltage telemetry and loaded BATT_VOLT_MULT…",
                    color = BmTextMuted, fontSize = 11.sp
                )
            }

            // ── Current calibration (charger method) ──────────────────
            HorizontalDivider(color = BmTextMuted.copy(alpha = 0.3f))
            Text("Current Calibration", color = BmTextW, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "1. Start with a fully charged pack and fly (hover is fine) for most of it.\n" +
                "2. Before unplugging, note the FC's consumed mAh below (or CurrTot from the log).\n" +
                "3. Charge back to full and note the mAh your charger reports.",
                color = BmTextMuted, fontSize = 12.sp
            )

            LabeledNumberField(
                label = "Consumed mAh (FC / log)",
                suffix = "mAh",
                value = state.loggedMahInput,
                onValueChange = viewModel::updateLoggedMahInput,
                keyboardType = KeyboardType.Decimal,
                helper = "FC counter now: ${state.consumedMahLive?.let { "$it mAh" } ?: "—"}"
            )
            LabeledNumberField(
                label = "Charged mAh (from charger)",
                suffix = "mAh",
                value = state.chargedMahInput,
                onValueChange = viewModel::updateChargedMahInput,
                keyboardType = KeyboardType.Decimal,
                helper = "Current BATT_AMP_PERVLT: ${state.loadedAmpPerVolt?.let { "%.3f".format(it) } ?: "—"}"
            )

            val ampCalc = state.calculatedAmpPerVolt
            CalculatedCard("Calculated BATT_AMP_PERVLT", ampCalc)
            state.ampCalRejectReason?.let {
                Text("⚠ $it", color = BmTextMuted, fontSize = 11.sp)
            }

            SaveButton(
                text = "Save BATT_AMP_PERVLT to Drone",
                isSaving = state.isSaving,
                enabled = state.isDroneConnected && !state.isSaving && !state.isLoading && ampCalc != null,
                onClick = {
                    viewModel.saveToDrone(
                        monitor     = null,
                        capacityMah = null,
                        voltMult    = null,
                        ampPerVolt  = ampCalc,
                        voltPin     = null,
                        currPin     = null
                    )
                }
            )
        }
    }
}

@Composable
private fun CalculatedCard(label: String, value: Float?) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BmCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = BmAccentLt, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    value?.let { "%.6f".format(it) } ?: "—",
                    color = BmTextW, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (value != null) {
                Text("ready to save", color = BmGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SaveButton(text: String, isSaving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = BmGreen,
            disabledContainerColor = BmAccent.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
            Spacer(Modifier.width(10.dp))
            Text("Saving…", fontWeight = FontWeight.SemiBold)
        } else {
            Icon(Icons.Filled.Save, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Building blocks
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun LabeledNumberField(
    label: String,
    suffix: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    helper: String? = null
) {
    Column {
        Text(label, color = BmAccentLt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BmFieldBg)
                .border(1.5.dp, BmAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = TextStyle(fontSize = 18.sp, color = BmTextW),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = BmTextW,
                    unfocusedTextColor = BmTextW,
                    cursorColor = BmAccentLt
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            )
            Text(
                suffix, color = BmTextMuted, fontSize = 15.sp,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(helper, color = BmTextMuted, fontSize = 12.sp)
        }
    }
}
