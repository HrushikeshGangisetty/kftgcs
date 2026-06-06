package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────────────
// Palette
// ─────────────────────────────────────────────────────────────────────────────

private val SoBg        = Color(0xFF0D1B4B)
private val SoTopBar    = Color(0xFF1A237E)
private val SoCard      = Color(0xFF1E2D6B)
private val SoAccent    = Color(0xFF3A6BD5)
private val SoWarning   = Color(0xFFFFC107)
private val SoError     = Color(0xFFE53935)
private val SoTextW     = Color.White
private val SoTextMuted = Color.White.copy(alpha = 0.55f)
private val SoFieldBg   = Color.White.copy(alpha = 0.07f)
private val SoArmed     = Color(0xFFE53935)
private val SoDisarmed  = Color(0xFF38A169)
private val SoRowOdd    = Color(0xFF1A2A5E)
private val SoRowEven   = Color(0xFF172459)

// ─────────────────────────────────────────────────────────────────────────────
// Fixed column widths — header and every row reference the same constants so
// columns align even though each row has its own horizontalScroll modifier.
// ─────────────────────────────────────────────────────────────────────────────

private val ColCh  = 40.dp   // channel number
private val ColPos = 156.dp  // live-PWM position bar
private val ColRev = 64.dp   // reversed checkbox
private val ColFn  = 200.dp  // function dropdown
private val ColPwm = 92.dp   // min / trim / max (each)

