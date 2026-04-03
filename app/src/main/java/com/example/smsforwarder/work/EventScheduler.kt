package com.example.smsforwarder.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.smsforwarder.data.QueueDao
import java.time.Duration
import java.util.concurrent.TimeUnit

open class EventScheduler(
    protected val context: Context,
    protected val workManager: WorkManager,
    protected val queueDao: QueueDao,
) {
    open fun ensureRecurringWork() {
        val heartbeat = PeriodicWorkRequestBuilder<HeartbeatWorker>(30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeat,
        )
    }

    open fun enqueueDelivery(eventId: Long, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<EventDeliveryWorker>()
            .setConstraints(networkConstraints())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EventDeliveryWorker.KEY_EVENT_ID to eventId))
            .build()
        workManager.enqueueUniqueWork(
            deliveryWorkName(eventId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    open suspend fun rescheduleQueuedEvents() {
        val now = System.currentTimeMillis()
        queueDao.getAll().forEach { event ->
            enqueueDelivery(event.id, (event.nextAttemptAt - now).coerceAtLeast(0))
        }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun deliveryWorkName(eventId: Long): String = "deliver-event-$eventId"

    companion object {
        private const val HEARTBEAT_WORK_NAME = "heartbeat-work"
    }
}
