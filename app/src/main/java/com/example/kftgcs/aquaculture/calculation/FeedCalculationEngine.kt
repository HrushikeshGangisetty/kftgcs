package com.example.kftgcs.aquaculture.calculation

import com.example.kftgcs.aquaculture.model.AquacultureFeedResult
import com.example.kftgcs.aquaculture.model.AquacultureInput
import kotlin.math.ceil

fun interface FeedCalculationEngine {
    fun calculate(input: AquacultureInput): AquacultureFeedResult
}

/** DEMO ONLY: these percentages are placeholders, not scientifically validated feeding advice. */
class DemoFeedCalculationEngine : FeedCalculationEngine {
    override fun calculate(input: AquacultureInput): AquacultureFeedResult {
        require(input.cultureDay in 1..120) { "culture day must be between 1 and 120" }
        require(input.prawnCountPerKg.isFinite() && input.prawnCountPerKg > 0) { "Count/kg must be positive" }
        require(input.estimatedBiomassKg.isFinite() && input.estimatedBiomassKg > 0) { "Enter a positive estimated biomass" }
        require(input.feedingSessionsPerDay in 1..24) { "Feeding sessions must be between 1 and 24" }
        require(input.hopperCapacityKg.isFinite() && input.hopperCapacityKg > 0) { "Hopper capacity must be positive" }
        require(input.dischargeRateKgPerSecond.isFinite() && input.dischargeRateKgPerSecond > 0) { "Discharge rate must be positive" }
        val rate = input.feedRateOverridePercent ?: when (input.cultureDay) {
            in 1..20 -> 3.0
            in 21..40 -> 4.0
            in 41..60 -> 5.0
            in 61..80 -> 4.5
            in 81..100 -> 3.5
            else -> 2.5
        }
        require(rate.isFinite() && rate > 0 && rate <= 100) { "Feed rate must be greater than 0 and at most 100%" }
        val daily = input.estimatedBiomassKg * rate / 100.0
        val session = daily / input.feedingSessionsPerDay
        val averageWeight = 1000.0 / input.prawnCountPerKg
        require(averageWeight.isFinite()) { "Count/kg is too small to calculate an average weight" }
        val loads = ceil(session / input.hopperCapacityKg - 1e-10).coerceAtLeast(1.0)
        require(daily.isFinite() && session > 0 && loads <= 100) { "supports at most 100 hopper loads per session" }
        return AquacultureFeedResult(
            rate, averageWeight, daily, session, loads.toInt(),
            if (input.feedRateOverridePercent == null) "Culture-day table v1" else "Operator rate override"
        )
    }
}
