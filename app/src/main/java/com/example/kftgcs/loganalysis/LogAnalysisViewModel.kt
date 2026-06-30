package com.example.kftgcs.loganalysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.loganalysis.diagnostic.CrashAnalyzer
import com.example.kftgcs.loganalysis.model.DiagnosticFlag
import com.example.kftgcs.loganalysis.model.ReplayFrame
import com.example.kftgcs.loganalysis.parser.DataFlashParser
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI states for the offline log analysis screen.
 *
 * Flow: [Loading] → [Parsing] (with optional 0..1 progress) → [AnalysisComplete] (diagnostics +
 * decimated replay timeline). Any failure (missing/corrupt file) surfaces [Error].
 */
sealed interface LogAnalysisUiState {
    data object Loading : LogAnalysisUiState
    data class Parsing(val percent: Float?) : LogAnalysisUiState
    data class AnalysisComplete(
        val diagnostics: List<DiagnosticFlag>,
        val replayTimeline: List<ReplayFrame>,
        val truncated: Boolean
    ) : LogAnalysisUiState
    data class Error(val message: String) : LogAnalysisUiState
}

/**
 * ViewModel for the offline DataFlash log analysis screen.
 *
 * Takes a downloaded `.bin` [File] and, entirely on [Dispatchers.IO], streams it through the
 * [DataFlashParser]:
 *  - Pass 1 runs the [CrashAnalyzer] to extract the [DiagnosticFlag]s.
 *  - Pass 2 runs the [ReplayTimelineBuilder] to extract the decimated [ReplayFrame] timeline.
 *
 * Two sequential streaming passes (each re-opening the file) keep peak memory low — the full log is
 * never held in memory, only the small diagnostic window and the decimated timeline.
 */
class LogAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<LogAnalysisUiState>(LogAnalysisUiState.Loading)
    val uiState: StateFlow<LogAnalysisUiState> = _uiState.asStateFlow()

    private var analyzedPath: String? = null

    /** Analyze [file]. Idempotent for the same file while an analysis result is already showing. */
    fun analyze(file: File) {
        if (analyzedPath == file.absolutePath && _uiState.value is LogAnalysisUiState.AnalysisComplete) {
            return
        }
        analyzedPath = file.absolutePath
        _uiState.value = LogAnalysisUiState.Parsing(0f)

        viewModelScope.launch {
            try {
                if (!file.exists() || file.length() == 0L) {
                    _uiState.value = LogAnalysisUiState.Error("Log file is missing or empty.")
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    // Pass 1: diagnostics (progress 0% → 50%).
                    val analyzer = CrashAnalyzer()
                    val summary = DataFlashParser(file).parse(
                        onProgress = { p -> publishProgress(p * 0.5f) }
                    ) { msg -> analyzer.onMessage(msg) }
                    val diagnostics = analyzer.finish(summary)

                    // Pass 2: decimated replay timeline (progress 50% → 100%).
                    val timelineBuilder = ReplayTimelineBuilder()
                    DataFlashParser(file).parse(
                        onProgress = { p -> publishProgress(0.5f + p * 0.5f) }
                    ) { msg -> timelineBuilder.onMessage(msg) }
                    val timeline = timelineBuilder.build()

                    Triple(diagnostics, timeline, summary.truncated)
                }

                LogUtils.i(
                    TAG,
                    "Analysis done: ${result.first.size} flags, ${result.second.size} frames, " +
                        "truncated=${result.third}"
                )
                _uiState.value = LogAnalysisUiState.AnalysisComplete(
                    diagnostics = result.first,
                    replayTimeline = result.second,
                    truncated = result.third
                )
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to analyze log", e)
                analyzedPath = null
                _uiState.value = LogAnalysisUiState.Error(
                    "Analysis failed: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private fun publishProgress(percent: Float) {
        _uiState.value = LogAnalysisUiState.Parsing(percent.coerceIn(0f, 1f))
    }

    companion object {
        private const val TAG = "LogAnalysisVM"
    }
}
