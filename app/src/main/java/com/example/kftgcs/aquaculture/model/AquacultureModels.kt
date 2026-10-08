package com.example.kftgcs.aquaculture.model

data class PondPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -85.0..85.0) { "Invalid pond latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid pond longitude" }
    }
}

data class PondBoundary(val vertices: List<PondPoint>)

/** Operator estimates; no stocking/survival assumptions are inferred by this prototype. */
data class AquacultureInput(
    val cultureDay: Int,
    val prawnCountPerKg: Double,
    val estimatedBiomassKg: Double,
    val feedingSessionsPerDay: Int,
    val hopperCapacityKg: Double,
    val dischargeRateKgPerSecond: Double,
    val feedRateOverridePercent: Double? = null
)

data class AquacultureFeedResult(
    val feedRatePercent: Double,
    val averagePrawnWeightGrams: Double,
    val dailyFeedKg: Double,
    val feedPerSessionKg: Double,
    val requiredLoads: Int,
    val formulaLabel: String
)

data class BoundaryMissionParameters(
    val passes: Int,
    val targetSpeedMetersPerSecond: Double,
    val feedReleaseSecondsPerPass: Double,
    val waypointSpacingMeters: Double,
    val altitudeMeters: Double,
    val indentationMeters: Double = 3.0
)

data class BoundaryWaypoint(val position: PondPoint, val passIndex: Int)

data class BoundaryMission(
    val waypoints: List<BoundaryWaypoint>,
    val parameters: BoundaryMissionParameters,
    val perimeterMeters: Double,
    val distanceMeters: Double,
    val estimatedFlightSeconds: Double,
    val flightBoundary: PondBoundary
)

data class AquacultureLoadMission(val loadNumber: Int, val feedKg: Double, val mission: BoundaryMission) {
    val feedKgPerPass: Double get() = feedKg / mission.parameters.passes
}

data class AquacultureMissionPlan(
    val boundary: PondBoundary,
    val feed: AquacultureFeedResult,
    val loads: List<AquacultureLoadMission>
) {
    val totalPasses: Int get() = loads.sumOf { it.mission.parameters.passes }
    val totalWaypoints: Int get() = loads.sumOf { it.mission.waypoints.size }
    val distanceMeters: Double get() = loads.sumOf { it.mission.distanceMeters }
    val flightSeconds: Double get() = loads.sumOf { it.mission.estimatedFlightSeconds }
}
