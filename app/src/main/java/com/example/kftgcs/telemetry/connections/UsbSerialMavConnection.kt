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
        // 0 = blocking read through a queued UsbRequest, which is what usb-serial-for-android
        // recommends: a bulkTransfer with a short timeout (the library saw it up to 200 ms) loses
        // data on a continuous stream such as a log download. close() unblocks the read by closing
        // the port, which cancels the request. Set this back to 200 to restore the old behaviour.
        private const val READ_TIMEOUT_MS = 0
        private const val WRITE_TIMEOUT_MS = 2000

        // Every USB read goes into a buffer of this size. It must be a multiple of the endpoint's
        // packet size (64 or 512): a read into anything smaller than the incoming packet overflows
        // and the kernel throws the bytes away. 16 KB is also the most Android moves per request.
        private const val READ_BUFFER_SIZE = 16 * 1024

        /** Name registered in [UsbPortOwnership] while this connection holds the port. */
        const val OWNER = "MAVLink telemetry (USB)"
    }

    private var port: UsbSerialPort? = null
    private var bufferedConnection: BufferedMavConnection? = null

    @Throws(IOException::class)
    override fun connect() {
        // Ensure any previous connection is closed
        close()

        // Refuse rather than force-claim a port something else in the app already has open (e.g.
        // T12 video) — stealing the interface kills the other link and usually this one too.
        UsbPortOwnership.claim(device, OWNER)?.let { holder ->
            if (holder != OWNER) {
                throw IOException("USB device ${device.deviceName} is already in use by $holder. Stop it first.")
            }
        }

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
            // Some CDC devices send nothing until the host raises DTR. Not every driver supports
            // the control lines, so a failure here is not fatal.
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
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
        } catch (e: Exception) {
            // Not just IOException: openDevice/open can throw SecurityException or
            // IllegalArgumentException, and the ownership claim must be released for those too.
            close() // Clean up on failure
            throw e as? IOException ?: IOException(e.message ?: "USB open failed", e)
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
        UsbPortOwnership.release(device, OWNER)
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
     * Bridges [UsbSerialPort.read] into a blocking [InputStream]. USB is always read into [buf]
     * (see [READ_BUFFER_SIZE]) and the caller is served from it, because okio asks for whatever
     * space is left in its segment, which is usually not a whole number of USB packets. A read
     * that returns 0 just means "nothing yet"; we loop until bytes arrive or the stream is closed.
     * A real device error propagates as [IOException].
     */
    private class UsbSerialInputStream(private val port: UsbSerialPort) : InputStream() {

        @Volatile
        private var closed = false

        private val single = ByteArray(1)

        private val buf = ByteArray(READ_BUFFER_SIZE)
        private var pos = 0
        private var limit = 0

        @Throws(IOException::class)
        override fun read(): Int {
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            while (pos == limit) {
                if (closed) return -1
                try {
                    limit = port.read(buf, READ_TIMEOUT_MS)
                    pos = 0
                } catch (e: Exception) {
                    // close() races with a read in flight; whatever that throws is end-of-stream.
                    if (closed) return -1
                    throw e as? IOException ?: IOException(e.message ?: "USB read failed", e)
                }
            }

            val n = minOf(len, limit - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
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
