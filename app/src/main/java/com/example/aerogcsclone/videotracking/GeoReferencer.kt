package com.example.aerogcsclone.videotracking

import com.example.aerogcsclone.Telemetry.TelemetryState
import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Geo-Referencing utility — maps image coordinates to GPS locations.
 *
 * Mirrors MissionPlanner's CalculateImagePointLocation:
 * - Uses CAMERA_FOV_STATUS for precise geo-referencing when available
 * - Falls back to geometric projection from drone GPS + altitude + gimbal angles
 */
object GeoReferencer {

    private const val EARTH_RADIUS = 6371000.0 // meters

    /**
     * Calculate the GPS location of a point in the video frame.
     *
     * @param normX Normalized X coordinate (0=left, 1=right)
     * @param normY Normalized Y coordinate (0=top, 1=bottom)
     * @param fovStatus Current camera FOV status (from MAVLink)
     * @param telemetryState Current drone telemetry
     * @param gimbalState Current gimbal orientation
     * @param cameraInfo Camera sensor information
     * @return GPS location of the point, or null if insufficient data
     */
    fun imagePointToGps(
        normX: Float,
        normY: Float,
        fovStatus: CameraFovStatus?,
        telemetryState: TelemetryState,
        gimbalState: GimbalState?,
        cameraInfo: CameraInfo?
    ): LatLng? {
        // Method 1: Use CAMERA_FOV_STATUS if available (most accurate)
        if (fovStatus != null && fovStatus.latImage != 0.0 && fovStatus.lonImage != 0.0) {
            return calculateFromFovStatus(normX, normY, fovStatus)
        }

        // Method 2: Geometric projection from drone position + altitude
        return calculateFromGeometry(normX, normY, telemetryState, gimbalState, cameraInfo)
    }

    /**
     * Calculate GPS location using CAMERA_FOV_STATUS data.
     * Uses bilinear interpolation across the FOV footprint.
     */
    private fun calculateFromFovStatus(
        normX: Float,
        normY: Float,
        fovStatus: CameraFovStatus
    ): LatLng? {
        if (fovStatus.hFov <= 0f || fovStatus.vFov <= 0f) return null

        // The FOV status gives us the center of the image (latImage, lonImage)
        // and the camera position (latCamera, lonCamera, altCamera)
        // We can interpolate based on field of view angles

        val centerLat = fovStatus.latImage
        val centerLon = fovStatus.lonImage
        val alt = fovStatus.altCamera

        if (alt <= 0f) return null

        // Convert normalized coords to offset from center (-0.5 to 0.5)
        val offsetX = normX - 0.5f
        val offsetY = normY - 0.5f

        // Calculate ground footprint dimensions using FOV angles
        val halfHFovRad = Math.toRadians(fovStatus.hFov / 2.0)
        val halfVFovRad = Math.toRadians(fovStatus.vFov / 2.0)
        val groundHalfWidth = alt * tan(halfHFovRad)  // meters
        val groundHalfHeight = alt * tan(halfVFovRad)  // meters

        // Calculate offset in meters
        val offsetEast = offsetX * 2.0 * groundHalfWidth
        val offsetNorth = -offsetY * 2.0 * groundHalfHeight // Negative because Y increases downward

        // Apply quaternion rotation if available
        val rotatedOffset = applyQuaternionRotation(
            offsetEast, offsetNorth, fovStatus.quaternion
        )

        // Convert meter offset to lat/lng offset
        return offsetLatLng(centerLat, centerLon, rotatedOffset.first, rotatedOffset.second)
    }

