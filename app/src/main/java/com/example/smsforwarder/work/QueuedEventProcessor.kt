package com.example.smsforwarder.work

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.data.QueuedEventEntity
import com.example.smsforwarder.heartbeat.HeartbeatRunner
import com.example.smsforwarder.net.HttpRequest

object QueuedEventProcessor {
    suspend fun deliverNow(appContainer: AppContainer, eventId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val event = appContainer.eventRepository.getQueuedEvent(eventId) ?: return true
        return deliverNow(appContainer, event, now)
    }

    suspend fun drainAll(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Int {
        return appContainer.eventRepository.allQueuedEvents()
            .fold(0) { deliveredCount, event ->
                deliveredCount + if (deliverNow(appContainer, event, now)) 1 else 0
            }
    }

    suspend fun drainOlderThan(appContainer: AppContainer, minimumAgeMillis: Long, now: Long = System.currentTimeMillis()): Int {
        return appContainer.eventRepository.allQueuedEvents()
            .filter { now - it.eventTimestamp >= minimumAgeMillis }
            .fold(0) { deliveredCount, event ->
                deliveredCount + if (deliverNow(appContainer, event, now)) 1 else 0
            }
    }

    private suspend fun deliverNow(appContainer: AppContainer, event: QueuedEventEntity, now: Long): Boolean {
        return try {
            val responseCode = appContainer.httpClient.send(
                HttpRequest(
                    url = event.url,
                    method = event.method,
                    contentType = event.contentType,
                    body = event.body,
                ),
            )
            if (responseCode in 200..299) {
                appContainer.eventRepository.markDelivered(event.id)
                appContainer.eventRepository.addLog("Delivered ${event.type} event ${event.eventId} with $responseCode")
                true
            } else {
                recordFailure(appContainer, event, now, "HTTP $responseCode")
                false
            }
        } catch (error: Exception) {
            recordFailure(appContainer, event, now, error.message ?: error::class.java.simpleName)
            false
        }
    }

    private suspend fun recordFailure(appContainer: AppContainer, event: QueuedEventEntity, now: Long, reason: String) {
        val nextAttemptAt = now + HeartbeatRunner.INTERVAL_MILLIS
        appContainer.eventRepository.scheduleRetry(event.id, event.attemptCount + 1, nextAttemptAt)
        appContainer.eventRepository.addLog(
            "Queued ${event.type} event ${event.eventId} remains pending until heartbeat retry: $reason",
        )
    }
}
