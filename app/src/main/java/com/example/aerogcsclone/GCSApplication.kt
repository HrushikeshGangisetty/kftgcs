package com.example.aerogcsclone

import android.app.Application
import com.example.aerogcsclone.api.SessionManager
import com.example.aerogcsclone.security.SecurePinManager
import timber.log.Timber

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
                val buildConfigClass = Class.forName("com.example.aerogcsclone.BuildConfig")
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

        // Migrate any plaintext data to secure encrypted storage (one-time operation)
        migrateToSecureStorage()

        // Setup global crash handler
        setupCrashHandler()

        Timber.i("✓ Application initialized with crash handler")
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
