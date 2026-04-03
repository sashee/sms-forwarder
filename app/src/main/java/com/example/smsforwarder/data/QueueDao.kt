package com.example.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: QueuedEventEntity): Long

    @Query("SELECT * FROM queued_events WHERE id = :id")
    suspend fun getById(id: Long): QueuedEventEntity?

    @Query("SELECT * FROM queued_events ORDER BY nextAttemptAt ASC")
    suspend fun getAll(): List<QueuedEventEntity>

    @Query("DELETE FROM queued_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE queued_events SET attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun updateAttempt(id: Long, attemptCount: Int, nextAttemptAt: Long)

    @Query("DELETE FROM queued_events")
    suspend fun deleteAll()
}
