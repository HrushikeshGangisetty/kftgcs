package com.example.kftgcs.grid

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.*
import com.google.android.gms.maps.model.LatLng

/**
 * Converts grid survey waypoints to MAVLink mission items
 */
object GridMissionConverter {


    // MAV_CMD_DO_SPRAYER command ID (not available in library, using raw value)
    // param1: 0 = stop spraying, 1 = start spraying
    private const val MAV_CMD_DO_SPRAYER = 216u

    /**
     * Convert grid waypoints to MAVLink mission items
     * @param gridResult Grid survey result from GridGenerator
     * @param homePosition Home position for mission
     * @param holdNosePosition If true, adds MAV_CMD_CONDITION_YAW to maintain yaw throughout mission
     * @param initialYaw Initial yaw angle in degrees (0-360, 0=North, 90=East)
     * @param autoSpray If true, adds DO_SPRAYER commands at start/end of each survey line
     * @param fcuSystemId Target FCU system ID (defaults to 0 for compatibility)
     * @param fcuComponentId Target FCU component ID (defaults to 0 for compatibility)
     * @return List of MAVLink MissionItemInt objects
     */
    fun convertToMissionItems(
        gridResult: GridSurveyResult,
        homePosition: LatLng,
        holdNosePosition: Boolean = false,
        initialYaw: Float = 0f,
        autoSpray: Boolean = false,
        fcuSystemId: UByte = 0u,
        fcuComponentId: UByte = 0u,
        completionAction: String = "RTL"
    ): List<MissionItemInt> {

        val missionItems = mutableListOf<MissionItemInt>()

        // CRITICAL FIX: Sequence 0 = HOME position (NAV_WAYPOINT with current=1, z=0f)
        missionItems.add(
            MissionItemInt(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                seq = 0u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                current = 1u, // MUST be 1 for first item
                autocontinue = 1u,
                param1 = 0f, // Hold time
                param2 = 3f, // Acceptance radius in meters (CRITICAL: must be >0)
                param3 = 0f, // Pass through
                param4 = 0f, // Yaw
                x = (homePosition.latitude * 1E7).toInt(),
                y = (homePosition.longitude * 1E7).toInt(),
                z = 0f // CRITICAL: HOME altitude must be 0 (relative)
            )
        )

        // Sequence 1: Takeoff at home position to survey altitude
        val takeoffAltitude = gridResult.waypoints.firstOrNull()?.altitude ?: 15f
        missionItems.add(
            MissionItemInt(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                seq = 1u,
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                command = MavEnumValue.of(MavCmd.NAV_TAKEOFF),
                current = 0u,
                autocontinue = 1u,
                param1 = 0f, // Minimum pitch
                param2 = 0f,
                param3 = 0f,
                param4 = 0f, // Yaw
                x = (homePosition.latitude * 1E7).toInt(),
                y = (homePosition.longitude * 1E7).toInt(),
                z = takeoffAltitude // Takeoff to survey altitude
            )
        )

        var sequenceNumber = 2

        // Add CONDITION_YAW command right after takeoff if holdNosePosition is enabled
        // This sets the yaw and maintains it throughout the mission
        // ArduPilot MAV_CMD_CONDITION_YAW (115):
        //   param1: Target angle (degrees 0-360)
        //   param2: Angular speed (deg/s, 0 = default)
        //   param3: Direction (-1=CCW, 0=shortest, 1=CW)
        //   param4: 0=absolute angle, 1=relative offset
        // IMPORTANT: For ArduPilot Copter, yaw commands need MavFrame.MISSION frame
        if (holdNosePosition) {
            // First, clear any ROI that might override yaw control
            // MAV_CMD_DO_SET_ROI_NONE (197) clears the ROI so yaw is controlled by mission
            missionItems.add(
                MissionItemInt(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    seq = sequenceNumber.toUShort(),
                    frame = MavEnumValue.of(MavFrame.MISSION),
                    command = MavEnumValue.of(MavCmd.DO_SET_ROI_NONE),
                    current = 0u,
                    autocontinue = 1u,
                    param1 = 0f, // Unused
                    param2 = 0f, // Unused
                    param3 = 0f, // Unused
                    param4 = 0f, // Unused
                    x = 0,
                    y = 0,
                    z = 0f
                )
            )
            sequenceNumber++

            // Set the yaw angle to hold throughout the mission
            missionItems.add(
                MissionItemInt(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    seq = sequenceNumber.toUShort(),
                    frame = MavEnumValue.of(MavFrame.MISSION), // Use MISSION frame for non-nav commands
                    command = MavEnumValue.of(MavCmd.CONDITION_YAW),
                    current = 0u,
                    autocontinue = 1u,
                    param1 = initialYaw, // Target yaw angle in degrees (0-360)
                    param2 = 30f, // Yaw speed deg/s (use reasonable speed instead of default)
                    param3 = 0f, // Direction: 0 = shortest path to target
                    param4 = 0f, // 0 = absolute angle, 1 = relative angle
                    x = 0,
                    y = 0,
                    z = 0f
                )
            )
            sequenceNumber++
        }

        var lastLineIndex = -1
        var isFirstWaypoint = true

        // Convert grid waypoints to mission items (starting from seq=2 or seq=3 if holdNosePosition)
        gridResult.waypoints.forEach { waypoint ->
            val currentLineIndex = waypoint.lineIndex
            val isNewLine = currentLineIndex != lastLineIndex

            // For the first waypoint after takeoff, add speed command BEFORE the waypoint
            // ArduPilot MAV_CMD_DO_CHANGE_SPEED (178):
            //   param1: Speed type (0=Airspeed, 1=Ground Speed, 2=Climb, 3=Descent)
            //   param2: Speed in m/s (-1 = no change)
            //   param3: Throttle % (-1 = no change)
            //   param4: 0=absolute, 1=relative
            if (isFirstWaypoint && waypoint.speed != null) {
                missionItems.add(
                    MissionItemInt(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        seq = sequenceNumber.toUShort(),
                        frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                        command = MavEnumValue.of(MavCmd.DO_CHANGE_SPEED),
                        current = 0u,
                        autocontinue = 1u,
                        param1 = 1f, // Speed type: 1 = Ground Speed (for copter)
                        param2 = waypoint.speed, // Target speed in m/s
                        param3 = -1f, // Throttle (-1 = no change)
                        param4 = 0f, // 0 = absolute speed
                        x = 0,
                        y = 0,
                        z = 0f
                    )
                )
                sequenceNumber++
                isFirstWaypoint = false
            }
            // Add speed change command at start of each NEW line (but not the first waypoint)
            else if (!isFirstWaypoint && waypoint.isLineStart && waypoint.speed != null && isNewLine) {
                missionItems.add(
                    MissionItemInt(
                        targetSystem = fcuSystemId,
                        targetComponent = fcuComponentId,
                        seq = sequenceNumber.toUShort(),
                        frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                        command = MavEnumValue.of(MavCmd.DO_CHANGE_SPEED),
                        current = 0u,
                        autocontinue = 1u,
                        param1 = 1f, // Speed type: 1 = Ground Speed (for copter)
                        param2 = waypoint.speed, // Target speed in m/s
                        param3 = -1f, // Throttle (-1 = no change)
                        param4 = 0f, // 0 = absolute speed
                        x = 0,
                        y = 0,
                        z = 0f
                    )
                )
                sequenceNumber++
            }

            // Add the actual waypoint
            // ArduPilot MAV_CMD_NAV_WAYPOINT (16):
            //   param1: Hold time in seconds (0 = no hold, proceed immediately)
            //   param2: Acceptance radius in meters (vehicle considers WP reached when within this radius)
            //   param3: Pass radius (0 = pass through WP, >0 = pass by WP at this radius)
            //   param4: Desired yaw angle at WP (NaN = no yaw change, use current heading)
            // When holdNosePosition is true, use the initial yaw value to maintain nose position
            // Using actual yaw value instead of NaN for more reliable behavior across ArduPilot versions
            val waypointYaw = if (holdNosePosition) initialYaw else 0f

            missionItems.add(
                MissionItemInt(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    seq = sequenceNumber.toUShort(),
                    frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                    command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
                    current = 0u,
                    autocontinue = 1u,
                    param1 = 0f, // Hold time (0 = no hold, proceed immediately)
                    param2 = 3f, // Acceptance radius in meters (2-5m recommended for copter)
                    param3 = 0f, // Pass radius (0 = fly through waypoint)
                    param4 = waypointYaw, // Yaw angle (NaN = maintain current, 0 = north)
                    x = (waypoint.position.latitude * 1E7).toInt(),
                    y = (waypoint.position.longitude * 1E7).toInt(),
                    z = waypoint.altitude
                )
            )
            sequenceNumber++

            // Add sprayer commands AFTER reaching waypoint position
            // ArduPilot MAV_CMD_DO_SPRAYER (216) - ArduPilot specific:
            //   param1: 0 = disable/stop spraying, 1 = enable/start spraying
            //   param2-7: Unused (ignored)
            // This ensures spraying happens during the survey line, not while traveling to it
            if (autoSpray) {
                // If this is the START of a new line, add START spray command AFTER this waypoint
                if (waypoint.isLineStart && isNewLine) {
                    missionItems.add(
                        MissionItemInt(
                            targetSystem = fcuSystemId,
                            targetComponent = fcuComponentId,
                            seq = sequenceNumber.toUShort(),
                            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                            command = MavEnumValue.fromValue(MAV_CMD_DO_SPRAYER),  // MAV_CMD_DO_SPRAYER = 216
                            current = 0u,
                            autocontinue = 1u,
                            param1 = 1f,  // 1 = Enable/START spraying
                            param2 = 0f,  // Unused
                            param3 = 0f,  // Unused
                            param4 = 0f,  // Unused
                            x = 0,
                            y = 0,
                            z = 0f
                        )
                    )
                    sequenceNumber++
                }
                // If this is the END of a line, add STOP spray command AFTER this waypoint
                else if (waypoint.isLineEnd) {
                    missionItems.add(
                        MissionItemInt(
                            targetSystem = fcuSystemId,
                            targetComponent = fcuComponentId,
                            seq = sequenceNumber.toUShort(),
                            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                            command = MavEnumValue.fromValue(MAV_CMD_DO_SPRAYER),  // MAV_CMD_DO_SPRAYER = 216
                            current = 0u,
                            autocontinue = 1u,
                            param1 = 0f,  // 0 = Disable/STOP spraying
                            param2 = 0f,  // Unused
                            param3 = 0f,  // Unused
                            param4 = 0f,  // Unused
                            x = 0,
                            y = 0,
                            z = 0f
                        )
                    )
                    sequenceNumber++
                }
            }

            // Update lastLineIndex after processing this waypoint
            lastLineIndex = currentLineIndex

            if (isFirstWaypoint) {
                isFirstWaypoint = false
            }
        }

        // Final safety STOP spraying before RTL (in case last line end wasn't processed)
        // ArduPilot MAV_CMD_DO_SPRAYER (216): param1=0 to disable spraying
        // This ensures sprayer is definitely off before returning home
        if (autoSpray) {
            missionItems.add(
                MissionItemInt(
                    targetSystem = fcuSystemId,
                    targetComponent = fcuComponentId,
                    seq = sequenceNumber.toUShort(),
                    frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                    command = MavEnumValue.fromValue(MAV_CMD_DO_SPRAYER),  // MAV_CMD_DO_SPRAYER = 216
                    current = 0u,
                    autocontinue = 1u,
                    param1 = 0f,  // 0 = Disable/STOP spraying
                    param2 = 0f,  // Unused
                    param3 = 0f,  // Unused
                    param4 = 0f,  // Unused
                    x = 0,
                    y = 0,
                    z = 0f
                )
            )
            sequenceNumber++
        }

        // Add completion action at the end (configurable: RTL, LAND, or LOITER)
        val completionCommand = when (completionAction) {
            "LAND" -> MavEnumValue.of(MavCmd.NAV_LAND)
            "LOITER" -> MavEnumValue.of(MavCmd.NAV_LOITER_UNLIM)
            else -> MavEnumValue.of(MavCmd.NAV_RETURN_TO_LAUNCH)
        }
        missionItems.add(
            MissionItemInt(
                targetSystem = fcuSystemId,
                targetComponent = fcuComponentId,
                seq = sequenceNumber.toUShort(),
                frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
                command = completionCommand,
                current = 0u,
                autocontinue = 1u,
                param1 = 0f,
                param2 = 0f,
                param3 = 0f,
                param4 = 0f,
                x = 0,
                y = 0,
                z = 0f
            )
        )


        return missionItems
    }

