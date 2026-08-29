package com.example.kftgcs.videotracking

import timber.log.Timber

/**
 * One-shot reachability probe for the camera's RTSP port.
 *
 * "Connecting..." with no error is ambiguous: the tablet may have no route to the
 * camera at all, or it may reach it and fail during the RTSP handshake. Probing
 * the TCP port separates those two cases so the UI can say which one happened.
 */
object StreamReachability {

    sealed interface Result {
        object Reachable : Result
        data class Unreachable(val detail: String) : Result
    }

    /**
     * Attempts a plain TCP connect to [host]:[port] on the default route.
     * Must be called off the main thread.
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = 4_000
    ): Result {
        // Use a plain socket on the default route — the same thing a working
        // third-party player does. Binding to a hand-picked Network here would
        // make the probe report "no route" for a camera that is actually
        // reachable, which is exactly the false negative we are trying to avoid.
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                Timber.d("StreamReachability: %s:%d reachable", host, port)
                Result.Reachable
            }
        } catch (_: java.net.SocketTimeoutException) {
            Result.Unreachable(
                "No route to $host. Confirm the tablet is joined to the MK15 " +
                    "Wi-Fi (Settings > Wi-Fi) and that Wi-Fi is not being bypassed " +
                    "by mobile data."
            )
        } catch (_: java.net.ConnectException) {
            Result.Unreachable(
                "$host refused the connection on port $port. The camera is on the " +
                    "network but not serving RTSP there — check the stream port."
            )
        } catch (e: Exception) {
            Result.Unreachable("Cannot reach $host:$port — ${e.javaClass.simpleName}")
        }
    }
}
