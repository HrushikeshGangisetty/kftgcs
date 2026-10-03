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
import java.util.Locale

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)
private val CardBorder = Color(0xFF4A5568)
private val OverLimit = Color(0xFFFF5252)

/**
 * Motor Health: the per-ESC high-current alert limit, plus a read-only live view of every
 * ESC's telemetry (Mission Planner's ESCx_temp / ESCx_curr / ESCx_volt / ESCx_rpm).
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
    val escIdStatus by sharedViewModel.escIdStatus.collectAsState()

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
                            duplicateMotorId = motorIds.count { it == motorId } > 1,
                            enabled = !telemetry.armed,
                            onSet = { newNode, newMotor -> sharedViewModel.setHobbywingEscId(nodeId, newNode, newMotor) }
                        )
                    }
                }

                // ── Per-ESC settings: pick an ESC, then Set one value at a time ──
                Spacer(Modifier.height(16.dp))
                val nodes = hobbywingEscs.keys.sorted()
                val node = settingsNode.takeIf { it in nodes } ?: nodes.first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ESC settings for", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        var open by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { open = true }) {
                            Text("Node $node · Motor ${hobbywingEscs[node]}", color = Color.White)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(ScreenBg)) {
                            nodes.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text("Node $n · Motor ${hobbywingEscs[n]}", color = Color.White) },
                                    onClick = { settingsNode = n; open = false }
                                )
                            }
                        }
                    }
                }
                Text(
                    "Current values are not read back from the ESC. A baud rate change takes the ESC " +
                        "off this bus until the flight controller's CAN bitrate matches.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                val canSet = !telemetry.armed
                OptionSetter("Direction", listOf("CW", "CCW"), canSet) {
                    sharedViewModel.setHobbywingEscDirection(node, ccw = it == 1)
                }
                OptionSetter("Throttle source", listOf("CAN", "PWM"), canSet) {
                    sharedViewModel.setHobbywingEscThrottleSource(node, pwm = it == 1)
                }
                // Option order = SetBaud's enum values.
                OptionSetter("Baud rate", listOf("1000000", "500000", "250000", "200000", "100000", "50000"), canSet) {
                    sharedViewModel.setHobbywingEscBaud(node, it)
                }
                // Option order = SetReportingFrequency's enum values, which start at 1.
                val rates = listOf("500 Hz", "250 Hz", "200 Hz", "100 Hz", "50 Hz", "20 Hz", "10 Hz", "1 Hz", "Off")
                (1..3).forEach { msg ->
                    // Defaults as in the GUI tool: 50 Hz for message 1, 10 Hz for 2 and 3.
                    OptionSetter("Message $msg rate", rates, canSet, initial = if (msg == 1) 4 else 6) {
                        sharedViewModel.setHobbywingEscMsgRate(node, msg, it + 1)
                    }
                }
            }
            if (escIdStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(escIdStatus, color = Accent, fontSize = 13.sp)
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
private fun EscIdRow(nodeId: Int, motorId: Int, duplicateMotorId: Boolean, enabled: Boolean, onSet: (Int, Int) -> Unit) {
    var nodeText by remember(nodeId) { mutableStateOf(nodeId.toString()) }
    var motorText by remember(nodeId, motorId) { mutableStateOf(motorId.toString()) }
    val newNode = nodeText.toIntOrNull()?.takeIf { it in 1..126 } // 127 is the GCS itself
    val newMotor = motorText.toIntOrNull()?.takeIf { it in 1..32 }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Node $nodeId · Motor $motorId" + if (duplicateMotorId) " (duplicate)" else "",
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

/** "label [dropdown] [Set]": nothing is sent until Set is pressed; [onSet] gets the option index. */
@Composable
private fun OptionSetter(label: String, options: List<String>, enabled: Boolean, initial: Int = 0, onSet: (Int) -> Unit) {
    var index by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options[index], color = Color.White) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(ScreenBg)) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, color = Color.White) },
                        onClick = { index = i; open = false }
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Button(
            enabled = enabled,
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