    /**
     * Convert single waypoint to mission item
     * @param waypoint Grid waypoint to convert
     * @param sequence Mission sequence number
     * @param holdNosePosition If true, sets param4 to the initial yaw to maintain nose position
     * @param initialYaw The yaw angle to use when holdNosePosition is true
     */
    private fun waypointToMissionItem(
        waypoint: GridWaypoint,
        sequence: Int,
        holdNosePosition: Boolean = false,
        initialYaw: Float = 0f
    ): MissionItemInt {
        return MissionItemInt(
            targetSystem = 0u,
            targetComponent = 0u,
            seq = sequence.toUShort(),
            frame = MavEnumValue.of(MavFrame.GLOBAL_RELATIVE_ALT_INT),
            command = MavEnumValue.of(MavCmd.NAV_WAYPOINT),
            current = 0u,
            autocontinue = 1u,
            param1 = 0f, // Hold time (0 = no hold)
            param2 = 3f, // Acceptance radius in meters (2-5m recommended)
            param3 = 0f, // Pass radius (0 = pass through waypoint)
            param4 = if (holdNosePosition) initialYaw else 0f, // Use initialYaw to maintain nose position
            x = (waypoint.position.latitude * 1E7).toInt(),
            y = (waypoint.position.longitude * 1E7).toInt(),
            z = waypoint.altitude
        )
    }

