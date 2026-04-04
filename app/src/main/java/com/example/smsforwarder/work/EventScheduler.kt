package com.example.smsforwarder.work

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.smsforwarder.data.QueueDao
import com.example.smsforwarder.heartbeat.HeartbeatAlarmReceiver
import com.example.smsforwarder.heartbeat.HeartbeatForegroundService
import com.example.smsforwarder.heartbeat.HeartbeatRunner
import com.example.smsforwarder.util.TimeFormatter
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

open class EventScheduler(
    protected val context: Context,
    protected val workManager: WorkManager,
    protected val queueDao: QueueDao,
) {
    private data class HeartbeatAlarmState(
        val nextDueAt: Long,
        val scheduledAt: Long?,
        val isMissing: Boolean,
        val isStale: Boolean,
        val isTooFarAhead: Boolean,
        val shouldStartImmediately: Boolean,
    )

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    open fun ensureRecurringWork() {
        cancelLegacyHeartbeatWork()
        ensureHeartbeatScheduled("scheduler", startServiceIfOverdue = true)
    }

    open fun cancelLegacyHeartbeatWork() {
        workManager.cancelAllWorkByTag(HeartbeatWorker::class.java.name)
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

    open fun startHeartbeatService(reason: String) {
        ContextCompat.startForegroundService(
            context,
            HeartbeatForegroundService.createStartIntent(context, reason),
        )
    }

    open fun ensureHeartbeatScheduled(reason: String, startServiceIfOverdue: Boolean) {
        runBlocking {
            val appContainer = (context.applicationContext as com.example.smsforwarder.SmsForwarderApp).appContainer
            val now = System.currentTimeMillis()
            val state = heartbeatAlarmState(appContainer, now, startServiceIfOverdue)

            if ((state.isMissing || state.isStale || state.isTooFarAhead) && !state.shouldStartImmediately) {
                scheduleHeartbeatRecoveryAlarm(state.nextDueAt)
                val status = when {
                    state.isMissing -> "missing"
                    state.isStale -> "stale"
                    else -> "misaligned"
                }
                appContainer.eventRepository.addLog(
                    "Heartbeat alarm repair requested via $reason ($status) with scheduledAt=${TimeFormatter.toDebugLocal(state.scheduledAt)} nextDueAt=${TimeFormatter.toDebugLocal(state.nextDueAt)}",
                )
            } else if (state.isMissing || state.isStale || state.isTooFarAhead) {
                appContainer.eventRepository.addLog(
                    "Heartbeat alarm repair via $reason deferred to immediate execution with scheduledAt=${TimeFormatter.toDebugLocal(state.scheduledAt)} nextDueAt=${TimeFormatter.toDebugLocal(state.nextDueAt)}",
                )
            } else {
                appContainer.eventRepository.addLog(
                    "Heartbeat alarm check via $reason found scheduledAt=${TimeFormatter.toDebugLocal(state.scheduledAt)} nextDueAt=${TimeFormatter.toDebugLocal(state.nextDueAt)}",
                )
            }

            if (state.shouldStartImmediately) {
                appContainer.eventRepository.addLog(
                    "Heartbeat immediate execution requested via $reason for nextDueAt=${TimeFormatter.toDebugLocal(state.nextDueAt)}",
                )
                startHeartbeatService(reason)
            }
        }
    }

    open fun scheduleHeartbeatRecoveryAlarm(triggerAtMillis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                heartbeatAlarmIntent(),
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                heartbeatAlarmIntent(),
            )
        }
        runBlocking {
            val appContainer = (context.applicationContext as com.example.smsforwarder.SmsForwarderApp).appContainer
            appContainer.configRepository.setHeartbeatAlarmScheduledAt(triggerAtMillis)
            appContainer.eventRepository.addLog(
                "Heartbeat recovery alarm scheduled for ${TimeFormatter.toDebugLocal(triggerAtMillis)}",
            )
        }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun heartbeatAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        android.content.Intent(context, HeartbeatAlarmReceiver::class.java).setAction(HeartbeatAlarmReceiver.ACTION_HEARTBEAT_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun deliveryWorkName(eventId: Long): String = "deliver-event-$eventId"

    private suspend fun heartbeatAlarmState(
        appContainer: com.example.smsforwarder.AppContainer,
        now: Long,
        startServiceIfOverdue: Boolean,
    ): HeartbeatAlarmState {
        val nextDueAt = HeartbeatRunner.nextDueAt(appContainer, now)
        val scheduledAt = appContainer.configRepository.getHeartbeatAlarmScheduledAt()
        val isMissing = scheduledAt == null
        val isStale = scheduledAt?.let { it < nextDueAt } == true
        val isTooFarAhead = scheduledAt?.let { it > nextDueAt + HeartbeatRunner.INTERVAL_MILLIS } == true
        return HeartbeatAlarmState(
            nextDueAt = nextDueAt,
            scheduledAt = scheduledAt,
            isMissing = isMissing,
            isStale = isStale,
            isTooFarAhead = isTooFarAhead,
            shouldStartImmediately = startServiceIfOverdue && now >= nextDueAt,
        )
    }
}
