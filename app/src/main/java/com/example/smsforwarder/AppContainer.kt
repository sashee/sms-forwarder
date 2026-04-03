package com.example.smsforwarder

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.smsforwarder.data.AppDatabase
import com.example.smsforwarder.data.ConfigRepository
import com.example.smsforwarder.data.EventRepository
import com.example.smsforwarder.net.EventHttpClient
import com.example.smsforwarder.work.EventScheduler

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DB_NAME).build()
    }

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(appContext)
    }

    val httpClient: EventHttpClient by lazy {
        EventHttpClient(appContext)
    }

    val eventRepository: EventRepository by lazy {
        EventRepository(appContext, database, configRepository)
    }

    val workManager: WorkManager by lazy {
        WorkManager.getInstance(appContext)
    }

    val scheduler: EventScheduler by lazy {
        EventScheduler(appContext, workManager, database.queueDao())
    }
}
