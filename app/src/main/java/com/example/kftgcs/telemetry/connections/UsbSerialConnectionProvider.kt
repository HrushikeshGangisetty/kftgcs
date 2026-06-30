package com.example.kftgcs.telemetry.connections

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.asCoroutine

class UsbSerialConnectionProvider(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val baudRate: Int
) : MavConnectionProvider {
    override fun createConnection(): CoroutinesMavConnection {
        return UsbSerialMavConnection(usbManager, device, baudRate).asCoroutine()
    }
}
