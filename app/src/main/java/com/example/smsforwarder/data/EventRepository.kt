package com.example.smsforwarder.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.model.EventType
import com.example.smsforwarder.util.PlaceholderRenderer

open class EventRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val configRepository: ConfigRepository,
) {
    open fun observeLogs(limit: Int = 100): Flow<List<LogEntryEntity>> = database.logDao().observeLatest(limit)

    open suspend fun enqueueSms(number: String, text: String, timestamp: Long): Long {
        val config = configRepository.getConfig().sms
        return enqueueConfiguredEvent(EventType.SMS, config, number, text, timestamp)
    }

    open suspend fun enqueueCall(number: String, timestamp: Long): Long {
        val config = configRepository.getConfig().call
        return enqueueConfiguredEvent(EventType.CALL, config, number, "", timestamp)
    }

    open suspend fun heartbeatConfig(): EventConfig = configRepository.getConfig().heartbeat

    open suspend fun addLog(text: String, timestamp: Long = System.currentTimeMillis()) {
        database.logDao().insert(LogEntryEntity(timestamp = timestamp, text = text))
    }

    open suspend fun clearLogs() {
        database.logDao().deleteAll()
    }

    open suspend fun getQueuedEvent(id: Long): QueuedEventEntity? = database.queueDao().getById(id)

    open suspend fun markDelivered(id: Long) {
        database.queueDao().deleteById(id)
    }

    open suspend fun scheduleRetry(id: Long, attemptCount: Int, nextAttemptAt: Long) {
        database.queueDao().updateAttempt(id, attemptCount, nextAttemptAt)
    }

    open suspend fun allQueuedEvents(): List<QueuedEventEntity> = database.queueDao().getAll()

    open suspend fun resetDatabase(reason: String, resetTimestamp: Long) {
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
