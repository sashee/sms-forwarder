package com.example.smsforwarder.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.installTestContainer
import com.example.smsforwarder.testing.runBlockingTest
import com.example.smsforwarder.testing.testAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class WorkerAndSchedulerTest {
    @Before
    fun setUp() {
        TestEnvironment.reset()
        installTestContainer()
    }

    @After
    fun tearDown() {
        testAppContainer().database.close()
        TestEnvironment.reset()
    }

    @Test
    fun eventDeliveryWorkerFailsWithoutEventId() = runBlocking {
        val worker = TestListenableWorkerBuilder<EventDeliveryWorker>(ApplicationProvider.getApplicationContext()).build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun eventDeliveryWorkerSucceedsWhenQueueEntryAlreadyGone() = runBlocking {
        val worker = TestListenableWorkerBuilder<EventDeliveryWorker>(
            ApplicationProvider.getApplicationContext(),
            inputData = workDataOf(EventDeliveryWorker.KEY_EVENT_ID to 999L),
        ).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun eventDeliveryWorkerDeliversAndDeletesQueueEntry() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(
            AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")),
        )
        val eventId = container.eventRepository.enqueueSms("+1555", "hello", 0)

        val worker = TestListenableWorkerBuilder<EventDeliveryWorker>(
            ApplicationProvider.getApplicationContext(),
            inputData = androidx.work.workDataOf(EventDeliveryWorker.KEY_EVENT_ID to eventId),
        ).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, container.sender.requests.size)
        assertEquals("http://sms", container.sender.requests.single().url)
        assertEquals("POST", container.sender.requests.single().method)
        assertEquals("text/plain", container.sender.requests.single().contentType)
        assertEquals("body", container.sender.requests.single().body)
        assertEquals(null, container.eventRepository.getQueuedEvent(eventId))
        assertTrue(container.eventRepository.observeLogs().first().first().text.contains("Delivered SMS event"))
    }

    @Test
    fun eventDeliveryWorkerSchedulesRetryOnFailure() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        container.sender.nextResponseCode = 500
        val eventId = container.eventRepository.enqueueSms("+1555", "hello", 0)

        val worker = TestListenableWorkerBuilder<EventDeliveryWorker>(
            ApplicationProvider.getApplicationContext(),
            inputData = androidx.work.workDataOf(EventDeliveryWorker.KEY_EVENT_ID to eventId),
        ).build()

        worker.doWork()

        val queued = container.eventRepository.getQueuedEvent(eventId)!!
        assertEquals(1, queued.attemptCount)
        assertEquals(listOf(eventId to EventDeliveryWorker.retryDelayMillisForAttempt(1)), container.scheduler.enqueuedDeliveries)
        assertTrue(container.eventRepository.observeLogs().first().first().text.contains("Retry scheduled"))
    }

    @Test
    fun eventDeliveryWorkerSchedulesRetryOnExceptionWithoutFaultState() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        container.sender.nextException = IllegalStateException("boom")
        val eventId = container.eventRepository.enqueueSms("+1555", "hello", 0)

        val worker = TestListenableWorkerBuilder<EventDeliveryWorker>(
            ApplicationProvider.getApplicationContext(),
            inputData = workDataOf(EventDeliveryWorker.KEY_EVENT_ID to eventId),
        ).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())

        val queued = container.eventRepository.getQueuedEvent(eventId)!!
        assertEquals(1, queued.attemptCount)
        assertEquals(null, container.configRepository.getFaultState())
        assertTrue(container.eventRepository.observeLogs().first().first().text.contains("boom"))
    }

    @Test
    fun heartbeatWorkerSkipsWhenUrlBlank() = runBlocking {
        val container = testAppContainer()
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertTrue(container.sender.requests.isEmpty())
        assertEquals("Heartbeat skipped because URL is empty", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun heartbeatWorkerSendsRequestAndLogsSuccess() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertEquals(1, container.sender.requests.size)
        assertEquals("Heartbeat completed with HTTP 200", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun heartbeatWorkerLogsFailureAndDoesNotSetFaultState() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.sender.nextException = IllegalStateException("network down")
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())

        assertEquals(null, container.configRepository.getFaultState())
        assertTrue(container.scheduler.enqueuedDeliveries.isEmpty())
        assertEquals("Heartbeat failed: network down", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun heartbeatWorkerSkipsWhenFaultStateIsRecent() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.configRepository.setFaultState("db broken", System.currentTimeMillis() - 1_000L)
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())

        assertTrue(container.sender.requests.isEmpty())
        assertEquals("Heartbeat skipped while fault state is active", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun heartbeatWorkerResetsDatabaseAfterLongFaultAndPreservesConfig() = runBlocking {
        val container = testAppContainer()
        container.eventRepository.addLog("old", 1L)
        val config = AppConfig(
            heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
            sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
            call = EventConfig("http://call", "PATCH", "text/xml", "call"),
        )
        container.configRepository.saveConfig(config)
        container.configRepository.setFaultState("db broken", System.currentTimeMillis() - 25L * 60L * 60L * 1000L)
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertEquals(null, container.configRepository.getFaultState())
        assertEquals(config, container.configRepository.getConfig())
        val logs = container.eventRepository.observeLogs().first()
        assertEquals(1, logs.size)
        assertTrue(logs.first().text.contains("Database reset"))
    }

    @Test
    fun eventSchedulerEnqueuesUniqueWorkRequests() = runBlockingTest {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        val database = testAppContainer().database
        val workManager = WorkManager.getInstance(context)
        val scheduler = EventScheduler(context, workManager, database.queueDao())

        scheduler.ensureRecurringWork()
        scheduler.enqueueDelivery(10L, 1234L)

        val heartbeatInfos = workManager.getWorkInfosForUniqueWork("heartbeat-work").get()
        val deliveryInfos = workManager.getWorkInfosForUniqueWork("deliver-event-10").get()
        assertEquals(1, heartbeatInfos.size)
        assertEquals(1, deliveryInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, heartbeatInfos.first().state)
        assertEquals(WorkInfo.State.ENQUEUED, deliveryInfos.first().state)

        val workDatabase = workManager.javaClass.getMethod("getWorkDatabase").invoke(workManager)
        val openHelper = workDatabase.javaClass.getMethod("getOpenHelper").invoke(workDatabase)
        val databaseHandle = openHelper.javaClass.getMethod("getWritableDatabase").invoke(openHelper)
        val queryMethod = databaseHandle.javaClass.getMethod("query", String::class.java)

        val heartbeatCursor = requireNotNull(queryMethod.invoke(
            databaseHandle,
            "SELECT interval_duration, required_network_type FROM workspec WHERE id = '${heartbeatInfos.first().id}'",
        ))
        heartbeatCursor.useCursor { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(TimeUnit.MINUTES.toMillis(30), cursor.getLong(0))
            assertEquals(NetworkType.CONNECTED.ordinal, cursor.getInt(1))
        }

        val deliveryCursor = requireNotNull(queryMethod.invoke(
            databaseHandle,
            "SELECT required_network_type FROM workspec WHERE id = '${deliveryInfos.first().id}'",
        ))
        deliveryCursor.useCursor { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(NetworkType.CONNECTED.ordinal, cursor.getInt(0))
        }
    }

    @Test
    fun eventSchedulerReschedulesQueuedEventsWithOverdueAndFutureDelays() = runBlockingTest {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        val container = testAppContainer()
        val scheduler = object : EventScheduler(context, WorkManager.getInstance(context), container.database.queueDao()) {
            val scheduled = mutableListOf<Pair<Long, Long>>()

            override fun enqueueDelivery(eventId: Long, delayMillis: Long) {
                scheduled += eventId to delayMillis
            }
        }

        val overdueId = container.eventRepository.enqueueSms("+1555", "overdue", 0)
        val futureId = container.eventRepository.enqueueSms("+1666", "future", 0)
        container.eventRepository.scheduleRetry(overdueId, 1, System.currentTimeMillis() - 1_000L)
        val futureDelay = 20_000L
        val futureTarget = System.currentTimeMillis() + futureDelay
        container.eventRepository.scheduleRetry(futureId, 1, futureTarget)

        scheduler.rescheduleQueuedEvents()

        val overdue = scheduler.scheduled.single { it.first == overdueId }
        val future = scheduler.scheduled.single { it.first == futureId }
        assertEquals(0L, overdue.second)
        assertTrue(future.second in (futureDelay - 2_000L)..futureDelay)
    }

    @Test
    fun appWorkerFactoryCreatesKnownWorkersAndReturnsNullForUnknown() {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        val container = testAppContainer()
        val factory = AppWorkerFactory(container)

        val deliveryWorker = TestListenableWorkerBuilder<EventDeliveryWorker>(
            context,
            inputData = workDataOf(EventDeliveryWorker.KEY_EVENT_ID to 1L),
        ).setWorkerFactory(factory).build()
        val heartbeatWorker = TestListenableWorkerBuilder<HeartbeatWorker>(context)
            .setWorkerFactory(factory)
            .build()

        assertEquals(EventDeliveryWorker::class.java, deliveryWorker::class.java)
        assertEquals(HeartbeatWorker::class.java, heartbeatWorker::class.java)

        val workerParameters = listOf(heartbeatWorker, deliveryWorker)
            .asSequence()
            .mapNotNull { candidate -> extractWorkerParameters(candidate) }
            .first()
        assertEquals(null, factory.createWorker(context, "missing.Worker", workerParameters))
    }

    private fun extractWorkerParameters(worker: Any): WorkerParameters? {
        var current: Class<*>? = worker.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { it.type.name == "androidx.work.WorkerParameters" }?.let { field ->
                field.isAccessible = true
                return field.get(worker) as WorkerParameters
            }
            current = current.superclass
        }
        return null
    }

    private fun Any.useCursor(block: (android.database.Cursor) -> Unit) {
        val cursor = this as android.database.Cursor
        cursor.use(block)
    }

}
