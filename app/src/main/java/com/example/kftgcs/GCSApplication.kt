package com.example.kftgcs

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.kftgcs.api.SessionManager
import com.example.kftgcs.logging.CrashLogger
import com.example.kftgcs.security.SecurePinManager
import com.example.kftgcs.sync.SyncWorker
import com.example.kftgcs.telemetry.WebSocketManager
import com.google.android.gms.maps.MapsInitializer
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Custom Application class to handle app-level initialization and crash detection.
 * Implements crash handler that triggers RTL when app crashes during flight.
 */
class GCSApplication : Application() {

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        @Volatile
        private var instance: GCSApplication? = null

        fun getInstance(): GCSApplication? = instance

        // Flag to track if drone is currently in flight
        @Volatile
        var isDroneInFlight: Boolean = false

        // Flag to track if we're connected to drone
        @Volatile
        var isConnectedToDrone: Boolean = false

        // Callback to trigger RTL
        @Volatile
        var onTriggerEmergencyRTL: (() -> Unit)? = null

        /**
         * Check if running in debug mode using reflection
         * This avoids compile-time dependency on BuildConfig
         */
        fun isDebugBuild(): Boolean {
            return try {
                val buildConfigClass = Class.forName("com.example.kftgcs.BuildConfig")
                val debugField = buildConfigClass.getField("DEBUG")
                debugField.getBoolean(null)
            } catch (e: Exception) {
                // Default to false (production behavior) if we can't determine
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Timber for logging - ONLY in debug builds
        // In release builds, no logs will be output (security feature)
        initializeTimber()

        // Initialize on-device crash logging. Unlike Timber this persists in
        // RELEASE builds too, so production crashes leave a recoverable record.
        CrashLogger.init(this)

        // Initialize Maps SDK as early as possible to avoid race conditions
        // where the map renders before its internal HTTP client is ready
        // (fixes intermittent "referer is null" tile loading failures)
        try {
            MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) { renderer ->
                Timber.d("Maps SDK initialized with renderer: $renderer")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to pre-initialize Maps SDK")
        }

        // Initialize offline queue support in WebSocketManager
        WebSocketManager.initWithContext(this)

        // Schedule periodic background sync as a safety net.
        // Runs every 15 min when CONNECTED; ExistingPeriodicWorkPolicy.KEEP
        // ensures only one instance is ever enqueued across app restarts.
        scheduleSyncWorker()

        // Migrate any plaintext data to secure encrypted storage (one-time operation)
        migrateToSecureStorage()

        // Setup global crash handler
        setupCrashHandler()

        Timber.i("✓ Application initialized with crash handler")
    }

    private fun scheduleSyncWorker() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.i("SyncWorker scheduled (15-min periodic, CONNECTED constraint)")
    }

    /**
     * Initialize Timber logging - only plants tree in debug builds
     * This prevents sensitive data from being logged in production
     */
    private fun initializeTimber() {
        if (isDebugBuild()) {
            Timber.plant(Timber.DebugTree())
            Timber.d("🌲 Timber initialized for DEBUG build")
        }
        // In release builds, no tree is planted, so all Timber calls are no-ops
    }

    /**
     * Migrate plaintext session data and PIN to encrypted storage
     * This runs once on app startup to ensure legacy data is securely migrated
     */
    private fun migrateToSecureStorage() {
        try {
            // Migrate session data (email, pilot ID, etc.)
            SessionManager.migrateFromPlaintextStorage(this)

            // Migrate PIN (handled by SecurePinManager)
            SecurePinManager.migrateFromPlaintextStorage(this)

            // 🔥 Fix adminId if it's set to 1 (which doesn't exist in DB)
            // This ensures existing sessions with wrong adminId are fixed
            SessionManager.fixAdminIdIfNeeded(this)

            Timber.i("✓ Secure storage migration check completed")
        } catch (e: Exception) {
            Timber.e(e, "❌ Migration error: ${e.message}")
        }
    }

    /**
     * Setup global uncaught exception handler to trigger RTL on crash
     */
    private fun setupCrashHandler() {
        // Save the default exception handler
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

        // Set custom exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist the crash FIRST (fast, never throws) so it is captured
            // even in release builds where the Timber calls below are no-ops.
            CrashLogger.logCrash(
                thread = thread,
                throwable = throwable,
                extra = mapOf(
                    "Drone in flight" to isDroneInFlight,
                    "Connected to drone" to isConnectedToDrone
                )
            )

            Timber.e("========== APP CRASH DETECTED ==========")
            Timber.e("Thread: ${thread.name}")
            Timber.e(throwable, "Error: ${throwable.message}")
            Timber.e("Drone in flight: $isDroneInFlight")
            Timber.e("Connected: $isConnectedToDrone")

            // If drone is in flight and connected, trigger emergency RTL
            if (isDroneInFlight && isConnectedToDrone) {
                Timber.w("🚨 TRIGGERING EMERGENCY RTL DUE TO APP CRASH 🚨")

                try {
                    // Trigger RTL synchronously before app dies
                    onTriggerEmergencyRTL?.invoke()

                    // Give some time for RTL command to be sent
                    Thread.sleep(500)

                    Timber.i("✓ Emergency RTL command sent")
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to send emergency RTL")
                }
            } else {
                Timber.i("No emergency RTL needed (not in flight or not connected)")
            }

            Timber.e("=========================================")

            // Call the default handler to properly crash the app
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        instance = null
    }
}
