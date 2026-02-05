package com.example.aerogcsclone.telemetry

import com.example.aerogcsclone.GCSApplication
import com.example.aerogcsclone.Telemetry.TelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Automatic disconnection RTL handler that monitors connection status
 * and triggers RTL when drone disconnects mid-flight.
 *
 * Usage: Call startMonitoring() after repository is created
 */
object DisconnectionRTLHandler {

    private var isMonitoring = false
    private var wasConnected = false
    private var wasInFlight = false
    private var lastKnownAltitude = 0f
    private var rtlSentForCurrentDisconnection = false

    /**
     * Start monitoring telemetry for mid-flight disconnections
     */
    fun startMonitoring(
        telemetryState: StateFlow<TelemetryState>,
        repository: MavlinkTelemetryRepository,
        scope: CoroutineScope
    ) {
        if (isMonitoring) {
            return
        }

        isMonitoring = true

        scope.launch {
            telemetryState.collect { state ->
                monitorConnectionState(state, repository)
            }
        }
    }

    /**
     * Monitor connection state and detect disconnections
     */
    private suspend fun monitorConnectionState(
        state: TelemetryState,
        repository: MavlinkTelemetryRepository
    ) {
        val isConnected = state.connected
        val isArmed = state.armed
        val altitude = state.altitudeRelative ?: 0f

        // Track flight status while connected
        if (isConnected) {
            wasInFlight = isArmed && altitude > 0.5f
            lastKnownAltitude = altitude

            // Reset RTL flag when reconnected
            if (!wasConnected) {
                rtlSentForCurrentDisconnection = false
            }
        }

        // Detect mid-flight disconnection
        if (wasConnected && !isConnected) {
            handleDisconnection(repository)
        }

        wasConnected = isConnected
    }

    /**
     * Handle disconnection event
     */
    private suspend fun handleDisconnection(repository: MavlinkTelemetryRepository) {
        // Only trigger RTL if drone was in flight
        if (wasInFlight && !rtlSentForCurrentDisconnection) {
            // Mark RTL as sent for this disconnection
            rtlSentForCurrentDisconnection = true

            // Update global state to prevent crash handler from also sending RTL
            GCSApplication.isDroneInFlight = false

            // Attempt to send RTL command
            try {
                repository.changeMode(6u) // RTL mode
            } catch (e: Exception) {
                // Failed to send RTL command - continue silently
            }
        }
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        isMonitoring = false
        wasConnected = false
        wasInFlight = false
        rtlSentForCurrentDisconnection = false
    }

    /**
     * Reset state (for testing)
     */
    fun reset() {
        wasConnected = false
        wasInFlight = false
        lastKnownAltitude = 0f
        rtlSentForCurrentDisconnection = false
    }
}

