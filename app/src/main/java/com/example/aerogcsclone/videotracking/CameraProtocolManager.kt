package com.example.aerogcsclone.videotracking

import com.divpundir.mavlink.api.wrap
import com.divpundir.mavlink.definitions.common.*
import com.example.aerogcsclone.telemetry.MavlinkTelemetryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.divpundir.mavlink.definitions.common.CameraFovStatus as MavCameraFovStatus

/**
 * Camera Protocol Manager — handles MAVLink camera protocol communication.
 *
 * Mirrors MissionPlanner's CameraProtocol class:
 * - Detects cameras via CAMERA_INFORMATION
 * - Sends tracking commands (CAMERA_TRACK_POINT, CAMERA_TRACK_RECTANGLE)
 * - Receives tracking status (CAMERA_TRACKING_IMAGE_STATUS)
 * - Receives FOV status (CAMERA_FOV_STATUS)
 * - Manages video stream information (VIDEO_STREAM_INFORMATION)
 * - Requests periodic tracking status updates
 */
class CameraProtocolManager(
    private val repository: MavlinkTelemetryRepository
) {
    companion object {
        private const val TAG = "CameraProtocol"

        // MAVLink message IDs for camera protocol
        const val MSG_ID_CAMERA_INFORMATION = 259u
        const val MSG_ID_VIDEO_STREAM_INFORMATION = 269u
        const val MSG_ID_CAMERA_FOV_STATUS = 271u
        const val MSG_ID_CAMERA_TRACKING_IMAGE_STATUS = 275u

        // Camera capability flag bit positions (from MAVLink CAMERA_CAP_FLAGS)
        const val CAP_CAPTURE_IMAGE = 0x01u
        const val CAP_CAPTURE_VIDEO = 0x02u
        const val CAP_HAS_VIDEO_STREAM = 0x20u
        const val CAP_HAS_TRACKING_POINT = 0x40u
        const val CAP_HAS_TRACKING_RECTANGLE = 0x80u
        const val CAP_HAS_TRACKING_GEO = 0x100u
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State flows for camera data
    private val _cameraInfo = MutableStateFlow(CameraInfo())
    val cameraInfo: StateFlow<CameraInfo> = _cameraInfo.asStateFlow()

    private val _cameraDetected = MutableStateFlow(false)
    val cameraDetected: StateFlow<Boolean> = _cameraDetected.asStateFlow()

    private val _trackingImageStatus = MutableStateFlow(TrackingImageStatus())
    val trackingImageStatus: StateFlow<TrackingImageStatus> = _trackingImageStatus.asStateFlow()

    private val _cameraFovStatus = MutableStateFlow(CameraFovStatus())
    val cameraFovStatus: StateFlow<CameraFovStatus> = _cameraFovStatus.asStateFlow()

    private val _videoStreams = MutableStateFlow<List<VideoStreamInfo>>(emptyList())
    val videoStreams: StateFlow<List<VideoStreamInfo>> = _videoStreams.asStateFlow()

    private var cameraSystemId: UByte = 0u
    private var cameraComponentId: UByte = 0u

    /**
     * Start listening for camera MAVLink messages.
     * Call this after FCU is detected.
     */
    fun startListening() {
        Timber.d("$TAG: Starting camera protocol listener")

        // Listen for camera-related MAVLink messages
        scope.launch {
            try {
                repository.mavFrame
                    .collect { frame ->
                        try {
                            when (val msg = frame.message) {
                                is CameraInformation -> handleCameraInformation(frame.systemId, frame.componentId, msg)
                                is MavCameraFovStatus -> handleCameraFovStatus(msg)
                                is CameraTrackingImageStatus -> handleTrackingImageStatus(msg)
                                is VideoStreamInformation -> handleVideoStreamInformation(msg)
                            }
                        } catch (_: Exception) {
                            // Some message types may not be available in this library version
                            // Silently ignore class resolution errors
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error in camera message listener")
            }
        }

        // Request camera information after a short delay
        scope.launch {
            delay(2000) // Wait for connection to stabilize
            requestCameraInformation()
        }
    }

    /**
     * Request CAMERA_INFORMATION message from the flight controller.
     */
    private suspend fun requestCameraInformation() {
        Timber.d("$TAG: Requesting CAMERA_INFORMATION")
        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = 0u, // Broadcast to find cameras
            command = MavCmd.REQUEST_MESSAGE.wrap(),
            confirmation = 0u,
            param1 = MSG_ID_CAMERA_INFORMATION.toFloat(),
            param2 = 0f, param3 = 0f, param4 = 0f,
            param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)

        // Also request video stream information
        delay(500)
        requestVideoStreamInformation()
    }

    /**
     * Request VIDEO_STREAM_INFORMATION.
     */
    private suspend fun requestVideoStreamInformation() {
        Timber.d("$TAG: Requesting VIDEO_STREAM_INFORMATION")
        val cmd = CommandLong(
            targetSystem = repository.fcuSystemId,
            targetComponent = 0u,
            command = MavCmd.REQUEST_MESSAGE.wrap(),
            confirmation = 0u,
            param1 = MSG_ID_VIDEO_STREAM_INFORMATION.toFloat(),
            param2 = 0f, param3 = 0f, param4 = 0f,
            param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Handle CAMERA_INFORMATION message.
     */
    private fun handleCameraInformation(sysId: UByte, compId: UByte, msg: CameraInformation) {
        cameraSystemId = sysId
        cameraComponentId = compId

        // flags is a MavBitmaskValue — access its .value property for UInt bitmask
        val flagsValue = msg.flags.value

        val capabilities = CameraCapabilities(
            hasTrackingPoint = (flagsValue and CAP_HAS_TRACKING_POINT) != 0u,
            hasTrackingRectangle = (flagsValue and CAP_HAS_TRACKING_RECTANGLE) != 0u,
            hasTrackingGeoStatus = (flagsValue and CAP_HAS_TRACKING_GEO) != 0u,
            hasCaptureImage = (flagsValue and CAP_CAPTURE_IMAGE) != 0u,
            hasCaptureVideo = (flagsValue and CAP_CAPTURE_VIDEO) != 0u,
            hasVideoStream = (flagsValue and CAP_HAS_VIDEO_STREAM) != 0u
        )

        val vendorName = msg.vendorName.takeWhile { it != 0.toUByte() }
            .map { it.toByte().toInt().toChar() }.joinToString("")
        val modelName = msg.modelName.takeWhile { it != 0.toUByte() }
            .map { it.toByte().toInt().toChar() }.joinToString("")

        _cameraInfo.value = CameraInfo(
            vendorName = vendorName,
            modelName = modelName,
            firmwareVersion = "${msg.firmwareVersion}",
            focalLength = msg.focalLength,
            sensorSizeH = msg.sensorSizeH,
            sensorSizeV = msg.sensorSizeV,
            resolutionH = msg.resolutionH.toInt(),
            resolutionV = msg.resolutionV.toInt(),
            capabilities = capabilities,
            componentId = compId,
            systemId = sysId
        )
        _cameraDetected.value = true

        Timber.i("$TAG: Camera detected — $vendorName $modelName, tracking: point=${capabilities.hasTrackingPoint}, rect=${capabilities.hasTrackingRectangle}")
    }

    /**
     * Handle CAMERA_TRACKING_IMAGE_STATUS message.
     */
    private fun handleTrackingImageStatus(msg: CameraTrackingImageStatus) {
        val status = when (msg.trackingStatus.value) {
            1u -> TrackingStatus.ACTIVE
            2u -> TrackingStatus.ERROR
            else -> TrackingStatus.IDLE
        }

        val mode = when (msg.trackingMode.value) {
            1u -> TrackingMode.POINT
            2u -> TrackingMode.RECTANGLE
            else -> TrackingMode.NONE
        }

        _trackingImageStatus.value = TrackingImageStatus(
            trackingStatus = status,
            trackingMode = mode,
            pointX = msg.pointX,
            pointY = msg.pointY,
            radius = msg.radius,
            rectTopLeftX = msg.recTopX,
            rectTopLeftY = msg.recTopY,
            rectBottomRightX = msg.recBottomX,
            rectBottomRightY = msg.recBottomY
        )
    }

    /**
     * Handle CAMERA_FOV_STATUS message.
     */
    private fun handleCameraFovStatus(msg: MavCameraFovStatus) {
        // The quaternion field in CameraFovStatus is a List<Float> in the divpundir library
        val qList = msg.q
        val qArray = if (qList.size >= 4) floatArrayOf(qList[0], qList[1], qList[2], qList[3])
                     else FloatArray(4)

        _cameraFovStatus.value = CameraFovStatus(
            latCamera = msg.latCamera / 1e7,
            lonCamera = msg.lonCamera / 1e7,
            altCamera = msg.altCamera / 1000f,
            latImage = msg.latImage / 1e7,
            lonImage = msg.lonImage / 1e7,
            altImage = msg.altImage / 1000f,
            quaternion = qArray,
            hFov = msg.hfov,
            vFov = msg.vfov
        )
    }

    /**
     * Handle VIDEO_STREAM_INFORMATION message.
     */
    private fun handleVideoStreamInformation(msg: VideoStreamInformation) {
        val streamType = when (msg.type.value) {
            1u -> VideoStreamType.RTSP
            2u -> VideoStreamType.RTPUDP
            3u -> VideoStreamType.TCP_MPEG
            4u -> VideoStreamType.MPEG_TS
            else -> VideoStreamType.UNKNOWN
        }

        val uri = msg.uri.takeWhile { it != '\u0000' }
        val name = msg.name.takeWhile { it != '\u0000' }

        val streamInfo = VideoStreamInfo(
            streamId = msg.streamId.toInt(),
            name = name,
            uri = uri,
            type = streamType,
            resolutionH = msg.resolutionH.toInt(),
            resolutionV = msg.resolutionV.toInt(),
            framerate = msg.framerate,
            encoding = msg.encoding.value.toString()
        )

        val currentStreams = _videoStreams.value.toMutableList()
        val existingIndex = currentStreams.indexOfFirst { it.streamId == streamInfo.streamId }
        if (existingIndex >= 0) {
            currentStreams[existingIndex] = streamInfo
        } else {
            currentStreams.add(streamInfo)
        }
        _videoStreams.value = currentStreams

        Timber.i("$TAG: Video stream — id=${streamInfo.streamId}, type=$streamType, uri=$uri")
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRACKING COMMANDS (sent to camera via MAVLink)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send point tracking command.
     * Coordinates are normalized 0-1 (top-left origin), matching MissionPlanner's mapping.
     *
     * @param x Normalized X coordinate (0=left, 1=right)
     * @param y Normalized Y coordinate (0=top, 1=bottom)
     */
    suspend fun setTrackingPoint(x: Float, y: Float) {
        if (!_cameraDetected.value) {
            Timber.w("$TAG: Cannot track — no camera detected")
            return
        }

        Timber.d("$TAG: Setting tracking point (%.3f, %.3f)".format(x, y))

        // Request tracking status updates at 5Hz (same as MissionPlanner)
        requestTrackingMessageInterval(5)

        val cmd = CommandLong(
            targetSystem = cameraSystemId,
            targetComponent = cameraComponentId,
            command = MavCmd.CAMERA_TRACK_POINT.wrap(),
            confirmation = 0u,
            param1 = x,   // point_x (0-1)
            param2 = y,   // point_y (0-1)
            param3 = 0f,  // radius
            param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Send rectangle tracking command.
     * Coordinates are normalized 0-1 (top-left origin).
     *
     * @param x1 Top-left X (0-1)
     * @param y1 Top-left Y (0-1)
     * @param x2 Bottom-right X (0-1)
     * @param y2 Bottom-right Y (0-1)
     */
    suspend fun setTrackingRectangle(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!_cameraDetected.value) {
            Timber.w("$TAG: Cannot track — no camera detected")
            return
        }

        // Ensure correct ordering (top-left < bottom-right)
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val right = maxOf(x1, x2)
        val bottom = maxOf(y1, y2)

        Timber.d("$TAG: Setting tracking rectangle (%.3f,%.3f)-(%.3f,%.3f)".format(left, top, right, bottom))

        // Request tracking status updates at 5Hz
        requestTrackingMessageInterval(5)

        val cmd = CommandLong(
            targetSystem = cameraSystemId,
            targetComponent = cameraComponentId,
            command = MavCmd.CAMERA_TRACK_RECTANGLE.wrap(),
            confirmation = 0u,
            param1 = left,   // top_left_x (0-1)
            param2 = top,    // top_left_y (0-1)
            param3 = right,  // bottom_right_x (0-1)
            param4 = bottom, // bottom_right_y (0-1)
            param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)
    }

    /**
     * Stop active tracking.
     */
    suspend fun stopTracking() {
        Timber.d("$TAG: Stopping tracking")
        val cmd = CommandLong(
            targetSystem = cameraSystemId,
            targetComponent = cameraComponentId,
            command = MavCmd.CAMERA_STOP_TRACKING.wrap(),
            confirmation = 0u,
            param1 = 0f, param2 = 0f, param3 = 0f,
            param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)

        // Reset tracking status locally
        _trackingImageStatus.value = TrackingImageStatus()
    }

    /**
     * Request periodic CAMERA_TRACKING_IMAGE_STATUS messages at given rate.
     * This is equivalent to MissionPlanner's RequestTrackingMessageInterval.
     */
    private suspend fun requestTrackingMessageInterval(rateHz: Int) {
        val intervalUs = if (rateHz > 0) (1_000_000f / rateHz) else 0f

        val cmd = CommandLong(
            targetSystem = cameraSystemId,
            targetComponent = cameraComponentId,
            command = MavCmd.SET_MESSAGE_INTERVAL.wrap(),
            confirmation = 0u,
            param1 = MSG_ID_CAMERA_TRACKING_IMAGE_STATUS.toFloat(),
            param2 = intervalUs,
            param3 = 0f, param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(cmd)

        // Also request FOV status for geo-referencing
        val fovCmd = CommandLong(
            targetSystem = cameraSystemId,
            targetComponent = cameraComponentId,
            command = MavCmd.SET_MESSAGE_INTERVAL.wrap(),
            confirmation = 0u,
            param1 = MSG_ID_CAMERA_FOV_STATUS.toFloat(),
            param2 = intervalUs,
            param3 = 0f, param4 = 0f, param5 = 0f, param6 = 0f, param7 = 0f
        )
        repository.sendCommandLong(fovCmd)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        scope.cancel()
    }
}

