package com.example.kftgcs.database.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OfflineMessageDao {

    @Insert
    suspend fun insert(message: OfflineMessageEntity): Long

    /** Returns all pending messages in the order they were created. */
    @Query("SELECT * FROM offline_messages ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<OfflineMessageEntity>

    @Query("DELETE FROM offline_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM offline_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM offline_messages")
    suspend fun count(): Int
}