    /**
     * Estimate mission duration
     * @param gridResult Grid survey result
     * @param cruiseSpeed Average cruise speed in m/s
     * @return Estimated time in minutes
     */
    fun estimateMissionDuration(gridResult: GridSurveyResult, cruiseSpeed: Float = 10f): Double {
        val surveyTime = (gridResult.totalDistance / cruiseSpeed) / 60f // Convert to minutes
        val setupTime = 2f // Minutes for takeoff, positioning, etc.
        val rtlTime = 1f // Minutes for return to launch

        return surveyTime + setupTime + rtlTime
    }

    /**
     * Calculate total mission items count
     * @param gridResult Grid survey result
     * @param holdNosePosition If true, includes DO_SET_ROI_NONE + CONDITION_YAW commands in count
     * @param autoSpray If true, includes DO_SPRAYER commands in count
     */
    fun calculateMissionItemCount(
        gridResult: GridSurveyResult,
        holdNosePosition: Boolean = false,
        autoSpray: Boolean = false
    ): Int {
        var count = 2 // Home + Takeoff

        // Add DO_SET_ROI_NONE + CONDITION_YAW commands if holdNosePosition is enabled
        if (holdNosePosition) {
            count += 2 // DO_SET_ROI_NONE + CONDITION_YAW
        }

        count += gridResult.waypoints.size // Survey waypoints

        // Count speed change commands (one per line)
        val speedCommands = gridResult.waypoints.count { it.isLineStart && it.speed != null }
        count += speedCommands

        // Count sprayer commands if autoSpray is enabled
        // New logic: START spray after each line start waypoint, STOP spray after each line end waypoint
        // Plus one final safety STOP before RTL
        if (autoSpray) {
            // Count line starts (START commands)
            val lineStartCount = gridResult.waypoints.count { it.isLineStart }
            // Count line ends (STOP commands)
            val lineEndCount = gridResult.waypoints.count { it.isLineEnd }
            // Add final safety STOP before RTL
            count += lineStartCount + lineEndCount + 1
        }

        count += 1 // RTL

        return count
    }
}
