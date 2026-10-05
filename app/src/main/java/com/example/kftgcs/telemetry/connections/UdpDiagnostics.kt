package com.example.kftgcs.telemetry.connections

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Process-wide record of what the last UDP connection attempt actually did.
 *
 * This exists because the failure the customer sees ("Connection timed out") carries no
 * information, and the app is installed directly on the RC where no logcat is available.
 * [LogUtils][com.example.kftgcs.utils.LogUtils] is gated on `BuildConfig.DEBUG`, so in a release
 * build the transport reports *nothing* to anyone.
 *
 * It deliberately lives outside the connection: the UI tears the connection down
 * (`SharedViewModel.cancelConnection()`) *before* it renders the failure dialog, so any state held
 * on the connection object would already be gone by the time we want to show it.
 *
 * The connection itself is mavlink-kotlin's `connection-udp`, which exposes no per-packet hooks, so
 * what is recorded here is only what we can know from the outside: what was configured, whether the
 * configured remote is even reachable from this device, whether the multicast lock is held, and
 * whether any MAVLink frame ever parsed. That is enough to separate the failures that used to look
 * identical:
 *  1. **Remote not on any local subnet** — nothing sent there can arrive (measured on a SIYI MK15).
 *  2. **Nothing arrived** — wrong port, or nothing is sending to it.
 *  3. **Frames arrived, no FCU heartbeat** — the link is up but the autopilot is not answering.
 */
object UdpDiagnostics {

    /** Set when a connection is attempted, so we can tell "never tried" from "tried and failed". */
    @Volatile
    var attempted: Boolean = false
        private set

    @Volatile
    var localPort: Int = 0
        private set

    @Volatile
    var configuredRemote: String? = null
        private set

    @Volatile
    var bound: Boolean = false
        private set

    /** "held", or why the Wi-Fi multicast lock could not be taken (broadcast telemetry is lost). */
    @Volatile
    var multicastLock: String = "-"

    /** Null when listening only; else whether the pinned remote is on a subnet this device is on. */
    @Volatile
    var remoteOnLocalSubnet: Boolean? = null
        private set

    /** This device's own IPv4 addresses, e.g. "wlan0 10.141.214.23/24". */
    @Volatile
    var localAddresses: String = "-"
        private set

    /** Set by the repository once a MAVLink frame actually parses. */
    @Volatile
    var mavlinkFrameSeen: Boolean = false

    fun reset(localPort: Int, remote: String?) {
        attempted = true
        this.localPort = localPort
        configuredRemote = remote
        bound = false
        remoteOnLocalSubnet = null
        localAddresses = "-"
        mavlinkFrameSeen = false
    }

    fun onBound() {
        bound = true
    }

    /**
     * Records whether [remote] shares a subnet with any interface on this device.
     *
     * Measured on a SIYI MK15 handheld: its documented datalink address 192.168.144.12 exists only
     * on the ground unit's Ethernet/USB port, so from an app on the RC's own Android there is no
     * route to it. Sending still "succeeds" (the kernel hands the datagram to the default route),
     * so without this check the only symptom is a silent 10-second timeout.
     */
    fun onRemoteResolved(remote: InetAddress) {
        val locals = mutableListOf<String>()
        var sameSubnet = false
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    locals += "${nif.name} ${addr.hostAddress}/${ia.networkPrefixLength}"
                    if (remote is Inet4Address && sameSubnet(addr, remote, ia.networkPrefixLength.toInt())) {
                        sameSubnet = true
                    }
                }
            }
        } catch (e: Exception) {
            // Best effort — this is diagnostics, never a reason to fail a connection.
        }
        localAddresses = locals.joinToString(", ").ifEmpty { "-" }
        remoteOnLocalSubnet = sameSubnet
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLen: Int): Boolean {
        if (prefixLen !in 1..32) return false
        fun toInt(x: Inet4Address) = x.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        val mask = (-1 shl (32 - prefixLen))
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    /**
     * A single plain-language sentence naming the most likely cause, ordered so the first failing
     * stage wins. This is what goes at the top of the failure dialog.
     */
    fun verdict(): String = when {
        !attempted -> "No UDP connection was attempted."
        remoteOnLocalSubnet == false ->
            "$configuredRemote is not on any network this device is connected to " +
                "($localAddresses), so nothing sent there can arrive. On a SIYI MK15 the " +
                "192.168.144.x datalink exists only on the ground unit's Ethernet/USB port — an " +
                "app running on the controller itself cannot reach it. Use the controller's " +
                "Bluetooth or USB datalink mode instead, or run this app on a device plugged into " +
                "that Ethernet port."
        configuredRemote != null ->
            "Nothing came back from $configuredRemote in 10 seconds. Check that address and port " +
                "against the controller's manual (SIYI MK15: 192.168.144.12:19856). If the " +
                "controller sends telemetry unprompted (most RC routers do), clear the Remote Host " +
                "field and just listen."
        !mavlinkFrameSeen ->
            "Local port $localPort opened, but no telemetry arrived in 10 seconds. Either nothing " +
                "is sending to this port, or another app on this device is already holding it. " +
                "Close any other ground station app, or use Scan to find the right port."
        else ->
            "MAVLink frames were received, but no flight-controller heartbeat. The link is up but " +
                "the autopilot is not responding."
    }

    /**
     * Compact technical block the customer can screenshot or copy back to support.
     *
     * MODE is deliberately first: it is the single fact that distinguishes a real listen-only test
     * from an attempt that still had a Remote Host set, and on a short RC screen only the first
     * line or two survive a screenshot.
     */
    fun details(): String = buildString {
        appendLine("UDP diagnostics")
        appendLine("MODE       : ${if (configuredRemote == null) "LISTEN-ONLY" else "TARGET -> $configuredRemote"}")
        appendLine("local port : $localPort")
        appendLine("remote     : ${configuredRemote ?: "(listen only)"}")
        appendLine("opened     : ${if (bound) "yes" else "no"}")
        appendLine("mcast lock : $multicastLock")
        appendLine("local addrs: $localAddresses")
        appendLine("remote rout: " + when (remoteOnLocalSubnet) {
            null -> "n/a (listen only)"
            true -> "on a local subnet"
            false -> "NOT on any local subnet - unreachable from here"
        })
        append("mavlink    : ${if (mavlinkFrameSeen) "parsed OK" else "none parsed"}")
    }

    fun hasData(): Boolean = attempted
}

