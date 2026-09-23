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

/**
 * Requests USB runtime permission for a device, as a one-shot [Flow].
 *
 * This mirrors the broadcast-receiver pattern already used for the MAVLink USB
 * connection in [com.example.kftgcs.telemetry.SharedViewModel.requestUsbPermission];
 * it is duplicated here (rather than shared) because that one is an instance
 * method on the telemetry ViewModel, and the video player composables should
 * not need to depend on it.
 *
 * Callers of this for T12 video should be aware: the T12 exposes exactly one
 * USB-serial port, which MAVLink telemetry may already have open via
 * [com.example.kftgcs.telemetry.connections.UsbSerialMavConnection]. Both register
 * in [com.example.kftgcs.telemetry.connections.UsbPortOwnership], and whichever
 * starts second refuses with a clear message instead of stealing the interface.
 * See [T12SerialVideoSource]'s class doc.
 */
object UsbSerialPermission {

    private const val ACTION = "com.example.kftgcs.videotracking.T12_USB_PERMISSION"

    fun request(context: Context, usbManager: UsbManager, device: UsbDevice): Flow<Boolean> =
        callbackFlow {
            if (usbManager.hasPermission(device)) {
                trySend(true)
                close()
                return@callbackFlow
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION) return
                    trySend(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    close()
                }
            }

            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(device, permissionIntent)

            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }
}
