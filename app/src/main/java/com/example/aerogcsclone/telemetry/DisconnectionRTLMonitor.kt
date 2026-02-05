package com.example.aerogcsclone.telemetry

import com.example.aerogcsclone.GCSApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Monitors connection status and triggers RTL if drone disconnects during flight.
 * This provides failsafe protection when connection is lost mid-flight.
 */
class DisconnectionRTLMonitor(
    private val repository: MavlinkTelemetryRepository,
    private val scope: CoroutineScope
) {
    // Track previous connection state to detect disconnection events
    private var wasConnected = false

    // Track if drone was in flight before disconnection
    private var wasInFlight = false
    private var lastKnownAltitude = 0f
    private var lastKnownArmed = false

    // Flag to track if RTL was already sent for this disconnection
    private var rtlSentForCurrentDisconnection = false

    /**
     * Start monitoring connection and flight status
     */
    fun startMonitoring(telemetryState: StateFlow<com.example.aerogcsclone.Telemetry.TelemetryState>) {
        scope.launch {
            telemetryState.collect { state ->
                val isConnected = state.connected
                val isArmed = state.armed
                val altitude = state.altitudeRelative ?: 0f

                // Update flight status tracking
                if (isConnected) {
                    wasInFlight = isArmed && altitude > 0.5f
                    lastKnownAltitude = altitude
                    lastKnownArmed = isArmed

                    // Reset RTL flag when connected
                    if (!wasConnected) {
                        rtlSentForCurrentDisconnection = false
                    }
                }

                // Detect disconnection event
                if (wasConnected && !isConnected) {
                    handleDisconnection()
                }

                wasConnected = isConnected
            }
        }
    }

    /**
     * Handle disconnection event - trigger RTL if drone was in flight
     */
    private fun handleDisconnection() {
        // Check if we should trigger RTL
        if (wasInFlight && !rtlSentForCurrentDisconnection) {
            // Mark that we've sent RTL for this disconnection
            rtlSentForCurrentDisconnection = true

            // Update global app state
            GCSApplication.isDroneInFlight = false // Prevent crash handler from also sending RTL

            // Attempt to send RTL command
            scope.launch {
                try {
                    // Try to reconnect briefly and send RTL
                    attemptEmergencyRTL()
                } catch (e: Exception) {
                    // Exception while sending RTL - continue silently
                }
            }
        }
    }

    /**
     * Attempt to send emergency RTL command
     */
    private suspend fun attemptEmergencyRTL() {
        try {
            // Send RTL command directly through repository
            // Mode 6 = RTL for ArduPilot
            repository.changeMode(6u)
        } catch (e: Exception) {
            // Failed to send RTL command - continue silently
        }
    }

    /**
     * Stop monitoring (cleanup)
     */
    fun stopMonitoring() {
        wasConnected = false
        wasInFlight = false
        rtlSentForCurrentDisconnection = false
    }
}

