package com.example.smsforwarder

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.smsforwarder.data.AppDatabase
import com.example.smsforwarder.data.ConfigRepository
import com.example.smsforwarder.data.EventRepository
import com.example.smsforwarder.net.EventHttpClient
import com.example.smsforwarder.net.HttpSender
import com.example.smsforwarder.work.EventScheduler
import kotlinx.coroutines.runBlocking

open class AppContainer(context: Context) {
    protected val appContext = context.applicationContext

    open val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DB_NAME).build()
    }

    open val configRepository: ConfigRepository by lazy {
        ConfigRepository(appContext)
    }

    open val httpClient: HttpSender by lazy {
        EventHttpClient(
            context = appContext,
            onDohFailure = { message ->
                runCatching {
                    runBlocking {
                        eventRepository.addLog(message)
                    }
                }
            },
        )
    }

    open val eventRepository: EventRepository by lazy {
        EventRepository(appContext, database, configRepository)
    }

    open val workManager: WorkManager by lazy {
        WorkManager.getInstance(appContext)
    }

    open val scheduler: EventScheduler by lazy {
        EventScheduler(appContext, workManager, database.queueDao())
    }
}
