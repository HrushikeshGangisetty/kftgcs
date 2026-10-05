package com.example.kftgcs.database.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineMessageDao {

    @Insert
    suspend fun insert(message: OfflineMessageEntity): Long

    /**
     * Returns all PENDING messages that have not yet hit the retry ceiling (5 attempts),
     * ordered by creation time (oldest first — preserve delivery order).
     * Literal 5 used instead of OfflineMessageEntity.MAX_RETRIES because Room @Query
     * values must be compile-time string literals; KAPT cannot expand const references.
     */
    @Query("""
        SELECT * FROM offline_messages
        WHERE status = 'PENDING'
          AND retryCount < 5
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingBelowMaxRetry(): List<OfflineMessageEntity>

    /** Bump the retry counter after a failed send attempt. */
    @Query("UPDATE offline_messages SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    /** Mark a message as permanently failed (retries exhausted). */
    @Query("UPDATE offline_messages SET status = 'FAILED' WHERE id = :id")
    suspend fun markFailed(id: Long)

    @Query("DELETE FROM offline_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM offline_messages WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    /**
     * Reactive stream of pending message count for UI badge display.
     * Emits a new value whenever the offline_messages table changes.
     */
    @Query("SELECT COUNT(*) FROM offline_messages WHERE status = 'PENDING'")
    fun countPendingFlow(): Flow<Int>

    /**
     * Garbage-collect FAILED messages older than [thresholdMs] epoch millis.
     * Called from SyncWorker to prevent unbounded table growth.
     * Messages that exhaust retries are kept for diagnostics for 7 days,
     * then purged by this query.
     */
    @Query("DELETE FROM offline_messages WHERE status = 'FAILED' AND createdAt < :thresholdMs")
    suspend fun deleteFailedOlderThan(thresholdMs: Long)
}
