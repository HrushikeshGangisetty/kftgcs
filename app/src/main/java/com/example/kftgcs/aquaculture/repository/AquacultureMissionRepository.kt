package com.example.kftgcs.aquaculture.repository

import com.example.kftgcs.aquaculture.mission.BoundaryMissionConverter
import com.example.kftgcs.aquaculture.model.*
import com.example.kftgcs.telemetry.SharedViewModel
import com.google.android.gms.maps.model.LatLng

/** Thin integration with the existing ACK/progress/geofence upload path, never a second uploader. */
class AquacultureMissionRepository(private val telemetry: SharedViewModel) {
    fun upload(plan: AquacultureMissionPlan, loadIndex: Int, onResult: (Boolean, String?) -> Unit) {
        val state = telemetry.telemetryState.value
        if (!state.connected || !state.fcuDetected || state.armed || state.isMissionActive) {
            onResult(false, "Connect a disarmed vehicle before uploading a load")
            return
        }
        val lat = state.homeLatitude
        val lon = state.homeLongitude
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) {
            onResult(false, "Waiting for the vehicle's HOME_POSITION; no fallback home will be invented")
            return
        }
        val load = plan.loads[loadIndex]
        val items = try {
            BoundaryMissionConverter.convert(load.mission, PondPoint(lat, lon), telemetry.getFcuSystemId(), telemetry.getFcuComponentId())
        } catch (error: IllegalArgumentException) {
            onResult(false, error.message)
            return
        }
        telemetry.uploadMission(items) { success, error ->
            if (success) {
                val points = load.mission.waypoints.map { LatLng(it.position.latitude, it.position.longitude) }
                telemetry.setSurveyPolygon(plan.boundary.vertices.map { LatLng(it.latitude, it.longitude) })
                telemetry.setPlanningWaypoints(points)
                telemetry.setGridWaypoints(points)
                telemetry.setGridLines(points.zipWithNext())
                telemetry.setObstacles(emptyList())
                telemetry.setMissionType(SharedViewModel.MissionType.WAYPOINT)
                telemetry.setGridSetupSource(SharedViewModel.GridSetupSource.MAP_DRAW)
                telemetry.announceSelectedAutomatic()
                telemetry.resetCurrentSprayMissionParams()
                telemetry.setCurrentMissionNames("Aquaculture", "Prawn • load ${load.loadNumber}/${plan.loads.size}")
            }
            onResult(success, error)
        }
    }
}
