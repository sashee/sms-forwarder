package com.example.smsforwarder.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.model.EventType
import com.example.smsforwarder.util.PlaceholderRenderer

class EventRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val configRepository: ConfigRepository,
) {
    fun observeLogs(limit: Int = 100): Flow<List<LogEntryEntity>> = database.logDao().observeLatest(limit)

    suspend fun enqueueSms(number: String, text: String, timestamp: Long): Long {
        val config = configRepository.getConfig().sms
        return enqueueConfiguredEvent(EventType.SMS, config, number, text, timestamp)
    }

    suspend fun enqueueCall(number: String, timestamp: Long): Long {
        val config = configRepository.getConfig().call
        return enqueueConfiguredEvent(EventType.CALL, config, number, "", timestamp)
    }

    suspend fun heartbeatConfig(): EventConfig = configRepository.getConfig().heartbeat

    suspend fun addLog(text: String, timestamp: Long = System.currentTimeMillis()) {
        database.logDao().insert(LogEntryEntity(timestamp = timestamp, text = text))
    }

    suspend fun clearLogs() {
        database.logDao().deleteAll()
    }

    suspend fun getQueuedEvent(id: Long): QueuedEventEntity? = database.queueDao().getById(id)

    suspend fun markDelivered(id: Long) {
        database.queueDao().deleteById(id)
    }

    suspend fun scheduleRetry(id: Long, attemptCount: Int, nextAttemptAt: Long) {
        database.queueDao().updateAttempt(id, attemptCount, nextAttemptAt)
    }

    suspend fun allQueuedEvents(): List<QueuedEventEntity> = database.queueDao().getAll()

    suspend fun resetDatabase(reason: String, resetTimestamp: Long) {
        database.queueDao().deleteAll()
        database.logDao().deleteAll()
        addLog(
            text = "Database reset at ${resetTimestamp}: $reason",
            timestamp = resetTimestamp,
        )
    }

    private suspend fun enqueueConfiguredEvent(
        type: EventType,
        config: EventConfig,
        number: String,
        text: String,
        timestamp: Long,
    ): Long {
        val renderedBody = PlaceholderRenderer.render(type, config.body, number, text, timestamp)
        val event = QueuedEventEntity(
            eventId = UUID.randomUUID().toString(),
            type = type,
            number = number,
            text = text,
            eventTimestamp = timestamp,
            url = config.url,
            method = config.method,
            contentType = config.contentType.ifBlank { "text/plain" },
            body = renderedBody,
            attemptCount = 0,
            nextAttemptAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
        )
        return database.queueDao().insert(event)
    }
}
