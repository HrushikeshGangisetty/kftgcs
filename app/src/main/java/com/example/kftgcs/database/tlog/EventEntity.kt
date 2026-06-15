package com.example.kftgcs.database.tlog

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

/**
 * Entity for storing flight events (crashes, MAVLink events, etc.)
 */
@Entity(
    tableName = "flight_events",
    foreignKeys = [
        ForeignKey(
            entity = FlightEntity::class,
            parentColumns = ["id"],
            childColumns = ["flightId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val flightId: Long,
    val timestamp: Long,
    val eventType: EventType,
    val severity: EventSeverity,
    val message: String,
    val additionalData: String? = null, // JSON string for extra data
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Float? = null
)

enum class EventType {
    CRASH,
    EMERGENCY_LANDING,
    LOW_BATTERY,
    GPS_LOSS,
    CONNECTION_LOSS,
    MODE_CHANGE,
    ARM_DISARM,
    WAYPOINT_REACHED,
    MISSION_COMPLETE,
    CUSTOM_MAVLINK,
    SYSTEM_ERROR,
    TANK_EMPTY,
    LOW_VOLTAGE,
    NOTIFICATION
}

enum class EventSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
