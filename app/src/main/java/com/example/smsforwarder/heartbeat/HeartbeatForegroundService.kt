package com.example.smsforwarder.heartbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.smsforwarder.R
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.util.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartbeatForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContainer by lazy { (applicationContext as SmsForwarderApp).appContainer }
    private var loopStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_START_REASON).orEmpty().ifBlank { "scheduler" }
        val wasLoopStarted = loopStarted
        serviceScope.launch {
            val now = System.currentTimeMillis()
            appContainer.configRepository.setHeartbeatServiceSeenAt(now)
            appContainer.eventRepository.addLog(
                "Heartbeat service start requested via $reason (startId=$startId, loopStarted=$wasLoopStarted, now=${TimeFormatter.toDebugLocal(now)})",
            )
        }
        if (!loopStarted) {
            loopStarted = true
            serviceScope.launch {
                runLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runLoop() {
        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            appContainer.configRepository.setHeartbeatServiceSeenAt(now)
            val nextDueAt = HeartbeatRunner.nextDueAt(appContainer, now)
            appContainer.eventRepository.addLog(
                "Heartbeat loop iteration (now=${TimeFormatter.toDebugLocal(now)}, nextDueAt=${TimeFormatter.toDebugLocal(nextDueAt)}, delayMillis=${(nextDueAt - now).coerceAtLeast(0)})",
            )
            ensureRecoveryAlarm(nextDueAt)
            val delayMillis = (nextDueAt - now).coerceAtLeast(0)
            if (delayMillis > 0) {
                appContainer.eventRepository.addLog("Heartbeat loop delaying for $delayMillis ms")
                delay(delayMillis)
                appContainer.eventRepository.addLog(
                    "Heartbeat loop woke after $delayMillis ms delay at ${TimeFormatter.toDebugLocal(System.currentTimeMillis())}",
                )
            }
            if (!serviceScope.isActive) {
                return
            }
            HeartbeatRunner.runHeartbeatSlot(appContainer)
            if (!serviceScope.isActive) {
                return
            }
            val nextExpectedTriggerAt = HeartbeatRunner.nextDueAt(appContainer, System.currentTimeMillis())
            appContainer.eventRepository.addLog(
                "Heartbeat loop finished slot; nextDueAt=${TimeFormatter.toDebugLocal(nextExpectedTriggerAt)}",
            )
            ensureRecoveryAlarm(nextExpectedTriggerAt)
        }
    }

    private suspend fun ensureRecoveryAlarm(expectedTriggerAtMillis: Long) {
        val scheduledAt = appContainer.configRepository.getHeartbeatAlarmScheduledAt()
        val isMissing = scheduledAt == null
        val isStale = scheduledAt?.let { it < expectedTriggerAtMillis } == true
        val isTooFarAhead = scheduledAt?.let { it > expectedTriggerAtMillis + HeartbeatRunner.INTERVAL_MILLIS } == true
        if (isMissing || isStale || isTooFarAhead) {
            appContainer.scheduler.scheduleHeartbeatRecoveryAlarm(expectedTriggerAtMillis)
            val state = when {
                isMissing -> "missing"
                isStale -> "stale"
                else -> "misaligned"
            }
            appContainer.eventRepository.addLog(
                "Heartbeat recovery alarm repaired ($state) with scheduledAt=${TimeFormatter.toDebugLocal(scheduledAt)} expectedTriggerAt=${TimeFormatter.toDebugLocal(expectedTriggerAtMillis)}",
            )
        } else {
            appContainer.eventRepository.addLog(
                "Heartbeat recovery alarm already aligned with scheduledAt=${TimeFormatter.toDebugLocal(scheduledAt)} expectedTriggerAt=${TimeFormatter.toDebugLocal(expectedTriggerAtMillis)}",
            )
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.heartbeat_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.heartbeat_notification_channel_description)
            },
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.heartbeat_notification_title))
        .setContentText(getString(R.string.heartbeat_notification_text))
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "heartbeat-service"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_START_REASON = "start_reason"

        fun createStartIntent(context: Context, reason: String): Intent = Intent(context, HeartbeatForegroundService::class.java).apply {
            putExtra(EXTRA_START_REASON, reason)
        }
    }
}
