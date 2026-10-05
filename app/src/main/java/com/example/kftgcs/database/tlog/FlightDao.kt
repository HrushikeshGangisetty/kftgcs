package com.example.kftgcs.database.tlog

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Flight operations
 */
@Dao
interface FlightDao {

    @Query("SELECT * FROM flights ORDER BY startTime DESC")
    fun getAllFlights(): Flow<List<FlightEntity>>

    @Query("SELECT * FROM flights WHERE id = :flightId")
    suspend fun getFlightById(flightId: Long): FlightEntity?

    @Query("SELECT * FROM flights WHERE isCompleted = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveFlightOrNull(): FlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFlight(flight: FlightEntity): Long

    @Query("UPDATE flights SET endTime = :endTime, flightDuration = :duration, area = :area, consumedLiquid = :consumedLiquid, isCompleted = 1 WHERE id = :flightId")
    suspend fun completeFlight(flightId: Long, endTime: Long, duration: Long, area: Float?, consumedLiquid: Float?)

    @Delete
    suspend fun deleteFlight(flight: FlightEntity)

    @Query("DELETE FROM flights WHERE id = :flightId")
    suspend fun deleteFlightById(flightId: Long)

    @Query("SELECT COUNT(*) FROM flights")
    suspend fun getTotalFlightsCount(): Int

    @Query("SELECT SUM(flightDuration) FROM flights WHERE isCompleted = 1")
    suspend fun getTotalFlightTime(): Long?
}