    /**
     * Calculate GPS location using geometric projection.
     * Requires drone GPS, altitude, and gimbal angles.
     */
    private fun calculateFromGeometry(
        normX: Float,
        normY: Float,
        telemetryState: TelemetryState,
        gimbalState: GimbalState?,
        cameraInfo: CameraInfo?
    ): LatLng? {
        val droneLat = telemetryState.latitude ?: return null
        val droneLng = telemetryState.longitude ?: return null
        val droneAlt = telemetryState.altitudeRelative ?: return null

        if (droneAlt <= 0.5f) return null // Too low for meaningful projection

        // Use camera FOV or default (for typical drone cameras)
        val hFovDeg = if (cameraInfo != null && cameraInfo.focalLength > 0 && cameraInfo.sensorSizeH > 0) {
            2.0 * Math.toDegrees(atan((cameraInfo.sensorSizeH / 2.0f) / cameraInfo.focalLength).toDouble())
        } else {
            72.0 // Default horizontal FOV for typical drone cameras
        }
        val vFovDeg = if (cameraInfo != null && cameraInfo.focalLength > 0 && cameraInfo.sensorSizeV > 0) {
            2.0 * Math.toDegrees(atan((cameraInfo.sensorSizeV / 2.0f) / cameraInfo.focalLength).toDouble())
        } else {
            54.0 // Default vertical FOV (4:3 ratio)
        }

        // Gimbal pitch (negative = looking down, default = straight down at -90°)
        val gimbalPitch = gimbalState?.pitchDeg ?: -90f
        val gimbalYaw = gimbalState?.yawDeg ?: (telemetryState.heading ?: 0f)

        // Convert normalized coords to angle offsets from gimbal center
        val offsetX = (normX - 0.5f) * hFovDeg.toFloat()
        val offsetY = (normY - 0.5f) * vFovDeg.toFloat()

        // Calculate the total look angle from nadir
        val lookAnglePitch = gimbalPitch + offsetY
        val lookAngleYaw = gimbalYaw + offsetX

        // If looking up, no ground intersection
        if (lookAnglePitch >= 0) return null

        // Calculate ground distance from nadir point
        val pitchRad = Math.toRadians(lookAnglePitch.toDouble())
        val groundDistance = droneAlt * tan(Math.abs(pitchRad))

        // Calculate offset in meters (North/East)
        val yawRad = Math.toRadians(lookAngleYaw.toDouble())
        val offsetNorth = groundDistance * cos(yawRad)
        val offsetEast = groundDistance * sin(yawRad)

        return offsetLatLng(droneLat, droneLng, offsetEast, offsetNorth)
    }

    /**
     * Apply quaternion rotation to an (east, north) offset.
     */
    private fun applyQuaternionRotation(
        east: Double, north: Double, q: FloatArray
    ): Pair<Double, Double> {
        if (q.size < 4 || (q[0] == 0f && q[1] == 0f && q[2] == 0f && q[3] == 0f)) {
            return Pair(east, north)
        }

        // Extract yaw from quaternion
        val sinYCosP = 2.0 * (q[0] * q[3] + q[1] * q[2])
        val cosYCosP = 1.0 - 2.0 * (q[2] * q[2] + q[3] * q[3])
        val yaw = atan2(sinYCosP, cosYCosP)

        // Rotate the offset by yaw
        val rotatedEast = east * cos(yaw) - north * sin(yaw)
        val rotatedNorth = east * sin(yaw) + north * cos(yaw)

        return Pair(rotatedEast, rotatedNorth)
    }

    /**
     * Calculate a new lat/lng given offsets in meters (east, north).
     */
    private fun offsetLatLng(lat: Double, lng: Double, eastMeters: Double, northMeters: Double): LatLng {
        val latRad = Math.toRadians(lat)
        val dLat = northMeters / EARTH_RADIUS
        val dLng = eastMeters / (EARTH_RADIUS * cos(latRad))

        return LatLng(
            lat + Math.toDegrees(dLat),
            lng + Math.toDegrees(dLng)
        )
    }

    /**
     * Calculate the distance in meters between two GPS coordinates (Haversine formula).
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS * 2.0 * atan2(sqrt(a), sqrt(1 - a))
    }
}

