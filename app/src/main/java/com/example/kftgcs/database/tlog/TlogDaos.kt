package com.example.kftgcs.database.tlog

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Telemetry operations
 */
@Dao
interface TelemetryDao {

    @Query("SELECT * FROM telemetry_logs WHERE flightId = :flightId ORDER BY timestamp ASC")
    fun getTelemetryForFlight(flightId: Long): Flow<List<TelemetryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(telemetry: TelemetryEntity)
}

/**
 * Data Access Object for Event operations
 */
@Dao
interface EventDao {

    @Query("SELECT * FROM flight_events WHERE flightId = :flightId ORDER BY timestamp ASC")
    fun getEventsForFlight(flightId: Long): Flow<List<EventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)
}

/**
 * Data Access Object for Map Data operations
 */
@Dao
interface MapDataDao {

    @Query("SELECT * FROM map_data WHERE flightId = :flightId ORDER BY timestamp ASC")
    fun getMapDataForFlight(flightId: Long): Flow<List<MapDataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapData(mapData: MapDataEntity)
}
