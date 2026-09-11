package com.example.kftgcs.telemetry.connections

import android.content.Context
import android.net.wifi.WifiManager
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.example.kftgcs.utils.LogUtils
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * A [MavConnection] over UDP.
 *
 * Two modes, matching the conventions used by Mission Planner / QGroundControl:
 *
 *  - **Server / listen mode** (no [remoteHost]): bind a local [DatagramSocket] on [localPort] and
 *    learn the vehicle's address from inbound datagrams. This is the usual setup for SITL
 *    (`--out udp:<gcs-ip>:14550`) and for telemetry radios / companion computers that push to the GCS.
 *  - **Client mode** ([remoteHost] set): send an initial packet to `remoteHost:remotePort` so the
 *    peer learns our address, then talk to that endpoint.
 *
 * Mirrors [BluetoothMavConnection] / [UsbSerialMavConnection]: open the transport, then pipe the
 * byte streams through okio into a [BufferedMavConnection] using [ArdupilotmegaDialect]. UDP is
 * datagram-oriented, so the adapters below flatten packets into a byte stream on read and wrap each
 * write in a single datagram (MAVLink frames max out at 280 bytes, far under the UDP payload limit).
 *
 * [appContext] is optional and only used to hold a [WifiManager.MulticastLock] so broadcast
 * telemetry (e.g. to 255.255.255.255) is not dropped by Android's Wi-Fi filter.
 */
