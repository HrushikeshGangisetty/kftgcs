package com.example.kftgcs.videotracking.source

import android.hardware.usb.UsbDevice

/**
 * Abstraction over "where the camera picture is coming from", so the player UI
 * ([com.example.kftgcs.videotracking.ui.VideoStreamPlayer]) and the rest of the
 * camera overlay ([com.example.kftgcs.ui.components.DroneCameraFeedOverlay]) do
 * not need to know whether the feed is:
 *
 * - an MK15 air unit + SIYI camera, reachable as an RTSP/HTTP network stream, or
 * - a Skydroid T12 controller, whose video does NOT use a separate USB video
 *   interface at all. Confirmed against hardware (2026-09-22): the T12 exposes
 *   exactly one USB-serial port (the same one MAVLink heartbeats already arrive
 *   on), and video is H.264 muxed into a vendor AT-command protocol tunneled
 *   over that serial link — see [T12SerialVideoSource] for the full writeup.
 *
 * Keeping this as a sealed type (rather than overloading [String] stream URIs
 * with magic prefixes) is what lets MK15 and T12 share the tracking overlay,
 * gimbal controls, settings dialog, and PiP/fullscreen chrome while using
 * completely different capture mechanisms underneath.
 */
sealed interface VideoSource {

    /**
     * A network stream URI, played through ExoPlayer. Covers the existing MK15
     * (RTSP) path and any HTTP/HLS/progressive stream — behavior is unchanged
     * from before this abstraction was introduced.
     */
    data class Network(val uri: String) : VideoSource

    /**
     * A Skydroid T12 controller attached over USB OTG, its video read out via
     * [T12SerialVideoSource] over the same USB-serial link used for MAVLink.
     * No dependency on the vendor's Skydroid FPV app being installed or running.
     */
    data class Usb(val device: UsbDevice) : VideoSource

    companion object {
        /** Wraps a nullable URI the way callers previously passed a bare `String?`. */
        fun ofUriOrNull(uri: String?): Network? =
            if (uri.isNullOrBlank()) null else Network(uri)
    }
}
