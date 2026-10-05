package com.example.kftgcs.uimain

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.kftgcs.telemetry.SharedViewModel
import kotlinx.coroutines.delay
import java.util.Locale

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)
private val CardBorder = Color(0xFF4A5568)
private val OverLimit = Color(0xFFFF5252)
private val Confirmed = Color(0xFF66BB6A)

/** A change waiting for the user's OK: [warning] is shown in red when it can ground the drone. */
private class PendingChange(val title: String, val message: String, val warning: String? = null, val onConfirm: () -> Unit)

/**
 * Motor Health: the per-ESC high-current / high-temperature alert limits, Hobbywing ESC IDs and
 * settings, plus a read-only live view of every ESC's telemetry (Mission Planner's ESCx_temp /
 * ESCx_curr / ESCx_volt / ESCx_rpm).
 */
@Composable
fun MotorHealthScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel
) {
    val telemetry by sharedViewModel.telemetryState.collectAsState()
    val limitA by sharedViewModel.motorHighCurrentLimitA.collectAsState()
    val limitC by sharedViewModel.motorHighTempLimitC.collectAsState()
    val hobbywingEscs by sharedViewModel.hobbywingEscs.collectAsState()
    val escConfigs by sharedViewModel.hobbywingEscConfigs.collectAsState()
    val escIdStatus by sharedViewModel.escIdStatus.collectAsState()
    val escBusy by sharedViewModel.escBusy.collectAsState()
    var pendingChange by remember { mutableStateOf<PendingChange?>(null) }

    // ESC ID scan runs only while this screen is open and the FC is connected.
    var canBus by remember { mutableStateOf(0) }
    var settingsNode by remember { mutableStateOf(-1) } // ESC picked for the per-ESC settings
    LaunchedEffect(canBus, telemetry.fcuDetected) {
        if (telemetry.fcuDetected) sharedViewModel.startEscIdScan(canBus) else sharedViewModel.stopEscIdScan()
    }
    DisposableEffect(Unit) { onDispose { sharedViewModel.stopEscIdScan() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(24.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Motor Health", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { navController.navigate("main") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Home, contentDescription = "Go to Home", tint = Accent, modifier = Modifier.size(32.dp))
                }
            }
            HorizontalDivider(color = Accent, thickness = 1.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))

            // ── High-current alert limit ──
            LimitEditor(
                title = "Motor High Current",
                description = if (limitA > 0f)
                    String.format(Locale.US, "Alert when any ESC draws more than %.1f A.", limitA)
                else
                    "Alert is off. Set a limit in amps to enable it.",
                label = "Current limit (A), 0 = off",
                limit = limitA,
                onSave = sharedViewModel::setMotorHighCurrentLimitA
            )

            Spacer(Modifier.height(28.dp))

            // ── High-temperature alert limit ──
            LimitEditor(
                title = "Motor High Temperature",
                description = if (limitC > 0f)
                    String.format(Locale.US, "Alert when any ESC is hotter than %.0f °C.", limitC)
                else
                    "Alert is off. Set a limit in °C to enable it.",
                label = "Temperature limit (°C), 0 = off",
                limit = limitC,
                onSave = sharedViewModel::setMotorHighTempLimitC
            )

            Spacer(Modifier.height(28.dp))

            // ── Hobbywing ESC IDs (DroneCAN GUI Tool's Hobbywing ESC panel) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ESC IDs", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                listOf("CAN1", "CAN2").forEachIndexed { i, name ->
                    Button(
                        onClick = { canBus = i },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canBus == i) Accent else CardBorder,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text(name, color = Color.Black) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Motor ID is the motor number the ESC drives (and its ESC number below). " +
                    "Node IDs and motor IDs must each be unique. Disarm before changing.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            if (hobbywingEscs.isEmpty()) {
                Text(
                    text = when {
                        !telemetry.fcuDetected -> "Not connected."
                        telemetry.armed -> "Disarm to scan for ESCs."
                        else -> "Scanning… no Hobbywing ESC has answered on ${if (canBus == 0) "CAN1" else "CAN2"}."
                    },
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    val motorIds = hobbywingEscs.values
                    hobbywingEscs.toSortedMap().forEach { (nodeId, motorId) ->
                        EscIdRow(
                            nodeId = nodeId,
                            motorId = motorId,
                            direction = escConfigs[nodeId]?.ccw?.let { if (it) "CCW" else "CW" },
                            duplicateMotorId = motorIds.count { it == motorId } > 1,
                            enabled = !telemetry.armed && !escBusy,
                            onSet = { newNode, newMotor -> sharedViewModel.setHobbywingEscId(nodeId, newNode, newMotor) }
                        )
                    }
                }

                // ── Per-ESC settings: pick an ESC, then Set one value at a time ──
                Spacer(Modifier.height(20.dp))
                val nodes = hobbywingEscs.keys.sorted()
                val node = settingsNode.takeIf { it in nodes } ?: nodes.first()
                val escName = "Node $node · Motor ${hobbywingEscs[node]}"
                val config = escConfigs[node]
                val canSet = !telemetry.armed && !escBusy
                Text("ESC settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick an ESC. \"On ESC now\" is what the ESC itself reports; a change is only " +
                        "done when that line shows the new value.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    nodes.forEach { n ->
                        Button(
                            onClick = { settingsNode = n },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (n == node) Accent else CardBorder,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Motor ${hobbywingEscs[n]}", color = Color.Black) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(escName, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 6.dp))
                    // "Reading…" must not sit there for ever when the ESC never answers.
                    var readTimedOut by remember(node) { mutableStateOf(false) }
                    LaunchedEffect(node) { delay(8000); readTimedOut = true }
                    val reading = if (readTimedOut) "Unknown: the ESC has not answered the settings read" else "Reading from ESC…"
                    val notReported = "The ESC does not report this value"
                    OptionSetter("Direction", listOf("CW", "CCW"), canSet, node, config?.ccw?.let { if (it) 1 else 0 }, reading) {
                        pendingChange = PendingChange(
                            title = "Change motor direction?",
                            message = "$escName: set direction to ${if (it == 1) "CCW" else "CW"}.",
                            warning = "A motor spinning the wrong way will flip the drone on take-off. " +
                                "Check the direction with propellers removed before flying."
                        ) { sharedViewModel.setHobbywingEscDirection(node, ccw = it == 1) }
                    }
                    OptionSetter("Throttle source", listOf("CAN", "PWM"), canSet, node, config?.pwm?.let { if (it) 1 else 0 }, reading) {
                        pendingChange = PendingChange(
                            title = "Change throttle source?",
                            message = "$escName: take throttle from ${if (it == 1) "PWM" else "CAN"}.",
                            warning = "The motor will not respond unless the flight controller drives it the same way."
                        ) { sharedViewModel.setHobbywingEscThrottleSource(node, pwm = it == 1) }
                    }
                    // Option order = SetBaud's enum values.
                    val bauds = listOf("1000000", "500000", "250000", "200000", "100000", "50000")
                    OptionSetter("Baud rate", bauds, canSet, node, null, notReported) {
                        pendingChange = PendingChange(
                            title = "Change CAN baud rate?",
                            message = "$escName: set baud rate to ${bauds[it]}.",
                            warning = "The ESC drops off this bus until the flight controller's CAN bitrate matches."
                        ) { sharedViewModel.setHobbywingEscBaud(node, it) }
                    }
                    // Option order = SetReportingFrequency's enum values, which start at 1.
                    val rates = listOf("500 Hz", "250 Hz", "200 Hz", "100 Hz", "50 Hz", "20 Hz", "10 Hz", "1 Hz", "Off")
                    (1..3).forEach { msg ->
                        val unknown = if (msg == 3) "Not reported by the ESC until it is set" else reading
                        OptionSetter("Message $msg rate", rates, canSet, node, config?.msgRates?.get(msg)?.minus(1), unknown) {
                            pendingChange = PendingChange(
                                title = "Change message $msg rate?",
                                message = "$escName: send message $msg at ${rates[it]}."
                            ) { sharedViewModel.setHobbywingEscMsgRate(node, msg, it + 1) }
                        }
                    }
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Defaults: throttle source CAN, message 1 at 50 Hz, messages 2 and 3 at 10 Hz.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            enabled = canSet,
                            onClick = {
                                pendingChange = PendingChange(
                                    title = "Reset to defaults?",
                                    message = "$escName: throttle source CAN, message 1 at 50 Hz, messages 2 and 3 " +
                                        "at 10 Hz. Direction, IDs and baud rate are not changed."
                                ) { sharedViewModel.resetHobbywingEscDefaults(node) }
                            }
                        ) { Text("Reset to defaults", color = if (canSet) Color.White else Color.Gray) }
                    }
                }
            }
            if (escIdStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(escIdStatus, color = Accent, fontSize = 13.sp)
            }

            pendingChange?.let { change ->
                AlertDialog(
                    onDismissRequest = { pendingChange = null },
                    containerColor = ScreenBg,
                    title = { Text(change.title, color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(change.message, color = Color.White)
                            change.warning?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = OverLimit)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { pendingChange = null; change.onConfirm() },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                        ) { Text("Confirm", color = Color.Black) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingChange = null }) { Text("Cancel", color = Color.White) }
                    }
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Live ESC telemetry (read-only) ──
            Text("ESC Telemetry", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            val escs = telemetry.escs.values.sortedBy { it.escNumber }
            if (escs.isEmpty()) {
                Text(
                    text = if (telemetry.fcuDetected)
                        "No ESC telemetry received from the flight controller."
                    else
                        "Not connected.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    EscRow(listOf("ESC", "Temp (°C)", "Current (A)", "Voltage (V)", "RPM", "Used (mAh)"), Accent, bold = true)
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 6.dp))
                    escs.forEach { esc ->
                        val over = buildSet {
                            if (limitC > 0f && esc.tempC > limitC) add(1)
                            if (limitA > 0f && esc.currentA > limitA) add(2)
                        }
                        EscRow(
                            cells = listOf(
                                "ESC${esc.escNumber}",
                                esc.tempC.toString(),
                                String.format(Locale.US, "%.2f", esc.currentA),
                                String.format(Locale.US, "%.2f", esc.voltageV),
                                esc.rpm.toString(),
                                esc.consumedMah.toString()
                            ),
                            color = Color.White,
                            highlight = over
                        )
                    }
                }
            }
        }
    }
}

