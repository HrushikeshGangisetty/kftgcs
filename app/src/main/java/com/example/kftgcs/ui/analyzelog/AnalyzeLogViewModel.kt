package com.example.kftgcs.ui.analyzelog

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.LogEntryInfo
import com.example.kftgcs.telemetry.MavlinkTelemetryRepository
import com.example.kftgcs.telemetry.SdLogEntry
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
 * Alternatively the user can import a local `.bin` ([Copying] → [Downloaded] with a null log).
 * Any failure surfaces [Error].
 */
sealed interface AnalyzeLogUiState {
    data object Loading : AnalyzeLogUiState
    data class ListReady(val logs: List<LogEntryInfo>) : AnalyzeLogUiState
    /** Logs discovered by browsing the FC's SD card over MAVLink FTP (`/APM/LOGS`). */
    data class SdListReady(val logs: List<SdLogEntry>) : AnalyzeLogUiState
    data class Downloading(val log: LogEntryInfo, val percent: Float) : AnalyzeLogUiState
    /** Downloading an SD-card log over MAVLink FTP. */
    data class DownloadingSd(val log: SdLogEntry, val percent: Float) : AnalyzeLogUiState
    /** Copying a SAF-picked local file into the cache. */
    data object Copying : AnalyzeLogUiState
    /** Browsing the FC's SD card over MAVLink FTP. */
    data object BrowsingSd : AnalyzeLogUiState
    /**
     * Raw `.bin` is cached and ready for analysis. [log] is null for a locally imported or SD-card
     * file; [label] gives a human-readable name in that case.
     */
    data class Downloaded(val log: LogEntryInfo?, val file: File, val label: String? = null) :
        AnalyzeLogUiState
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
                if (logs.isEmpty()) {
                    // Our custom FC returns an empty log index over LOG_REQUEST even though logs sit
                    // in /APM/LOGS. Auto-fall-back to browsing the SD card over MAVLink FTP.
                    loadSdLogsInternal(repo, autoFallback = true)
                } else {
                    _uiState.value = AnalyzeLogUiState.ListReady(logs)
                }
            } catch (e: Exception) {
                LogUtils.e("AnalyzeLogVM", "Failed to read log list", e)
                _uiState.value = AnalyzeLogUiState.Error(
                    "Failed to read logs: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Explicitly browse the FC's SD card (`/APM/LOGS`) over MAVLink FTP. Surfaced as a "Browse SD
     * card" action so the user can reach the files even when the MAVLink log list is unreliable.
     */
    fun browseSdLogs(repo: MavlinkTelemetryRepository?) {
        if (repo == null) {
            _uiState.value = AnalyzeLogUiState.Error("Not connected to a flight controller.")
            return
        }
        _uiState.value = AnalyzeLogUiState.BrowsingSd
        viewModelScope.launch { loadSdLogsInternal(repo, autoFallback = false) }
    }

    /**
     * Shared SD-card listing. When [autoFallback] is true (invoked because the MAVLink list was
     * empty) a failure or empty result degrades gracefully to the empty MAVLink list so the user can
     * still import a local file; when false (explicit browse) it surfaces an error.
     */
    private suspend fun loadSdLogsInternal(repo: MavlinkTelemetryRepository, autoFallback: Boolean) {
        try {
            val sd = repo.listSdLogs()
            if (sd.isEmpty() && autoFallback) {
                _uiState.value = AnalyzeLogUiState.ListReady(emptyList())
            } else {
                _uiState.value = AnalyzeLogUiState.SdListReady(sd)
            }
        } catch (e: Exception) {
            LogUtils.e("AnalyzeLogVM", "Failed to browse SD card logs", e)
            if (autoFallback) {
                _uiState.value = AnalyzeLogUiState.ListReady(emptyList())
            } else {
                _uiState.value = AnalyzeLogUiState.Error(
                    "Failed to browse SD card: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Download an SD-card log over MAVLink FTP, cache it to `cacheDir`, then expose
     * [AnalyzeLogUiState.Downloaded] so the identical analysis flow runs on the `.bin`.
     */
    fun downloadSdLog(repo: MavlinkTelemetryRepository?, log: SdLogEntry) {
        if (repo == null) {
            _uiState.value = AnalyzeLogUiState.Error("Not connected to a flight controller.")
            return
        }
        _uiState.value = AnalyzeLogUiState.DownloadingSd(log, 0f)
        viewModelScope.launch {
            try {
                val bytes = repo.downloadSdLog(log.path) { percent ->
                    _uiState.value = AnalyzeLogUiState.DownloadingSd(log, percent)
                }
                val file = withContext(Dispatchers.IO) {
                    val out = File(getApplication<Application>().cacheDir, "sdlog_${log.name}")
                    out.writeBytes(bytes)
                    out
                }
                LogUtils.i("AnalyzeLogVM", "SD log ${log.name} saved (${file.length()} bytes)")
                _uiState.value = AnalyzeLogUiState.Downloaded(null, file, label = log.name)
            } catch (e: Exception) {
                LogUtils.e("AnalyzeLogVM", "Failed to download SD log ${log.path}", e)
                _uiState.value = AnalyzeLogUiState.Error(
                    "Download failed: ${e.message ?: "unknown error"}"
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

    /**
     * Import a local `.bin` picked through the Storage Access Framework (e.g. from an SD-card reader).
     * Copies it into the cache off the main thread, then exposes [AnalyzeLogUiState.Downloaded] with a
     * null log — the rest of the analysis flow is identical to a USB download.
     */
    fun importLocalLog(uri: Uri) {
        _uiState.value = AnalyzeLogUiState.Copying
        viewModelScope.launch {
            try {
                val file = importLogUriToCache(getApplication<Application>(), uri)
                LogUtils.i("AnalyzeLogVM", "Imported local log (${file.length()} bytes) at ${file.absolutePath}")
                _uiState.value = AnalyzeLogUiState.Downloaded(null, file)
            } catch (e: Exception) {
                LogUtils.e("AnalyzeLogVM", "Failed to import local log", e)
                _uiState.value = AnalyzeLogUiState.Error(
                    "Import failed: ${e.message ?: "unknown error"}"
                )
            }
        }
    }
}
