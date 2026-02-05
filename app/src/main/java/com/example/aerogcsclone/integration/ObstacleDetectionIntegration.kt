package com.example.aerogcsclone.integration

import android.content.Context
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MavFrame
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavMissionType
import com.divpundir.mavlink.api.MavEnumValue
import com.example.aerogcsclone.database.ObstacleDatabase
import com.example.aerogcsclone.obstacle.*
import com.example.aerogcsclone.telemetry.SharedViewModel
import com.example.aerogcsclone.viewmodel.ObstacleDetectionViewModel
import com.google.android.gms.maps.model.LatLng

/**
 * Example integration of Obstacle Detection system with existing GCS app
 * This shows how to integrate the obstacle detection functionality step-by-step
 */
class ObstacleDetectionIntegrationExample(
    private val context: Context,
    private val sharedViewModel: SharedViewModel
) {

    private lateinit var obstacleViewModel: ObstacleDetectionViewModel
    private lateinit var missionStateRepository: MissionStateRepository

    /**
     * Step 1: Initialize the obstacle detection system
     * Call this when your app starts or when connecting to drone
     */
    fun initializeObstacleDetection(viewModel: ObstacleDetectionViewModel) {
        // Store the ViewModel instance
        obstacleViewModel = viewModel

        // 1. Get database instance
        val database = ObstacleDatabase.getDatabase(context)
        val savedMissionStateDao = database.savedMissionStateDao()

        // 2. Create repository
        missionStateRepository = MissionStateRepository(savedMissionStateDao)

        // 3. Configure obstacle detection parameters
        val config = ObstacleDetectionConfig(
            minDetectionRange = 0f,
            maxDetectionRange = 50f,
            highThreatThreshold = 10f,      // Trigger RTL below 10m
            mediumThreatThreshold = 20f,    // Caution alert below 20m
            lowThreatThreshold = 50f,       // Warning below 50m
            detectionIntervalMs = 100,      // Check every 100ms
            minimumConsecutiveDetections = 3, // Require 3 consecutive HIGH detections
            enableAutoRTL = true,           // Auto-trigger RTL
            sensorType = SensorType.SIMULATED // Use SIMULATED for testing, PROXIMITY for real sensor
        )

        // 4. Initialize the ViewModel
        obstacleViewModel.initialize(
            missionStateRepository = missionStateRepository,
            config = config
        )
    }

    /**
     * Step 2: Start monitoring when mission begins
     * Call this after mission upload succeeds and before takeoff
     */
    fun startObstacleMonitoring(
        missionWaypoints: List<MissionItemInt>,
        homeLocation: LatLng,
        surveyPolygon: List<LatLng> = emptyList()
    ) {
        // Extract mission parameters from waypoints
        val altitude = extractAltitudeFromWaypoints(missionWaypoints)
        val speed = 12f // Default speed, can be extracted from mission

        // Start monitoring
        obstacleViewModel.startMissionMonitoring(
            waypoints = missionWaypoints,
            homeLocation = homeLocation,
            surveyPolygon = surveyPolygon,
            altitude = altitude,
            speed = speed
        )
    }

    /**
     * Step 3: Observe obstacle detection status
     * Collect these flows in your UI to display status
     */
    fun observeObstacleStatus() {
        // In your Composable or Activity:
        /*
        val detectionStatus by obstacleViewModel.detectionStatus.collectAsState()
        val currentObstacle by obstacleViewModel.currentObstacle.collectAsState()
        val resumeOptions by obstacleViewModel.resumeOptions.collectAsState()

        when (detectionStatus) {
            ObstacleDetectionStatus.MONITORING -> {
                // Show "Monitoring Active" indicator
                currentObstacle?.let { obstacle ->
                    when (obstacle.threatLevel) {
                        ThreatLevel.LOW -> // Show green indicator
                        ThreatLevel.MEDIUM -> // Show yellow indicator
                        ThreatLevel.HIGH -> // Show red indicator
                        else -> {}
                    }
                }
            }
            ObstacleDetectionStatus.OBSTACLE_DETECTED -> {
                // Show "Emergency RTL Triggered" alert
            }
            ObstacleDetectionStatus.RTL_IN_PROGRESS -> {
                // Show "Returning Home" with progress
            }
            ObstacleDetectionStatus.READY_TO_RESUME -> {
                // Show resume options dialog
                if (resumeOptions.isNotEmpty()) {
                    // Display options to user
                    showResumeOptionsDialog(resumeOptions)
                }
            }
            else -> {}
        }
        */
    }

    /**
     * Step 4: Handle mission resume when user selects option
     */
    fun handleMissionResume(selectedOption: ResumeOption) {

        // Resume mission from selected waypoint
        obstacleViewModel.resumeMission(selectedOption)

        // After upload completes, user should:
        // 1. Place fresh battery in drone
        // 2. Place drone on ground at home location
        // 3. Arm and takeoff
        // 4. System will automatically resume monitoring
    }

    /**
     * Step 5: Stop monitoring when mission completes
     */
    fun stopObstacleMonitoring(missionCompleted: Boolean = true) {
        if (missionCompleted) {
            obstacleViewModel.completeMission()
        } else {
            obstacleViewModel.stopMonitoring()
        }
    }

    /**
     * TESTING: Simulate obstacle detection
     * Use this to test the system without real sensors
     */
    fun testObstacleDetection() {
        // Test LOW threat (20-50m)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            obstacleViewModel.injectSimulatedObstacle(25f)
        }, 5000)

        // Test MEDIUM threat (10-20m)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            obstacleViewModel.injectSimulatedObstacle(15f)
        }, 10000)

        // Test HIGH threat (< 10m) - should trigger RTL after 3 consecutive
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            obstacleViewModel.injectSimulatedObstacle(8f)
        }, 15000)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            obstacleViewModel.injectSimulatedObstacle(7f)
        }, 15200)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            obstacleViewModel.injectSimulatedObstacle(6f)
        }, 15400)
    }

    // Helper functions

    private fun extractAltitudeFromWaypoints(waypoints: List<MissionItemInt>): Float {
        // Find first waypoint with altitude
        return waypoints.firstOrNull { it.z > 0 }?.z ?: 30f
    }

    private fun showResumeOptionsDialog(options: List<ResumeOption>) {
        // Options available for resuming mission
        // In production, display these in a UI dialog
    }
}