// Shared cell gap so every column has consistent breathing room.
private val CellGap = 10.dp

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServoOutputScreen(
    navController: NavController,
    viewModel: ServoOutputViewModel
) {
    val channels   by viewModel.servoChannels.collectAsState()
    val vehicle    by viewModel.vehicleState.collectAsState()
    val loadingAll by viewModel.isLoadingAll.collectAsState()
    val uiState    by viewModel.uiState.collectAsState()
    val lastError  by viewModel.repository.lastError.collectAsState()
    val ctx        = LocalContext.current

    LaunchedEffect(vehicle.isConnected) {
        if (vehicle.isConnected && channels.all { it.function == ServoFunction.DISABLED }) {
            viewModel.loadChannels()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            Toast.makeText(ctx, "✅ $it", Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            Toast.makeText(ctx, "⚠ $it", Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(lastError) {
        lastError?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Servo Output", color = SoTextW, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SoTextW)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SoTopBar)
            )
        },
        containerColor = SoBg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            StatusBar(vehicle, loadingAll) { viewModel.loadChannels() }
            if (vehicle.isArmed) ArmedWarningBanner()

            // Both the header row and every channel row share this scroll state
            // so they pan left/right in perfect sync.
            val hScroll = rememberScrollState()

            TableHeader(hScroll)
            HorizontalDivider(color = SoAccent.copy(alpha = 0.4f), thickness = 1.dp)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(channels, key = { it.channelIndex }) { channel ->
                    ServoChannelRow(
                        channel          = channel,
                        hScrollState     = hScroll,
                        onFunctionChange = { fn -> viewModel.setFunction(channel.channelIndex, fn) },
                        onMinChange      = { v  -> viewModel.setMin(channel.channelIndex, v) },
                        onTrimChange     = { v  -> viewModel.setTrim(channel.channelIndex, v) },
                        onMaxChange      = { v  -> viewModel.setMax(channel.channelIndex, v) },
                        onReverseChange  = { r  -> viewModel.setReverse(channel.channelIndex, r) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Table header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TableHeader(hScroll: ScrollState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll)
            .background(SoTopBar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CellGap)
    ) {
        HeaderCell("CH",       ColCh)
        HeaderCell("Position", ColPos)
        HeaderCell("Reverse",  ColRev)
        HeaderCell("Function", ColFn)
        HeaderCell("Min",      ColPwm)
        HeaderCell("Trim",     ColPwm)
        HeaderCell("Max",      ColPwm)
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp) {
    Text(
        text       = label,
        color      = SoTextMuted,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier.width(width)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Channel row  — reusable, one per output channel (CH 1–16)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single horizontal row representing one ArduPilot SERVO output channel,
 * mirroring the Mission Planner "Servo Output" table layout.
 *
 * Parameter bindings:
 *   Position bar  → SERVO_OUTPUT_RAW telemetry  (see [PositionCell] note)
 *   Rev checkbox  → SERVOn_REVERSED  (0 / 1)
 *   Function      → SERVOn_FUNCTION
 *   Min           → SERVOn_MIN
 *   Trim          → SERVOn_TRIM
 *   Max           → SERVOn_MAX
 */
@Composable
fun ServoChannelRow(
    channel:          ServoChannel,
    hScrollState:     ScrollState,
    onFunctionChange: (ServoFunction) -> Unit,
    onMinChange:      (Int) -> Unit,
    onTrimChange:     (Int) -> Unit,
    onMaxChange:      (Int) -> Unit,
    onReverseChange:  (Boolean) -> Unit
) {
    val rowBg = if (channel.channelIndex % 2 == 1) SoRowOdd else SoRowEven

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScrollState)
            .background(rowBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CellGap)
    ) {
        // ── CH number ─────────────────────────────────────────────────────────
        Text(
            text       = "${channel.channelIndex}",
            color      = SoTextW,
            fontSize   = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.width(ColCh)
        )

        // ── Position (live PWM via SERVO_OUTPUT_RAW) ──────────────────────────
        PositionCell(
            livePwm   = channel.livePwm,
            minPwm    = channel.minPwm,
            maxPwm    = channel.maxPwm,
            isLoading = channel.isLoading,
            modifier  = Modifier.width(ColPos)
        )

        // ── Reversed → SERVOn_REVERSED ────────────────────────────────────────
        Box(Modifier.width(ColRev), contentAlignment = Alignment.Center) {
            if (channel.isLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(16.dp),
                    color       = SoAccent,
                    strokeWidth = 2.dp
                )
            } else {
                Checkbox(
                    checked         = channel.reversed,
                    onCheckedChange = onReverseChange,
                    colors          = CheckboxDefaults.colors(
                        checkedColor   = SoAccent,
                        checkmarkColor = SoTextW,
                        uncheckedColor = SoTextMuted
                    )
                )
            }
        }

        // ── Function → SERVOn_FUNCTION ────────────────────────────────────────
        if (channel.isLoading) {
            Box(
                modifier = Modifier.width(ColFn),
                contentAlignment = Alignment.Center
            ) {
                LinearProgressIndicator(
                    modifier   = Modifier.fillMaxWidth().height(6.dp),
                    color      = SoAccent,
                    trackColor = SoFieldBg
                )
            }
        } else {
            CompactFunctionDropdown(
                current  = channel.function,
                onChange = onFunctionChange,
                modifier = Modifier.width(ColFn)
            )
        }

        // ── Min → SERVOn_MIN ──────────────────────────────────────────────────
        CompactPwmField(
            value    = channel.minPwm,
            onSave   = onMinChange,
            enabled  = !channel.isLoading,
            modifier = Modifier.width(ColPwm)
        )

        // ── Trim → SERVOn_TRIM ────────────────────────────────────────────────
        CompactPwmField(
            value    = channel.trimPwm,
            onSave   = onTrimChange,
            enabled  = !channel.isLoading,
            modifier = Modifier.width(ColPwm)
        )

        // ── Max → SERVOn_MAX ──────────────────────────────────────────────────
        CompactPwmField(
            value    = channel.maxPwm,
            onSave   = onMaxChange,
            enabled  = !channel.isLoading,
            modifier = Modifier.width(ColPwm)
        )
    }

    HorizontalDivider(color = SoTextMuted.copy(alpha = 0.1f), thickness = 0.5.dp)
}

// ─────────────────────────────────────────────────────────────────────────────
// Position cell
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays a live progress bar for the current servo PWM value.
 *
 * IMPORTANT: [livePwm] is NOT a parameter-protocol value. It must be supplied
 * by parsing the SERVO_OUTPUT_RAW MAVLink message (high-frequency telemetry
 * stream) and calling ServoOutputViewModel.onServoOutputRawReceived(channel, pwm).
 * The progress fraction is computed relative to [minPwm]..[maxPwm].
 */
@Composable
private fun PositionCell(
    livePwm:  Int?,
    minPwm:   Int,
    maxPwm:   Int,
    isLoading: Boolean,
    modifier:  Modifier = Modifier
) {
    val fraction = if (livePwm != null && !isLoading) {
        val lo = minPwm.toFloat()
        val hi = maxPwm.coerceAtLeast(minPwm + 1).toFloat()
        ((livePwm.toFloat() - lo) / (hi - lo)).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LinearProgressIndicator(
            progress   = { fraction },
            modifier   = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            color      = SoAccent,
            trackColor = SoFieldBg
        )
        Text(
            text  = if (livePwm != null && !isLoading) "$livePwm µs" else "—— µs",
            color = if (livePwm != null && !isLoading) SoAccent else SoTextMuted,
            fontSize = 11.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compact function dropdown
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactFunctionDropdown(
    current:  ServoFunction,
    onChange: (ServoFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier         = modifier
    ) {
        OutlinedTextField(
            value         = "${current.value}: ${current.displayName}",
            onValueChange = {},
            readOnly      = true,
            singleLine    = true,
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle     = LocalTextStyle.current.copy(color = SoTextW, fontSize = 12.sp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor      = SoAccent,
                unfocusedBorderColor    = SoTextMuted.copy(alpha = 0.4f),
                focusedContainerColor   = SoFieldBg,
                unfocusedContainerColor = SoFieldBg
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            modifier         = Modifier.background(SoCard)
        ) {
            ServoFunction.ALL.forEach { fn ->
                DropdownMenuItem(
                    text    = { Text("${fn.value} – ${fn.displayName}", color = SoTextW, fontSize = 12.sp) },
                    onClick = { onChange(fn); expanded = false }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compact PWM number field (saves on keyboard Done action)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CompactPwmField(
    value:    Int,
    onSave:   (Int) -> Unit,
    enabled:  Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focusManager = LocalFocusManager.current
    val parsed  = text.toIntOrNull()
    val isDirty = parsed != null && parsed != value
    val isValid = parsed != null && parsed in ServoRepository.PWM_MIN..ServoRepository.PWM_MAX

    val borderColor = when {
        isDirty && isValid  -> SoWarning
        isDirty && !isValid -> SoError
        else                -> SoTextMuted.copy(alpha = 0.4f)
    }

    OutlinedTextField(
        value         = text,
        onValueChange = { new ->
            if (new.all { it.isDigit() } && new.length <= 4) text = new
        },
        enabled         = enabled,
        singleLine      = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction    = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (isValid) { onSave(parsed!!); focusManager.clearFocus() }
            }
        ),
        textStyle = LocalTextStyle.current.copy(
            color    = when {
                isDirty && isValid  -> SoWarning
                isDirty && !isValid -> SoError
                else                -> SoTextW
            },
            fontSize = 13.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = if (isDirty) borderColor else SoAccent,
            unfocusedBorderColor    = borderColor,
            focusedContainerColor   = SoFieldBg,
            unfocusedContainerColor = SoFieldBg,
            disabledContainerColor  = SoFieldBg.copy(alpha = 0.5f),
            disabledBorderColor     = SoTextMuted.copy(alpha = 0.2f),
            disabledTextColor       = SoTextMuted
        ),
        modifier = modifier.height(54.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Status bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(
    vehicle:   VehicleState,
    loading:   Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SoCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (vehicle.isConnected) SoDisarmed else SoError,
                        shape = RoundedCornerShape(50)
                    )
            )
            Text(
                if (vehicle.isConnected) "Connected" else "Disconnected",
                color    = SoTextW,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (vehicle.isArmed) SoArmed.copy(alpha = 0.25f) else SoDisarmed.copy(alpha = 0.2f)
            ) {
                Text(
                    if (vehicle.isArmed) "ARMED" else "DISARMED",
                    color      = if (vehicle.isArmed) SoArmed else SoDisarmed,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = SoAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onRefresh, enabled = vehicle.isConnected && !loading) {
                Icon(
                    Icons.Filled.Refresh,
                    "Refresh",
                    tint     = if (!loading) SoTextW else SoTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ArmedWarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SoArmed.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Warning, null, tint = SoArmed, modifier = Modifier.size(16.dp))
        Text(
            "Vehicle is ARMED — servo output is under autopilot control.",
            color    = SoArmed,
            fontSize = 12.sp
        )
    }
}
