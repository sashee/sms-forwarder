package com.example.smsforwarder.heartbeat

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.util.BootState
import com.example.smsforwarder.util.TimeFormatter
import com.example.smsforwarder.work.EventScheduler

object HeartbeatSupervisor {
    const val SERVICE_CHECK_INTERVAL_MILLIS: Long = 5L * 60L * 1000L
    private const val SERVICE_STALE_AFTER_MILLIS: Long = 2L * SERVICE_CHECK_INTERVAL_MILLIS

    suspend fun run(
        appContainer: AppContainer,
        scheduler: EventScheduler = appContainer.scheduler,
        reason: String,
        ensureService: Boolean,
        allowImmediateHeartbeat: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val config = appContainer.eventRepository.heartbeatConfig()
        if (config.url.isBlank()) {
            scheduler.cancelHeartbeatAlarm()
            scheduler.cancelHeartbeatWatchdogWork()
            appContainer.configRepository.clearHeartbeatServiceSeenState()
            appContainer.configRepository.clearHeartbeatAlarmScheduledState()
            if (ensureService) {
                scheduler.stopHeartbeatService()
            }
            appContainer.eventRepository.addLog("Heartbeat supervisor via $reason disabled heartbeat infrastructure because URL is empty")
            return false
        }

        scheduler.ensureHeartbeatWatchdogWork()
        if (ensureService) {
            ensureForegroundService(appContainer, scheduler, reason, now)
        }

        val nextDueAtBeforeRun = scheduler.ensureHeartbeatAlarmScheduled("supervisor:$reason", now)
        val ranHeartbeat = if (allowImmediateHeartbeat && now >= nextDueAtBeforeRun) {
            appContainer.eventRepository.addLog(
                "Heartbeat supervisor via $reason is running due heartbeat for nextDueAt=${TimeFormatter.toDebugLocal(nextDueAtBeforeRun)}",
            )
            HeartbeatRunner.runHeartbeatSlot(appContainer, now)
        } else {
            appContainer.eventRepository.addLog(
                "Heartbeat supervisor via $reason did not run heartbeat immediately (allowImmediate=$allowImmediateHeartbeat, nextDueAt=${TimeFormatter.toDebugLocal(nextDueAtBeforeRun)}, now=${TimeFormatter.toDebugLocal(now)})",
            )
            false
        }

        scheduler.ensureHeartbeatAlarmScheduled("supervisor:$reason:post", System.currentTimeMillis())
        return ranHeartbeat
    }

    private suspend fun ensureForegroundService(appContainer: AppContainer, scheduler: EventScheduler, reason: String, now: Long) {
        val seenAt = appContainer.configRepository.getHeartbeatServiceSeenAt()
        val seenBootCount = appContainer.configRepository.getHeartbeatServiceSeenBootCount()
        val currentBootCount = appContainer.configRepository.currentBootCount()
        val serviceIsCurrentBoot = currentBootCount != null && seenBootCount == currentBootCount
        val serviceIsStale = seenAt == null || !serviceIsCurrentBoot || now - seenAt > SERVICE_STALE_AFTER_MILLIS
        if (serviceIsStale) {
            appContainer.eventRepository.addLog(
                "Heartbeat supervisor via $reason requested foreground service start with lastSeenAt=${TimeFormatter.toDebugLocal(seenAt)} lastSeenBootCount=${seenBootCount ?: "none"} currentBootCount=${currentBootCount ?: "none"}",
            )
            scheduler.startHeartbeatService("supervisor:$reason")
        } else {
            appContainer.eventRepository.addLog(
                "Heartbeat supervisor via $reason confirmed foreground service health with lastSeenAt=${TimeFormatter.toDebugLocal(seenAt)} lastSeenBootCount=${seenBootCount ?: "none"} currentBootCount=${currentBootCount ?: "none"}",
            )
        }
    }
}
