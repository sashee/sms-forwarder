package com.example.smsforwarder.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.smsforwarder.AppContainer

class AppWorkerFactory(private val appContainer: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        EventDeliveryWorker::class.java.name -> EventDeliveryWorker(appContext, workerParameters, appContainer)
        HeartbeatWorker::class.java.name -> HeartbeatWorker(appContext, workerParameters, appContainer)
        else -> null
    }
}
