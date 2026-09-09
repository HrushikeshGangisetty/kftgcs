package com.example.kftgcs.telemetry.connections

import android.content.Context
import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.asCoroutine

/**
 * Provides a MAVLink connection over UDP.
 *
 * With [remoteHost] blank/null this listens on [localPort] and learns the vehicle address from
 * inbound packets (the common SITL / telemetry-radio setup). With [remoteHost] set it also pushes to
 * that endpoint.
 *
 * [appContext] is optional; when supplied the connection holds a multicast lock so broadcast
 * telemetry is not filtered out by Android's Wi-Fi stack.
 */
class UdpConnectionProvider(
    private val localPort: Int,
    private val remoteHost: String? = null,
    private val remotePort: Int = localPort,
    private val appContext: Context? = null
) : MavConnectionProvider {
    override fun createConnection(): CoroutinesMavConnection {
        return UdpMavConnection(
            localPort,
            remoteHost?.takeIf { it.isNotBlank() },
            remotePort,
            appContext
        ).asCoroutine()
    }
}
