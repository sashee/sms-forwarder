package com.example.smsforwarder.testing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.smsforwarder.AppContainer
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.data.AppDatabase
import com.example.smsforwarder.data.ConfigRepository
import com.example.smsforwarder.data.EventRepository
import com.example.smsforwarder.net.HttpRequest
import com.example.smsforwarder.net.HttpSender
import com.example.smsforwarder.work.EventScheduler
import com.example.smsforwarder.model.AppConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object TestEnvironment {
    var containerFactory: ((Context) -> AppContainer)? = null

    fun reset() {
        containerFactory = null
    }
}

open class TestSmsForwarderApp : SmsForwarderApp() {
    override fun createAppContainer(): AppContainer {
        return TestEnvironment.containerFactory?.invoke(this) ?: TestAppContainer(this)
    }
}

class RecordingHttpSender : HttpSender {
    val requests: MutableList<HttpRequest> = CopyOnWriteArrayList()
    var nextResponseCode: Int = 200
    var nextException: Exception? = null

    override fun send(request: HttpRequest): Int {
        requests += request
        nextException?.let { throw it }
        return nextResponseCode
    }
}

open class RecordingScheduler(context: Context, queueDao: com.example.smsforwarder.data.QueueDao) :
    EventScheduler(context, WorkManager.getInstance(context), queueDao) {
    var heartbeatScheduledCount: Int = 0
    val enqueuedDeliveries: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
    var rescheduleInvocations: Int = 0

    override fun ensureRecurringWork() {
        heartbeatScheduledCount += 1
    }

    override fun enqueueDelivery(eventId: Long, delayMillis: Long) {
        enqueuedDeliveries += eventId to delayMillis
    }

    override suspend fun rescheduleQueuedEvents() {
        rescheduleInvocations += 1
        super.rescheduleQueuedEvents()
    }
}

open class TestAppContainer(
    context: Context,
    val sender: RecordingHttpSender = RecordingHttpSender(),
) : AppContainer(context) {
    override val database: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    override val configRepository: ConfigRepository by lazy {
        clearDataStore(appContext)
        ConfigRepository(appContext)
    }

    override val httpClient: HttpSender
        get() = sender

    override val eventRepository: EventRepository by lazy {
        EventRepository(appContext, database, configRepository)
    }

    override val scheduler: RecordingScheduler by lazy {
        RecordingScheduler(appContext, database.queueDao())
    }
}

fun initializeWorkManager(context: Context) {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build(),
    )
}

fun testAppContainer(): TestAppContainer {
    val app = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
    return app.appContainer as TestAppContainer
}

fun installTestContainer(factory: (Context) -> AppContainer = { TestAppContainer(it) }) {
    TestEnvironment.containerFactory = factory
    val context = ApplicationProvider.getApplicationContext<Application>()
    if (context is TestSmsForwarderApp) {
        val container = factory(context)
        context.replaceAppContainerForTest(container)
        if (container is TestAppContainer) {
            runBlocking {
                container.configRepository.saveConfig(AppConfig())
                container.configRepository.clearFaultState()
                container.configRepository.clearCallScreeningSeenAt()
            }
        }
    }
    initializeWorkManager(context)
}

fun clearDataStore(context: Context) {
    val file = File(context.filesDir.parentFile, "datastore/app-config.preferences_pb")
    if (file.exists()) {
        file.delete()
    }
}

fun waitFor(condition: () -> Boolean) {
    repeat(100) {
        if (condition()) {
            return
        }
        Thread.sleep(10)
    }
    error("Condition was not met in time")
}

fun runBlockingTest(block: suspend () -> Unit) {
    runBlocking {
        block()
    }
}
