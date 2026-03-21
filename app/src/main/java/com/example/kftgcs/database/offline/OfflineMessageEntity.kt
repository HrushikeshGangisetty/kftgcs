package com.example.kftgcs.database.offline

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores outgoing WebSocket messages that couldn't be sent due to no internet.
 * Flushed to the backend when connectivity is restored and the session is re-established.
 *
 * Queued message types: mission_status, mission_event, mission_summary
 * Telemetry is NOT queued (high-frequency; already persisted via TlogRepository).
 */
@Entity(tableName = "offline_messages")
data class OfflineMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageType: String,   // "mission_status" | "mission_event" | "mission_summary"
    val payload: String,       // Full JSON string as originally constructed
    val createdAt: Long = System.currentTimeMillis()
)
