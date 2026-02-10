package com.example.aerogcsclone.manager

import android.content.Context
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.database.tlog.EventType
import com.example.aerogcsclone.database.tlog.EventSeverity
import com.example.aerogcsclone.service.FlightLoggingService
import com.example.aerogcsclone.telemetry.Notification
import com.example.aerogcsclone.telemetry.NotificationType
import com.example.aerogcsclone.telemetry.SharedViewModel
import com.example.aerogcsclone.viewmodel.TlogViewModel
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Unified Flight Tracker - Single source of truth for flight state
 * Handles both MANUAL and AUTO mission modes with proper state machine
 */
class UnifiedFlightTracker(
    private val context: Context,
    private val tlogViewModel: TlogViewModel,
    private val sharedViewModel: SharedViewModel
) {

    // Flight state
    private enum class FlightState {
        IDLE,           // Not flying, waiting for start conditions
        STARTING,       // Start conditions met, in debounce period
        ACTIVE,         // Flight in progress, logging telemetry
        STOPPING,       // Stop conditions met, in debounce period
        FINALIZING      // Saving data and showing results
    }

    private var currentState = FlightState.IDLE
    private var monitoringJob: Job? = null

    // Mission mode tracking
    private enum class MissionMode {
        MANUAL,   // Manual flight modes (Stabilize, Alt Hold, Loiter, etc)
        AUTO      // Autonomous mission (Auto mode)
    }

    private var missionMode: MissionMode? = null

    // Timing and debounce
    private var startConditionMetAt: Long = 0
    private var stopConditionMetAt: Long = 0
    private val START_DEBOUNCE_MS = 1500L  // 1.5 seconds
    private val STOP_DEBOUNCE_MS = 2000L   // 2 seconds

    // Flight data tracking
    private var flightStartTime: Long = 0
    private var groundLevelAltitude: Float = 0f
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var totalDistanceMeters: Float = 0f
    private var totalSprayedDistanceMeters: Float = 0f  // Distance traveled while pump ON and flow > 0

    // Telemetry logging
    private var loggingService: FlightLoggingService? = null

    // Previous state tracking
    private var previousArmed = false
    private var previousMode: String? = null

    // Constants
    private val TAKEOFF_ALTITUDE_THRESHOLD = 1.0f  // meters above ground
    private val LANDING_ALTITUDE_THRESHOLD = 0.5f  // meters above ground
    private val MIN_SPEED_THRESHOLD = 0.1f         // m/s
    private val MIN_HDOP_THRESHOLD = 2.5f

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        monitoringJob = CoroutineScope(Dispatchers.Main).launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                try {
                    processFlightStateMachine(telemetry)
                    handleBatteryWarnings(telemetry)
                    handleConnectionLoss(telemetry)
                } catch (e: Exception) {
                    // Error in flight state machine - continue monitoring
                }
            }
        }
    }

    private suspend fun processFlightStateMachine(telemetry: TelemetryState) {
        when (currentState) {
            FlightState.IDLE -> checkStartConditions(telemetry)
            FlightState.STARTING -> checkStartDebounce(telemetry)
            FlightState.ACTIVE -> {
                updateFlightData(telemetry)
                checkStopConditions(telemetry)
            }
            FlightState.STOPPING -> checkStopDebounce(telemetry)
            FlightState.FINALIZING -> { /* Wait for finalization to complete */ }
        }

        // Track state changes
        previousArmed = telemetry.armed
        previousMode = telemetry.mode
    }

    // ==================== START CONDITIONS ====================

    private suspend fun checkStartConditions(telemetry: TelemetryState) {
        // Global preconditions (must ALL be true)
        if (!checkGlobalPreconditions(telemetry)) {
            startConditionMetAt = 0
            return
        }

        // Determine mission mode from flight mode
        val detectedMode = detectMissionMode(telemetry.mode)
        if (detectedMode == null) {
            startConditionMetAt = 0
            return
        }

        // Capture ground level when armed
        if (telemetry.armed && !previousArmed) {
            groundLevelAltitude = telemetry.altitudeRelative ?: 0f
        }

        // Check mode-specific start predicate
        val modeStartMet = checkModeSpecificStart(telemetry, detectedMode)

        if (modeStartMet) {
            val now = System.currentTimeMillis()
            if (startConditionMetAt == 0L) {
                startConditionMetAt = now
            }

            // Check if debounce period passed
            if (now - startConditionMetAt >= START_DEBOUNCE_MS) {
                missionMode = detectedMode
                currentState = FlightState.STARTING
            }
        } else {
            startConditionMetAt = 0
        }
    }

    private fun checkGlobalPreconditions(telemetry: TelemetryState): Boolean {
        // 1. MAVLink connection healthy
        if (!telemetry.connected) {
            return false
        }

        // 2. GPS acceptable (if using GPS for distance)
        val hdop = telemetry.hdop ?: 99f
        if (hdop > MIN_HDOP_THRESHOLD) {
            // GPS not good enough, but we can still track time (just not distance accurately)
        }

        // 3. System time available (always true in Android)
        // 4. Storage available (assume true for now, can add check later)

        return true
    }

    private fun detectMissionMode(flightMode: String?): MissionMode? {
        return when (flightMode?.lowercase()) {
            "auto" -> MissionMode.AUTO
            "stabilize", "alt hold", "loiter", "pos hold", "guided" -> MissionMode.MANUAL
            else -> null  // Unknown or invalid mode
        }
    }

    private fun checkModeSpecificStart(telemetry: TelemetryState, mode: MissionMode): Boolean {
        val altitude = telemetry.altitudeRelative ?: 0f
        val speed = telemetry.groundspeed ?: 0f

        return when (mode) {
            MissionMode.AUTO -> {
                // AUTO mode: armed + in AUTO + altitude rising OR moving
                telemetry.armed &&
                telemetry.mode?.equals("Auto", ignoreCase = true) == true &&
                (altitude > groundLevelAltitude + TAKEOFF_ALTITUDE_THRESHOLD || speed > MIN_SPEED_THRESHOLD)
            }
            MissionMode.MANUAL -> {
                // MANUAL mode: armed + altitude rising OR moving
                telemetry.armed &&
                (altitude > groundLevelAltitude + TAKEOFF_ALTITUDE_THRESHOLD || speed > MIN_SPEED_THRESHOLD)
            }
        }
    }

    private suspend fun checkStartDebounce(telemetry: TelemetryState) {
        // Verify conditions still met
        val stillMet = checkGlobalPreconditions(telemetry) &&
                       missionMode?.let { checkModeSpecificStart(telemetry, it) } == true

        if (!stillMet) {
            currentState = FlightState.IDLE
            startConditionMetAt = 0
            missionMode = null
            return
        }

        // Transition to ACTIVE
        startFlight(telemetry)
    }

    // ==================== ACTIVE FLIGHT ====================

    private suspend fun startFlight(telemetry: TelemetryState) {
        currentState = FlightState.ACTIVE
        flightStartTime = System.currentTimeMillis()
        totalDistanceMeters = 0f
        lastLat = telemetry.latitude
        lastLon = telemetry.longitude

        // Start database logging
        try {
            tlogViewModel.startFlight()

            // Start telemetry logging service
            loggingService = FlightLoggingService(tlogViewModel)
            loggingService?.startLogging(sharedViewModel.telemetryState)

            // Log start event
            tlogViewModel.logEvent(
                eventType = EventType.ARM_DISARM,
                severity = EventSeverity.INFO,
                message = "Flight started - mode: ${missionMode?.name}"
            )
        } catch (e: Exception) {
            // Error starting flight logging - continue anyway
        }

        // Show notification
        sharedViewModel.addNotification(
            Notification("Mission started", NotificationType.SUCCESS)
        )

        // Update shared view model state
        sharedViewModel.updateFlightState(
            isActive = true,
            elapsedSeconds = 0L,
            distanceMeters = 0f
        )
    }

    private suspend fun updateFlightData(telemetry: TelemetryState) {
        // Update elapsed time
        val elapsedSeconds = (System.currentTimeMillis() - flightStartTime) / 1000L

        // Update distance (if GPS is valid)
        val lat = telemetry.latitude
        val lon = telemetry.longitude
        val hdop = telemetry.hdop ?: 99f

        if (lat != null && lon != null && lastLat != null && lastLon != null && hdop <= MIN_HDOP_THRESHOLD) {
            val distance = haversine(lastLat!!, lastLon!!, lat, lon)
            if (distance > 0.1f && distance < 100f) {  // Sanity check: 0.1m to 100m per update
                totalDistanceMeters += distance

                // Track sprayed distance - only when pump is ON and flow rate > 0
                val isPumpOn = telemetry.sprayTelemetry.sprayEnabled
                val flowRate = telemetry.sprayTelemetry.flowRateLiterPerMin ?: 0f
                if (isPumpOn && flowRate > 0f) {
                    totalSprayedDistanceMeters += distance
                }
            }
            lastLat = lat
            lastLon = lon
        }

        // Update shared view model with current flight data
        sharedViewModel.updateFlightState(
            isActive = true,
            elapsedSeconds = elapsedSeconds,
            distanceMeters = totalDistanceMeters,
            sprayedDistanceMeters = totalSprayedDistanceMeters
        )
    }

    // ==================== STOP CONDITIONS ====================

    private suspend fun checkStopConditions(telemetry: TelemetryState) {
        val stopReason = evaluateStopConditions(telemetry)

        if (stopReason != null) {
            val now = System.currentTimeMillis()
            if (stopConditionMetAt == 0L) {
                stopConditionMetAt = now
            }

            // Check if debounce period passed
            if (now - stopConditionMetAt >= STOP_DEBOUNCE_MS) {
                currentState = FlightState.STOPPING
            }
        } else {
            stopConditionMetAt = 0
        }
    }

    private fun evaluateStopConditions(telemetry: TelemetryState): String? {
        // Priority order (evaluate top to bottom)

        // 1. User STOP pressed (not implemented yet, but would be highest priority)

        // 2. AUTO-only: mission completed (last item reached)
        if (missionMode == MissionMode.AUTO) {
            // Check if mission is complete (mode changed from AUTO or mission ended)
            // BUT IGNORE mode change if mission is paused (paused missions go to LOITER)
            if (telemetry.mode?.equals("Auto", ignoreCase = true) == false &&
                previousMode?.equals("Auto", ignoreCase = true) == true &&
                !telemetry.missionPaused) {  // Don't treat pause as mission completion
                return "Mission completed - exited AUTO mode"
            }
        }

        // 3. Common: disarmed + low speed
        val speed = telemetry.groundspeed ?: 0f
        if (!telemetry.armed && speed < MIN_SPEED_THRESHOLD) {
            return "Disarmed with low speed"
        }

        // 4. Common: landed (altitude near ground + low speed)
        val altitude = telemetry.altitudeRelative ?: 0f
        val landingThreshold = groundLevelAltitude + LANDING_ALTITUDE_THRESHOLD
        if (altitude <= landingThreshold && speed < MIN_SPEED_THRESHOLD) {
            return "Landed (altitude: ${altitude}m ≤ ${landingThreshold}m, speed: ${speed}m/s)"
        }

        // 5. Failsafe modes (RTL, Land)
        if (telemetry.mode?.equals("RTL", ignoreCase = true) == true ||
            telemetry.mode?.equals("Land", ignoreCase = true) == true) {
            // Wait for actual landing confirmation
            if (!telemetry.armed || altitude <= landingThreshold) {
                return "Failsafe landing completed (${telemetry.mode})"
            }
        }

        // 6. Connection lost (not stopping immediately, just logging)
        // Connection loss is handled separately

        return null
    }

    private suspend fun checkStopDebounce(telemetry: TelemetryState) {
        // Verify conditions still met
        val stopReason = evaluateStopConditions(telemetry)

        if (stopReason == null) {
            currentState = FlightState.ACTIVE
            stopConditionMetAt = 0
            return
        }

        // Transition to FINALIZING
        stopFlight(stopReason)
    }

    private suspend fun stopFlight(reason: String) {
        currentState = FlightState.FINALIZING

        val elapsedSeconds = (System.currentTimeMillis() - flightStartTime) / 1000L

        // CRITICAL: Preserve final values BEFORE any cleanup
        val finalDistance = totalDistanceMeters
        val finalSprayedDistance = totalSprayedDistanceMeters
        val finalTime = elapsedSeconds

        // Capture consumed litres from spray telemetry
        val finalConsumedLitres = sharedViewModel.telemetryState.value.sprayTelemetry.consumedLiters

        // Stop telemetry logging
        loggingService?.stopLogging()
        loggingService = null

        // Finalize database entry
        try {
            tlogViewModel.logEvent(
                eventType = EventType.ARM_DISARM,
                severity = EventSeverity.INFO,
                message = "Flight ended - $reason"
            )

            tlogViewModel.endFlight(
                area = null,  // Calculate if needed
                consumedLiquid = finalConsumedLitres
            )
        } catch (e: Exception) {
            // Error saving flight data
        }

        // Show completion notification with preserved values
        sharedViewModel.addNotification(
            Notification(
                "Flight completed! Time: ${formatTime(finalTime)}, Area: ${formatAcres(finalDistance)}",
                NotificationType.SUCCESS
            )
        )

        // Show mission completion dialog with time, acres (converted from distance), sprayed acres, and consumed litres
        val consumedLitresStr = finalConsumedLitres?.let { "%.2f L".format(it) } ?: "N/A"
        sharedViewModel.showMissionCompletionDialog(
            totalTime = formatTime(finalTime),
            totalAcres = formatAcres(finalDistance),
            sprayedAcres = formatAcres(finalSprayedDistance),
            consumedLitres = consumedLitresStr
        )

        // CRITICAL: Update shared view model with FINAL values BEFORE reset
        // This ensures the UI can capture the values
        sharedViewModel.updateFlightState(
            isActive = false,
            elapsedSeconds = finalTime,
            distanceMeters = finalDistance,
            sprayedDistanceMeters = finalSprayedDistance,
            completed = true
        )


        // Give UI time to capture the values before we reset
        delay(500)

        // Reset state AFTER UI has had time to capture values
        resetToIdle()
    }

    private fun resetToIdle() {
        currentState = FlightState.IDLE
        missionMode = null
        startConditionMetAt = 0
        stopConditionMetAt = 0
        flightStartTime = 0
        groundLevelAltitude = 0f
        totalDistanceMeters = 0f
        totalSprayedDistanceMeters = 0f
        lastLat = null
        lastLon = null
    }

    // ==================== EDGE CASES ====================

    private suspend fun handleBatteryWarnings(telemetry: TelemetryState) {
        if (currentState != FlightState.ACTIVE) return

        telemetry.batteryPercent?.let { percent ->
            when {
                percent <= 15 -> {
                    tlogViewModel.logEvent(
                        eventType = EventType.LOW_BATTERY,
                        severity = EventSeverity.CRITICAL,
                        message = "Critical battery: ${percent}%"
                    )
                }
                percent <= 20 -> {
                    tlogViewModel.logEvent(
                        eventType = EventType.LOW_BATTERY,
                        severity = EventSeverity.WARNING,
                        message = "Low battery: ${percent}%"
                    )
                }
            }
        }
    }

    private suspend fun handleConnectionLoss(telemetry: TelemetryState) {
        if (currentState == FlightState.ACTIVE && !telemetry.connected) {
            tlogViewModel.logEvent(
                eventType = EventType.CONNECTION_LOSS,
                severity = EventSeverity.WARNING,
                message = "Connection lost during flight"
            )
        }
    }

    // ==================== UTILITIES ====================

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return (R * c).toFloat()
    }

    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    private fun formatDistance(meters: Float): String {
        return when {
            meters >= 1000f -> String.format("%.2f km", meters / 1000f)
            else -> String.format("%.1f m", meters)
        }
    }

    /**
     * Convert distance traveled to acres covered
     * Formula: distance (m) * spray width (m) / 4046.86 (sq meters per acre)
     * Using default spray width of 5 meters
     */
    private fun formatAcres(distanceMeters: Float): String {
        val sprayWidthMeters = 5.0f  // Default spray width
        val areaSqMeters = distanceMeters * sprayWidthMeters
        val acres = areaSqMeters / 4046.86f
        return String.format("%.2f acres", acres)
    }

    fun destroy() {
        monitoringJob?.cancel()
        loggingService?.stopLogging()
    }

    // Public API for manual control (if needed)
    fun forceStop(reason: String = "User requested") {
        if (currentState == FlightState.ACTIVE) {
            CoroutineScope(Dispatchers.Main).launch {
                stopFlight(reason)
            }
        }
    }

    fun getCurrentState(): String = currentState.name
    fun isFlightActive(): Boolean = currentState == FlightState.ACTIVE
}
