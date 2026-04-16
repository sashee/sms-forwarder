package com.example.smsforwarder.telecom

import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.work.QueuedEventProcessor

object CallEventHandler {
    enum class Source(val logLabel: String) {
        SCREENING("screening"),
        TELEPHONY("telephony"),
    }

    suspend fun handleIncomingCall(appContainer: AppContainer, number: String, timestamp: Long, source: Source) {
        try {
            when (source) {
                Source.SCREENING -> appContainer.configRepository.setCallScreeningSeenAt(timestamp)
                Source.TELEPHONY -> appContainer.configRepository.setTelephonyCallSeenAt(timestamp)
            }
            val id = appContainer.eventRepository.enqueueCall(number, timestamp)
            appContainer.eventRepository.addLog("Queued call event $id via ${source.logLabel}")
            QueuedEventProcessor.deliverNow(appContainer, id)
        } catch (error: Exception) {
            appContainer.configRepository.setFaultState(
                reason = "Call enqueue failed: ${error.message ?: error::class.java.simpleName}",
                timestamp = timestamp,
            )
        } finally {
            appContainer.scheduler.ensureHeartbeatScheduled("call:${source.logLabel}", startServiceIfOverdue = true)
        }
    }
}
