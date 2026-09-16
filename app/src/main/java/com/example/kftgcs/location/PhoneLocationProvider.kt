package com.example.kftgcs.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.kftgcs.utils.LogUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide source of the phone's own GPS position — the "RC" position, i.e. where the
 * pilot holding the tablet/controller is standing.
 *
 * There is exactly one FusedLocationProvider subscription no matter how many screens want the
 * position: every consumer calls [acquire] / [release] (normally via [rememberPhoneLocation])
 * and the last one out stops the updates. The last known fix is kept in [location] after the
 * updates stop, so a map that re-opens shows the RC marker immediately instead of blinking
 * back in a second later.
 *
 * Location permission is requested up front by MainActivity. If it has not been granted yet
 * (or the user revoked it) [acquire] simply does nothing and retries on the next acquire, so
 * the marker appears as soon as permission is available without any extra wiring.
 */
object PhoneLocationProvider {

    /** How often we ask for a fix. 1 s keeps the marker in step with the drone marker. */
    private const val UPDATE_INTERVAL_MS = 1000L
    private const val FASTEST_UPDATE_INTERVAL_MS = 500L
    private const val MAX_CACHED_FIX_AGE_MS = 120_000L

    private val _location = MutableStateFlow<LatLng?>(null)

    /** Latest known phone position, or null until the first fix arrives. */
    val location: StateFlow<LatLng?> = _location.asStateFlow()

    private var client: FusedLocationProviderClient? = null
    private var consumers = 0
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { fix ->
                _location.value = LatLng(fix.latitude, fix.longitude)
            }
        }
    }

    /** True when the app may read the phone's position right now. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Register interest in the phone position; starts updates for the first consumer. */
    @Synchronized
    fun acquire(context: Context) {
        consumers++
        start(context.applicationContext)
    }

    /**
     * Try again to start updates — call after returning to the foreground, when the user may
     * have just granted the location permission that [acquire] found missing. No-op when
     * updates are already running or nobody is listening.
     */
    @Synchronized
    fun refresh(context: Context) {
        if (consumers > 0) start(context.applicationContext)
    }

    /** Drop interest; stops updates once the last consumer is gone. */
    @Synchronized
    fun release() {
        if (consumers > 0) consumers--
        if (consumers == 0) stop()
    }

    @SuppressLint("MissingPermission") // guarded by hasPermission() directly above the call
    @Synchronized
    private fun start(appContext: Context) {
        // Retried on every acquire so that a permission granted after the first attempt
        // still brings the marker up.
        if (started) return
        if (!hasPermission(appContext)) {
            LogUtils.w("PhoneLocation", "Location permission not granted - RC position unavailable")
            return
        }

        val locationClient = client ?: LocationServices.getFusedLocationProviderClient(appContext)
            .also { client = it }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            .build()

        try {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            started = true

            // Seed the marker from the cached fix so it shows up without waiting a full second.
            // getFusedLocationProviderClient().lastLocation is Android's system-wide last-known
            // fix, not this app's — it can be whatever ANY app last requested, anywhere, up to
            // hours or days ago (e.g. still sitting at home/office before the drive to the
            // field). Showing it unconditionally is what made the RC marker jump to "an entirely
            // different location": a stale fix from far away rendered as if it were current until
            // the first real update arrived (which in the field, with weak GPS, can take a while
            // or never beat a wrong cached value the pilot already walked away trusting).
            // Age-gate it so a fix from more than 2 minutes ago is treated as absent instead.
            locationClient.lastLocation.addOnSuccessListener { fix ->
                if (fix == null || _location.value != null) return@addOnSuccessListener
                val ageMs = System.currentTimeMillis() - fix.time
                if (ageMs > MAX_CACHED_FIX_AGE_MS) {
                    LogUtils.w("PhoneLocation", "Ignoring stale cached fix (${ageMs / 1000}s old) — waiting for a fresh one")
                    return@addOnSuccessListener
                }
                _location.value = LatLng(fix.latitude, fix.longitude)
            }
        } catch (e: SecurityException) {
            LogUtils.e("PhoneLocation", "Location permission denied while starting updates", e)
        }
    }

    @Synchronized
    private fun stop() {
        if (!started) return
        client?.removeLocationUpdates(callback)
        started = false
        // _location is deliberately kept: it is the last known RC position.
    }
}

/**
 * Phone position for Compose, scoped to the composition that asks for it.
 *
 * Updates run while at least one composable is subscribed and stop when the last one leaves.
 */
@Composable
fun rememberPhoneLocation(): LatLng? {
    val context = LocalContext.current
    val location by PhoneLocationProvider.location.collectAsState()

    DisposableEffect(Unit) {
        PhoneLocationProvider.acquire(context)
        onDispose { PhoneLocationProvider.release() }
    }

    // Picks the updates back up if the location permission was granted while we were in the
    // background (system permission dialog, or a trip to app settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) PhoneLocationProvider.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return location
}
