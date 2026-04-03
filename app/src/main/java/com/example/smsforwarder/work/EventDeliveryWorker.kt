package com.example.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.net.HttpRequest
import kotlin.math.pow

class EventDeliveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val appContainer: AppContainer = (appContext.applicationContext as SmsForwarderApp).appContainer,
) : CoroutineWorker(appContext, workerParameters) {

    constructor(appContext: Context, workerParameters: WorkerParameters) : this(
        appContext,
        workerParameters,
        (appContext.applicationContext as SmsForwarderApp).appContainer,
    )

    override suspend fun doWork(): Result {
        val eventId = inputData.getLong(KEY_EVENT_ID, -1L)
        if (eventId <= 0L) {
            return Result.failure()
        }

        val event = appContainer.eventRepository.getQueuedEvent(eventId) ?: return Result.success()
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
                appContainer.eventRepository.markDelivered(eventId)
                appContainer.eventRepository.addLog("Delivered ${event.type} event ${event.eventId} with $responseCode")
            } else {
                scheduleRetry(eventId, event.attemptCount + 1, "HTTP $responseCode")
            }
            Result.success()
        } catch (error: Exception) {
            scheduleRetry(eventId, event.attemptCount + 1, error.message ?: error::class.java.simpleName)
            Result.success()
        }
    }

    private suspend fun scheduleRetry(eventId: Long, attemptCount: Int, reason: String) {
        val delayMillis = retryDelayMillisForAttempt(attemptCount)
        val nextAttemptAt = System.currentTimeMillis() + delayMillis
        appContainer.eventRepository.scheduleRetry(eventId, attemptCount, nextAttemptAt)
        appContainer.eventRepository.addLog("Retry scheduled for event $eventId in ${delayMillis / 1000}s: $reason")
        appContainer.scheduler.enqueueDelivery(eventId, delayMillis)
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        private const val MAX_DELAY_MILLIS = 24L * 60L * 60L * 1000L

        internal fun retryDelayMillisForAttempt(attemptCount: Int): Long {
            val exponentialMinutes = 2.0.pow((attemptCount - 1).coerceAtLeast(0)).toLong() * 15L
            return (exponentialMinutes * 60_000L).coerceAtMost(MAX_DELAY_MILLIS)
        }
    }
}
