package com.example.kftgcs.videotracking.source

import android.hardware.usb.UsbDevice

/**
 * Abstraction over "where the camera picture is coming from", so the player UI
 * ([com.example.kftgcs.videotracking.ui.VideoStreamPlayer]) and the rest of the
 * camera overlay ([com.example.kftgcs.ui.components.DroneCameraFeedOverlay]) do
 * not need to know whether the feed is:
 *
 * - an MK15 air unit + SIYI camera, reachable as an RTSP/HTTP network stream, or
 * - a Skydroid T12 controller, whose video comes out of a USB port as a UVC
 *   (USB Video Class) device — there is no network URI for this at all.
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
     * A Skydroid T12 (or any other UVC-class camera) attached over USB OTG.
     * Captured directly via [com.example.kftgcs.videotracking.source.UsbUvcVideoSource]
     * — no dependency on the vendor's Skydroid FPV app being installed or running,
     * since that app only exposes its picture as a floating overlay window with no
     * programmatic access (no SDK, intent, or shared Surface).
     */
    data class Usb(val device: UsbDevice) : VideoSource

    companion object {
        /** Wraps a nullable URI the way callers previously passed a bare `String?`. */
        fun ofUriOrNull(uri: String?): Network? =
            if (uri.isNullOrBlank()) null else Network(uri)
    }
}
