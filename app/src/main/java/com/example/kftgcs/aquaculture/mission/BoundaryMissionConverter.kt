package com.example.kftgcs.aquaculture.mission

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.*
import com.example.kftgcs.aquaculture.model.BoundaryMission
import com.example.kftgcs.aquaculture.model.PondPoint

/** Flight-only DEMO adapter. Feed timing is simulated; no uncalibrated actuator commands are sent. */
object BoundaryMissionConverter {
    fun convert(mission: BoundaryMission, home: PondPoint, systemId: UByte, componentId: UByte): List<MissionItemInt> {
        require(mission.waypoints.isNotEmpty()) { "Boundary mission is empty" }
        val items = mutableListOf<MissionItemInt>()
        fun add(command: MavCmd, position: PondPoint? = null, altitude: Float = 0f,
                param1: Float = 0f, param2: Float = 0f, param3: Float = 0f, param4: Float = 0f) {
            items += MissionItemInt(
                targetSystem = systemId, targetComponent = componentId, seq = items.size.toUShort(),
                frame = MavEnumValue.of(if (position == null) MavFrame.MISSION else MavFrame.GLOBAL_RELATIVE_ALT_INT),
                command = MavEnumValue.of(command), current = if (items.isEmpty()) 1u else 0u,
                autocontinue = 1u, param1 = param1, param2 = param2, param3 = param3, param4 = param4,
                x = position?.let { (it.latitude * 1e7).toInt() } ?: 0,
                y = position?.let { (it.longitude * 1e7).toInt() } ?: 0, z = altitude
            )
        }
        val altitude = mission.parameters.altitudeMeters.toFloat()
        // Follow the existing GCS HOME / TAKEOFF / path / RTL contract.
        // Only navigation commands with a yaw parameter accept NaN (maintain heading).
        // Unused parameters on DO_CHANGE_SPEED and RTL must be finite for ArduPilot uploads.
        // HOME and TAKEOFF are launch metadata; operational waypoints follow the inset contour.
        add(MavCmd.NAV_WAYPOINT, home, param2 = 1f, param4 = Float.NaN)
        add(MavCmd.NAV_TAKEOFF, home, altitude, param4 = Float.NaN)
        add(MavCmd.DO_CHANGE_SPEED, param1 = 1f, param2 = mission.parameters.targetSpeedMetersPerSecond.toFloat(), param3 = -1f)
        mission.waypoints.forEach { add(MavCmd.NAV_WAYPOINT, it.position, altitude, param2 = 1f, param4 = Float.NaN) }
        add(MavCmd.NAV_RETURN_TO_LAUNCH)
        return items
    }
}
