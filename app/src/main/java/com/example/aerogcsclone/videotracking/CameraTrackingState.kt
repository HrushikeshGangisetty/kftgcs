package com.example.aerogcsclone.videotracking

import com.google.android.gms.maps.model.LatLng

/**
 * Camera tracking modes matching MAVLink CAMERA_TRACKING_MODE enum.
 */
enum class TrackingMode {
    NONE,
    POINT,
    RECTANGLE
}

/**
 * Camera tracking status matching MAVLink CAMERA_TRACKING_STATUS_FLAGS enum.
 */
enum class TrackingStatus {
    IDLE,
    ACTIVE,
    ERROR
}

/**
 * Camera capability flags matching MAVLink CAMERA_CAP_FLAGS.
 */
data class CameraCapabilities(
    val hasTrackingPoint: Boolean = false,
    val hasTrackingRectangle: Boolean = false,
    val hasTrackingGeoStatus: Boolean = false,
    val hasCaptureImage: Boolean = false,
    val hasCaptureVideo: Boolean = false,
    val hasVideoStream: Boolean = false
)

/**
 * Information about a detected camera on the drone.
 */
data class CameraInfo(
    val vendorName: String = "",
    val modelName: String = "",
    val firmwareVersion: String = "",
    val focalLength: Float = 0f,
    val sensorSizeH: Float = 0f,
    val sensorSizeV: Float = 0f,
    val resolutionH: Int = 0,
    val resolutionV: Int = 0,
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val componentId: UByte = 0u,
    val systemId: UByte = 0u
)

/**
 * Video stream information from MAVLink VIDEO_STREAM_INFORMATION.
 */
data class VideoStreamInfo(
    val streamId: Int = 0,
    val name: String = "",
    val uri: String = "",
    val type: VideoStreamType = VideoStreamType.UNKNOWN,
    val resolutionH: Int = 0,
    val resolutionV: Int = 0,
    val framerate: Float = 0f,
    val encoding: String = ""
)

enum class VideoStreamType {
    UNKNOWN,
    RTSP,
    RTPUDP,
    TCP_MPEG,
    MPEG_TS
}

/**
 * Tracking image status from MAVLink CAMERA_TRACKING_IMAGE_STATUS.
 * Coordinates are normalized 0-1 (top-left origin).
 */
data class TrackingImageStatus(
    val trackingStatus: TrackingStatus = TrackingStatus.IDLE,
    val trackingMode: TrackingMode = TrackingMode.NONE,
    /** Point tracking: center X (0-1) */
    val pointX: Float = 0f,
    /** Point tracking: center Y (0-1) */
    val pointY: Float = 0f,
    /** Point tracking: radius (0-1) */
    val radius: Float = 0f,
    /** Rectangle tracking: top-left X (0-1) */
    val rectTopLeftX: Float = 0f,
    /** Rectangle tracking: top-left Y (0-1) */
    val rectTopLeftY: Float = 0f,
    /** Rectangle tracking: bottom-right X (0-1) */
    val rectBottomRightX: Float = 0f,
    /** Rectangle tracking: bottom-right Y (0-1) */
    val rectBottomRightY: Float = 0f
)

/**
 * Camera Field-of-View status from MAVLink CAMERA_FOV_STATUS.
 */
data class CameraFovStatus(
    val latCamera: Double = 0.0,
    val lonCamera: Double = 0.0,
    val altCamera: Float = 0f,
    val latImage: Double = 0.0,
    val lonImage: Double = 0.0,
    val altImage: Float = 0f,
    val quaternion: FloatArray = FloatArray(4),
    val hFov: Float = 0f,
    val vFov: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraFovStatus) return false
        return latCamera == other.latCamera && lonCamera == other.lonCamera &&
                altCamera == other.altCamera && latImage == other.latImage &&
                lonImage == other.lonImage && altImage == other.altImage &&
                quaternion.contentEquals(other.quaternion) &&
                hFov == other.hFov && vFov == other.vFov
    }

    override fun hashCode(): Int {
        var result = latCamera.hashCode()
        result = 31 * result + lonCamera.hashCode()
        result = 31 * result + quaternion.contentHashCode()
        return result
    }
}

/**
 * Gimbal state (current angles).
 */
data class GimbalState(
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val isAvailable: Boolean = false
)

/**
 * Overall camera tracking state combining all sub-states.
 * This is the single source of truth exposed to the UI.
 */
data class CameraTrackingState(
    val cameraInfo: CameraInfo = CameraInfo(),
    val cameraDetected: Boolean = false,
    val trackingImageStatus: TrackingImageStatus = TrackingImageStatus(),
    val cameraFovStatus: CameraFovStatus = CameraFovStatus(),
    val gimbalState: GimbalState = GimbalState(),
    val videoStreams: List<VideoStreamInfo> = emptyList(),
    val selectedStreamUri: String? = null,
    val isTrackingActive: Boolean = false,
    val trackingTargetGps: LatLng? = null
)

