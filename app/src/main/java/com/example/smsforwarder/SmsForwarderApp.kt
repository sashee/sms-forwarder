package com.example.smsforwarder

import android.app.Application
import androidx.work.Configuration
import com.example.smsforwarder.work.AppWorkerFactory

class SmsForwarderApp : Application(), Configuration.Provider {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.scheduler.ensureRecurringWork()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory(appContainer))
            .build()
}