/** Title, description and a "limit + Save" row for one alert limit. Blank or 0 = alert off. */
@Composable
private fun LimitEditor(title: String, description: String, label: String, limit: Float, onSave: (Float) -> Unit) {
    // Local edit text; re-synced when the saved limit changes.
    var text by remember { mutableStateOf("") }
    LaunchedEffect(limit) { text = if (limit > 0f) limit.toString() else "" }
    val parsed = if (text.isBlank()) 0f else text.toFloatOrNull()
    val valid = parsed != null && parsed >= 0f

    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(description, color = Color.Gray, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim() },
            label = { Text(label, color = Color.White) },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Button(
            enabled = valid && parsed != limit,
            onClick = { onSave(parsed ?: 0f) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Save", color = Color.Black) }
    }
}

/** One discovered Hobbywing ESC: editable node ID (1-127) and motor/throttle ID (1-32). */
@Composable
private fun EscIdRow(
    nodeId: Int, motorId: Int, direction: String?, duplicateMotorId: Boolean, enabled: Boolean, onSet: (Int, Int) -> Unit
) {
    var nodeText by remember(nodeId) { mutableStateOf(nodeId.toString()) }
    var motorText by remember(nodeId, motorId) { mutableStateOf(motorId.toString()) }
    val newNode = nodeText.toIntOrNull()?.takeIf { it in 1..126 } // 127 is the GCS itself
    val newMotor = motorText.toIntOrNull()?.takeIf { it in 1..32 }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Node $nodeId · Motor $motorId" + (direction?.let { " · $it" } ?: "") +
                if (duplicateMotorId) " (duplicate)" else "",
            color = if (duplicateMotorId) OverLimit else Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1.4f)
        )
        IdField("Node ID", nodeText, isError = newNode == null) { nodeText = it }
        IdField("Motor ID", motorText, isError = newMotor == null) { motorText = it }
        Button(
            enabled = enabled && newNode != null && newMotor != null && (newNode != nodeId || newMotor != motorId),
            onClick = { onSet(newNode ?: nodeId, newMotor ?: motorId) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Set", color = Color.Black) }
    }
}

