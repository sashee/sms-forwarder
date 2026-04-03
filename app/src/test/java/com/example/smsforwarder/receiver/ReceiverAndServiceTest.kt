package com.example.smsforwarder.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.data.EventRepository
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.telecom.ForwardingCallScreeningService
import com.example.smsforwarder.testing.TestAppContainer
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.installTestContainer
import com.example.smsforwarder.testing.testAppContainer
import com.example.smsforwarder.testing.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class ReceiverAndServiceTest {
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
    fun smsReceiverIgnoresUnrelatedAction() = runBlocking {
        val receiver = SmsReceiver()
        receiver.onReceive(ApplicationProvider.getApplicationContext(), Intent("other"))
        assertTrue(testAppContainer().eventRepository.allQueuedEvents().isEmpty())
    }

    @Test
    fun smsReceiverHandlesMessagesAndDuplicates() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        val receiver = SmsReceiver()

        receiver.handleMessages(
            container,
            listOf(
                SmsReceiver.IncomingSms("+1555", "one", 1L),
                SmsReceiver.IncomingSms("+1555", "one", 1L),
            ),
        )

        assertEquals(2, container.eventRepository.allQueuedEvents().size)
        assertEquals(2, container.scheduler.enqueuedDeliveries.size)
    }

    @Test
    fun smsReceiverStoresFaultStateWhenEnqueueFails() = runBlocking {
        TestEnvironment.reset()
        installTestContainer { context ->
            object : TestAppContainer(context) {
                override val eventRepository: EventRepository by lazy {
                    object : EventRepository(appContext, database, configRepository) {
                        override suspend fun enqueueSms(number: String, text: String, timestamp: Long): Long {
                            error("db down")
                        }
                    }
                }
            }
        }

        val container = testAppContainer()
        val receiver = SmsReceiver()
        receiver.handleMessages(container, listOf(SmsReceiver.IncomingSms("+1555", "one", 1L)))

        assertTrue(container.configRepository.getFaultState()!!.reason.contains("SMS enqueue failed"))
    }

    @Test
    fun bootReceiverReschedulesAndLogs() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        container.eventRepository.enqueueSms("+1555", "one", 1L)

        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent(Intent.ACTION_BOOT_COMPLETED))

        waitFor { container.scheduler.rescheduleInvocations == 1 }
        waitFor { runBlocking { container.eventRepository.observeLogs().first().isNotEmpty() } }
        assertEquals("Boot completed; rescheduling work", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun callScreeningServiceQueuesCallAndTracksStatus() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val service = ForwardingCallScreeningService()

        service.handleCall(container, "+1888", 123L)

        assertEquals(123L, container.configRepository.getCallScreeningSeenAt())
        assertEquals(1, container.eventRepository.allQueuedEvents().size)
        assertEquals(1, container.scheduler.enqueuedDeliveries.size)
        assertEquals("Queued call event 1", container.eventRepository.observeLogs().first().first().text)
    }

    @Test
    fun callScreeningServiceUsesEmptyNumberAndSetsFaultStateOnFailure() = runBlocking {
        TestEnvironment.reset()
        installTestContainer { context ->
            object : TestAppContainer(context) {
                override val eventRepository: EventRepository by lazy {
                    object : EventRepository(appContext, database, configRepository) {
                        override suspend fun enqueueCall(number: String, timestamp: Long): Long {
                            error("call queue failed")
                        }
                    }
                }
            }
        }
        val container = testAppContainer()
        val service = ForwardingCallScreeningService()

        service.handleCall(container, "", 456L)

        assertTrue(container.configRepository.getFaultState()!!.reason.contains("Call enqueue failed"))
        val response = service.rejectionResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertTrue(response.skipCallLog)
        assertTrue(response.skipNotification)
        assertNull(container.configRepository.getCallScreeningSeenAt()?.takeIf { it == 0L })
    }
}
