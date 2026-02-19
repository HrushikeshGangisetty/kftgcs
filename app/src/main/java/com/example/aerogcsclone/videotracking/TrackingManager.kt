package com.example.aerogcsclone.videotracking

import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.telemetry.MavlinkTelemetryRepository
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/**
 * Tracking Manager — orchestrates the entire video tracking pipeline.
 *
 * Combines CameraProtocolManager, GimbalController, and GeoReferencer
 * into a single high-level interface for the UI layer.
 *
 * This is the equivalent of MissionPlanner's GimbalVideoControl user-interaction logic.
 */
class TrackingManager(
    private val repository: MavlinkTelemetryRepository
) {
    companion object {
        private const val TAG = "TrackingManager"
    }

    // Sub-components
    val cameraProtocol = CameraProtocolManager(repository)
    val gimbalController = GimbalController(repository)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Combined camera tracking state for UI
    private val _cameraTrackingState = MutableStateFlow(CameraTrackingState())
    val cameraTrackingState: StateFlow<CameraTrackingState> = _cameraTrackingState.asStateFlow()

    // Tracking target GPS location
    private val _trackingTargetGps = MutableStateFlow<LatLng?>(null)
    val trackingTargetGps: StateFlow<LatLng?> = _trackingTargetGps.asStateFlow()

    private var isInitialized = false

    /**
     * Initialize the tracking manager.
     * Call after FCU is detected and connected.
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        Timber.i("$TAG: Initializing tracking manager")

        // Start sub-component listeners
        cameraProtocol.startListening()
        gimbalController.startListening()

        // Combine all state flows into unified CameraTrackingState
        // Note: Kotlin combine() only supports up to 5 typed flows, so we nest two combines
        scope.launch {
            combine(
                combine(
                    cameraProtocol.cameraInfo,
                    cameraProtocol.cameraDetected,
                    cameraProtocol.trackingImageStatus,
                    cameraProtocol.cameraFovStatus,
                    gimbalController.gimbalState
                ) { cameraInfo, detected, trackingStatus, fovStatus, gimbalState ->
                    CameraTrackingState(
                        cameraInfo = cameraInfo,
                        cameraDetected = detected,
                        trackingImageStatus = trackingStatus,
                        cameraFovStatus = fovStatus,
                        gimbalState = gimbalState,
                        isTrackingActive = trackingStatus.trackingStatus == TrackingStatus.ACTIVE
                    )
                },
                cameraProtocol.videoStreams,
                _trackingTargetGps
            ) { baseState, videoStreams, targetGps ->
                baseState.copy(
                    videoStreams = videoStreams,
                    selectedStreamUri = videoStreams.firstOrNull()?.uri,
                    trackingTargetGps = targetGps
                )
            }.collect { state ->
                _cameraTrackingState.value = state
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  USER INTERACTION HANDLERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle single tap on video feed — initiate point tracking.
     *
     * @param normX Normalized X (0-1, left to right)
     * @param normY Normalized Y (0-1, top to bottom)
     * @param telemetryState Current telemetry for geo-referencing
     */
    suspend fun onVideoTap(normX: Float, normY: Float, telemetryState: TelemetryState) {
        Timber.d("$TAG: Video tap at (%.3f, %.3f)".format(normX, normY))

        // Send point tracking command
        cameraProtocol.setTrackingPoint(normX, normY)

        // Calculate GPS location of tapped point
        updateTrackingTargetGps(normX, normY, telemetryState)
    }

    /**
     * Handle drag completion on video feed — initiate rectangle tracking.
     *
     * @param startX Start X (0-1)
     * @param startY Start Y (0-1)
     * @param endX End X (0-1)
     * @param endY End Y (0-1)
     * @param telemetryState Current telemetry for geo-referencing
     */
    suspend fun onVideoDragComplete(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        telemetryState: TelemetryState
    ) {
        Timber.d("$TAG: Video drag (%.3f,%.3f)→(%.3f,%.3f)".format(startX, startY, endX, endY))

        // Send rectangle tracking command
        cameraProtocol.setTrackingRectangle(startX, startY, endX, endY)

        // Calculate GPS location of rectangle center
        val centerX = (startX + endX) / 2f
        val centerY = (startY + endY) / 2f
        updateTrackingTargetGps(centerX, centerY, telemetryState)
    }

    /**
     * Handle long-press on video feed — move gimbal to point at location.
     *
     * @param normX Normalized X (0-1)
     * @param normY Normalized Y (0-1)
     * @param telemetryState Current telemetry
     */
    suspend fun onVideoLongPress(normX: Float, normY: Float, telemetryState: TelemetryState) {
        Timber.d("$TAG: Video long-press at (%.3f, %.3f)".format(normX, normY))

        val gps = calculateImagePointGps(normX, normY, telemetryState) ?: return
        gimbalController.setROILocation(gps.latitude, gps.longitude, telemetryState.altitudeRelative ?: 0f)
    }

    /**
     * Stop tracking.
     */
    suspend fun stopTracking() {
        cameraProtocol.stopTracking()
        _trackingTargetGps.value = null
    }

    /**
     * Move gimbal by delta angles.
     */
    suspend fun nudgeGimbal(deltaPitchDeg: Float, deltaYawDeg: Float) {
        val current = gimbalController.gimbalState.value
        gimbalController.setPitchYaw(
            pitchDeg = current.pitchDeg + deltaPitchDeg,
            yawDeg = current.yawDeg + deltaYawDeg
        )
    }

    /**
     * Calculate GPS location of a point in the video.
     */
    private fun calculateImagePointGps(
        normX: Float, normY: Float, telemetryState: TelemetryState
    ): LatLng? {
        val fovStatus = _cameraTrackingState.value.cameraFovStatus
        val gimbalState = _cameraTrackingState.value.gimbalState
        val cameraInfo = _cameraTrackingState.value.cameraInfo

        return GeoReferencer.imagePointToGps(
            normX, normY,
            fovStatus.takeIf { it.latImage != 0.0 },
            telemetryState,
            gimbalState.takeIf { it.isAvailable },
            cameraInfo.takeIf { _cameraTrackingState.value.cameraDetected }
        )
    }

    /**
     * Update the tracking target GPS location.
     */
    private fun updateTrackingTargetGps(normX: Float, normY: Float, telemetryState: TelemetryState) {
        val gps = calculateImagePointGps(normX, normY, telemetryState)
        _trackingTargetGps.value = gps
        if (gps != null) {
            Timber.d("$TAG: Tracking target GPS: lat=%.6f lng=%.6f".format(gps.latitude, gps.longitude))
        }
    }

    /**
     * Clean up all resources.
     */
    fun destroy() {
        scope.cancel()
        cameraProtocol.destroy()
        gimbalController.destroy()
        isInitialized = false
    }
}

