package com.example.kftgcs.aquaculture.ui

import androidx.lifecycle.ViewModel
import com.example.kftgcs.aquaculture.calculation.DemoFeedCalculationEngine
import com.example.kftgcs.aquaculture.calculation.FeedCalculationEngine
import com.example.kftgcs.aquaculture.mission.DemoMissionParameterPlanner
import com.example.kftgcs.aquaculture.mission.PondGeometry
import com.example.kftgcs.aquaculture.model.*
import com.example.kftgcs.aquaculture.repository.AquacultureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AquacultureField(val label: String, val defaultValue: String, val integer: Boolean = false) {
    CULTURE_DAY("Culture day (1–120)", "25", true),
    COUNT("Count / kg", "60"),
    BIOMASS("Biomass (kg)", "320"),
    FEED_RATE("Feed rate override (%)", ""),
    SESSIONS("Sessions / day", "3", true),
    HOPPER("Hopper capacity (kg)", "2"),
    DISCHARGE("Discharge rate (kg/s)", "0.05"),
    AREA("Pond area (acres, optional)", ""),
    ALTITUDE("Altitude (m)", "10"),
    SPEED("Base flight speed (m/s)", "3"),
    SPACING("Spacing (m)", "25"),
    INDENTATION("Indentation (m)", "3")
}

data class AquacultureUiState(
    val fields: Map<AquacultureField, String> = AquacultureField.entries.associateWith { it.defaultValue },
    val boundary: List<PondPoint> = emptyList(),
    val summary: AquacultureFeedResult? = null,
    val plan: AquacultureMissionPlan? = null,
    val selectedLoad: Int = 0,
    val selectedPass: Int = 0,
    val editingBoundary: Boolean = true,
    val selectedVertex: Int? = null,
    val error: String? = null,
    val uploading: Boolean = false,
    val uploadMessage: String? = null,
    val uploadedLoad: Int? = null
)

class AquacultureViewModel(
    private val feedEngine: FeedCalculationEngine = DemoFeedCalculationEngine(),
    private val planner: DemoMissionParameterPlanner = DemoMissionParameterPlanner(),
    private val settings: AquacultureSettings? = null
) : ViewModel() {
    private val mutableState = MutableStateFlow(AquacultureUiState().let { initial ->
        settings?.loadHopperCapacityKg()?.takeIf { it.isFinite() && it > 0 }?.let { capacity ->
            initial.copy(fields = initial.fields + (AquacultureField.HOPPER to capacity.toString()))
        } ?: initial
    })
    val state = mutableState.asStateFlow()

    fun updateField(field: AquacultureField, value: String) {
        if (state.value.uploading) return
        edit { copy(fields = fields + (field to value)) }
        if (field == AquacultureField.HOPPER) {
            value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { settings?.saveHopperCapacityKg(it) }
        }
    }
    fun setCultureDay(day: Int) = edit {
        copy(fields = fields + (AquacultureField.CULTURE_DAY to day.toString()) + (AquacultureField.FEED_RATE to ""))
    }
    fun setBoundary(points: List<PondPoint>) = edit { copy(boundary = points.toList(), selectedVertex = null) }
    fun addVertex(point: PondPoint) { if (state.value.editingBoundary) setBoundary(state.value.boundary + point) }
    fun moveVertex(index: Int, point: PondPoint) {
        if (state.value.editingBoundary) setBoundary(state.value.boundary.mapIndexed { i, old -> if (i == index) point else old })
    }
    fun selectVertex(index: Int) { mutableState.update { it.copy(selectedVertex = index) } }
    fun deleteSelectedVertex() {
        state.value.selectedVertex?.let { index -> setBoundary(state.value.boundary.filterIndexed { i, _ -> i != index }) }
    }
    fun toggleBoundaryEditing() { mutableState.update { it.copy(editingBoundary = !it.editingBoundary) } }
    fun selectLoad(index: Int) { mutableState.update { it.copy(selectedLoad = index, selectedPass = 0) } }
    fun selectPass(index: Int) { mutableState.update { it.copy(selectedPass = index) } }

    fun calculate() = validated {
        PondGeometry.normalized(PondBoundary(state.value.boundary))
        val area = state.value.fields.getValue(AquacultureField.AREA)
        if (area.isNotBlank()) require(number(AquacultureField.AREA) > 0) { "Pond area must be positive" }
        val feed = feedEngine.calculate(input())
        mutableState.update { it.copy(summary = feed, plan = null, error = null, uploadedLoad = null, uploadMessage = null) }
    }

    fun generate() = validated {
        val current = state.value
        val feed = current.summary ?: error("Calculate and review the feed summary first")
        val plan = planner.plan(PondBoundary(current.boundary), input(), feed,
            number(AquacultureField.ALTITUDE), number(AquacultureField.SPEED), number(AquacultureField.SPACING),
            number(AquacultureField.INDENTATION))
        mutableState.update { it.copy(plan = plan, selectedLoad = 0, selectedPass = 0, editingBoundary = false, error = null, uploadedLoad = null, uploadMessage = null) }
    }

    fun beginUpload(): Boolean {
        if (state.value.uploading || state.value.plan == null) return false
        mutableState.update { it.copy(uploading = true, uploadMessage = null, error = null) }
        return true
    }
    fun finishUpload(load: Int, success: Boolean, error: String?) {
        mutableState.update { it.copy(uploading = false, uploadedLoad = if (success) load else null,
            uploadMessage = if (success) "Load ${load + 1} uploaded. Flight commands only; feed is simulated. The uploaded mission remains on the vehicle until another upload replaces it." else null,
            error = if (success) null else error ?: "Upload failed") }
    }

    private fun edit(change: AquacultureUiState.() -> AquacultureUiState) {
        if (state.value.uploading) return
        mutableState.update { it.change().copy(summary = null, plan = null, error = null, uploadMessage = null, uploadedLoad = null) }
    }
    private fun validated(action: () -> Unit) {
        try { action() } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = error.message, plan = null) }
        } catch (error: IllegalStateException) {
            mutableState.update { it.copy(error = error.message, plan = null) }
        }
    }
    private fun number(field: AquacultureField): Double {
        val number = state.value.fields.getValue(field).trim().toDoubleOrNull()
        require(number != null && number.isFinite()) { "Enter a valid number for ${field.label}" }
        return number
    }
    private fun integer(field: AquacultureField): Int {
        return state.value.fields.getValue(field).trim().toIntOrNull()
            ?: throw IllegalArgumentException("Enter a whole number for ${field.label}")
    }
    private fun input() = AquacultureInput(
        integer(AquacultureField.CULTURE_DAY), number(AquacultureField.COUNT), number(AquacultureField.BIOMASS),
        integer(AquacultureField.SESSIONS), number(AquacultureField.HOPPER), number(AquacultureField.DISCHARGE),
        if (state.value.fields.getValue(AquacultureField.FEED_RATE).isBlank()) null else number(AquacultureField.FEED_RATE)
    )
}
