package com.example.kftgcs.ui.replay

import com.example.kftgcs.loganalysis.model.ReplayFrame

/**
 * In-memory handoff for the replay timeline between [com.example.kftgcs.loganalysis.LogAnalysisScreen]
 * and [LogReplayScreen].
 *
 * Why a holder rather than a nav argument: the decimated timeline can be a couple of thousand
 * [ReplayFrame]s — far too large to serialise through a navigation route, and re-parsing the 50–150 MB
 * `.bin` just to replay would be wasteful. The analysis screen drops the already-computed list here
 * before navigating; the replay screen reads it back.
 *
 * This is process-scoped and transient: after process death [frames] is empty, which the replay screen
 * detects and handles by asking the user to re-run analysis.
 */
object ReplaySession {
    @Volatile
    var frames: List<ReplayFrame> = emptyList()

    /** Short human-readable source label (e.g. the log file name) for the screen header. */
    @Volatile
    var sourceLabel: String = ""

    /** Absolute path to the source `.bin` log, so the replay screen can offer "share log". */
    @Volatile
    var sourceFilePath: String? = null

    fun set(frames: List<ReplayFrame>, sourceFilePath: String? = null, sourceLabel: String = "") {
        this.frames = frames
        this.sourceFilePath = sourceFilePath
        this.sourceLabel = sourceLabel
    }

    fun clear() {
        frames = emptyList()
        sourceLabel = ""
        sourceFilePath = null
    }
}
