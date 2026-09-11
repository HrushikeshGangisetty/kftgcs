package com.example.kftgcs.telemetry.connections

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide record of what the last UDP connection attempt actually did.
 *
 * This exists because the failure the customer sees ("Connection timed out") carries no
 * information, and the app is installed directly on the RC where no logcat is available.
 * [LogUtils][com.example.kftgcs.utils.LogUtils] is gated on `BuildConfig.DEBUG`, so in a release
 * build the transport currently reports *nothing* to anyone.
 *
 * It deliberately lives outside [UdpMavConnection]: the UI tears the connection down
 * (`SharedViewModel.cancelConnection()` closes the socket and nulls `repo`) *before* it renders the
 * failure dialog, so any state held on the connection object would already be gone by the time we
 * want to show it.
 *
 * The stages below are distinct on purpose — they separate the three failures that all look
 * identical today:
 *  1. **Bind failed** — the port could not be opened at all.
 *  2. **Bound, zero packets** — nothing is sending to this port (wrong port, or another app on the
 *     RC already owns it; note SO_REUSEADDR means our bind *succeeds* while datagrams go elsewhere).
 *  3. **Packets arriving, no MAVLink** — bytes are flowing but nothing parsed into a heartbeat,
 *     which is what `connected` actually waits for (see TelemetryRepository: `connected` flips on
 *     FCU heartbeat, not on StreamState.Active).
 */
object UdpDiagnostics {

    /** Set when a bind is attempted, so we can tell "never tried" from "tried and failed". */
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
    var bindError: String? = null
        private set

    @Volatile
    var bound: Boolean = false
        private set

    private val packetsReceived = AtomicInteger(0)
    private val bytesReceived = AtomicLong(0)
    private val packetsSent = AtomicInteger(0)
    private val sendErrors = AtomicInteger(0)
    private val firstPeer = AtomicReference<String?>(null)
    private val lastPeer = AtomicReference<String?>(null)

    @Volatile
    var lastSendError: String? = null
        private set

    /** Set by the repository once a MAVLink frame actually parses. */
    @Volatile
    var mavlinkFrameSeen: Boolean = false

    fun reset(localPort: Int, remote: String?) {
        attempted = true
        this.localPort = localPort
        configuredRemote = remote
        bindError = null
        bound = false
        packetsReceived.set(0)
        bytesReceived.set(0)
        packetsSent.set(0)
        sendErrors.set(0)
        firstPeer.set(null)
        lastPeer.set(null)
        lastSendError = null
        mavlinkFrameSeen = false
    }

    fun onBound() {
        bound = true
    }

    fun onBindFailed(message: String?) {
        bound = false
        bindError = message ?: "unknown"
    }

    fun onPacket(from: InetAddress, port: Int, length: Int) {
        packetsReceived.incrementAndGet()
        bytesReceived.addAndGet(length.toLong())
        val label = "${from.hostAddress}:$port"
        firstPeer.compareAndSet(null, label)
        lastPeer.set(label)
    }

    fun onSent() {
        packetsSent.incrementAndGet()
    }

    fun onSendError(message: String?) {
        sendErrors.incrementAndGet()
        lastSendError = message
    }

    /**
     * A single plain-language sentence naming the most likely cause, ordered so the first failing
     * stage wins. This is what goes at the top of the failure dialog.
     */
    fun verdict(): String = when {
        !attempted -> "No UDP connection was attempted."
        bindError != null ->
            "Could not open local port $localPort ($bindError). Another app on this device is " +
                "probably already using it — close the other ground station and try again."
        packetsReceived.get() == 0 && configuredRemote != null ->
            "Local port $localPort opened, but no data arrived in 10 seconds. Try clearing the " +
                "Remote Host field so the app just listens — that is how QGroundControl and " +
                "Mission Planner connect, and what the controller expects."
        packetsReceived.get() == 0 ->
            "Local port $localPort opened, but no data arrived in 10 seconds. Either nothing is " +
                "sending telemetry to this port, or another app on this device is already holding " +
                "it. Close any other ground station app, or use Scan to find the right port."
        !mavlinkFrameSeen ->
            "Data is arriving on port $localPort from ${lastPeer.get()}, but none of it is valid " +
                "MAVLink. The port is probably carrying a different protocol (e.g. video)."
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
        appendLine("MODE       : ${if (configuredRemote == null) "LISTEN-ONLY" else "SEEDED -> $configuredRemote"}")
        appendLine("local port : $localPort")
        appendLine("remote     : ${configuredRemote ?: "(listen only)"}")
        appendLine("bound      : ${if (bound) "yes" else "NO — ${bindError ?: "not attempted"}"}")
        appendLine("packets in : ${packetsReceived.get()} (${bytesReceived.get()} bytes)")
        appendLine("first peer : ${firstPeer.get() ?: "-"}")
        appendLine("last peer  : ${lastPeer.get() ?: "-"}")
        appendLine("packets out: ${packetsSent.get()}")
        appendLine("send errors: ${sendErrors.get()}${lastSendError?.let { " ($it)" } ?: ""}")
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
    val CANDIDATE_PORTS = listOf(14550, 14551, 14552, 14553, 14555, 5760, 5762)

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

    /** The best port to auto-fill, if any. */
    fun bestPort(results: List<UdpScanResult>): Int? = bestResult(results)?.port

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
