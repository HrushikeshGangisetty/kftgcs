package com.example.kftgcs.service

import com.example.kftgcs.telemetry.TelemetryState
import com.example.kftgcs.viewmodel.TlogViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Service for automatic flight logging every 5 seconds
 */
class FlightLoggingService(
    private val tlogViewModel: TlogViewModel
) {
    private var loggingJob: Job? = null
    private val loggingInterval = 4000L // 4 seconds

    fun startLogging(telemetryStateFlow: StateFlow<TelemetryState>) {
        if (loggingJob?.isActive == true) return

        loggingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val telemetryState = telemetryStateFlow.value

                // Only log if we have valid telemetry data
                if (telemetryState.connected) {
                    tlogViewModel.logTelemetryData(
                        voltage = telemetryState.voltage,
                        current = telemetryState.currentA,
                        batteryPercent = telemetryState.batteryPercent,
                        satCount = telemetryState.sats,
                        hdop = telemetryState.hdop,
                        altitude = telemetryState.altitudeRelative, // Using relative altitude
                        speed = telemetryState.groundspeed, // Correct property name
                        latitude = telemetryState.latitude,
                        longitude = telemetryState.longitude,
                        heading = telemetryState.heading,
                        droneUid = telemetryState.droneUid,

                    )

                    // Also log GPS position for map replay
                    val lat = telemetryState.latitude
                    val lng = telemetryState.longitude
                    val alt = telemetryState.altitudeRelative

                    if (lat != null && lng != null && alt != null) {
                        tlogViewModel.logMapPosition(
                            latitude = lat,
                            longitude = lng,
                            altitude = alt,
                            heading = telemetryState.heading,
                            speed = telemetryState.groundspeed
                        )
                    }
                }

                delay(loggingInterval)
            }
        }
    }

    fun stopLogging() {
        loggingJob?.cancel()
        loggingJob = null
    }

    fun isLogging(): Boolean {
        return loggingJob?.isActive == true
    }
}
