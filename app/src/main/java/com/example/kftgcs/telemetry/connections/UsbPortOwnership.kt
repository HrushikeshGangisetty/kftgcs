package com.example.kftgcs.telemetry.connections

import android.hardware.usb.UsbDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide record of which component currently has a USB device's serial port open.
 *
 * Android lets two `UsbDeviceConnection`s in one process open the same device, and
 * usb-serial-for-android claims the interface with `force = true`. So a second opener does not
 * fail cleanly. It takes the interface away from the first one, which then sees its bulk
 * transfers fail with `rc=-1`, and often both links end up dead. The Skydroid T12 makes this easy
 * to hit: telemetry and video both want its single CP2102 port.
 *
 * Owners register here after a successful open and release on close, so a second component can
 * refuse up front with a message naming who holds the port.
 */
object UsbPortOwnership {

    private val owners = ConcurrentHashMap<String, String>()

    /** Records [owner] as holding [device]. Returns the existing owner instead if one is set. */
    fun claim(device: UsbDevice, owner: String): String? =
        owners.putIfAbsent(device.deviceName, owner)

    /** Releases [device] if (and only if) [owner] is the one holding it. */
    fun release(device: UsbDevice, owner: String) {
        owners.remove(device.deviceName, owner)
    }

    fun ownerOf(device: UsbDevice): String? = owners[device.deviceName]
}
