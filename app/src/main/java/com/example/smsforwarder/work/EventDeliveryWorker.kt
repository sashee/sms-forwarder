package com.example.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.SmsForwarderApp

class EventDeliveryWorker(
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
        val eventId = inputData.getLong(KEY_EVENT_ID, -1L)
        if (eventId <= 0L) {
            return Result.failure()
        }
        QueuedEventProcessor.deliverNow(appContainer, eventId)
        return Result.success()
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
    }
}
