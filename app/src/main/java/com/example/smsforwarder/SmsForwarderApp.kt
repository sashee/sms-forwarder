package com.example.smsforwarder

import android.app.Application
import androidx.work.Configuration
import com.example.smsforwarder.work.AppWorkerFactory

open class SmsForwarderApp : Application(), Configuration.Provider {
    lateinit var appContainer: AppContainer
        protected set

    override fun onCreate() {
        super.onCreate()
        appContainer = createAppContainer()
        appContainer.scheduler.ensureRecurringWork()
    }

    protected open fun createAppContainer(): AppContainer = AppContainer(this)

    internal fun replaceAppContainerForTest(appContainer: AppContainer) {
        this.appContainer = appContainer
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory(appContainer))
            .build()
}
