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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeartbeatForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContainer by lazy { (applicationContext as SmsForwarderApp).appContainer }
    @Volatile
    private var executionJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var loopJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_START_REASON).orEmpty().ifBlank { "scheduler" }
        val isExecutionActive = executionJob?.isActive == true
        val isLoopActive = loopJob?.isActive == true
        serviceScope.launch {
            val now = System.currentTimeMillis()
            appContainer.configRepository.setHeartbeatServiceSeenAt(now)
            appContainer.eventRepository.addLog(
                "Heartbeat service start requested via $reason (startId=$startId, executionActive=$isExecutionActive, loopActive=$isLoopActive, now=${TimeFormatter.toDebugLocal(now)})",
            )
        }
        if (!isExecutionActive) {
            startExecution(reason)
        } else {
            serviceScope.launch {
                appContainer.eventRepository.addLog(
                    "Heartbeat supervision already active; start request via $reason did not restart it",
                )
            }
        }
        if (!isLoopActive) {
            startLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        executionJob = null
        loopJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startExecution(reason: String) {
        var startedJob: kotlinx.coroutines.Job? = null
        val job = serviceScope.launch {
            appContainer.eventRepository.addLog("Heartbeat supervision starting via $reason")
            try {
                val now = System.currentTimeMillis()
                appContainer.configRepository.setHeartbeatServiceSeenAt(now)
                HeartbeatSupervisor.run(
                    appContainer = appContainer,
                    reason = "service:$reason",
                    ensureService = false,
                    allowImmediateHeartbeat = true,
                    now = now,
                )
                val nextDueAt = HeartbeatRunner.nextDueAt(appContainer, System.currentTimeMillis())
                appContainer.eventRepository.addLog(
                    "Heartbeat supervision finished via $reason; nextDueAt=${TimeFormatter.toDebugLocal(nextDueAt)}",
                )
            } catch (error: CancellationException) {
                appContainer.eventRepository.addLog("Heartbeat supervision cancelled")
                throw error
            } catch (error: Exception) {
                appContainer.eventRepository.addLog(
                    "Heartbeat supervision failed: ${error.message ?: error::class.java.simpleName}",
                )
                throw error
            } finally {
                if (executionJob === startedJob) {
                    executionJob = null
                }
                appContainer.eventRepository.addLog("Heartbeat supervision execution no longer active")
            }
        }
        startedJob = job
        executionJob = job
    }

    private fun startLoop() {
        var startedJob: kotlinx.coroutines.Job? = null
        val job = serviceScope.launch {
            try {
                while (true) {
                    delay(HeartbeatSupervisor.SERVICE_CHECK_INTERVAL_MILLIS)
                    val now = System.currentTimeMillis()
                    appContainer.configRepository.setHeartbeatServiceSeenAt(now)
                    HeartbeatSupervisor.run(
                        appContainer = appContainer,
                        reason = "service:loop",
                        ensureService = false,
                        allowImmediateHeartbeat = true,
                        now = now,
                    )
                }
            } finally {
                if (loopJob === startedJob) {
                    loopJob = null
                }
            }
        }
        startedJob = job
        loopJob = job
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
