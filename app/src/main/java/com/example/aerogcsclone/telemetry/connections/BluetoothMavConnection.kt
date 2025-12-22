package com.example.aerogcsclone.telemetry.connections

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.common.CommonDialect
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission") // Permissions are checked before this class is instantiated
class BluetoothMavConnection(
    private val bluetoothDevice: BluetoothDevice
) : MavConnection {

    companion object {
        // The standard UUID for the Serial Port Profile (SPP)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Increased buffer size for Bluetooth reliability
        private const val BUFFER_SIZE = 2048

        // Reconnection backoff delay
        const val RECONNECT_DELAY_MS = 2000L
    }

    private var socket: BluetoothSocket? = null
    private var bufferedConnection: BufferedMavConnection? = null

    @Throws(IOException::class)
    override fun connect() {
        // Ensure any previous connection is closed
        close()

        try {
            val socket = bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect() // This is a blocking call

            this.socket = socket
            this.bufferedConnection = BufferedMavConnection(
                socket.inputStream.source().buffer(),
                socket.outputStream.sink().buffer(),
                socket, // The socket is the closeable resource
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
            // It's safe to ignore exceptions here, as we're tearing down the connection.
        }
        bufferedConnection = null
        socket = null
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
}