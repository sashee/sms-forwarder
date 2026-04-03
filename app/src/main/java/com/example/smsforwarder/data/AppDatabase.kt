package com.example.smsforwarder.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [QueuedEventEntity::class, LogEntryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
    abstract fun logDao(): LogDao

    companion object {
        const val DB_NAME = "sms-forwarder.db"
    }
}
