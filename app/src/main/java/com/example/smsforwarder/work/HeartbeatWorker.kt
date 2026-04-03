package com.example.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.net.HttpRequest

class HeartbeatWorker(
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
        val now = System.currentTimeMillis()
        val faultState = appContainer.configRepository.getFaultState()
        if (faultState != null) {
            if (now - faultState.timestamp >= RESET_AFTER_MILLIS) {
                appContainer.eventRepository.resetDatabase(faultState.reason, now)
                appContainer.configRepository.clearFaultState()
            } else {
                appContainer.eventRepository.addLog("Heartbeat skipped while fault state is active")
            }
            return Result.success()
        }

        val config = appContainer.eventRepository.heartbeatConfig()
        if (config.url.isBlank()) {
            appContainer.eventRepository.addLog("Heartbeat skipped because URL is empty")
            return Result.success()
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
            appContainer.eventRepository.addLog("Heartbeat completed with HTTP $responseCode")
            Result.success()
        } catch (error: Exception) {
            appContainer.eventRepository.addLog("Heartbeat failed: ${error.message ?: error::class.java.simpleName}")
            Result.success()
        }
    }

    companion object {
        private const val RESET_AFTER_MILLIS = 24L * 60L * 60L * 1000L
    }
}
