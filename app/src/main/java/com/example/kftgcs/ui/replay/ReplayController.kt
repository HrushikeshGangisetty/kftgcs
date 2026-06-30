package com.example.kftgcs.ui.replay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kftgcs.loganalysis.model.ReplayFrame

/**
 * Holds the playback position and play/pause state for the replay screen as Compose state.
 *
 * The state lives here (not in the screen body) so that mutating [currentIndex] 4×/second does not
 * recompose the screen layout — only the children that actually read these values via a provider
 * lambda recompose. See the recomposition-isolation notes in the replay screen.
 */
class ReplayController(val frameCount: Int) {

    var currentIndex by mutableIntStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)

    private val lastIndex: Int get() = (frameCount - 1).coerceAtLeast(0)

    fun togglePlay() {
        if (frameCount == 0) return
        // Restart from the beginning if we're paused at the very end.
        if (!isPlaying && currentIndex >= lastIndex) currentIndex = 0
        isPlaying = !isPlaying
    }

    /** Advance one frame; stops playback when the end is reached. Returns true if it advanced. */
    fun advance(): Boolean {
        if (currentIndex >= lastIndex) {
            isPlaying = false
            return false
        }
        currentIndex += 1
        return true
    }

    fun seekTo(index: Int) {
        currentIndex = index.coerceIn(0, lastIndex)
    }

    /** The frame at the current index, safely clamped to [frames]'s valid range. */
    fun frameOf(frames: List<ReplayFrame>): ReplayFrame =
        frames[currentIndex.coerceIn(0, frames.lastIndex.coerceAtLeast(0))]
}

@Composable
fun rememberReplayController(frameCount: Int): ReplayController =
    remember(frameCount) { ReplayController(frameCount) }
