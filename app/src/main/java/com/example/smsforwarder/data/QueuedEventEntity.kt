package com.example.smsforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smsforwarder.model.EventType

@Entity(tableName = "queued_events")
data class QueuedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val type: EventType,
    val number: String,
    val text: String,
    val eventTimestamp: Long,
    val url: String,
    val method: String,
    val contentType: String,
    val body: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)
