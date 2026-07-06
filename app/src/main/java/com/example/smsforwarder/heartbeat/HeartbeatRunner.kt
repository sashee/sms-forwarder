package com.example.smsforwarder.heartbeat

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.net.HttpRequest
import com.example.smsforwarder.util.TimeFormatter
import com.example.smsforwarder.work.QueuedEventProcessor
import java.time.Instant
import java.time.ZoneOffset

object HeartbeatRunner {
    const val INTERVAL_MILLIS: Long = 30L * 60L * 1000L
    private const val RESET_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    // On a failed send, retry a few times over the next several minutes (60s, 120s, 240s) so a brief
    // connectivity blackout does not cost a whole 30-minute slot. After the budget is spent we fall
    // back to the normal interval.
    const val RETRY_BASE_DELAY_MILLIS: Long = 60L * 1000L
    const val MAX_FAST_RETRIES: Long = 3L

    suspend fun nextDueAt(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Long {
        appContainer.configRepository.getHeartbeatRetryAt()?.let { return it.coerceAtLeast(now) }
        val lastAttemptAt = appContainer.configRepository.getHeartbeatLastAttemptAt() ?: return now
        return (lastAttemptAt + INTERVAL_MILLIS).coerceAtLeast(now)
    }

    suspend fun runHeartbeatSlot(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Boolean {
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

        return try {
            appContainer.eventRepository.addLog(
                "Heartbeat sending (now=${TimeFormatter.toDebugLocal(now)}, method=${config.method}, url=${config.url})",
            )
            val responseCode = appContainer.httpClient.send(
                HttpRequest(
                    url = config.url,
                    method = config.method,
                    contentType = config.contentType,
                    body = config.body,
                ),
            )
            appContainer.configRepository.setHeartbeatLastSuccessAt(now)
            val retriesUsed = appContainer.configRepository.getHeartbeatRetryCount()
            val onRetry = if (retriesUsed > 0) " on fast-retry attempt $retriesUsed/$MAX_FAST_RETRIES" else ""
            appContainer.eventRepository.addLog(
                "Heartbeat completed with HTTP $responseCode$onRetry (now=${TimeFormatter.toDebugLocal(now)})",
            )
            appContainer.configRepository.clearHeartbeatRetryState()
            true
        } catch (error: Exception) {
            appContainer.eventRepository.addLog(
                "Heartbeat failed (now=${TimeFormatter.toDebugLocal(now)}): ${error.message ?: error::class.java.simpleName}",
            )
            scheduleFastRetry(appContainer, now)
            false
        }
    }

    /**
     * After a failed send, arm the next fast retry (until the budget is spent) so the alarm-repair
     * path reschedules the wakeup to the retry time. Logs each decision so the retry burst is
     * queryable: the preceding "Heartbeat failed" line records why, this line records how many.
     */
    private suspend fun scheduleFastRetry(appContainer: AppContainer, now: Long) {
        val failures = appContainer.configRepository.getHeartbeatRetryCount()
        if (failures < MAX_FAST_RETRIES) {
            val attempt = failures + 1
            val delay = RETRY_BASE_DELAY_MILLIS shl failures.toInt()
            appContainer.configRepository.setHeartbeatRetryAt(now + delay)
            appContainer.configRepository.setHeartbeatRetryCount(attempt)
            appContainer.eventRepository.addLog(
                "Heartbeat will fast-retry in ${delay / 1000L}s (attempt $attempt/$MAX_FAST_RETRIES)",
            )
        } else {
            appContainer.configRepository.clearHeartbeatRetryState()
            appContainer.eventRepository.addLog(
                "Heartbeat exhausted $MAX_FAST_RETRIES fast retries; waiting for next ${INTERVAL_MILLIS / 60_000L}m slot",
            )
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
