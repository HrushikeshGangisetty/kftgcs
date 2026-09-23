package com.example.kftgcs.telemetry.connections

import android.content.Context
import android.net.wifi.WifiManager
import com.example.kftgcs.utils.LogUtils

/**
 * Holds a [WifiManager.MulticastLock] for the life of the process.
 *
 * Android's Wi-Fi driver drops broadcast and multicast datagrams unless a multicast lock is held.
 * Some ground setups push telemetry to a broadcast address (SITL's `--out udpbcast`, some companion
 * computers and RC routers) rather than unicasting it, and without this the app simply never sees
 * those packets.
 *
 * It is held for the whole process rather than per connection because the UDP connection now comes
 * from mavlink-kotlin's `connection-udp` module, which has no hook to release a lock on close.
 * The cost is that Wi-Fi multicast filtering stays off; on a dedicated ground station that is a
 * fair trade for broadcast links working at all.
 *
 * Needs `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`, both declared in the manifest.
 */
object WifiMulticast {

    private const val TAG = "WifiMulticast"

    private var lock: WifiManager.MulticastLock? = null

    fun acquire(context: Context) {
        if (lock != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: run {
                    UdpDiagnostics.multicastLock = "NOT held (no Wi-Fi service)"
                    return
                }
            lock = wifi.createMulticastLock("kftgcs-udp").apply {
                setReferenceCounted(false)
                acquire()
            }
            UdpDiagnostics.multicastLock = "held"
            LogUtils.i(TAG, "Wi-Fi multicast lock held — broadcast telemetry will be received")
        } catch (e: Exception) {
            // Best effort: unicast telemetry still works without it. A SecurityException here means
            // the manifest is missing CHANGE_WIFI_MULTICAST_STATE.
            UdpDiagnostics.multicastLock = "NOT held (${e.javaClass.simpleName}: ${e.message})"
            LogUtils.w(TAG, "Could not acquire multicast lock: ${e.message}")
        }
    }
}
