package com.example.kftgcs.aquaculture

import com.divpundir.mavlink.definitions.common.MavCmd
import com.example.kftgcs.aquaculture.calculation.DemoFeedCalculationEngine
import com.example.kftgcs.aquaculture.calculation.FeedCalculationEngine
import com.example.kftgcs.aquaculture.mission.*
import com.example.kftgcs.aquaculture.model.*
import com.example.kftgcs.aquaculture.repository.AquacultureSettings
import com.example.kftgcs.aquaculture.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AquacultureDemoTest {
    private val boundary = PondBoundary(listOf(
        PondPoint(17.0, 78.0), PondPoint(17.0, 78.001),
        PondPoint(17.0008, 78.001), PondPoint(17.0008, 78.0)
    ))
    private val input = AquacultureInput(75, 60.0, 320.0, 3, 2.0, 0.05)
    private val engine = DemoFeedCalculationEngine()
    private val parameters = BoundaryMissionParameters(2, 3.0, 10.0, 25.0, 10.0)

    @Test fun exampleFeedSummaryAndAllDayTableBoundaries() {
        val result = engine.calculate(input)
        assertEquals(4.5, result.feedRatePercent, 0.0)
        assertEquals(14.4, result.dailyFeedKg, 1e-9)
        assertEquals(4.8, result.feedPerSessionKg, 1e-9)
        assertEquals(1000.0 / 60, result.averagePrawnWeightGrams, 1e-9)
        assertEquals(3, result.requiredLoads)
        listOf(1 to 3.0, 20 to 3.0, 21 to 4.0, 40 to 4.0, 41 to 5.0, 60 to 5.0,
            61 to 4.5, 80 to 4.5, 81 to 3.5, 100 to 3.5, 101 to 2.5, 120 to 2.5).forEach { (day, rate) ->
            assertEquals(rate, engine.calculate(input.copy(cultureDay = day)).feedRatePercent, 0.0)
        }
        assertEquals(10.0, engine.calculate(input.copy(prawnCountPerKg = 100.0)).averagePrawnWeightGrams, 0.0)
        assertEquals(12.5, engine.calculate(input.copy(prawnCountPerKg = 80.0)).averagePrawnWeightGrams, 0.0)
    }

    @Test fun rateOverrideAndExactHopperCapacity() {
        val result = engine.calculate(input.copy(estimatedBiomassKg = 300.0, feedRateOverridePercent = 4.0))
        assertEquals(4.0, result.feedPerSessionKg, 0.0)
        assertEquals(2, result.requiredLoads)
        assertTrue(result.formulaLabel.contains("override"))
    }

    @Test fun cultureDayChangesMissionOnIdenticalBoundaryAndRespectsHopper() {
        fun plan(day: Int): AquacultureMissionPlan {
            val current = input.copy(cultureDay = day)
            return DemoMissionParameterPlanner().plan(boundary, current, engine.calculate(current), 10.0, 3.0, 25.0)
        }
        val day25 = plan(25)
        val day75 = plan(75)
        assertEquals(boundary, day25.boundary)
        assertEquals(day25.boundary, day75.boundary)
        assertEquals(3, day25.totalPasses)
        assertEquals(4, day75.totalPasses)
        assertTrue(day75.totalWaypoints > day25.totalWaypoints)
        assertTrue(day75.distanceMeters > day25.distanceMeters)
        assertNotEquals(day25.loads.first().mission.parameters.targetSpeedMetersPerSecond,
            day75.loads.first().mission.parameters.targetSpeedMetersPerSecond)
        listOf(day25, day75).forEach { plan ->
            assertEquals(3, plan.loads.size)
            assertEquals(plan.feed.feedPerSessionKg, plan.loads.sumOf { it.feedKg }, 1e-9)
            plan.loads.forEach {
                assertTrue(it.feedKg > 0 && it.feedKg <= input.hopperCapacityKg)
                assertEquals(it.feedKg, it.feedKgPerPass * it.mission.parameters.passes, 1e-9)
                assertTrue(it.mission.parameters.feedReleaseSecondsPerPass <= it.mission.perimeterMeters / it.mission.parameters.targetSpeedMetersPerSecond + 1e-5)
            }
        }
        // Identical inputs produce byte-for-byte-equivalent domain plans.
        assertEquals(day75, plan(75))
    }

    @Test fun samplesFollowInsetBoundaryIncludeCornersAndCloseEachPass() {
        val generator = BoundaryMissionGenerator()
        val mission = generator.generate(boundary, parameters)
        val closedInput = generator.generate(PondBoundary(boundary.vertices + boundary.vertices.first()), parameters)
        assertEquals(mission, closedInput)
        mission.waypoints.groupBy { it.passIndex }.values.forEach { lap ->
            assertEquals(mission.flightBoundary.vertices.first(), lap.first().position)
            assertEquals(lap.first().position, lap.last().position)
            assertTrue(lap.map { it.position }.containsAll(mission.flightBoundary.vertices))
            lap.forEach { waypoint ->
                assertOnBoundary(waypoint.position, mission.flightBoundary)
                assertMinimumClearance(waypoint.position, boundary)
            }
            lap.zipWithNext().forEach { (a, b) -> assertTrue(PondGeometry.distance(a.position, b.position) <= 25.01) }
        }
        assertEquals(PondGeometry.perimeterMeters(mission.flightBoundary) * 2, mission.distanceMeters, 0.01)
        assertTrue(mission.distanceMeters < PondGeometry.perimeterMeters(boundary) * 2)
        assertEquals(mission.distanceMeters / 3, mission.estimatedFlightSeconds, 1e-9)
    }

    @Test fun concaveBoundaryNeverShortcutsAcrossPondInterior() {
        val concave = PondBoundary(listOf(PondPoint(17.0, 78.0), PondPoint(17.0, 78.001),
            PondPoint(17.0004, 78.0004), PondPoint(17.0008, 78.001), PondPoint(17.0008, 78.0)))
        val mission = BoundaryMissionGenerator().generate(concave, parameters)
        mission.waypoints.forEach {
            assertOnBoundary(it.position, mission.flightBoundary)
            assertMinimumClearance(it.position, concave)
        }
        // Check the flight segments too; sparse samples must not cut into the boundary margin.
        mission.waypoints.zipWithNext().forEach { (a, b) ->
            assertMinimumClearance(PondPoint((a.position.latitude + b.position.latitude) / 2,
                (a.position.longitude + b.position.longitude) / 2), concave)
        }
    }

    @Test fun indentationWorksForBothWindingsAndLargerSetbacks() {
        listOf(boundary, PondBoundary(boundary.vertices.reversed())).forEach { pond ->
            val inset = BoundaryMissionGenerator().generate(pond, parameters.copy(indentationMeters = 5.0))
            inset.waypoints.forEach { assertMinimumClearance(it.position, pond, 5.0) }
            assertTrue(PondGeometry.areaSquareMeters(inset.flightBoundary) < PondGeometry.areaSquareMeters(pond))
        }
    }

    @Test fun narrowPondsAndSubMinimumIndentationAreRejected() {
        val tiny = PondBoundary(listOf(PondPoint(17.0, 78.0), PondPoint(17.0, 78.00004),
            PondPoint(17.00004, 78.00004), PondPoint(17.00004, 78.0)))
        assertThrows(IllegalArgumentException::class.java) { BoundaryMissionGenerator().generate(tiny, parameters) }
        assertThrows(IllegalArgumentException::class.java) {
            BoundaryMissionGenerator().generate(boundary, parameters.copy(indentationMeters = 2.99))
        }
    }

    @Test fun malformedInputsAndPolygonsFailBeforeGeneration() {
        listOf(input.copy(cultureDay = 0), input.copy(cultureDay = 121), input.copy(prawnCountPerKg = 0.0),
            input.copy(estimatedBiomassKg = Double.NaN), input.copy(hopperCapacityKg = 0.0),
            input.copy(dischargeRateKgPerSecond = -1.0), input.copy(feedingSessionsPerDay = 0),
            input.copy(feedRateOverridePercent = Double.POSITIVE_INFINITY)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { engine.calculate(bad) }
        }
        val bowTie = PondBoundary(listOf(boundary.vertices[0], boundary.vertices[2], boundary.vertices[1], boundary.vertices[3]))
        val collinear = PondBoundary(listOf(PondPoint(17.0, 78.0), PondPoint(17.0, 78.001), PondPoint(17.0, 78.002)))
        listOf(bowTie, collinear, PondBoundary(boundary.vertices.take(2)), PondBoundary(boundary.vertices + boundary.vertices[1])).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { BoundaryMissionGenerator().generate(bad, parameters) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundaryMissionGenerator().generate(boundary, parameters.copy(feedReleaseSecondsPerPass = 10000.0))
        }
    }

    @Test fun mavlinkIsSequentialFlightOnlyWithHomeTakeoffBoundaryAndRtl() {
        val mission = BoundaryMissionGenerator().generate(boundary, parameters)
        val home = PondPoint(17.0002, 78.0002)
        val items = BoundaryMissionConverter.convert(mission, home, 1u, 1u)
        assertEquals(items.indices.toList(), items.map { it.seq.toInt() })
        assertEquals(1, items.first().current.toInt())
        assertTrue(items.drop(1).all { it.current.toInt() == 0 })
        assertEquals(MavCmd.NAV_TAKEOFF.value, items[1].command.value)
        assertEquals(MavCmd.DO_CHANGE_SPEED.value, items[2].command.value)
        assertEquals(3.0, items[2].param2.toDouble(), 0.0)
        assertEquals(MavCmd.NAV_RETURN_TO_LAUNCH.value, items.last().command.value)
        val path = items.drop(3).dropLast(1)
        assertEquals(mission.waypoints.size, path.size)
        path.forEachIndexed { i, item ->
            assertEquals(MavCmd.NAV_WAYPOINT.value, item.command.value)
            assertEquals(mission.waypoints[i].position.latitude, item.x / 1e7, 1e-7)
            assertEquals(mission.waypoints[i].position.longitude, item.y / 1e7, 1e-7)
            assertEquals(10f, item.z, 0f)
            assertMinimumClearance(PondPoint(item.x / 1e7, item.y / 1e7), boundary)
        }
        assertTrue(items.all { it.targetSystem == 1.toUByte() && it.targetComponent == 1.toUByte() })
    }

    @Test fun mavlinkSpeedAndRtlUseFiniteUnusedParamsAndNavigationPreservesYaw() {
        val mission = BoundaryMissionGenerator().generate(boundary, parameters)
        val items = BoundaryMissionConverter.convert(mission, boundary.vertices.first(), 1u, 1u)
        items.forEach { item ->
            assertTrue(item.param1.isFinite() && item.param2.isFinite() && item.param3.isFinite())
            when (item.command.value) {
                MavCmd.NAV_WAYPOINT.value, MavCmd.NAV_TAKEOFF.value -> assertTrue(item.param4.isNaN())
                else -> assertEquals("Unused param4 must be zero at seq=${item.seq}", 0f, item.param4, 0f)
            }
        }
        assertEquals(-1f, items[2].param3, 0f) // Keep throttle unchanged.
    }

    @Test fun hopperCapacityPersistsChangesAndInvalidatesPlanWithoutSavingInvalidInput() {
        val settings = object : AquacultureSettings {
            var capacity: Double? = null
            override fun loadHopperCapacityKg() = capacity
            override fun saveHopperCapacityKg(capacityKg: Double) { capacity = capacityKg }
        }
        val vm = AquacultureViewModel(settings = settings)
        vm.setBoundary(boundary.vertices)
        vm.setCultureDay(75)
        vm.calculate()
        vm.generate()
        vm.updateField(AquacultureField.HOPPER, "5")
        assertNull(vm.state.value.plan)
        assertNull(vm.state.value.summary)
        assertEquals(5.0, settings.capacity!!, 0.0)
        vm.calculate()
        vm.generate()
        assertEquals(1, vm.state.value.plan!!.loads.size)
        assertEquals(4.8, vm.state.value.plan!!.loads.first().feedKg, 1e-9)
        vm.updateField(AquacultureField.HOPPER, "NaN")
        vm.calculate()
        assertNull(vm.state.value.plan)
        assertNotNull(vm.state.value.error)
        assertEquals(5.0, settings.capacity!!, 0.0)
        val reopened = AquacultureViewModel(settings = settings)
        assertEquals(5.0, reopened.state.value.fields.getValue(AquacultureField.HOPPER).toDouble(), 0.0)
    }

    @Test fun viewModelInvalidatesOldPlansAndAllowsEngineReplacement() {
        val replacement = FeedCalculationEngine { engine.calculate(it.copy(feedRateOverridePercent = 2.0)) }
        val vm = AquacultureViewModel(feedEngine = replacement)
        vm.setBoundary(boundary.vertices)
        vm.calculate()
        assertEquals(2.0, vm.state.value.summary!!.feedRatePercent, 0.0)
        vm.generate()
        assertNotNull(vm.state.value.plan)
        val original = vm.state.value.boundary
        vm.setCultureDay(75)
        assertEquals(original, vm.state.value.boundary)
        assertNull(vm.state.value.plan)
        assertNull(vm.state.value.summary)
        vm.calculate()
        vm.generate()
        vm.moveVertex(0, PondPoint(17.00001, 78.0)) // boundary is locked after generation
        assertEquals(original, vm.state.value.boundary)
        vm.toggleBoundaryEditing()
        vm.moveVertex(0, PondPoint(17.00001, 78.0))
        assertNull(vm.state.value.plan)
    }

    private fun assertMinimumClearance(point: PondPoint, pond: PondBoundary, minimum: Double = 3.0) {
        val ring = PondGeometry.projected(pond.vertices)
        val projected = PondGeometry.projected(pond.vertices + point).last()
        // Re-project the point using the ring's original latitude scale.
        val ringScale = kotlin.math.cos(Math.toRadians(pond.vertices.map { it.latitude }.average()))
        val combinedScale = kotlin.math.cos(Math.toRadians((pond.vertices + point).map { it.latitude }.average()))
        val p = projected.first * ringScale / combinedScale to projected.second
        val clearance = ring.indices.minOf { i ->
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            kotlin.math.hypot(p.first - a.first - dx * t, p.second - a.second - dy * t)
        }
        assertTrue("Clearance $clearance must be at least $minimum m", clearance >= minimum)
    }

    private fun assertOnBoundary(point: PondPoint, pond: PondBoundary) {
        assertTrue("Point must lie on an original polygon edge: $point", pond.vertices.indices.any { i ->
            val a = pond.vertices[i]
            val b = pond.vertices[(i + 1) % pond.vertices.size]
            val cross = (b.longitude - a.longitude) * (point.latitude - a.latitude) -
                (b.latitude - a.latitude) * (point.longitude - a.longitude)
            abs(cross) < 1e-13 && point.latitude >= minOf(a.latitude, b.latitude) - 1e-12 &&
                point.latitude <= maxOf(a.latitude, b.latitude) + 1e-12 &&
                point.longitude >= minOf(a.longitude, b.longitude) - 1e-12 && point.longitude <= maxOf(a.longitude, b.longitude) + 1e-12
        })
    }
}
