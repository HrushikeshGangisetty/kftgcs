package com.example.kftgcs.ui.replay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.loganalysis.model.ReplayFrame
import java.util.Locale

private val CardBg = Color(0xFF2A2E33)
private val CardBorder = Color(0xFF4A5568)
private val Accent = Color(0xFF87CEEB)

/**
 * Text dashboard of the current frame's telemetry — the fields a pilot needs for crash-log analysis:
 * altitude, speed, voltage/current, GPS satellites and HDOP, and the per-motor ESC PWM outputs.
 *
 * Reads [frameProvider] in its composition scope, so this small composable is the only thing that
 * recomposes per playback tick — it is isolated from the artificial horizon and the map. Scrolls
 * vertically so all fields fit on smaller panels.
 */
@Composable
fun TelemetryOverlay(
    frameProvider: () -> ReplayFrame,
    modifier: Modifier = Modifier,
    showEsc: Boolean = true
) {
    val frame = frameProvider()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Cell("Alt (rel)", fmt(frame.relAlt, "m"), Modifier.weight(1f))
            Cell("Ground Spd", fmt(frame.groundSpeed, "m/s"), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Cell("Voltage", fmt(frame.volt, "V"), Modifier.weight(1f))
            Cell("Current", fmt(frame.curr, "A"), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Cell("Sats", if (frame.nSats >= 0) frame.nSats.toString() else "—", Modifier.weight(1f))
            Cell("HDOP", fmtPlain(frame.hdop), Modifier.weight(1f))
        }

        if (showEsc && frame.escOutputs.isNotEmpty()) {
            EscRow(frame.escOutputs)
        }

        Cell("Flight Mode", frame.modeName ?: "—", Modifier.fillMaxWidth())
    }
}

/** A compact row of the first few motor/ESC PWM outputs (µs). */
@Composable
private fun EscRow(esc: List<Int>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "ESC outputs (µs)", color = Color.Gray, fontSize = 12.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            esc.take(4).forEachIndexed { i, value ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "M${i + 1}", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = value.toString(),
                        color = Accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
        Text(
            text = value,
            color = Accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Format a value as `%.1f unit`, or "—" when the value is unavailable (NaN). */
private fun fmt(value: Double, unit: String): String =
    if (value.isNaN()) "—" else String.format(Locale.US, "%.1f %s", value, unit)

/** Format a unitless value as `%.2f`, or "—" when unavailable (NaN). */
private fun fmtPlain(value: Double): String =
    if (value.isNaN()) "—" else String.format(Locale.US, "%.2f", value)
