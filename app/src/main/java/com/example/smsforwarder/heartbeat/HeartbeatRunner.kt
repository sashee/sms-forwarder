package com.example.smsforwarder.heartbeat

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.net.HttpRequest
import java.time.Instant
import java.time.ZoneOffset

object HeartbeatRunner {
    const val INTERVAL_MILLIS: Long = 30L * 60L * 1000L
    private const val RESET_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    suspend fun nextDueAt(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Long {
        val lastAttemptAt = appContainer.configRepository.getHeartbeatLastAttemptAt() ?: return now
        return (lastAttemptAt + INTERVAL_MILLIS).coerceAtLeast(now)
    }

    suspend fun runHeartbeatSlot(appContainer: AppContainer, now: Long = System.currentTimeMillis()): Boolean {
        appContainer.configRepository.setHeartbeatLastAttemptAt(now)
        trimLogsIfNeeded(appContainer, now)

        val faultState = appContainer.configRepository.getFaultState()
        if (faultState != null) {
            if (now - faultState.timestamp >= RESET_AFTER_MILLIS) {
                appContainer.eventRepository.resetDatabase(faultState.reason, now)
                appContainer.configRepository.clearFaultState()
            } else {
                appContainer.eventRepository.addLog("Heartbeat skipped while fault state is active")
            }
            return false
        }

        val config = appContainer.eventRepository.heartbeatConfig()
        if (config.url.isBlank()) {
            appContainer.eventRepository.addLog("Heartbeat skipped because URL is empty")
            return false
        }

        return try {
            val responseCode = appContainer.httpClient.send(
                HttpRequest(
                    url = config.url,
                    method = config.method,
                    contentType = config.contentType,
                    body = config.body,
                ),
            )
            appContainer.configRepository.setHeartbeatLastSuccessAt(now)
            appContainer.eventRepository.addLog("Heartbeat completed with HTTP $responseCode")
            true
        } catch (error: Exception) {
            appContainer.eventRepository.addLog("Heartbeat failed: ${error.message ?: error::class.java.simpleName}")
            false
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