/**
 * "label / On ESC now: x  [dropdown] [Set]". [current] is the option index the ESC reports, null
 * when it has not (yet) reported one - [unknownNote] then says why. The dropdown follows
 * [current], and nothing is sent until Set is pressed; [onSet] gets the option index.
 */
@Composable
private fun OptionSetter(
    label: String, options: List<String>, enabled: Boolean, node: Int, current: Int?, unknownNote: String,
    onSet: (Int) -> Unit
) {
    val known = current?.takeIf { it in options.indices }
    var index by remember(node, known) { mutableStateOf(known ?: -1) }
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(
                text = if (known != null) "On ESC now: ${options[known]}" else unknownNote,
                color = if (known != null) Confirmed else Color.Gray,
                fontSize = 13.sp,
                fontWeight = if (known != null) FontWeight.Bold else FontWeight.Normal
            )
        }
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.getOrNull(index) ?: "Select…", color = Color.White) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(ScreenBg)) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(if (i == known) "$option (now)" else option, color = Color.White) },
                        onClick = { index = i; open = false }
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Button(
            enabled = enabled && index >= 0 && index != known,
            onClick = { onSet(index) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Set", color = Color.Black) }
    }
}

@Composable
private fun RowScope.IdField(label: String, value: String, isError: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label, color = Color.White) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(color = Color.White),
        modifier = Modifier.weight(1f).padding(end = 8.dp)
    )
}

@Composable
private fun EscRow(cells: List<String>, color: Color, bold: Boolean = false, highlight: Set<Int> = emptySet()) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                text = cell,
                color = if (i in highlight) OverLimit else color,
                fontSize = 14.sp,
                fontWeight = if (bold || i in highlight) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
