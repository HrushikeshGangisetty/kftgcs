package com.example.aerogcsclone.obstacle

import android.content.Context
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.telemetry.SharedViewModel
import com.example.aerogcsclone.telemetry.MavMode
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

/**
 * Main manager for obstacle detection and mission split functionality
 * Coordinates sensor monitoring, obstacle detection, RTL triggering, and mission resume
 *
 * Follows the detailed step-by-step guide for obstacle detection mission split
 */
class ObstacleDetectionManager(
    context: Context,
    private val sharedViewModel: SharedViewModel,
    private val missionStateRepository: MissionStateRepository,
    private val config: ObstacleDetectionConfig = ObstacleDetectionConfig()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Core components
    private val sensorManager = ObstacleSensorManager(context, config)
    private val obstacleDetector = ObstacleDetector(config)

    // Current mission tracking
    private var currentMissionWaypoints = mutableListOf<MissionItemInt>()
    private var currentWaypointIndex = 0
    private var homeLocation: LatLng? = null
    private var surveyPolygon: List<LatLng> = emptyList()
    private var missionParameters: MissionParameters? = null

    // State flows
    private val _detectionStatus = MutableStateFlow(ObstacleDetectionStatus.INACTIVE)
    val detectionStatus: StateFlow<ObstacleDetectionStatus> = _detectionStatus.asStateFlow()

    private val _currentObstacle = MutableStateFlow<ObstacleInfo?>(null)
    val currentObstacle: StateFlow<ObstacleInfo?> = _currentObstacle.asStateFlow()

    private val _rtlMonitoring = MutableStateFlow(RTLMonitoringState())
    @Suppress("unused")
    val rtlMonitoring: StateFlow<RTLMonitoringState> = _rtlMonitoring.asStateFlow()

    private val _savedMissionState = MutableStateFlow<SavedMissionState?>(null)
    @Suppress("unused")
    val savedMissionState: StateFlow<SavedMissionState?> = _savedMissionState.asStateFlow()

    private val _resumeOptions = MutableStateFlow<List<ResumeOption>>(emptyList())
    val resumeOptions: StateFlow<List<ResumeOption>> = _resumeOptions.asStateFlow()

    // Monitoring jobs
    private var monitoringJob: Job? = null
    private var rtlMonitoringJob: Job? = null

    /**
     * PHASE 2: Initialize sensor systems
     */
    fun initialize(): Boolean {
        val sensorReady = sensorManager.initialize()
        if (!sensorReady) {
            return false
        }

        val calibration = sensorManager.calibrate()
        if (!calibration.success) {
            return false
        }

        return true
    }

    /**
     * PHASE 3: Load mission and start monitoring
     */
    fun startMissionMonitoring(
        waypoints: List<MissionItemInt>,
        home: LatLng,
        polygon: List<LatLng> = emptyList(),
        parameters: MissionParameters? = null
    ) {
        currentMissionWaypoints = waypoints.toMutableList()
        homeLocation = home
        surveyPolygon = polygon
        missionParameters = parameters
        currentWaypointIndex = 0

        _detectionStatus.value = ObstacleDetectionStatus.MONITORING

        // Start sensor monitoring
        sensorManager.startMonitoring()

        // Start obstacle detection loop
        startObstacleMonitoringLoop()
    }

    /**
     * PHASE 4: Continuous obstacle monitoring loop
     */
    private fun startObstacleMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            // Combine sensor readings with telemetry
            combine(
                sensorManager.sensorReading,
                sharedViewModel.telemetryState
            ) { reading, telemetry ->
                Pair(reading, telemetry)
            }.collect { pair ->
                processObstacleDetection(pair.first, pair.second)
            }
        }
    }

    /**
     * PHASE 4: Process each sensor reading and check for obstacles
     */
    private suspend fun processObstacleDetection(
        reading: SensorReading?,
        telemetry: TelemetryState
    ) {
        if (_detectionStatus.value != ObstacleDetectionStatus.MONITORING) {
            return
        }

        // Get current drone state
        val droneLocation = if (telemetry.latitude != null && telemetry.longitude != null) {
            LatLng(telemetry.latitude, telemetry.longitude)
        } else null

        val droneHeading = telemetry.heading

        // Update current waypoint index based on mission progress
        updateCurrentWaypoint(telemetry)

        // Get next waypoint target
        val nextWaypoint = getNextWaypoint()
        val targetLocation = nextWaypoint?.let {
            LatLng(it.x / 1E7, it.y / 1E7)
        }

        // Detect obstacle
        val obstacle = obstacleDetector.detectObstacle(
            reading = reading,
            droneLocation = droneLocation,
            droneHeading = droneHeading,
            targetLocation = targetLocation
        )

        _currentObstacle.value = obstacle

        // Check if HIGH threat should trigger RTL
        if (obstacle != null && obstacleDetector.shouldTriggerRTL()) {
            triggerEmergencyRTL(telemetry, obstacle)
        }
    }

    /**
     * Update current waypoint index based on drone position
     */
    private fun updateCurrentWaypoint(telemetry: TelemetryState) {
        val droneLocation = if (telemetry.latitude != null && telemetry.longitude != null) {
            LatLng(telemetry.latitude, telemetry.longitude)
        } else return

        // Find closest waypoint
        currentMissionWaypoints.forEachIndexed { index, waypoint ->
            val waypointLocation = LatLng(waypoint.x / 1E7, waypoint.y / 1E7)
            val distance = obstacleDetector.calculateDistance(droneLocation, waypointLocation)

            if (distance < 10f) { // Within 10 meters
                currentWaypointIndex = index
            }
        }
    }

    /**
     * Get next waypoint in mission
     */
    private fun getNextWaypoint(): MissionItemInt? {
        return if (currentWaypointIndex < currentMissionWaypoints.size) {
            currentMissionWaypoints[currentWaypointIndex]
        } else null
    }

    /**
     * PHASE 5: Trigger emergency RTL when HIGH threat detected
     */
    private suspend fun triggerEmergencyRTL(
        telemetry: TelemetryState,
        obstacle: ObstacleInfo
    ) {
        // Stop mission monitoring
        stopMissionMonitoring()

        _detectionStatus.value = ObstacleDetectionStatus.OBSTACLE_DETECTED


        // Capture complete mission state
        val droneLocation = LatLng(telemetry.latitude ?: 0.0, telemetry.longitude ?: 0.0)
        val home = homeLocation ?: droneLocation

        val remainingWaypoints = if (currentWaypointIndex < currentMissionWaypoints.size) {
            currentMissionWaypoints.subList(currentWaypointIndex, currentMissionWaypoints.size)
        } else emptyList()

        val missionProgress = if (currentMissionWaypoints.isNotEmpty()) {
            (currentWaypointIndex.toFloat() / currentMissionWaypoints.size) * 100f
        } else 0f

        val missionState = SavedMissionState(
            interruptedWaypointIndex = currentWaypointIndex,
            currentDroneLocation = droneLocation,
            homeLocation = home,
            originalWaypoints = currentMissionWaypoints.toList(),
            remainingWaypoints = remainingWaypoints,
            obstacleInfo = obstacle,
            missionProgress = missionProgress,
            surveyPolygon = surveyPolygon,
            missionParameters = missionParameters
        )

        // Save mission state to persistent storage
        val saved = missionStateRepository.saveMissionState(missionState)
        if (saved) {
            _savedMissionState.value = missionState
        }

        // Issue RTL command to drone
        withContext(Dispatchers.Main) {
            // Use the repository's changeMode method via telemetry
            val repo = sharedViewModel.repository
            if (repo != null) {
                val success = repo.changeMode(MavMode.RTL)
                if (success) {
                    _detectionStatus.value = ObstacleDetectionStatus.RTL_IN_PROGRESS
                    startRTLMonitoring()
                }
            }
        }
    }

    /**
     * PHASE 6: Monitor RTL - drone return to home
     */
    private fun startRTLMonitoring() {

        rtlMonitoringJob?.cancel()
        rtlMonitoringJob = scope.launch {
            sharedViewModel.telemetryState.collect { telemetry ->
                monitorRTLProgress(telemetry)
            }
        }
    }

    /**
     * Monitor distance to home during RTL
     */
    private fun monitorRTLProgress(telemetry: TelemetryState) {
        if (_detectionStatus.value != ObstacleDetectionStatus.RTL_IN_PROGRESS) {
            return
        }

        val droneLocation = if (telemetry.latitude != null && telemetry.longitude != null) {
            LatLng(telemetry.latitude, telemetry.longitude)
        } else return

        val home = homeLocation ?: return
        val distance = obstacleDetector.calculateDistance(droneLocation, home)

        val currentState = _rtlMonitoring.value

        // Initialize if first reading
        if (!currentState.isActive) {
            _rtlMonitoring.value = RTLMonitoringState(
                isActive = true,
                initialDistance = distance,
                currentDistance = distance
            )
            return
        }

        // Update current distance
        _rtlMonitoring.value = currentState.copy(currentDistance = distance)

        // Check if arrived at home (within 5 meters)
        if (distance < currentState.arrivalThresholdMeters) {
            val newConsecutiveChecks = currentState.consecutiveArrivalChecks + 1
            _rtlMonitoring.value = currentState.copy(
                consecutiveArrivalChecks = newConsecutiveChecks,
                currentDistance = distance
            )


            // Require 3 consecutive readings below threshold
            if (newConsecutiveChecks >= 3) {
                onDroneArrivedHome()
            }
        } else {
            // Reset counter if moved away
            if (currentState.consecutiveArrivalChecks > 0) {
                _rtlMonitoring.value = currentState.copy(
                    consecutiveArrivalChecks = 0,
                    currentDistance = distance
                )
            }
        }
    }

    /**
     * Handle drone arrival at home
     */
    private fun onDroneArrivedHome() {
        rtlMonitoringJob?.cancel()
        _rtlMonitoring.value = RTLMonitoringState()
        _detectionStatus.value = ObstacleDetectionStatus.READY_TO_RESUME

        // Generate resume options
        generateResumeOptions()
    }

    /**
     * PHASE 7: Generate resume options for user
     */
    private fun generateResumeOptions() {
        val missionState = _savedMissionState.value ?: return
        val obstacle = missionState.obstacleInfo
        val obstacleLocation = obstacle.location ?: return

        val options = mutableListOf<ResumeOption>()

        // Generate options for each remaining waypoint
        missionState.remainingWaypoints.forEachIndexed { index, waypoint ->
            val waypointLocation = LatLng(waypoint.x / 1E7, waypoint.y / 1E7)
            val distanceFromObstacle = obstacleDetector.calculateDistance(
                obstacleLocation,
                waypointLocation
            )

            val originalIndex = missionState.interruptedWaypointIndex + index
            val skippedWaypoints = (missionState.interruptedWaypointIndex until originalIndex).toList()
            val coveragePercentage = ((originalIndex + 1).toFloat() / missionState.originalWaypoints.size) * 100f

            // First remaining waypoint is recommended (skips obstacle area)
            val isRecommended = index == 1 && distanceFromObstacle > 30f

            options.add(
                ResumeOption(
                    waypointIndex = originalIndex,
                    waypoint = waypoint,
                    location = waypointLocation,
                    distanceFromObstacle = distanceFromObstacle,
                    coveragePercentage = coveragePercentage,
                    skippedWaypoints = skippedWaypoints,
                    isRecommended = isRecommended
                )
            )
        }

        _resumeOptions.value = options
    }

    /**
     * PHASE 8-9: Create and upload new mission for resume
     */
    suspend fun resumeMissionFromWaypoint(selectedOption: ResumeOption): Boolean {
        val missionState = _savedMissionState.value
        if (missionState == null) {
            return false
        }

        _detectionStatus.value = ObstacleDetectionStatus.RESUMING

        // Build new mission structure
        val newMission = buildResumeMission(missionState, selectedOption)

        // Upload mission via SharedViewModel
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                sharedViewModel.uploadMission(newMission) { success, _ ->
                    if (success) {
                        scope.launch {
                            // Mark old mission as resolved
                            missionStateRepository.markAsResolved(missionState.missionId)

                            // Start monitoring new mission
                            val newHome = missionState.homeLocation
                            currentMissionWaypoints = newMission.toMutableList()
                            homeLocation = newHome
                            currentWaypointIndex = 0
                        }
                        continuation.resume(true)
                    } else {
                        _detectionStatus.value = ObstacleDetectionStatus.READY_TO_RESUME
                        continuation.resume(false)
                    }
                }
            }
        }
    }

    /**
     * Build new mission from saved state and selected resume option
     */
    private fun buildResumeMission(
        state: SavedMissionState,
        option: ResumeOption
    ): List<MissionItemInt> {
        val newMission = mutableListOf<MissionItemInt>()
        val params = state.missionParameters ?: MissionParameters()

        // Waypoint 0: TAKEOFF
        newMission.add(
            MissionItemInt(
                targetSystem = 1u,
                targetComponent = 1u,
                seq = 0u.toUShort(),
                frame = com.divpundir.mavlink.api.MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavFrame.GLOBAL_RELATIVE_ALT),
                command = com.divpundir.mavlink.api.MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavCmd.NAV_TAKEOFF),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = Float.NaN,
                x = (state.homeLocation.latitude * 1E7).toInt(),
                y = (state.homeLocation.longitude * 1E7).toInt(),
                z = params.altitude,
                missionType = com.divpundir.mavlink.api.MavEnumValue.of(com.divpundir.mavlink.definitions.common.MavMissionType.MISSION)
            )
        )

        // Find remaining waypoints starting from selected option
        val startIndex = state.remainingWaypoints.indexOfFirst {
            it.seq.toInt() == option.waypointIndex
        }

        if (startIndex >= 0) {
            val waypointsToInclude = state.remainingWaypoints.subList(
                startIndex,
                state.remainingWaypoints.size
            )

            // Add waypoints with new sequential IDs
            waypointsToInclude.forEachIndexed { index, waypoint ->
                newMission.add(
                    waypoint.copy(
                        seq = (index + 1).toUShort(),
                        current = 0u
                    )
                )
            }
        }

        return newMission
    }

    /**
     * Resume monitoring after mission uploaded and drone armed
     */
    fun resumeMonitoring() {
        _detectionStatus.value = ObstacleDetectionStatus.MONITORING

        // Restart sensor and obstacle monitoring
        sensorManager.startMonitoring()
        startObstacleMonitoringLoop()
    }

    /**
     * Stop mission monitoring
     */
    fun stopMissionMonitoring() {

        monitoringJob?.cancel()
        sensorManager.stopMonitoring()

        if (_detectionStatus.value == ObstacleDetectionStatus.MONITORING) {
            _detectionStatus.value = ObstacleDetectionStatus.INACTIVE
        }
    }

    /**
     * Complete mission successfully
     */
    fun completeMission() {
        stopMissionMonitoring()
        rtlMonitoringJob?.cancel()

        _detectionStatus.value = ObstacleDetectionStatus.INACTIVE

        // Generate mission statistics
        val stats = generateMissionStatistics()
        logMissionStatistics(stats)

        // Clear current mission
        currentMissionWaypoints.clear()
        _savedMissionState.value = null
        _currentObstacle.value = null
    }

    /**
     * Generate mission statistics
     */
    private fun generateMissionStatistics(): MissionStatistics {
        val missionState = _savedMissionState.value

        return MissionStatistics(
            coveragePercentage = missionState?.missionProgress ?: 100f,
            obstaclesDetected = if (missionState != null) 1 else 0,
            missionInterrupts = if (missionState != null) 1 else 0,
            missionResumes = if (missionState != null) 1 else 0,
            finalStatus = if (missionState != null) MissionStatus.PARTIAL_COMPLETE else MissionStatus.COMPLETED
        )
    }

    /**
     * Log mission statistics
     */
    @Suppress("UNUSED_PARAMETER")
    private fun logMissionStatistics(stats: MissionStatistics) {
        // Mission statistics logged internally
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {

        monitoringJob?.cancel()
        rtlMonitoringJob?.cancel()
        sensorManager.cleanup()
        scope.cancel()
    }

    /**
     * Inject simulated obstacle (for testing)
     */
    fun injectSimulatedObstacle(distance: Float) {
        if (config.sensorType == SensorType.SIMULATED) {
            sensorManager.injectSimulatedReading(distance)
        }
    }
}
