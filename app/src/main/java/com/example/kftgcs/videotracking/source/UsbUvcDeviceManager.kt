package com.example.kftgcs.videotracking.source

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * Finds and requests permission for a Skydroid T12 (or other UVC webcam) attached
 * over USB OTG, mirroring the attach/permission flow already used for USB-serial
 * flight-controller connections in
 * [com.example.kftgcs.telemetry.connections.UsbSerialConnectionProvider].
 *
 * USB Video Class devices report interface class 14 (0x0E) on at least one of
 * their interfaces; that is how this identifies "a UVC camera is plugged in"
 * without needing to hardcode the T12's vendor/product ID (which has not been
 * confirmed against hardware — see [isLikelyUvcDevice]).
 */
object UsbUvcDeviceManager {

    private const val USB_CLASS_VIDEO = 14 // android.hardware.usb.UsbConstants.USB_CLASS_VIDEO
    private const val ACTION_USB_PERMISSION = "com.example.kftgcs.videotracking.USB_UVC_PERMISSION"

    /** True if any interface on this device advertises the UVC (Video) class. */
    fun isLikelyUvcDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_VIDEO) return true
        }
        return false
    }

    /** All currently-attached USB devices that look like a UVC camera (e.g. a T12). */
    fun findAttachedUvcDevices(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values.filter { isLikelyUvcDevice(it) }

    /**
     * Requests runtime permission to open [device], suspending (as a cold [Flow] of
     * one value) until the user answers the system permission dialog or it is
     * already granted. Emits `true`/`false` once, then completes.
     */
    fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice): Flow<Boolean> =
        callbackFlow {
            if (usbManager.hasPermission(device)) {
                trySend(true)
                close()
                return@callbackFlow
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Timber.d("UsbUvcDeviceManager: permission result granted=%s for %s", granted, device.deviceName)
                    trySend(granted)
                    close()
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(device, permissionIntent)

            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }
}
