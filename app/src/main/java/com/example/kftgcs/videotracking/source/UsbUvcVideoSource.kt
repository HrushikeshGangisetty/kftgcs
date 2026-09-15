package com.example.kftgcs.videotracking.source

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Surface
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import timber.log.Timber

/**
 * Opens a UVC (USB Video Class) camera — the Skydroid T12's USB video output —
 * and streams decoded frames directly into an Android [Surface].
 *
 * This talks to the USB device as a generic UVC webcam via the `libuvccamera`
 * (saki4510t) library. It does **not** go through the vendor's Skydroid FPV app;
 * that app only renders into its own floating overlay window (no SDK, intent, or
 * shared-Surface hand-off exists to read frames out of it), so direct UVC access
 * is the only real integration path available without a vendor-supplied SDK.
 *
 * NOT YET HARDWARE-VERIFIED: this assumes the T12 enumerates as a standard UVC
 * device when plugged in over USB (as opposed to a proprietary/vendor-specific
 * protocol that only the Skydroid app understands). [connect] will fail loudly
 * (via [onError]) if [UVCCamera.open] cannot negotiate a stream — that failure
 * is the confirmation signal this needs from real hardware.
 *
 * Lifecycle: [connect] opens the device and starts preview into [surface];
 * [release] tears everything down. Not reentrant — create a new instance per
 * attach/detach cycle, mirroring [com.example.kftgcs.telemetry.connections.UsbSerialMavConnection].
 */
class UsbUvcVideoSource(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null

    @Volatile
    var isStreaming: Boolean = false
        private set

    /**
     * Requests USB permission (if needed) and starts preview into [surface] once
     * granted. [onReady] fires when frames should be flowing; [onError] fires on
     * permission denial or a UVC negotiation failure (see class doc — this is the
     * expected place a non-UVC T12 firmware would surface).
     */
    fun connect(
        surface: Surface,
        previewWidth: Int = DEFAULT_PREVIEW_WIDTH,
        previewHeight: Int = DEFAULT_PREVIEW_HEIGHT,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!usbManager.hasPermission(device)) {
            onError("USB permission for the T12 was not granted before connect() was called.")
            return
        }

        val usbMonitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(attachedDevice: UsbDevice) = Unit
            // Spelled "onDettach" — that typo is in the library's own interface
            // (com.serenegiant.usb.USBMonitor.OnDeviceConnectListener), not here, so the
            // override has to match it. Do not "correct" it.
            override fun onDettach(detachedDevice: UsbDevice) = Unit

            override fun onConnect(
                connectedDevice: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock,
                createNew: Boolean
            ) {
                try {
                    val cam = UVCCamera()
                    cam.open(ctrlBlock)
                    val size = runCatching {
                        cam.setPreviewSize(
                            previewWidth,
                            previewHeight,
                            UVCCamera.FRAME_FORMAT_MJPEG
                        )
                    }.recoverCatching {
                        // Some UVC firmwares only offer YUYV, not MJPEG — fall back.
                        cam.setPreviewSize(
                            previewWidth,
                            previewHeight,
                            UVCCamera.FRAME_FORMAT_YUYV
                        )
                    }
                    if (size.isFailure) {
                        onError("T12 camera did not accept preview size ${previewWidth}x$previewHeight: " +
                            "${size.exceptionOrNull()?.message}")
                        cam.destroy()
                        return
                    }
                    cam.setPreviewDisplay(surface)
                    cam.startPreview()
                    camera = cam
                    isStreaming = true
                    Timber.i("UsbUvcVideoSource: T12 UVC preview started (%dx%d)", previewWidth, previewHeight)
                    onReady()
                } catch (e: Exception) {
                    Timber.e(e, "UsbUvcVideoSource: failed to open UVC device %s", connectedDevice.deviceName)
                    onError(
                        "Could not start the T12 USB video feed (${e.message}). " +
                            "This device may not expose a standard UVC stream over USB — " +
                            "confirm with `adb shell dumpsys usb` / a USB descriptor dump " +
                            "whether it enumerates as a UVC (class 14) interface."
                    )
                }
            }

            override fun onDisconnect(disconnectedDevice: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                Timber.w("UsbUvcVideoSource: T12 USB device disconnected")
                release()
            }

            override fun onCancel(cancelledDevice: UsbDevice) {
                onError("USB permission for the T12 was denied.")
            }
        })
        monitor = usbMonitor
        usbMonitor.register()

        // Callers are expected to have already awaited
        // UsbUvcDeviceManager.requestPermission() before invoking connect(); this
        // request is what actually triggers onConnect() above once granted (it
        // resolves immediately if permission is already held).
        usbMonitor.requestPermission(device)
    }

    fun release() {
        isStreaming = false
        runCatching {
            camera?.stopPreview()
            camera?.destroy()
        }
        camera = null
        runCatching { monitor?.unregister() }
        runCatching { monitor?.destroy() }
        monitor = null
    }

    companion object {
        // Conservative default: the T12's actual supported modes are unconfirmed
        // against hardware. UVCCamera.getSupportedSize()/queryPreviewSize offers
        // the camera's real modes once opened — wire that into the UI once a unit
        // is available to test against, rather than guessing further here.
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
    }
}
