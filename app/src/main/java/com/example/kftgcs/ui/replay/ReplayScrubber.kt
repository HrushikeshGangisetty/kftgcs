package com.example.kftgcs.ui.replay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.loganalysis.model.ReplayFrame
import java.util.Locale

private val Accent = Color(0xFF87CEEB)
private val InactiveTrack = Color(0xFF30363D)

/**
 * Playback transport: play/pause toggle, a scrub [Slider] bound to the controller's index, and a
 * relative `MM:SS` timestamp. Reads `controller.currentIndex` in this composable's own scope, so it is
 * the only thing (besides the dashboard and marker) that recomposes per tick.
 */
@Composable
fun ReplayScrubber(
    controller: ReplayController,
    frames: List<ReplayFrame>,
    modifier: Modifier = Modifier
) {
    val index = controller.currentIndex.coerceIn(0, frames.lastIndex.coerceAtLeast(0))
    val lastIndex = frames.lastIndex.coerceAtLeast(0)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = { controller.togglePlay() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (controller.isPlaying) "Pause" else "Play",
                tint = Accent,
                modifier = Modifier.size(36.dp)
            )
        }

        Slider(
            value = index.toFloat(),
            onValueChange = {
                controller.isPlaying = false
                controller.seekTo(it.toInt())
            },
            valueRange = 0f..lastIndex.coerceAtLeast(1).toFloat(),
            enabled = frames.size > 1,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = InactiveTrack
            )
        )

        Spacer(Modifier.width(4.dp))
        Text(
            text = relativeTimestamp(frames, index),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** `MM:SS` elapsed from the first frame's `TimeUS`, so the timeline starts at 00:00. */
private fun relativeTimestamp(frames: List<ReplayFrame>, index: Int): String {
    if (frames.isEmpty()) return "00:00"
    val elapsedUs = frames[index].timeUs - frames.first().timeUs
    val totalSec = (elapsedUs / 1_000_000L).coerceAtLeast(0L)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
