package com.example.kftgcs.telemetry.connections

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.asCoroutine
import com.divpundir.mavlink.connection.udp.UdpClientMavConnection
import com.divpundir.mavlink.connection.udp.UdpServerMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import java.net.InetAddress

/**
 * Provides a MAVLink connection over UDP, using mavlink-kotlin's own `connection-udp` module.
 *
 * With [remoteHost] blank this is a **server**: it binds [localPort] with a `DatagramChannel`,
 * blocks until the first datagram arrives, and connects the channel to that sender — the same
 * bind-and-wait behaviour Mission Planner and QGroundControl use, and the setup nearly every RC
 * router and SITL expects. With [remoteHost] set it is a **client**: it sends to that endpoint
 * first so the peer learns our address, then waits for a reply.
 *
 * This replaced a hand-written transport that bound the port successfully but never received a
 * single datagram (verified on hardware: the app's own port Scan saw the stream on the same port at
 * the same moment that the connection read nothing). Two behaviours from that class are handled
 * elsewhere now, or not at all:
 *  - the Wi-Fi multicast lock for *broadcast* telemetry is held process-wide by [WifiMulticast];
 *  - re-latching to a peer whose port moves (a NAT rebind on a 4G link) is **not** supported here,
 *    because the channel is connected to the first sender. Mission Planner has the same limit.
 */
class UdpConnectionProvider(
    private val localPort: Int,
    private val remoteHost: String? = null,
    private val remotePort: Int = localPort
) : MavConnectionProvider {

    override fun createConnection(): CoroutinesMavConnection {
        val host = remoteHost?.takeIf { it.isNotBlank() }

        UdpDiagnostics.reset(localPort, host?.let { "$it:$remotePort" })
        // The channel is bound inside the library's open(), which the coroutine adapter calls on
        // connect, so there is no separate bind step to hook — record it here.
        UdpDiagnostics.onBound()
        if (host != null) noteReachability(host)

        val connection = if (host == null) {
            UdpServerMavConnection(localPort, ArdupilotmegaDialect)
        } else {
            UdpClientMavConnection(host, remotePort, ArdupilotmegaDialect)
        }

        return connection.asCoroutine()
    }

    /**
     * Records whether a pinned remote is reachable at all, so the failure dialog can say
     * "that address is not on any network this device is on" instead of timing out silently.
     *
     * Only for literal IPv4 addresses: this runs on the caller's thread (the ViewModel's, i.e. the
     * main thread), and resolving a *hostname* there would throw NetworkOnMainThreadException.
     * A literal needs no lookup.
     */
    private fun noteReachability(host: String) {
        if (!host.matches(IPV4_LITERAL)) return
        try {
            UdpDiagnostics.onRemoteResolved(InetAddress.getByName(host))
        } catch (e: Exception) {
            // Diagnostics only — never block a connection over this.
        }
    }

    private companion object {
        val IPV4_LITERAL = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    }
}
