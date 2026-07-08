package com.example.smsforwarder.heartbeat

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.net.HttpRequest
import com.example.smsforwarder.util.TimeFormatter
import com.example.smsforwarder.work.QueuedEventProcessor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset

object HeartbeatRunner {
    const val INTERVAL_MILLIS: Long = 30L * 60L * 1000L
    private const val RESET_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    // On a failed send, retry a few times within the same wake window (holding a wakelock so the
    // delays actually elapse) instead of losing the whole 30-minute slot to a brief blackout.
    // Production defaults; `var` only so tests can drop the delay to 0 and avoid real waiting.
    var maxFastRetries: Int = 3
    var fastRetryDelayMillis: Long = 10L * 1000L
    // Safety backstop for the wakelock: comfortably above the worst-case burst duration.
    const val RETRY_WAKELOCK_TIMEOUT_MILLIS: Long = 90L * 1000L

    suspend fun nextDueAt(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Long {
        val lastAttemptAt = appContainer.configRepository.getHeartbeatLastAttemptAt() ?: return now
        return (lastAttemptAt + INTERVAL_MILLIS).coerceAtLeast(now)
    }

    suspend fun runHeartbeatSlot(
        appContainer: AppContainer,
        now: Long = System.currentTimeMillis(),
        maxRetries: Int = maxFastRetries,
        retryDelayMillis: Long = fastRetryDelayMillis,
    ): Boolean {
        val lastAttemptAt = appContainer.configRepository.getHeartbeatLastAttemptAt()
        val nextDueAt = lastAttemptAt?.plus(INTERVAL_MILLIS)?.coerceAtLeast(now) ?: now
        if (!appContainer.configRepository.claimHeartbeatSlot(now, INTERVAL_MILLIS)) {
            appContainer.eventRepository.addLog(
                "Heartbeat skipped because next slot is not due yet (now=${TimeFormatter.toDebugLocal(now)}, lastAttemptAt=${TimeFormatter.toDebugLocal(lastAttemptAt)}, nextDueAt=${TimeFormatter.toDebugLocal(nextDueAt)})",
            )
            return false
        }
        trimLogsIfNeeded(appContainer, now)
        val recoveredEventCount = QueuedEventProcessor.drainOlderThan(appContainer, INTERVAL_MILLIS, now)
        if (recoveredEventCount > 0) {
            appContainer.eventRepository.addLog(
                "Heartbeat recovered $recoveredEventCount queued event(s) older than ${INTERVAL_MILLIS / 60_000L} minutes",
            )
        }

        val faultState = appContainer.configRepository.getFaultState()
        if (faultState != null) {
            if (now - faultState.timestamp >= RESET_AFTER_MILLIS) {
                appContainer.eventRepository.resetDatabase(faultState.reason, now)
                appContainer.configRepository.clearFaultState()
            } else {
                appContainer.eventRepository.addLog(
                    "Heartbeat skipped while fault state is active (reason=${faultState.reason}, faultTimestamp=${TimeFormatter.toDebugLocal(faultState.timestamp)})",
                )
            }
            return false
        }

        val config = appContainer.eventRepository.heartbeatConfig()
        if (config.url.isBlank()) {
            appContainer.eventRepository.addLog("Heartbeat skipped because URL is empty (now=${TimeFormatter.toDebugLocal(now)})")
            return false
        }

        // Hold a wakelock across the whole send + retry burst so the CPU stays awake and the retry
        // delays actually elapse in this one wake window (a deferred alarm can't fire fast in Doze).
        return appContainer.wakeGuard.withWakeLock("heartbeat", RETRY_WAKELOCK_TIMEOUT_MILLIS) {
            sendWithRetries(appContainer, config, now, maxRetries, retryDelayMillis)
        }
    }

    /**
     * Attempts the heartbeat send, retrying up to [maxRetries] times [retryDelayMillis] apart within the
     * caller's wake window. Logs each step so the burst is queryable: the "Heartbeat failed" line records
     * why (cause chain), the "will fast-retry" line records how many, and success records which attempt won.
     */
    private suspend fun sendWithRetries(
        appContainer: AppContainer,
        config: com.example.smsforwarder.model.EventConfig,
        now: Long,
        maxRetries: Int,
        retryDelayMillis: Long,
    ): Boolean {
        var attempt = 0
        while (true) {
            appContainer.eventRepository.addLog(
                "Heartbeat sending (now=${TimeFormatter.toDebugLocal(now)}, method=${config.method}, url=${config.url})",
            )
            val outcome = runCatching {
                appContainer.httpClient.send(
                    HttpRequest(
                        url = config.url,
                        method = config.method,
                        contentType = config.contentType,
                        body = config.body,
                    ),
                )
            }
            outcome.getOrNull()?.let { responseCode ->
                appContainer.configRepository.setHeartbeatLastSuccessAt(now)
                val onRetry = if (attempt > 0) " on fast-retry attempt $attempt/$maxRetries" else ""
                appContainer.eventRepository.addLog(
                    "Heartbeat completed with HTTP $responseCode$onRetry (now=${TimeFormatter.toDebugLocal(now)})",
                )
                return true
            }

            val error = outcome.exceptionOrNull()!!
            val onRetry = if (attempt > 0) " (fast-retry attempt $attempt/$maxRetries)" else ""
            appContainer.eventRepository.addLog(
                "Heartbeat failed (now=${TimeFormatter.toDebugLocal(now)})$onRetry: ${error.message ?: error::class.java.simpleName}",
            )
            if (attempt >= maxRetries) {
                appContainer.eventRepository.addLog(
                    "Heartbeat exhausted $maxRetries fast retries; waiting for next ${INTERVAL_MILLIS / 60_000L}m slot",
                )
                return false
            }
            attempt++
            appContainer.eventRepository.addLog(
                "Heartbeat will fast-retry in ${retryDelayMillis / 1000L}s (attempt $attempt/$maxRetries)",
            )
            delay(retryDelayMillis)
        }
    }

    private suspend fun trimLogsIfNeeded(appContainer: AppContainer, now: Long) {
        val lastTrimAt = appContainer.configRepository.getLogLastTrimAt()
        if (lastTrimAt != null && utcDayStartMillis(lastTrimAt) == utcDayStartMillis(now)) {
            return
        }

        appContainer.eventRepository.deleteLogsOlderThan(sixMonthsAgo(now))
        appContainer.configRepository.setLogLastTrimAt(now)
    }

    private fun utcDayStartMillis(timestamp: Long): Long {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    private fun sixMonthsAgo(now: Long): Long {
        return Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .minusMonths(6)
            .toInstant()
            .toEpochMilli()
    }
}
