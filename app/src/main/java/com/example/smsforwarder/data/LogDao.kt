package com.example.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntryEntity)

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<LogEntryEntity>>

    @Query("DELETE FROM logs")
    suspend fun deleteAll()
}