/** One candidate found by [UdpPortScanner]. */
data class UdpScanResult(
    val port: Int,
    val packets: Int,
    val bytes: Int,
    val peer: String?,
    val looksLikeMavlink: Boolean
)

/**
 * Briefly listens on a set of candidate loopback ports to find which one is actually carrying
 * MAVLink. Used by the "Scan" button so the customer does not have to know their RC's port map.
 *
 * The RC (Skydroid G12 and friends) runs its own MAVLink service that forwards telemetry to
 * loopback; which port it uses varies by firmware, and the app ships on the controller itself, so
 * probing locally is both safe and the fastest way to an answer.
 */
object UdpPortScanner {

    /** Ports worth trying: ArduPilot/QGC defaults plus the common router split-port pairs. */
    val CANDIDATE_PORTS = listOf(14550, 14551, 14552, 14553, 14555, 19856, 5760, 5762)

    /**
     * Binds each candidate for [perPortMillis] and reports what arrived. Ports that cannot be bound
     * (already in use) are reported with `packets = -1` so the caller can say so explicitly.
     */
    /**
     * Minimum packets before a port is treated as carrying a real stream.
     *
     * A telemetry stream runs at several Hz, so a genuine source yields multiple packets inside the
     * sample window. One or two packets is not a stream — it is a single reply or a stray datagram,
     * and treating it as telemetry is what made an earlier scan report a port that had nothing
     * usable on it. Anything below this is reported but never auto-applied.
     */
    const val MIN_PACKETS_FOR_STREAM = 3

    fun scan(
        ports: List<Int> = CANDIDATE_PORTS,
        perPortMillis: Long = 1500L
    ): List<UdpScanResult> {
        val results = mutableListOf<UdpScanResult>()
        for (port in ports) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 150
                    bind(InetSocketAddress(port))
                }
                // Our own source port, so we can discard anything we emitted ourselves. Without
                // this a probe sent by a live connection can echo back and be counted as telemetry.
                val ownPort = socket.localPort
                var packets = 0
                var bytes = 0
                var peer: String? = null
                var mavlink = false
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + perPortMillis
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    // Ignore datagrams that originated from this very socket.
                    if (p.address?.isLoopbackAddress == true && p.port == ownPort) continue
                    packets++
                    bytes += p.length
                    if (peer == null) peer = "${p.address?.hostAddress}:${p.port}"
                    // MAVLink v1 starts 0xFE, v2 starts 0xFD.
                    if (p.length > 0) {
                        val stx = buf[0].toInt() and 0xFF
                        if (stx == 0xFD || stx == 0xFE) mavlink = true
                    }
                }
                results += UdpScanResult(port, packets, bytes, peer, mavlink)
            } catch (e: Exception) {
                // Could not bind — almost always "already in use", which is itself useful to report.
                results += UdpScanResult(port, -1, 0, null, false)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
        return results
    }

    /** Human-readable summary of a scan, for the dialog. */
    fun summarize(results: List<UdpScanResult>): String {
        val live = results.filter { it.packets > 0 }
        val busy = results.filter { it.packets == -1 }
        return buildString {
            if (live.isEmpty()) {
                appendLine("No telemetry found on any common port.")
                if (busy.isNotEmpty()) {
                    appendLine(
                        "In use by another app: " + busy.joinToString(", ") { it.port.toString() }
                    )
                    appendLine("Close any other ground station app and scan again.")
                }
            } else {
                appendLine("Found data on:")
                live.sortedByDescending { it.looksLikeMavlink }.forEach {
                    val tag = when {
                        it.looksLikeMavlink && it.packets >= MIN_PACKETS_FOR_STREAM -> "  ← MAVLink"
                        it.looksLikeMavlink -> "  (MAVLink, but only ${it.packets} pkt — not a stream)"
                        else -> "  (not MAVLink)"
                    }
                    appendLine("  port ${it.port} — ${it.packets} pkts from ${it.peer ?: "?"}$tag")
                }
                if (busy.isNotEmpty()) {
                    appendLine(
                        "In use by another app: " + busy.joinToString(", ") { it.port.toString() }
                    )
                }
            }
        }.trim()
    }

    /**
     * The winning row — the port that actually carried a stream.
     *
     * Only the *local* port from this row should ever be applied to the UI. An earlier version also
     * derived a send target from the row's peer label; that was wrong. Re-populating Remote Host on
     * every scan silently switched the app back into seed-and-probe mode, so a user who cleared the
     * field and then scanned got tested in the very mode we were trying to avoid. The send target
     * is learned at runtime from the first received datagram (PeerAddress.observe), which is what
     * QGroundControl and Mission Planner both do.
     */
    fun bestResult(results: List<UdpScanResult>): UdpScanResult? =
        results.filter { it.looksLikeMavlink && it.packets >= MIN_PACKETS_FOR_STREAM }
            .maxByOrNull { it.packets }
            ?: results.filter { it.packets >= MIN_PACKETS_FOR_STREAM }.maxByOrNull { it.packets }

}
