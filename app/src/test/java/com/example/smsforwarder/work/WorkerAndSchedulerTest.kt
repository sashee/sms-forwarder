package com.example.smsforwarder.work

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.smsforwarder.heartbeat.HeartbeatAlarmReceiver
import com.example.smsforwarder.heartbeat.HeartbeatForegroundService
import com.example.smsforwarder.heartbeat.HeartbeatRunner
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestAppContainer
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.installTestContainer
import com.example.smsforwarder.testing.runBlockingTest
import com.example.smsforwarder.testing.testAppContainer
import com.example.smsforwarder.testing.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class WorkerAndSchedulerTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        TestEnvironment.reset()
        installTestContainer()
    }

    @After
    fun tearDown() {
        testAppContainer().database.close()
        TimeZone.setDefault(originalTimeZone)
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
        assertTrue(queued.nextAttemptAt >= System.currentTimeMillis() + HeartbeatRunner.INTERVAL_MILLIS - 5_000L)
        assertTrue(container.scheduler.enqueuedDeliveries.isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().first().text.contains("remains pending until heartbeat retry"))
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
        assertTrue(container.scheduler.enqueuedDeliveries.isEmpty())
    }

    @Test
    fun heartbeatWorkerSkipsWhenUrlBlank() = runBlocking {
        val container = testAppContainer()
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertTrue(container.sender.requests.isEmpty())
        assertEquals(null, container.configRepository.getHeartbeatLastAttemptAt())
        assertEquals(null, container.configRepository.getHeartbeatLastSuccessAt())
        assertTrue(
            container.eventRepository.observeLogs().first().first().text ==
                "Heartbeat supervisor via worker disabled heartbeat infrastructure because URL is empty",
        )
    }

    @Test
    fun heartbeatWorkerSendsRequestAndLogsSuccess() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        worker.doWork()

        assertEquals(1, container.sender.requests.size)
        assertEquals("http://heartbeat", container.sender.requests.single().url)
        assertEquals("POST", container.sender.requests.single().method)
        assertEquals("text/plain", container.sender.requests.single().contentType)
        assertEquals("hb", container.sender.requests.single().body)
        assertNotNull(container.configRepository.getHeartbeatLastAttemptAt())
        assertNotNull(container.configRepository.getHeartbeatLastSuccessAt())
        val logs = container.eventRepository.observeLogs().first().map { it.text }
        assertTrue(logs.any { it.startsWith("Heartbeat sending (") })
        assertTrue(logs.any { it.startsWith("Heartbeat completed with HTTP 200") })
    }

    @Test
    fun heartbeatWorkerLogsFailureAndDoesNotSetFaultState() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.sender.nextException = IllegalStateException("network down")
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())

        assertEquals(null, container.configRepository.getFaultState())
        assertNotNull(container.configRepository.getHeartbeatLastAttemptAt())
        assertEquals(null, container.configRepository.getHeartbeatLastSuccessAt())
        assertTrue(container.scheduler.enqueuedDeliveries.isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().any {
            it.text.startsWith("Heartbeat failed (now=") &&
                it.text.contains("+") &&
                it.text.contains("network down")
        })
    }

    @Test
    fun heartbeatWorkerSkipsSecondTriggerBeforeNextInterval() = runBlocking {
        val container = testAppContainer()
        val firstNow = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        val secondNow = firstNow + 5_000L
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))

        assertTrue(HeartbeatRunner.runHeartbeatSlot(container, firstNow))
        assertEquals(false, HeartbeatRunner.runHeartbeatSlot(container, secondNow))

        assertEquals(1, container.sender.requests.size)
        assertEquals(firstNow, container.configRepository.getHeartbeatLastAttemptAt())
        assertTrue(
            container.eventRepository.observeLogs().first().any {
                it.text.startsWith("Heartbeat skipped because next slot is not due yet") &&
                    it.text.contains("lastAttemptAt=") &&
                    it.text.contains("nextDueAt=") &&
                    it.text.contains("+")
            },
        )
    }

    @Test
    fun heartbeatWorkerSendsAgainAfterIntervalElapsed() = runBlocking {
        val container = testAppContainer()
        val firstNow = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        val secondNow = firstNow + HeartbeatRunner.INTERVAL_MILLIS
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))

        assertTrue(HeartbeatRunner.runHeartbeatSlot(container, firstNow))
        assertTrue(HeartbeatRunner.runHeartbeatSlot(container, secondNow))

        assertEquals(2, container.sender.requests.size)
        assertEquals(secondNow, container.configRepository.getHeartbeatLastAttemptAt())
    }

    @Test
    fun heartbeatWorkerSkipsWhenFaultStateIsRecent() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.configRepository.setFaultState("db broken", System.currentTimeMillis() - 1_000L)
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(ApplicationProvider.getApplicationContext()).build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())

        assertTrue(container.sender.requests.isEmpty())
        assertNotNull(container.configRepository.getHeartbeatLastAttemptAt())
        assertTrue(
            container.eventRepository.observeLogs().first().any {
                it.text.startsWith("Heartbeat skipped while fault state is active")
            },
        )
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
        assertNotNull(container.configRepository.getHeartbeatLastAttemptAt())
        val logs = container.eventRepository.observeLogs().first()
        assertTrue(logs.any { it.text.contains("Database reset") })
    }

    @Test
    fun heartbeatWorkerTrimsLogsOlderThanSixMonthsOnFirstHeartbeatOfUtcDay() = runBlocking {
        val container = testAppContainer()
        val now = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        val cutoff = Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .minusMonths(6)
            .toInstant()
            .toEpochMilli()
        container.eventRepository.addLog("old", cutoff - 1)
        container.eventRepository.addLog("keep", cutoff)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        val logs = container.eventRepository.observeLogs(10).first().map { it.text }
        assertTrue("old" !in logs)
        assertTrue("keep" in logs)
        assertEquals(now, container.configRepository.getLogLastTrimAt())
    }

    @Test
    fun heartbeatWorkerDoesNotTrimLogsAgainLaterTheSameUtcDay() = runBlocking {
        val container = testAppContainer()
        val firstTrimAt = Instant.parse("2026-04-04T00:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-04-04T23:30:00Z").toEpochMilli()
        val cutoff = Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .minusMonths(6)
            .toInstant()
            .toEpochMilli()
        container.configRepository.setLogLastTrimAt(firstTrimAt)
        container.eventRepository.addLog("old", cutoff - 1)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        val logs = container.eventRepository.observeLogs(10).first().map { it.text }
        assertTrue("old" in logs)
        assertEquals(firstTrimAt, container.configRepository.getLogLastTrimAt())
    }

    @Test
    fun heartbeatWorkerTrimsAgainOnFirstRunOfNewUtcDay() = runBlocking {
        val container = testAppContainer()
        val firstTrimAt = Instant.parse("2026-04-04T23:59:00Z").toEpochMilli()
        val now = Instant.parse("2026-04-05T00:01:00Z").toEpochMilli()
        val cutoff = Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .minusMonths(6)
            .toInstant()
            .toEpochMilli()
        container.configRepository.setLogLastTrimAt(firstTrimAt)
        container.eventRepository.addLog("old", cutoff - 1)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        val logs = container.eventRepository.observeLogs(10).first().map { it.text }
        assertTrue("old" !in logs)
        assertEquals(now, container.configRepository.getLogLastTrimAt())
    }

    @Test
    fun heartbeatWorkerStillTrimsLogsDuringRecentFaultState() = runBlocking {
        val container = testAppContainer()
        val now = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        val cutoff = Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .minusMonths(6)
            .toInstant()
            .toEpochMilli()
        container.eventRepository.addLog("old", cutoff - 1)
        container.configRepository.setFaultState("db broken", now - 1_000L)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        val logs = container.eventRepository.observeLogs(10).first().map { it.text }
        assertTrue("old" !in logs)
        assertTrue(logs.any { it.startsWith("Heartbeat skipped while fault state is active") })
        assertEquals(now, container.configRepository.getLogLastTrimAt())
        assertTrue(container.sender.requests.isEmpty())
    }

    @Test
    fun persistedFaultStateIsHonoredAfterContainerRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        val originalContainer = testAppContainer()
        val now = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()

        originalContainer.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        originalContainer.configRepository.setFaultState("db broken", now - 1_000L)

        val recreatedContainer = TestAppContainer(context)

        HeartbeatRunner.runHeartbeatSlot(recreatedContainer, now)

        assertEquals("db broken", recreatedContainer.configRepository.getFaultState()?.reason)
        assertTrue(recreatedContainer.sender.requests.isEmpty())
        assertTrue(
            recreatedContainer.eventRepository.observeLogs().first().first().text.startsWith(
                "Heartbeat skipped while fault state is active",
            ),
        )
        recreatedContainer.database.close()
    }

    @Test
    @Suppress("DEPRECATION")
    fun eventSchedulerStartsImmediateHeartbeatWhenOverdueAndEnqueuesDeliveryWork() = runBlockingTest {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        testAppContainer().configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        val database = testAppContainer().database
        val workManager = WorkManager.getInstance(context)
        val scheduler = EventScheduler(context, workManager, database.queueDao())

        scheduler.ensureRecurringWork()
        scheduler.enqueueDelivery(10L, 1234L)

        val deliveryInfos = workManager.getWorkInfosForUniqueWork("deliver-event-10").get()
        assertEquals(1, deliveryInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, deliveryInfos.first().state)

        val startedService = shadowOf(context as Application).nextStartedService
        assertEquals(HeartbeatForegroundService::class.java.name, startedService.component!!.className)
        assertTrue(
            testAppContainer().eventRepository.observeLogs().first().any {
                it.text.startsWith("Heartbeat supervisor via scheduler is running due heartbeat")
            },
        )

        val workDatabase = workManager.javaClass.getMethod("getWorkDatabase").invoke(workManager)
        val openHelper = workDatabase.javaClass.getMethod("getOpenHelper").invoke(workDatabase)
        val databaseHandle = openHelper.javaClass.getMethod("getWritableDatabase").invoke(openHelper)
        val queryMethod = databaseHandle.javaClass.getMethod("query", String::class.java)

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
    @Suppress("DEPRECATION")
    fun eventSchedulerSchedulesAlarmWithoutImmediateStartWhenHeartbeatNotDue() = runBlockingTest {
        val context = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        testAppContainer().configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        val database = testAppContainer().database
        val workManager = WorkManager.getInstance(context)
        val scheduler = EventScheduler(context, workManager, database.queueDao())
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        testAppContainer().configRepository.setHeartbeatLastAttemptAt(System.currentTimeMillis())

        scheduler.ensureRecurringWork()

        val scheduledAlarm = requireNotNull(shadowOf(alarmManager).nextScheduledAlarm)
        assertTrue(scheduledAlarm.triggerAtTime >= System.currentTimeMillis() + HeartbeatRunner.INTERVAL_MILLIS - 5_000L)
        assertTrue(testAppContainer().configRepository.getHeartbeatAlarmScheduledAt() != null)
        assertEquals(HeartbeatForegroundService::class.java.name, shadowOf(context as Application).nextStartedService.component!!.className)
        assertTrue(
            testAppContainer().eventRepository.observeLogs().first().any {
                it.text.startsWith("Heartbeat recovery alarm scheduled for ") && it.text.contains("+")
            },
        )
    }

    @Test
    fun heartbeatRunnerOnlyRecoversQueuedEventsOlderThanOneInterval() = runBlockingTest {
        val container = testAppContainer()
        val now = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.eventRepository.enqueueSms("+1555", "old", now - HeartbeatRunner.INTERVAL_MILLIS - 1_000L)
        container.eventRepository.enqueueSms("+1666", "fresh", now - 1_000L)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        assertEquals(2, container.sender.requests.size)
        assertEquals(1, container.eventRepository.allQueuedEvents().size)
        assertEquals("fresh", container.eventRepository.allQueuedEvents().single().text)
        assertTrue(
            container.eventRepository.observeLogs().first().any {
                it.text == "Heartbeat recovered 1 queued event(s) older than 30 minutes"
            },
        )
    }

    @Test
    fun heartbeatRunnerDoesNotRecoverFreshQueuedEvents() = runBlockingTest {
        val container = testAppContainer()
        val now = Instant.parse("2026-04-04T12:00:00Z").toEpochMilli()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.eventRepository.enqueueSms("+1666", "fresh", now - 1_000L)

        HeartbeatRunner.runHeartbeatSlot(container, now)

        assertEquals(1, container.sender.requests.size)
        assertEquals(1, container.eventRepository.allQueuedEvents().size)
        assertTrue(
            container.eventRepository.observeLogs().first().none {
                it.text.startsWith("Heartbeat recovered ")
            },
        )
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
    fun ensureRecurringWorkCancelsLegacyHeartbeatWorkerViaSchedulerPath() = runBlockingTest {
        val container = testAppContainer()

        container.scheduler.ensureRecurringWork()

        assertEquals(1, container.scheduler.heartbeatScheduledCount)
        assertEquals(1, container.scheduler.legacyHeartbeatCleanupCount)
        assertEquals(1, container.scheduler.heartbeatRepairCount)
    }

    @Test
    fun heartbeatAlarmReceiverRunsSupervisorAndLogs() = runBlocking {
        val container = testAppContainer()
        val receiver = HeartbeatAlarmReceiver()
        container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
        container.configRepository.setHeartbeatAlarmScheduledAt(123L)

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            Intent(HeartbeatAlarmReceiver.ACTION_HEARTBEAT_ALARM),
        )

        waitFor { container.scheduler.heartbeatServiceStartCount == 1 }
        waitFor { runBlocking { container.configRepository.getHeartbeatAlarmScheduledAt() != null } }
        assertEquals(1, container.scheduler.heartbeatServiceStartCount)
        assertTrue(container.configRepository.getHeartbeatAlarmScheduledAt() != null)
        assertTrue(
            container.eventRepository.observeLogs().first().any {
                it.text == "Heartbeat recovery alarm fired (previously scheduled for 1970-01-01T01:00:00.123+01:00 (123))"
            },
        )
    }

    @Test
    fun heartbeatAlarmReceiverIgnoresUnrelatedAction() = runBlocking {
        val container = testAppContainer()

        HeartbeatAlarmReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent("other"))

        assertEquals(0, container.scheduler.heartbeatServiceStartCount)
        assertTrue(container.eventRepository.observeLogs().first().isEmpty())
    }

    @Test
    fun heartbeatForegroundServiceRunsHeartbeatImmediatelyWhenDue() {
        runBlocking {
            TestEnvironment.reset()
            installTestContainer { context -> TestAppContainer(context) }
            val container = testAppContainer()
            container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
            val serviceController = Robolectric.buildService(HeartbeatForegroundService::class.java).create()
            val service = serviceController.get()

            service.onStartCommand(HeartbeatForegroundService.createStartIntent(service, "test"), 0, 1)

            waitFor { container.sender.requests.size == 1 }
            waitFor { container.scheduler.heartbeatAlarmTimes.isNotEmpty() }
            waitFor {
                runBlocking {
                    container.eventRepository.observeLogs().first().any {
                        it.text.startsWith("Heartbeat supervision finished via test; nextDueAt=")
                    }
                }
            }
            assertNotNull(container.configRepository.getHeartbeatServiceSeenAt())
            assertNotNull(container.configRepository.getHeartbeatAlarmScheduledAt())
            assertEquals(1, container.sender.requests.size)
            assertTrue(container.scheduler.heartbeatAlarmTimes.isNotEmpty())
            val logs = container.eventRepository.observeLogs().first().map { it.text }
            assertTrue(logs.any { it.contains("Heartbeat service start requested via test") && it.contains("executionActive=false") })
            assertTrue(logs.any { it.startsWith("Heartbeat completed with HTTP 200") })
            assertTrue(logs.any { it.startsWith("Heartbeat supervision finished via test; nextDueAt=") && it.contains("+") })
            serviceController.destroy()
        }
    }

    @Test
    fun heartbeatForegroundServiceSkipsSendAndRearmsNextAlarmWhenRecentlyAttempted() {
        runBlocking {
            TestEnvironment.reset()
            installTestContainer { context -> TestAppContainer(context) }
            val container = testAppContainer()
            container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
            val lastAttemptAt = System.currentTimeMillis()
            container.configRepository.setHeartbeatLastAttemptAt(lastAttemptAt)
            val serviceController = Robolectric.buildService(HeartbeatForegroundService::class.java).create()
            val service = serviceController.get()

            service.onStartCommand(HeartbeatForegroundService.createStartIntent(service, "recent"), 0, 1)

            waitFor { container.scheduler.heartbeatAlarmTimes.isNotEmpty() }
            Thread.sleep(50)
            assertTrue(container.sender.requests.isEmpty())
            assertNotNull(container.configRepository.getHeartbeatAlarmScheduledAt())
            assertTrue(container.scheduler.heartbeatAlarmTimes.last() >= lastAttemptAt + HeartbeatRunner.INTERVAL_MILLIS - 5_000L)
            assertTrue(
                container.eventRepository.observeLogs().first().any {
                    it.text.startsWith("Heartbeat supervisor via service:recent did not run heartbeat immediately")
                },
            )
            serviceController.destroy()
        }
    }

    @Test
    fun heartbeatForegroundServiceRepairsMissingAlarmStateAfterOneShotExecution() {
        runBlocking {
            TestEnvironment.reset()
            installTestContainer { context -> TestAppContainer(context) }
            val container = testAppContainer()
            container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
            container.configRepository.clearHeartbeatAlarmScheduledAt()
            val serviceController = Robolectric.buildService(HeartbeatForegroundService::class.java).create()
            val service = serviceController.get()

            service.onStartCommand(HeartbeatForegroundService.createStartIntent(service, "repair"), 0, 1)

            waitFor { container.scheduler.heartbeatAlarmTimes.isNotEmpty() }
            waitFor {
                runBlocking {
                    container.eventRepository.observeLogs().first().any {
                        it.text.startsWith("Heartbeat alarm repair requested via supervisor:service:repair (missing)")
                    }
                }
            }
            assertNotNull(container.configRepository.getHeartbeatAlarmScheduledAt())
            assertTrue(
                container.eventRepository.observeLogs().first().any {
                    it.text.startsWith("Heartbeat alarm repair requested via supervisor:service:repair (missing)") &&
                        it.text.contains("scheduledAt=none") &&
                        it.text.contains("nextDueAt=") &&
                        it.text.contains("+")
                },
            )
            serviceController.destroy()
        }
    }

    @Test
    fun heartbeatForegroundServiceIgnoresConcurrentExecutionStartRequest() {
        runBlocking {
            TestEnvironment.reset()
            installTestContainer { context -> TestAppContainer(context) }
            val container = testAppContainer()
            container.configRepository.saveConfig(AppConfig(heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb")))
            val serviceController = Robolectric.buildService(HeartbeatForegroundService::class.java).create()
            val service = serviceController.get()

            val activeJob = kotlinx.coroutines.Job()
            service.javaClass.getDeclaredField("executionJob").apply {
                isAccessible = true
            }.set(service, activeJob)

            service.onStartCommand(HeartbeatForegroundService.createStartIntent(service, "alarm"), 0, 2)

            waitFor {
                runBlocking {
                    container.eventRepository.observeLogs().first().any {
                        it.text.contains("Heartbeat service start requested via alarm") && it.text.contains("executionActive=true")
                    }
                }
            }
            assertTrue(container.sender.requests.isEmpty())
            assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Heartbeat supervision already active; start request via alarm did not restart it" })
            activeJob.cancel()
            serviceController.destroy()
        }
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
