package com.example.aerogcsclone.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aerogcsclone.database.MissionTemplateDatabase
import com.example.aerogcsclone.database.tlog.*
import com.example.aerogcsclone.export.ExportFormat
import com.example.aerogcsclone.export.FlightLogExporter
import com.example.aerogcsclone.repository.TlogRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing flight logs and telemetry data
 */
class TlogViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MissionTemplateDatabase.getDatabase(application)
    private val repository = TlogRepository(database)
    private val exporter = FlightLogExporter(application)

    // UI State
    private val _uiState = MutableStateFlow(TlogUiState())
    val uiState: StateFlow<TlogUiState> = _uiState.asStateFlow()

    // Flight data
    val flights = repository.getAllFlights()
    private var currentFlightId: Long? = null

    init {
        loadFlightStats()
        // Verify database is accessible
        viewModelScope.launch {
            try {
                val flightCount = repository.getTotalFlightsCount()

                // Check for any active flights on startup
                val activeFlight = repository.getActiveFlightOrNull()
                if (activeFlight != null) {
                    // Mark it as completed to prevent issues
                    repository.completeFlight(activeFlight.id, null, null)
                }
            } catch (e: Exception) {
                // Database error during initialization
            }
        }
    }

    fun startFlight() {
        viewModelScope.launch {
            try {
                // Check if there's already an active flight
                val activeFlight = repository.getActiveFlightOrNull()
                if (activeFlight != null) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "There's already an active flight in progress"
                    )
                    return@launch
                }

                currentFlightId = repository.startFlight()

                _uiState.value = _uiState.value.copy(
                    isFlightActive = true,
                    currentFlightId = currentFlightId,
                    errorMessage = null
                )

                // Log flight start event
                currentFlightId?.let { flightId ->
                    repository.logEvent(
                        flightId = flightId,
                        eventType = EventType.ARM_DISARM,
                        severity = EventSeverity.INFO,
                        message = "Flight started - Armed"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to start flight: ${e.message}"
                )
            }
        }
    }

    fun endFlight(area: Float? = null, consumedLiquid: Float? = null) {
        viewModelScope.launch {
            try {
                val flightId = currentFlightId
                if (flightId == null) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "No active flight to end"
                    )
                    return@launch
                }

                repository.completeFlight(flightId, area, consumedLiquid)

                // Log flight end event
                repository.logEvent(
                    flightId = flightId,
                    eventType = EventType.ARM_DISARM,
                    severity = EventSeverity.INFO,
                    message = "Flight completed - Disarmed"
                )

                currentFlightId = null
                _uiState.value = _uiState.value.copy(
                    isFlightActive = false,
                    currentFlightId = null,
                    errorMessage = null
                )
                loadFlightStats()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to end flight: ${e.message}"
                )
            }
        }
    }

    fun logTelemetryData(
        voltage: Float?,
        current: Float?,
        batteryPercent: Int?,
        satCount: Int?,
        hdop: Float?,
        altitude: Float?,
        speed: Float?,
        latitude: Double?,
        longitude: Double?,
        heading: Float? = null,
        droneUid: String? = null,

    ) {
        viewModelScope.launch {
            val flightId = currentFlightId
            if (flightId == null) {
                return@launch
            }

            repository.logTelemetry(
                flightId = flightId,
                voltage = voltage,
                current = current,
                batteryPercent = batteryPercent,
                satCount = satCount,
                hdop = hdop,
                altitude = altitude,
                speed = speed,
                latitude = latitude,
                longitude = longitude,
                heading = heading,
                droneUid = droneUid,

            )
        }
    }

    fun logMapPosition(
        latitude: Double,
        longitude: Double,
        altitude: Float,
        heading: Float? = null,
        speed: Float? = null
    ) {
        viewModelScope.launch {
            val flightId = currentFlightId
            if (flightId == null) {
                return@launch
            }

            repository.logMapData(
                flightId = flightId,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                heading = heading,
                speed = speed
            )
        }
    }

    fun logEvent(eventType: EventType, severity: EventSeverity, message: String) {
        viewModelScope.launch {
            val flightId = currentFlightId
            if (flightId == null) {
                return@launch
            }

            repository.logEvent(
                flightId = flightId,
                eventType = eventType,
                severity = severity,
                message = message
            )
        }
    }

    fun deleteFlight(flightId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteFlight(flightId)
                loadFlightStats()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to delete flight: ${e.message}"
                )
            }
        }
    }

    fun getTelemetryForFlight(flightId: Long): Flow<List<TelemetryEntity>> {
        return repository.getTelemetryForFlight(flightId)
    }

    fun getEventsForFlight(flightId: Long): Flow<List<EventEntity>> {
        return repository.getEventsForFlight(flightId)
    }

    fun getMapDataForFlight(flightId: Long): Flow<List<MapDataEntity>> {
        return repository.getMapDataForFlight(flightId)
    }

    private fun loadFlightStats() {
        viewModelScope.launch {
            try {
                val totalFlights = repository.getTotalFlightsCount()
                val totalFlightTime = repository.getTotalFlightTime()

                _uiState.value = _uiState.value.copy(
                    totalFlights = totalFlights,
                    totalFlightTime = totalFlightTime
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to load flight stats: ${e.message}"
                )
            }
        }
    }

    fun exportFlight(flight: FlightEntity, format: ExportFormat) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isExporting = true)

                val telemetryData = repository.getTelemetryForFlight(flight.id).first()
                val events = repository.getEventsForFlight(flight.id).first()
                val mapData = repository.getMapDataForFlight(flight.id).first()

                val file = when (format) {
                    ExportFormat.JSON -> exporter.exportFlightAsJson(flight, telemetryData, events, mapData)
                    ExportFormat.CSV -> exporter.exportFlightAsCsv(flight, telemetryData)
                    ExportFormat.TLOG -> exporter.exportFlightAsTlog(flight, telemetryData, events, mapData)
                }

                exporter.shareFile(file, "Flight ${flight.id} - ${format.name}")

                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportMessage = "Flight exported successfully!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    errorMessage = "Failed to export flight: ${e.message}"
                )
            }
        }
    }

    fun exportAllFlights() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isExporting = true)

                val allFlights = flights.first()
                val file = exporter.exportAllFlightsAsCsv(allFlights)

                exporter.shareFile(file, "All Flights Summary")

                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportMessage = "All flights exported successfully!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    errorMessage = "Failed to export flights: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearExportMessage() {
        _uiState.value = _uiState.value.copy(exportMessage = null)
    }
}

data class TlogUiState(
    val isFlightActive: Boolean = false,
    val currentFlightId: Long? = null,
    val totalFlights: Int = 0,
    val totalFlightTime: Long = 0L,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    val exportMessage: String? = null
)
