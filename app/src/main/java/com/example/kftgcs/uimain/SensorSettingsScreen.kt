package com.example.kftgcs.uimain

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.kftgcs.telemetry.SharedViewModel
import java.util.Locale
import kotlin.math.roundToInt

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)
private val CardBorder = Color(0xFF4A5568)
private const val STEP_M = 0.5f
private const val MIN_M = 0.5f
private const val MAX_M = 50f

/**
 * Lets the pilot view and override the proximity-radar colour thresholds. Values are seeded from the
 * vehicle's AVOID_DIST_MAX / AVOID_MARGIN on connect; editing here sets a local override (persisted
 * via UserSettingsManager) that survives reconnects until "Reset to vehicle defaults" is tapped.
 */
@Composable
fun SensorSettingsScreen(
    navController: NavHostController,
    sharedViewModel: SharedViewModel
) {
    val thresholds by sharedViewModel.radarThresholds.collectAsState()

    // Local edit state; re-synced whenever the persisted thresholds change (save / seed / reset).
    var caution by remember { mutableFloatStateOf(thresholds.cautionM) }
    var critical by remember { mutableFloatStateOf(thresholds.criticalM) }
    LaunchedEffect(thresholds) {
        caution = thresholds.cautionM
        critical = thresholds.criticalM
    }

    val overridden = sharedViewModel.isRadarThresholdsOverridden()
    val dirty = caution != thresholds.cautionM || critical != thresholds.criticalM

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
                Text("Sensor Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { navController.navigate("main") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Home, contentDescription = "Go to Home", tint = Accent, modifier = Modifier.size(32.dp))
                }
            }
            HorizontalDivider(color = Accent, thickness = 1.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))

            Text(
                text = "Proximity Radar Thresholds",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (overridden)
                    "Using your local override. Reset to follow the vehicle's AVOID_DIST_MAX / AVOID_MARGIN."
                else
                    "Seeded from the vehicle (AVOID_DIST_MAX / AVOID_MARGIN). Editing sets a local override.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))

            ThresholdControl(
                label = "Radar Caution Threshold (m)",
                helper = "Sectors closer than this turn yellow.",
                value = caution,
                // Caution must stay at or above Critical; keep a non-zero span for the slider.
                valueRange = critical.coerceAtMost(MAX_M - STEP_M)..MAX_M,
                onValueChange = { caution = it.coerceAtLeast(critical) }
            )

            Spacer(Modifier.height(24.dp))

            ThresholdControl(
                label = "Radar Critical Threshold (m)",
                helper = "Sectors closer than this turn red.",
                value = critical,
                // Critical must stay at or below Caution; keep a non-zero span for the slider.
                valueRange = MIN_M..caution.coerceAtLeast(MIN_M + STEP_M),
                onValueChange = { critical = it.coerceAtMost(caution) }
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { sharedViewModel.setRadarThresholds(caution, critical) },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Override", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { sharedViewModel.resetRadarThresholdsToVehicle() },
                enabled = overridden,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Reset to Vehicle Defaults", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ThresholdControl(
    label: String,
    helper: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2E33), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(helper, color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(icon = Icons.Filled.Remove, description = "Decrease") {
                onValueChange((value - STEP_M).coerceIn(valueRange))
            }
            Text(
                text = String.format(Locale.US, "%.1f m", value),
                color = Accent,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            StepButton(icon = Icons.Filled.Add, description = "Increase") {
                onValueChange((value + STEP_M).coerceIn(valueRange))
            }
        }

        Slider(
            value = value,
            onValueChange = { onValueChange(roundToStep(it).coerceIn(valueRange)) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = CardBorder
            )
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, CardBorder),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/** Round a raw slider value to the nearest STEP_M increment. */
private fun roundToStep(raw: Float): Float = (raw / STEP_M).roundToInt() * STEP_M