class UdpMavConnection(
    private val localPort: Int,
    private val remoteHost: String? = null,
    private val remotePort: Int = localPort,
    private val appContext: Context? = null
) : MavConnection {

    companion object {
        private const val TAG = "UdpMavConn"

        // Finite receive timeout (ms) so the read loop stays interruptible and close() can't hang
        // on a blocking receive(). A timeout just means "retry".
        private const val RECEIVE_TIMEOUT_MS = 200

        // Max UDP payload we'll accept in one datagram.
        private const val MAX_DATAGRAM = 65_507
    }

    private var socket: DatagramSocket? = null
    private var bufferedConnection: BufferedMavConnection? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Throws(IOException::class)
    override fun connect() {
        // Ensure any previous connection is closed
        close()

        UdpDiagnostics.reset(
            localPort,
            if (remoteHost.isNullOrBlank()) null else "$remoteHost:$remotePort"
        )

        var newSocket: DatagramSocket? = null
        try {
            // Gap #8: Android's Wi-Fi stack drops broadcast/multicast datagrams unless a multicast
            // lock is held. Some ground setups broadcast telemetry rather than unicasting it.
            acquireMulticastLock()

            newSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = RECEIVE_TIMEOUT_MS
                    bind(InetSocketAddress(localPort))
                }
            } catch (e: Exception) {
                // Record the bind failure before rethrowing so the UI can name the real cause
                // instead of showing a bare timeout.
                UdpDiagnostics.onBindFailed(e.message)
                throw if (e is IOException) e else IOException("Cannot bind UDP port $localPort", e)
            }
            UdpDiagnostics.onBound()
            this.socket = newSocket

            // Holds the peer address. In client mode we seed it up front; in server mode the input
            // stream fills it in from the first received packet. Either way it re-latches to
            // whoever most recently talked to us (see PeerAddress.observe).
            val peer = PeerAddress()

            if (!remoteHost.isNullOrBlank()) {
                // Gap #6: resolve explicitly so a bad hostname surfaces as a clear IOException here
                // rather than from a property getter deep in the write path.
                val resolved = try {
                    InetAddress.getByName(remoteHost)
                } catch (e: IOException) {
                    throw IOException("Cannot resolve UDP remote host '$remoteHost'", e)
                }

                // Never seed a LOOPBACK remote, on any port.
                //
                // On this hardware the router pushes telemetry to our bound port unprompted, so a
                // loopback seed buys nothing and can cost us the link: if nothing is bound at the
                // target loopback port the kernel replies to our own datagrams with ICMP
                // port-unreachable, and a pending ICMP error on the socket can suppress inbound
                // delivery. Measured symptom: "packets out: 9, packets in: 0" on a port that a scan
                // moments earlier had proved was carrying telemetry.
                //
                // Listen-only is also what QGroundControl and Mission Planner do — neither invents
                // a target — so PeerAddress learns the real endpoint from the first inbound packet.
                if (resolved.isLoopbackAddress) {
                    LogUtils.w(
                        TAG,
                        "Ignoring loopback remote $remoteHost:$remotePort — listening only on " +
                            "$localPort and learning the peer from inbound packets."
                    )
                } else {
                    peer.seed(resolved, remotePort)

                    // Gap #5: nudge the peer with a real MAVLink heartbeat rather than an empty
                    // datagram — ArduPilot/MAVProxy ignore zero-length payloads for peer learning.
                    try {
                        newSocket.send(DatagramPacket(HEARTBEAT_PROBE, HEARTBEAT_PROBE.size, resolved, remotePort))
                    } catch (e: IOException) {
                        // Non-fatal: the vehicle may still start streaming to us on its own.
                        LogUtils.w(TAG, "Initial UDP probe to $remoteHost:$remotePort failed: ${e.message}")
                    }
                }
            }

            val input = UdpInputStream(newSocket, peer)
            val output = UdpOutputStream(newSocket, peer)

            val socketRef = newSocket
            val resource = Closeable {
                input.close()
                try {
                    socketRef.close()
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
            val target = if (!remoteHost.isNullOrBlank()) " -> $remoteHost:$remotePort" else " (listening for peer)"
            LogUtils.i(TAG, "UDP link open on :$localPort$target")
        } catch (e: IOException) {
            // Gap #7: the socket is bound but no BufferedMavConnection owns it yet, so close() alone
            // would leak the port. Close it directly before delegating to the normal cleanup.
            try {
                newSocket?.close()
            } catch (_: Exception) {
                // Ignore — we're already failing.
            }
            this.socket = null
            close()
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
        // Gap #7: if we never got as far as building the BufferedMavConnection, nothing else owns
        // the socket — close it here so the bound port is released immediately.
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore — we're tearing down.
        }
        bufferedConnection = null
        socket = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val ctx = appContext ?: return
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("kftgcs-udp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Best effort — unicast still works without it.
            LogUtils.w(TAG, "Could not acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // Ignore — we're tearing down.
        }
        multicastLock = null
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
     * Thread-safe holder for the current peer endpoint.
     *
     * Gap #3: the endpoint must track the *most recent* sender, not just the first one. Telemetry
     * radios, 4G/LTE links and NAT rebinds routinely bring the vehicle back on a new source port.
     * Receiving is address-agnostic, so a latched-forever peer means telemetry keeps flowing (the UI
     * looks healthy) while every outbound command, param write and mission item goes to a dead port.
     * Mission Planner and QGC both re-latch; so do we.
     *
     * When the user pinned an explicit remote host we still re-latch, but only to a packet coming
     * from that same address — a different port on the pinned host is the NAT-rebind case we want to
     * follow, while an unrelated address is ignored.
     */
    private class PeerAddress {
        @Volatile private var addr: InetAddress? = null
        @Volatile private var p: Int = 0

        /** Set when the user pinned a remote host; restricts re-latching to that address. */
        @Volatile private var pinnedAddr: InetAddress? = null

        fun isKnown(): Boolean = addr != null

        /** Current endpoint, or null if no peer is known yet. Never throws. */
        fun current(): InetSocketAddress? {
            val a = addr ?: return null
            return InetSocketAddress(a, p)
        }

        /** Seed from an explicitly configured remote host. */
        fun seed(address: InetAddress, port: Int) {
            pinnedAddr = address
            addr = address
            p = port
        }

        /**
         * Called for every received datagram. Adopts the sender as the peer, logging whenever the
         * endpoint actually moves so a rebind is visible in the logs.
         */
        fun observe(from: InetAddress, fromPort: Int) {
            val pinned = pinnedAddr
            if (pinned != null && pinned != from) {
                // A pinned host was configured and this packet is from somewhere else — ignore it
                // for peer-tracking purposes (we still parse its bytes).
                return
            }
            val prevAddr = addr
            val prevPort = p
            if (prevAddr == from && prevPort == fromPort) return

            addr = from
            p = fromPort
            if (prevAddr == null) {
                LogUtils.i(TAG, "UDP peer learned: ${from.hostAddress}:$fromPort")
            } else {
                LogUtils.w(
                    TAG,
                    "UDP peer moved: ${prevAddr.hostAddress}:$prevPort -> ${from.hostAddress}:$fromPort"
                )
            }
        }
    }

    /**
     * Bridges datagram [DatagramSocket.receive] into a blocking [InputStream]. Each received packet
     * is buffered and drained to okio. A receive timeout yields no data and we retry, so okio sees
     * normal blocking semantics. Every packet also refreshes the peer address used for sends.
     */
    private class UdpInputStream(
        private val socket: DatagramSocket,
        private val peer: PeerAddress
    ) : InputStream() {

        @Volatile
        private var closed = false

        private val packetBuf = ByteArray(MAX_DATAGRAM)
        private var buffered: ByteArray = ByteArray(0)
        private var pos = 0

        @Throws(IOException::class)
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            while (pos >= buffered.size) {
                if (closed) return -1
                // A fresh DatagramPacket each time: after a failed receive the JDK leaves the
                // packet's length at the full buffer size, so reusing one risks a stale length.
                val packet = DatagramPacket(packetBuf, packetBuf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue // No data yet; keep waiting.
                } catch (e: IOException) {
                    if (closed) return -1
                    throw e
                }
                // Gap #3: re-latch to whoever is currently talking to us, every packet.
                val from = packet.address
                if (from != null) {
                    peer.observe(from, packet.port)
                    UdpDiagnostics.onPacket(from, packet.port, packet.length)
                }

                if (packet.length <= 0) continue
                buffered = packetBuf.copyOfRange(0, packet.length)
                pos = 0
            }

            val available = buffered.size - pos
            val n = if (len < available) len else available
            System.arraycopy(buffered, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    /** Bridges an [OutputStream] onto [DatagramSocket.send], one datagram per write. */
    private class UdpOutputStream(
        private val socket: DatagramSocket,
        private val peer: PeerAddress
    ) : OutputStream() {

        @Throws(IOException::class)
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            // Gap #4: never fail silently. If no vehicle has ever spoken to us there is nowhere to
            // send, and a dropped command must not look like a delivered one — surface it as an
            // IOException so the connection is marked failed instead of appearing healthy.
            val endpoint = peer.current()
                ?: run {
                    UdpDiagnostics.onSendError("no peer known")
                    throw IOException("No UDP peer yet — cannot send ${len}B (nothing has been received on this port).")
                }
            try {
                socket.send(DatagramPacket(b, off, len, endpoint.address, endpoint.port))
                UdpDiagnostics.onSent()
            } catch (e: IOException) {
                UdpDiagnostics.onSendError(e.message)
                throw e
            }
        }
    }
}

/**
 * A minimal MAVLink v2 HEARTBEAT frame used purely to announce our address to a pinned remote host
 * so it starts streaming back. Type/autopilot are set to GCS/INVALID, matching what a ground station
 * advertises. Precomputed because it never varies.
 *
 * Layout: STX(0xFD) LEN(9) INCOMPAT(0) COMPAT(0) SEQ(0) SYSID(255) COMPID(190) MSGID(0,0,0)
 *         payload[9] CRC(lo,hi)
 */
private val HEARTBEAT_PROBE: ByteArray = buildHeartbeatProbe()

private fun buildHeartbeatProbe(): ByteArray {
    // HEARTBEAT payload (v2, little endian): custom_mode u32, type u8, autopilot u8,
    // base_mode u8, system_status u8, mavlink_version u8
    val payload = byteArrayOf(
        0, 0, 0, 0,          // custom_mode = 0
        6,                   // type = MAV_TYPE_GCS
        8,                   // autopilot = MAV_AUTOPILOT_INVALID
        0,                   // base_mode = 0
        4,                   // system_status = MAV_STATE_ACTIVE
        3                    // mavlink_version = 3
    )

    val frame = ByteArray(12 + payload.size)
    frame[0] = 0xFD.toByte()          // STX v2
    frame[1] = payload.size.toByte()  // LEN
    frame[2] = 0                      // incompat_flags
    frame[3] = 0                      // compat_flags
    frame[4] = 0                      // seq
    frame[5] = 255.toByte()           // sysid (GCS convention)
    frame[6] = 190.toByte()           // compid = MAV_COMP_ID_MISSIONPLANNER
    frame[7] = 0                      // msgid low  (HEARTBEAT = 0)
    frame[8] = 0                      // msgid mid
    frame[9] = 0                      // msgid high
    payload.copyInto(frame, 10)

    // X.25 CRC over LEN..payload, then the HEARTBEAT crc_extra (50).
    var crc = 0xFFFF
    for (i in 1 until 10 + payload.size) {
        crc = crcAccumulate(frame[i], crc)
    }
    crc = crcAccumulate(50, crc)
    frame[10 + payload.size] = (crc and 0xFF).toByte()
    frame[11 + payload.size] = ((crc shr 8) and 0xFF).toByte()
    return frame
}

private fun crcAccumulate(data: Byte, crc: Int): Int {
    var tmp = (data.toInt() and 0xFF) xor (crc and 0xFF)
    tmp = tmp xor ((tmp shl 4) and 0xFF)
    return ((crc shr 8) and 0xFF) xor (tmp shl 8) xor (tmp shl 3) xor ((tmp shr 4) and 0x0F)
}
