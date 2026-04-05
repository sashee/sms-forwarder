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
    var workManagerInitialized: Boolean = false

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
    var legacyHeartbeatCleanupCount: Int = 0
    var heartbeatRepairCount: Int = 0
    var heartbeatServiceStartCount: Int = 0
    val heartbeatAlarmTimes: MutableList<Long> = CopyOnWriteArrayList()
    val enqueuedDeliveries: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
    var rescheduleInvocations: Int = 0

    override suspend fun ensureRecurringWork() {
        heartbeatScheduledCount += 1
        super.ensureRecurringWork()
    }

    override fun cancelLegacyHeartbeatWork() {
        legacyHeartbeatCleanupCount += 1
    }

    override fun startHeartbeatService(reason: String) {
        heartbeatServiceStartCount += 1
    }

    override suspend fun ensureHeartbeatScheduled(reason: String, startServiceIfOverdue: Boolean) {
        heartbeatRepairCount += 1
        super.ensureHeartbeatScheduled(reason, startServiceIfOverdue)
    }

    override fun scheduleHeartbeatRecoveryAlarm(triggerAtMillis: Long) {
        heartbeatAlarmTimes += triggerAtMillis
        runBlocking {
            val appContainer = (context.applicationContext as TestSmsForwarderApp).appContainer as TestAppContainer
            appContainer.configRepository.setHeartbeatAlarmScheduledState(triggerAtMillis, appContainer.configRepository.currentBootCount())
        }
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
    if (!TestEnvironment.workManagerInitialized) {
        initializeWorkManager(context)
        TestEnvironment.workManagerInitialized = true
    }
    WorkManager.getInstance(context).cancelAllWork().result.get()
    WorkManager.getInstance(context).pruneWork().result.get()
    if (context is TestSmsForwarderApp) {
        val container = factory(context)
        context.replaceAppContainerForTest(container)
        if (container is TestAppContainer) {
            runBlocking {
                container.configRepository.saveConfig(AppConfig())
                container.configRepository.clearFaultState()
                container.configRepository.clearCallScreeningSeenAt()
                container.configRepository.clearTelephonyCallSeenAt()
                container.configRepository.clearHeartbeatLastAttemptAt()
                container.configRepository.clearHeartbeatLastSuccessAt()
                container.configRepository.clearHeartbeatServiceSeenState()
                container.configRepository.clearHeartbeatAlarmScheduledState()
                container.configRepository.clearLogLastTrimAt()
            }
        }
    }
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
