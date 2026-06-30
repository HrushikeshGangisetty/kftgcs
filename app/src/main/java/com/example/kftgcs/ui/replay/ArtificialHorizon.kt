package com.example.kftgcs.ui.replay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.example.kftgcs.loganalysis.model.ReplayFrame
import kotlin.math.max

private val SkyColor = Color(0xFF3A7BD5)
private val GroundColor = Color(0xFF8B5A2B)
private val HorizonLine = Color.White
private val Reticle = Color(0xFFFFD54F)

/**
 * Attitude (artificial horizon) indicator showing the drone's roll and pitch for the current frame.
 *
 * Performance: [frameProvider] is invoked **inside the [Canvas] draw block** (the draw phase), not in
 * composition. When the playback index changes, only this Canvas's draw phase re-runs — it never
 * recomposes, and it cannot trigger recomposition of the map or dashboard.
 *
 * The sky/ground/horizon are rotated by `-roll` and shifted by `pitch` so the horizon moves *behind* a
 * fixed aircraft reticle drawn at the absolute centre.
 */
@Composable
fun ArtificialHorizon(
    frameProvider: () -> ReplayFrame,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    ) {
        val frame = frameProvider()
        val roll = frame.roll.takeIf { !it.isNaN() } ?: 0.0
        val pitch = frame.pitch.takeIf { !it.isNaN() } ?: 0.0
        drawHorizon(roll.toFloat(), pitch.toFloat())
    }
}

private fun DrawScope.drawHorizon(rollDeg: Float, pitchDeg: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val pxPerDeg = size.height / 70f          // ±30° comfortably visible
    val pitchPx = pitchDeg * pxPerDeg
    val big = max(size.width, size.height) * 2f

    // Rotate by -roll and translate vertically by pitch so the horizon moves behind the aircraft.
    withTransform({
        rotate(degrees = -rollDeg, pivot = center)
        translate(top = pitchPx)
    }) {
        // Sky (above horizon) and ground (below), oversized so they always fill the rotated view.
        drawRect(
            color = SkyColor,
            topLeft = Offset(center.x - big, center.y - big),
            size = Size(big * 2f, big)
        )
        drawRect(
            color = GroundColor,
            topLeft = Offset(center.x - big, center.y),
            size = Size(big * 2f, big)
        )
        // Horizon line.
        drawLine(
            color = HorizonLine,
            start = Offset(center.x - big, center.y),
            end = Offset(center.x + big, center.y),
            strokeWidth = 3f
        )
        // Pitch ladder: tick marks every 10°, longer at 30°.
        for (deg in intArrayOf(-30, -20, -10, 10, 20, 30)) {
            val y = center.y - deg * pxPerDeg
            val half = if (deg % 30 == 0) size.width * 0.18f else size.width * 0.11f
            drawLine(
                color = HorizonLine,
                start = Offset(center.x - half, y),
                end = Offset(center.x + half, y),
                strokeWidth = 2f
            )
        }
    }

    // Static aircraft reticle (unaffected by roll/pitch) — the horizon moves relative to this.
    val wing = size.width * 0.18f
    val gap = size.width * 0.05f
    drawLine(
        color = Reticle,
        start = Offset(center.x - wing, center.y),
        end = Offset(center.x - gap, center.y),
        strokeWidth = 5f
    )
    drawLine(
        color = Reticle,
        start = Offset(center.x + gap, center.y),
        end = Offset(center.x + wing, center.y),
        strokeWidth = 5f
    )
    drawCircle(color = Reticle, radius = 5f, center = center)
}
