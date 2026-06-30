package com.example.kftgcs.telemetry.connections

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [MavConnection] over a USB OTG serial port using the usb-serial-for-android library.
 *
 * Mirrors [BluetoothMavConnection]: it opens the transport, then pipes the byte streams through
 * okio into a [BufferedMavConnection] using [ArdupilotmegaDialect]. The only difference is that
 * [UsbSerialPort] exposes blocking `read`/`write` calls rather than [InputStream]/[OutputStream],
 * so we bridge them with the thin adapters below.
 *
 * The USB device permission must already be granted before [connect] is called.
 */
class UsbSerialMavConnection(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val baudRate: Int
) : MavConnection {

    companion object {
        // Finite read timeout (ms) so the read loop stays interruptible and close() can't hang
        // indefinitely on a blocking bulk transfer. A 0-byte return just means "retry".
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 2000
    }

    private var port: UsbSerialPort? = null
    private var bufferedConnection: BufferedMavConnection? = null

    @Throws(IOException::class)
    override fun connect() {
        // Ensure any previous connection is closed
        close()

        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw IOException("No USB serial driver for device ${device.deviceName}")

            val usbConnection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device (permission not granted?)")

            val port = driver.ports.firstOrNull()
                ?: throw IOException("USB serial driver has no ports")

            port.open(usbConnection)
            port.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            this.port = port

            val input = UsbSerialInputStream(port)
            val output = UsbSerialOutputStream(port)

            // Closeable that tears down both the input adapter (stops the read loop) and the port.
            val resource = Closeable {
                input.close()
                try {
                    port.close()
                } catch (e: Exception) {
                    // Ignore — we're tearing down.
                }
            }

            this.bufferedConnection = BufferedMavConnection(
                input.source().buffer(),
                output.sink().buffer(),
                resource,
                ArdupilotmegaDialect
            )
        } catch (e: IOException) {
            close() // Clean up on failure
            throw e
        }
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            bufferedConnection?.close()
        } catch (e: IOException) {
            // Safe to ignore while tearing down.
        }
        bufferedConnection = null
        port = null
    }

    @Throws(IOException::class)
    override fun next(): MavFrame<out MavMessage<*>> {
        return bufferedConnection?.next() ?: throw IOException("Connection is not active.")
    }

    @Throws(IOException::class)
    override fun <T : MavMessage<T>> sendV1(systemId: UByte, componentId: UByte, payload: T) {
        bufferedConnection?.sendV1(systemId, componentId, payload) ?: throw IOException("Connection is not active.")
    }

    @Throws(IOException::class)
    override fun <T : MavMessage<T>> sendUnsignedV2(systemId: UByte, componentId: UByte, payload: T) {
        bufferedConnection?.sendUnsignedV2(systemId, componentId, payload) ?: throw IOException("Connection is not active.")
    }

    @Throws(IOException::class)
    override fun <T : MavMessage<T>> sendSignedV2(systemId: UByte, componentId: UByte, payload: T, linkId: UByte, timestamp: UInt, secretKey: ByteArray) {
        bufferedConnection?.sendSignedV2(systemId, componentId, payload, linkId, timestamp, secretKey) ?: throw IOException("Connection is not active.")
    }

    /**
     * Bridges [UsbSerialPort.read] into a blocking [InputStream]. The serial read returns 0 on a
     * timeout (no data yet); we loop until bytes arrive or the stream is closed, so okio sees normal
     * blocking semantics. A real device error propagates as [IOException].
     */
    private class UsbSerialInputStream(private val port: UsbSerialPort) : InputStream() {

        @Volatile
        private var closed = false

        private val single = ByteArray(1)

        // Reusable scratch buffer for offset reads (okio reads into segment offsets).
        private var scratch = ByteArray(0)

        @Throws(IOException::class)
        override fun read(): Int {
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            // port.read() always fills from index 0; read into the target directly when possible,
            // otherwise into a reusable scratch buffer and copy to the requested offset.
            val direct = off == 0
            val dest: ByteArray = if (direct) {
                b
            } else {
                if (scratch.size < len) scratch = ByteArray(len)
                scratch
            }

            while (!closed) {
                val n = port.read(dest, len, READ_TIMEOUT_MS)
                if (n > 0) {
                    if (!direct) System.arraycopy(dest, 0, b, off, n)
                    return n
                }
                // n == 0 -> timed out with no data; keep waiting.
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Bridges an [OutputStream] onto [UsbSerialPort.write].
     */
    private class UsbSerialOutputStream(private val port: UsbSerialPort) : OutputStream() {

        @Throws(IOException::class)
        override fun write(b: Int) {
            port.write(byteArrayOf(b.toByte()), WRITE_TIMEOUT_MS)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            port.write(data, WRITE_TIMEOUT_MS)
        }
    }
}
