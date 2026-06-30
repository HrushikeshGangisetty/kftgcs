package com.example.kftgcs.ui.analyzelog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.LogEntryInfo
import com.example.kftgcs.telemetry.MavlinkTelemetryRepository
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI states for the Analyze Log screen.
 *
 * The flow is: [Loading] (reading the list from the FC) → [ListReady] → user taps a log →
 * [Downloading] (with live percent) → [Downloaded] (raw .bin cached, ready for analysis).
 * Any failure surfaces [Error].
 */
sealed interface AnalyzeLogUiState {
    data object Loading : AnalyzeLogUiState
    data class ListReady(val logs: List<LogEntryInfo>) : AnalyzeLogUiState
    data class Downloading(val log: LogEntryInfo, val percent: Float) : AnalyzeLogUiState
    data class Downloaded(val log: LogEntryInfo, val file: File) : AnalyzeLogUiState
    data class Error(val message: String) : AnalyzeLogUiState
}

/**
 * ViewModel for the "Analyze Log" screen. Drives DataFlash log discovery and download over the
 * active MAVLink (USB serial) connection via [MavlinkTelemetryRepository], and caches the raw
 * bytes into the app's internal cache directory.
 *
 * All serial read/write work runs on background threads inside the repository
 * (Dispatchers.IO); the file write here is also wrapped in [Dispatchers.IO]. This ViewModel
 * only orchestrates state transitions on the main thread.
 *
 * Scope intentionally stops at [AnalyzeLogUiState.Downloaded] — no parsing/analysis yet.
 */
class AnalyzeLogViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<AnalyzeLogUiState>(AnalyzeLogUiState.Loading)
    val uiState: StateFlow<AnalyzeLogUiState> = _uiState.asStateFlow()

    /**
     * Request the list of logs from the flight controller. Safe to call repeatedly (e.g. on a
     * retry); it resets to [AnalyzeLogUiState.Loading] first.
     */
    fun loadLogs(repo: MavlinkTelemetryRepository?) {
        if (repo == null) {
            _uiState.value = AnalyzeLogUiState.Error("Not connected to a flight controller.")
            return
        }
        _uiState.value = AnalyzeLogUiState.Loading
        viewModelScope.launch {
            try {
                val logs = repo.requestLogList()
                _uiState.value = AnalyzeLogUiState.ListReady(logs)
            } catch (e: Exception) {
                LogUtils.e("AnalyzeLogVM", "Failed to read log list", e)
                _uiState.value = AnalyzeLogUiState.Error(
                    "Failed to read logs: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Download [log] from the flight controller, streaming progress into [AnalyzeLogUiState.Downloading],
     * then write the raw bytes to `cacheDir/log_<id>.bin` and expose [AnalyzeLogUiState.Downloaded].
     */
    fun downloadLog(repo: MavlinkTelemetryRepository?, log: LogEntryInfo) {
        if (repo == null) {
            _uiState.value = AnalyzeLogUiState.Error("Not connected to a flight controller.")
            return
        }
        _uiState.value = AnalyzeLogUiState.Downloading(log, 0f)
        viewModelScope.launch {
            try {
                val bytes = repo.downloadLog(log.id, log.sizeBytes) { percent ->
                    _uiState.value = AnalyzeLogUiState.Downloading(log, percent)
                }
                val file = withContext(Dispatchers.IO) {
                    val out = File(getApplication<Application>().cacheDir, "log_${log.id}.bin")
                    out.writeBytes(bytes)
                    out
                }
                LogUtils.i("AnalyzeLogVM", "Log ${log.id} saved (${file.length()} bytes) at ${file.absolutePath}")
                _uiState.value = AnalyzeLogUiState.Downloaded(log, file)
            } catch (e: Exception) {
                LogUtils.e("AnalyzeLogVM", "Failed to download log ${log.id}", e)
                _uiState.value = AnalyzeLogUiState.Error(
                    "Download failed: ${e.message ?: "unknown error"}"
                )
            }
        }
    }
}
