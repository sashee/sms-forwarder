package com.example.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.heartbeat.HeartbeatSupervisor

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
        HeartbeatSupervisor.run(
            appContainer = appContainer,
            reason = "worker",
            ensureService = true,
            allowImmediateHeartbeat = true,
        )
        return Result.success()
    }
}
