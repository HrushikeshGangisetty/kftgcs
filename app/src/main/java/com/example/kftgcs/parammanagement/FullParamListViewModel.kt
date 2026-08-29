package com.example.kftgcs.parammanagement

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────

/**
 * Represents a single drone parameter with metadata from ArduPilot.
 */
data class DroneParam(
    val name: String,
    val value: Float,
    val paramIndex: Int,
    val paramCount: Int,
    val paramType: Int
)

/**
 * UI state for the Full Param List screen.
 */
data class FullParamListState(
    val params: Map<String, DroneParam> = emptyMap(),
    val isLoading: Boolean = false,
    val loadingProgress: Float = 0f,      // 0.0 – 1.0
    val receivedCount: Int = 0,
    val totalCount: Int = 0,
    val errorMessage: String? = null,
    val isDroneConnected: Boolean = false,
    /** Params the FC advertised but never delivered, after the gap-fill retries. */
    val missingCount: Int = 0,
    /** True while re-requesting the params that were dropped by the telemetry link. */
    val isFillingGaps: Boolean = false,
    // Write-param feedback
    val writingParam: String? = null,      // Name of param currently being written
    val writeSuccess: String? = null,      // Success message (param name)
    val writeError: String? = null,        // Error message
    // Metadata loading
    val isMetadataLoading: Boolean = false,
    val metadataCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────

class FullParamListViewModel(
    private val sharedViewModel: SharedViewModel,
    private val application: Application
) : ViewModel() {

    companion object {
        private const val TAG = "FullParamListVM"
        /** Idle gap after the last received PARAM_VALUE before we stop waiting for more. */
        private const val RECEIVE_TIMEOUT_MS = 4000L
        /**
         * Absolute cap on one fetch, so the screen can never sit on the spinner forever.
         *
         * Covers the initial download plus up to [MAX_GAP_FILL_PASSES] chunked gap-fill passes.
         * At 180s a large gap ran out of budget mid-recovery and the list stayed incomplete; the
         * pass-made-no-progress bailout in [fillGaps] is what ends a hopeless fetch early, so this
         * only needs to be generous enough not to cut a working recovery short.
         */
        private const val HARD_TIMEOUT_MS = 300_000L
        /** How often the accumulated params are pushed to the UI while downloading. */
        private const val PUBLISH_INTERVAL_MS = 250L
        /** Passes of index-targeted re-requests for params the FC never delivered. */
        private const val MAX_GAP_FILL_PASSES = 5
        /**
         * Skip gap filling only when nearly the whole list is missing — that means the link is
         * down, not lossy, and firing a thousand PARAM_REQUEST_READs would only make it worse.
         *
         * This was 0.5, which turned out to be far too eager to give up: the common failure is
         * losing a third of the list to buffer overflow (see MAV_FRAME_BUFFER_CAPACITY in
         * TelemetryRepository), which is exactly the case gap filling exists to repair.
         */
        private const val GAP_FILL_MAX_MISSING_FRACTION = 0.9f
        /**
         * Spacing between gap-fill requests so the link isn't flooded.
         *
         * Gap fill re-requests one param per message, and sending them faster than the FC can
         * answer just overflows the receive path again — recreating the loss it is trying to fix.
         */
        private const val GAP_FILL_REQUEST_SPACING_MS = 25L
        /** Chunk size for gap-fill requests; after each chunk we wait for the replies to land. */
        private const val GAP_FILL_CHUNK_SIZE = 100
    }

    private val _state = MutableStateFlow(FullParamListState())
    val state: StateFlow<FullParamListState> = _state.asStateFlow()

    /** Parameter metadata (descriptions, defaults, options) loaded from ArduPilot */
    private val _paramMetadata = MutableStateFlow<Map<String, ParamMeta>>(emptyMap())
    val paramMetadata: StateFlow<Map<String, ParamMeta>> = _paramMetadata.asStateFlow()

    private var fetchJob: Job? = null

    init {
        // Observe drone connection status
        viewModelScope.launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                _state.update { it.copy(isDroneConnected = telemetry.connected) }
            }
        }

        // Load parameter metadata (from cache/network/fallback)
        viewModelScope.launch {
            _state.update { it.copy(isMetadataLoading = true) }
            try {
                val metadata = ArduPilotParamMetadataRepository.loadMetadata(application)
                _paramMetadata.value = metadata
                _state.update { it.copy(isMetadataLoading = false, metadataCount = metadata.size) }
                LogUtils.d(TAG, "📋 Loaded ${metadata.size} parameter metadata entries")
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to load param metadata", e)
                _paramMetadata.value = FALLBACK_PARAM_METADATA
                _state.update { it.copy(isMetadataLoading = false, metadataCount = FALLBACK_PARAM_METADATA.size) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Force refresh metadata from ArduPilot servers.
     */
    fun refreshMetadata() {
        viewModelScope.launch {
            _state.update { it.copy(isMetadataLoading = true) }
            val success = ArduPilotParamMetadataRepository.refreshMetadata(application)
            if (success) {
                val metadata = ArduPilotParamMetadataRepository.loadMetadata(application)
                _paramMetadata.value = metadata
                _state.update { it.copy(isMetadataLoading = false, metadataCount = metadata.size) }
                LogUtils.d(TAG, "🔄 Refreshed metadata: ${metadata.size} params")
            } else {
                _state.update { it.copy(isMetadataLoading = false) }
                LogUtils.e(TAG, "❌ Failed to refresh metadata")
            }
        }
    }

    /**
     * Request all parameters from the drone (PARAM_REQUEST_LIST #21).
     * Collects incoming PARAM_VALUE messages and builds the list.
     */
    fun fetchAllParams() {
        if (_state.value.isLoading) return
        val previous = fetchJob
        // Runs off the main thread. The old implementation copied the whole param map
        // and emitted a new UI state on *every* PARAM_VALUE; for a 1000+ param download
        // that saturated the main thread, so the progress counter climbed but the screen
        // never got to render the table.
        fetchJob = viewModelScope.launch(Dispatchers.Default) {
            // Join the previous run so two collectors can't accumulate into rival maps.
            previous?.cancelAndJoin()
            runFetch()
        }
    }

    /**
     * Stop waiting for more parameters and show whatever has arrived so far.
     * Gives the pilot the list on a lossy link instead of an endless spinner.
     */
    fun stopFetch() {
        if (!_state.value.isLoading) return
        fetchJob?.cancel()
        fetchJob = null
        val current = _state.value
        _state.update {
            it.copy(
                isLoading = false,
                isFillingGaps = false,
                loadingProgress = 1f,
                missingCount = (current.totalCount - current.params.size).coerceAtLeast(0)
            )
        }
        LogUtils.d(TAG, "⏹ Fetch stopped by user at ${current.params.size}/${current.totalCount}")
    }

    private suspend fun runFetch() = coroutineScope {
        _state.update {
            it.copy(
                isLoading = true,
                isFillingGaps = false,
                loadingProgress = 0f,
                receivedCount = 0,
                totalCount = 0,
                missingCount = 0,
                errorMessage = null,
                params = emptyMap()
            )
        }

        // Params accumulate here rather than in the UI state, so the collector never
        // blocks on a state copy and keeps up with the incoming stream.
        val collected = ConcurrentHashMap<String, DroneParam>()
        val seenIndices = ConcurrentHashMap.newKeySet<Int>()
        val expectedTotal = AtomicInteger(0)
        val lastReceivedAt = AtomicLong(System.currentTimeMillis())

        // Step 1: Start collector BEFORE sending request (avoid race)
        val collector = launch {
            sharedViewModel.paramValue.collect { pv ->
                val paramName = pv.paramId.trim().replace("\u0000", "")
                if (paramName.isBlank()) return@collect

                val paramCount = pv.paramCount.toInt()
                val paramIndex = pv.paramIndex.toInt()
                if (paramCount > 0) expectedTotal.set(paramCount)

                collected[paramName] = DroneParam(
                    name = paramName,
                    value = pv.paramValue,
                    paramIndex = paramIndex,
                    paramCount = paramCount,
                    paramType = pv.paramType.value.toInt()
                )
                // PARAM_SET acks come back with index 65535; only real list indices count.
                if (paramIndex in 0 until 65535) seenIndices.add(paramIndex)
                lastReceivedAt.set(System.currentTimeMillis())
            }
        }

        // Step 2: Publish on a timer instead of once per message.
        val publisher = launch {
            while (isActive) {
                delay(PUBLISH_INTERVAL_MS)
                publishProgress(collected, expectedTotal.get())
            }
        }

        try {
            sharedViewModel.requestAllParameters()
            LogUtils.d(TAG, "📤 PARAM_REQUEST_LIST sent")

            // Step 3: Wait until the list is complete or the stream goes quiet.
            val deadline = System.currentTimeMillis() + HARD_TIMEOUT_MS
            awaitStreamIdle(collected, expectedTotal, lastReceivedAt, deadline)

            // Step 4: Re-request the indices the FC advertised but never delivered.
            fillGaps(collected, seenIndices, expectedTotal, lastReceivedAt, deadline)
        } catch (e: CancellationException) {
            // stopFetch() / onCleared() already settled the state — don't run step 5.
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to request all parameters", e)
            _state.update { it.copy(errorMessage = "Failed to request parameters: ${e.message}") }
        } finally {
            collector.cancel()
            publisher.cancel()
        }

        // Step 5: Final publish, then settle the loading flags.
        val total = expectedTotal.get()
        publishProgress(collected, total)
        val missing = if (total > 0) (total - collected.size).coerceAtLeast(0) else 0
        _state.update {
            it.copy(
                isLoading = false,
                isFillingGaps = false,
                loadingProgress = 1f,
                missingCount = missing,
                errorMessage = if (collected.isEmpty()) {
                    "No parameters received from the drone. Check the telemetry link and try again."
                } else it.errorMessage
            )
        }
        LogUtils.d(TAG, "✅ Fetch finished: ${collected.size}/$total ($missing missing)")
    }

    /** Block until every advertised param has arrived, the stream stalls, or [deadline] passes. */
    private suspend fun awaitStreamIdle(
        collected: Map<String, DroneParam>,
        expectedTotal: AtomicInteger,
        lastReceivedAt: AtomicLong,
        deadline: Long
    ) {
        while (System.currentTimeMillis() < deadline) {
            val total = expectedTotal.get()
            if (total > 0 && collected.size >= total) return
            if (System.currentTimeMillis() - lastReceivedAt.get() > RECEIVE_TIMEOUT_MS) return
            delay(200)
        }
    }

    /**
     * Re-request params the link dropped, by index.
     * ArduPilot streams the parameter list exactly once per PARAM_REQUEST_LIST, so
     * without this a single dropped packet leaves a permanent hole in the list.
     */
    private suspend fun fillGaps(
        collected: Map<String, DroneParam>,
        seenIndices: Set<Int>,
        expectedTotal: AtomicInteger,
        lastReceivedAt: AtomicLong,
        deadline: Long
    ) {
        var pass = 0
        while (pass < MAX_GAP_FILL_PASSES && System.currentTimeMillis() < deadline) {
            val total = expectedTotal.get()
            if (total <= 0 || collected.size >= total) return

            val missing = (0 until total).filter { it !in seenIndices }
            if (missing.isEmpty()) return
            if (missing.size > total * GAP_FILL_MAX_MISSING_FRACTION) {
                LogUtils.d(TAG, "⚠ ${missing.size}/$total params missing — link too lossy for gap fill")
                return
            }

            pass++
            val sizeBeforePass = collected.size
            _state.update { it.copy(isFillingGaps = true) }
            LogUtils.d(TAG, "🔁 Gap-fill pass $pass: re-requesting ${missing.size} params")

            // Send in chunks and let each chunk's replies arrive before queueing the next.
            // Firing all of them back to back is what overwhelmed the receive path and made a
            // large gap unrecoverable — the retries dropped as many params as they recovered.
            for (chunk in missing.chunked(GAP_FILL_CHUNK_SIZE)) {
                if (System.currentTimeMillis() >= deadline) break
                for (index in chunk) {
                    if (System.currentTimeMillis() >= deadline) break
                    sharedViewModel.requestParameterByIndex(index)
                    delay(GAP_FILL_REQUEST_SPACING_MS)
                }
                awaitStreamIdle(collected, expectedTotal, lastReceivedAt, deadline)
            }

            // Nothing new arrived this pass — the FC isn't answering, so more passes won't help.
            if (collected.size == sizeBeforePass) {
                LogUtils.d(TAG, "⚠ Gap-fill pass $pass recovered nothing — giving up")
                return
            }
        }
    }

    /** Copy the accumulated params into the UI state. Called on a timer, not per message. */
    private fun publishProgress(collected: Map<String, DroneParam>, total: Int) {
        val snapshot = collected.toMap()
        _state.update { current ->
            val effectiveTotal = if (total > 0) total else current.totalCount
            val progress = if (effectiveTotal > 0) snapshot.size.toFloat() / effectiveTotal else 0f
            current.copy(
                params = snapshot,
                receivedCount = snapshot.size,
                totalCount = effectiveTotal,
                loadingProgress = progress.coerceIn(0f, 1f)
            )
        }
    }

    /**
     * Write (set) a single parameter on the drone using PARAM_SET (#23).
     * Retries up to 2 times on timeout.
     */
    fun writeParam(paramName: String, newValue: Float) {
        if (!_state.value.isDroneConnected) {
            _state.update { it.copy(writeError = "Drone not connected") }
            return
        }

        _state.update { it.copy(writingParam = paramName, writeSuccess = null, writeError = null) }

        viewModelScope.launch {
            var ack: com.divpundir.mavlink.definitions.common.ParamValue? = null
            val maxRetries = 3

            for (attempt in 1..maxRetries) {
                try {
                    LogUtils.d(TAG, "📤 Writing param $paramName = $newValue (attempt $attempt/$maxRetries)")
                    ack = sharedViewModel.setParameter(paramName, newValue, timeoutMs = 5000L)
                    if (ack != null) break
                    LogUtils.e(TAG, "⏱ Timeout writing $paramName (attempt $attempt/$maxRetries)")
                } catch (e: Exception) {
                    LogUtils.e(TAG, "❌ Error writing $paramName (attempt $attempt)", e)
                }
                if (attempt < maxRetries) delay(500)
            }

            if (ack != null) {
                val confirmedValue = ack.paramValue
                LogUtils.d(TAG, "✅ Param $paramName confirmed = $confirmedValue")

                // Update local map with confirmed value
                _state.update { current ->
                    val updatedParams = current.params.toMutableMap()
                    updatedParams[paramName]?.let { existing ->
                        updatedParams[paramName] = existing.copy(value = confirmedValue)
                    }
                    current.copy(
                        params = updatedParams,
                        writingParam = null,
                        writeSuccess = paramName
                    )
                }
            } else {
                LogUtils.e(TAG, "❌ Failed to write $paramName after $maxRetries attempts")
                _state.update {
                    it.copy(writingParam = null, writeError = "Failed to write $paramName after $maxRetries attempts")
                }
            }
        }
    }

    /**
     * Refresh a single parameter (PARAM_REQUEST_READ #20).
     */
    fun refreshParam(paramName: String) {
        viewModelScope.launch {
            val value = sharedViewModel.readParameter(paramName, 3000L)
            if (value != null) {
                _state.update { current ->
                    val updatedParams = current.params.toMutableMap()
                    updatedParams[paramName]?.let { existing ->
                        updatedParams[paramName] = existing.copy(value = value)
                    }
                    current.copy(params = updatedParams)
                }
            }
        }
    }

    fun clearWriteMessages() {
        _state.update { it.copy(writeSuccess = null, writeError = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
    }
}

