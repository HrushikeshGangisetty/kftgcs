package com.example.kftgcs.ui.replay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.loganalysis.model.ReplayFrame
import kotlin.math.min

private val CardBg = Color(0xFF2A2E33)
private val StickBg = Color(0xFF1B1E22)
private val StickBorder = Color(0xFF4A5568)
private val Crosshair = Color(0xFF3A4048)
private val Accent = Color(0xFF87CEEB)

/**
 * Two Mode-2 transmitter gimbals reproducing the pilot's stick inputs for the current frame.
 *
 * Left gimbal  = throttle (vertical) + yaw (horizontal).
 * Right gimbal = pitch (vertical) + roll (horizontal).
 *
 * Values are the raw RCIN PWM (µs) held on the [ReplayFrame]; 1000–2000 is normalised to −1..+1 with
 * 1500 as centre. A value of −1 (channel absent in the log) is treated as centred.
 *
 * Performance: [frameProvider] is invoked **inside the [Canvas] draw block** (draw phase), so a
 * playback tick only re-runs this Canvas's draw — it never recomposes and cannot stall the other
 * replay panels (same isolation contract as [ArtificialHorizon]).
 */
@Composable
fun RcStickView(
    frameProvider: () -> ReplayFrame,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "RC sticks (µs)", color = Color.Gray, fontSize = 12.sp)

        Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
            val frame = frameProvider()
            val gap = 12.dp.toPx()
            val side = min((size.width - gap) / 2f, size.height)
            val top = (size.height - side) / 2f
            // Left gimbal: throttle (vertical) + yaw (horizontal).
            drawGimbal(
                origin = Offset(0f, top),
                side = side,
                xPwm = frame.rcYaw,
                yPwm = frame.rcThrottle
            )
            // Right gimbal: pitch (vertical) + roll (horizontal).
            drawGimbal(
                origin = Offset(size.width - side, top),
                side = side,
                xPwm = frame.rcRoll,
                yPwm = frame.rcPitch
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Thr / Yaw", color = Color.Gray, fontSize = 10.sp)
            Text(text = "Pitch / Roll", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

/** Draw one square gimbal with a crosshair and the stick dot at the normalised [xPwm]/[yPwm]. */
private fun DrawScope.drawGimbal(origin: Offset, side: Float, xPwm: Int, yPwm: Int) {
    val corner = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
    drawRoundRect(
        color = StickBg,
        topLeft = origin,
        size = Size(side, side),
        cornerRadius = corner
    )
    drawRoundRect(
        color = StickBorder,
        topLeft = origin,
        size = Size(side, side),
        cornerRadius = corner,
        style = Stroke(width = 2f)
    )

    val cx = origin.x + side / 2f
    val cy = origin.y + side / 2f
    // Crosshair.
    drawLine(Crosshair, Offset(origin.x + side * 0.1f, cy), Offset(origin.x + side * 0.9f, cy), 1.5f)
    drawLine(Crosshair, Offset(cx, origin.y + side * 0.1f), Offset(cx, origin.y + side * 0.9f), 1.5f)

    // Normalise PWM 1000..2000 → −1..+1 (1500 centre); positive Y is "up" on screen.
    val nx = norm(xPwm)
    val ny = norm(yPwm)
    val radius = side / 2f * 0.85f
    val dot = Offset(cx + nx * radius, cy - ny * radius)
    drawCircle(color = Accent, radius = side * 0.09f, center = dot)
}

/** Map a PWM value (µs) to −1..+1; an absent channel (−1) or out-of-range value centres to 0. */
private fun norm(pwm: Int): Float {
    if (pwm < 800) return 0f
    return ((pwm - 1500f) / 500f).coerceIn(-1f, 1f)
}
