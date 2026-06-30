package com.example.kftgcs.ui.replay.recording

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Process-level bridge between [ScreenRecordService] and the replay UI.
 *
 * The fields are Compose-observable ([mutableStateOf]) so the Record button reflects live state and
 * the screen can react when a recording finishes. The service is the writer; the UI reads (and clears
 * [pendingShareFile] after consuming it).
 */
object ScreenRecordController {
    /** True while a screen recording is active. */
    var isRecording by mutableStateOf(false)

    /** Set by the service when a recording finishes successfully, so the UI can offer to share it. */
    var pendingShareFile by mutableStateOf<File?>(null)
}