/**
 * Example: Complete Mission Flow with Obstacle Detection
 */
class CompleteFlightExample(
    context: Context,
    private val sharedViewModel: SharedViewModel,
    private val obstacleViewModel: ObstacleDetectionViewModel
) {
    private val integration = ObstacleDetectionIntegrationExample(context, sharedViewModel)

    fun runCompleteExample() {
        // PHASE 1-2: Initialize system
        integration.initializeObstacleDetection(obstacleViewModel)

        // PHASE 3: Upload mission and start monitoring
        val sampleWaypoints = createSampleMission()
        val homeLocation = LatLng(37.7749, -122.4194) // San Francisco

        sharedViewModel.uploadMission(sampleWaypoints) { success, _ ->
            if (success) {
                // Start obstacle monitoring
                integration.startObstacleMonitoring(
                    missionWaypoints = sampleWaypoints,
                    homeLocation = homeLocation
                )

                // Arm and takeoff (adjust to your SharedViewModel API)
                sharedViewModel.arm()
                // Note: takeoff is handled by mission waypoint

                // PHASE 4: System now monitors for obstacles automatically

                // For testing: inject simulated obstacle
                integration.testObstacleDetection()
            }
        }

        // PHASE 5-6: System automatically triggers RTL if HIGH threat detected
        // PHASE 7-10: User resumes mission via UI
        // PHASE 11-12: Mission continues and completes
    }

    private fun createSampleMission(): List<MissionItemInt> {
        val home = LatLng(37.7749, -122.4194)

        return listOf(
            // Takeoff
            MissionItemInt(
                targetSystem = 1u,
                targetComponent = 1u,
                seq = 0u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT),
                command = MavEnumValue.of(MavCmd.NAV_TAKEOFF),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = Float.NaN,
                x = (home.latitude * 1E7).toInt(),
                y = (home.longitude * 1E7).toInt(),
                z = 30f,
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            ),
            // Waypoint 1
            MissionItemInt(
                targetSystem = 1u,
                targetComponent = 1u,
                seq = 1u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT),
                command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = Float.NaN,
                x = ((home.latitude + 0.001) * 1E7).toInt(),
                y = ((home.longitude + 0.001) * 1E7).toInt(),
                z = 30f,
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            ),
            // Waypoint 2
            MissionItemInt(
                targetSystem = 1u,
                targetComponent = 1u,
                seq = 2u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT),
                command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = Float.NaN,
                x = ((home.latitude + 0.002) * 1E7).toInt(),
                y = ((home.longitude + 0.001) * 1E7).toInt(),
                z = 30f,
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            ),
            // Land
            MissionItemInt(
                targetSystem = 1u,
                targetComponent = 1u,
                seq = 3u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT),
                command = MavEnumValue.of(MavCmd.NAV_LAND),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = Float.NaN,
                x = (home.latitude * 1E7).toInt(),
                y = (home.longitude * 1E7).toInt(),
                z = 0f,
                missionType = MavEnumValue.of(MavMissionType.MISSION)
            )
        )
    }
}
