package com.example.smsforwarder.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
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
    fun heartbeatWorkerResetsDatabaseAfterLongFault() = runBlocking {
        val container = testAppContainer()
        container.eventRepository.addLog("old", 1L)
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.configRepository.setFaultState("db broken", System.currentTimeMillis() - 25L * 60L * 60L * 1000L)
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertEquals(null, container.configRepository.getFaultState())
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
    }

}
