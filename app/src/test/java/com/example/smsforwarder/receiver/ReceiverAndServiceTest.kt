package com.example.smsforwarder.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.telephony.SmsMessage
import android.telecom.Call
import android.telecom.DisconnectCause
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream

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

        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertEquals(2, container.sender.requests.size)
        assertEquals(1, container.scheduler.heartbeatRepairCount)
        val logs = container.eventRepository.observeLogs().first().map { it.text }
        assertTrue(logs.contains("Queued SMS event 1"))
        assertTrue(logs.any { it.startsWith("Delivered SMS event") })
    }

    @Test
    fun smsReceiverParsesRealSmsReceivedIntent() = runBlocking {
        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(deliverPdu("+16660000", "hello")))
            putExtra("format", SmsMessage.FORMAT_3GPP)
        }

        val messages = receiver.messagesFromIntent(intent)

        assertEquals(1, messages.size)
        assertEquals("+16660000", messages.single().number)
        assertEquals("hello", messages.single().text)
    }

    @Test
    fun smsReceiverOnReceiveHandlesSmsReceivedIntent() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(deliverPdu("+16660000", "hello")))
            putExtra("format", SmsMessage.FORMAT_3GPP)
        }

        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)

        waitFor { container.sender.requests.size == 1 }
        waitFor { container.scheduler.heartbeatRepairCount == 1 }
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertEquals(1, container.scheduler.heartbeatRepairCount)
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
        val originalConfig = AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body"))
        container.configRepository.saveConfig(originalConfig)
        val receiver = SmsReceiver()
        receiver.handleMessages(container, listOf(SmsReceiver.IncomingSms("+1555", "one", 1L)))

        assertTrue(container.configRepository.getFaultState()!!.reason.contains("SMS enqueue failed"))
        assertEquals(originalConfig, container.configRepository.getConfig())
        assertEquals(1, container.scheduler.heartbeatRepairCount)
    }

    @Test
    fun bootReceiverIgnoresUnrelatedAction() = runBlocking {
        val container = testAppContainer()

        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent("other"))

        assertEquals(0, container.scheduler.heartbeatScheduledCount)
        assertTrue(container.eventRepository.observeLogs().first().isEmpty())
    }

    @Test
    fun bootReceiverReschedulesAndLogsForBootCompleted() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(
            AppConfig(
                heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
                sms = EventConfig("http://sms", "POST", "text/plain", "body"),
            ),
        )
        container.eventRepository.enqueueSms("+1555", "one", 1L)
        container.configRepository.setHeartbeatServiceSeenState(System.currentTimeMillis(), 999L)
        container.configRepository.setHeartbeatAlarmScheduledState(System.currentTimeMillis() + 60_000L, 999L)

        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent(Intent.ACTION_BOOT_COMPLETED))

        waitFor { container.scheduler.heartbeatScheduledCount == 1 }
        waitFor { container.sender.requests.size == 2 }
        assertEquals(1, container.scheduler.heartbeatRepairCount)
        assertEquals(1, container.scheduler.heartbeatServiceStartCount)
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertTrue(container.sender.requests.any { it.url == "http://heartbeat" })
        assertTrue(container.sender.requests.any { it.url == "http://sms" })
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Boot completed; draining queued events" })
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Boot drain processed 1 queued event(s)" })
        assertTrue(container.eventRepository.observeLogs().first().any { it.text.contains("requested foreground service start") })
        assertTrue(container.eventRepository.observeLogs().first().any { it.text.contains("Heartbeat alarm repair requested via supervisor:scheduler") })
    }

    @Test
    fun bootReceiverReschedulesAndLogsForLockedBootCompleted() = runBlocking {
        val container = testAppContainer()

        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        waitFor { container.scheduler.heartbeatScheduledCount == 1 }
        waitFor { runBlocking { container.eventRepository.observeLogs().first().isNotEmpty() } }
        assertEquals(1, container.scheduler.heartbeatRepairCount)
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Boot completed; draining queued events" })
    }

    @Test
    fun bootReceiverDrainsMultipleQueuedEvents() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(sms = EventConfig("http://sms", "POST", "text/plain", "body")))
        container.eventRepository.enqueueSms("+1555", "overdue", 1L)
        container.eventRepository.enqueueSms("+1666", "future", 2L)

        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent(Intent.ACTION_BOOT_COMPLETED))

        waitFor { container.sender.requests.size == 2 }
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Boot drain processed 2 queued event(s)" })
    }

    @Test
    fun callScreeningServiceQueuesCallAndTracksStatus() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val service = ForwardingCallScreeningService()

        service.handleCall(container, "+1888", 123L, "screening")

        assertEquals(123L, container.configRepository.getCallScreeningSeenAt())
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertEquals(1, container.sender.requests.size)
        assertEquals(1, container.scheduler.heartbeatRepairCount)
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Queued call event 1 via screening" })
    }

    @Test
    fun callScreeningServiceAllowsDuplicateEvents() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val service = ForwardingCallScreeningService()

        service.handleCall(container, "+1888", 123L, "screening")
        service.handleCall(container, "+1888", 123L, "screening")

        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertEquals(2, container.sender.requests.size)
        assertEquals(2, container.scheduler.heartbeatRepairCount)
    }

    @Test
    fun callScreeningServiceOnScreenCallExtractsNumberAndRejects() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val serviceController = Robolectric.buildService(ForwardingCallScreeningService::class.java).create()
        val service = serviceController.get()
        val details = callDetails(Uri.parse("tel:+1888"))

        service.onScreenCall(details)

        waitFor { container.sender.requests.size == 1 }
        waitFor { runBlocking { container.eventRepository.observeLogs().first().any { it.text == "Rejected call" } } }

        val responseInput = shadowOf(service)
            .getLastRespondToCallInput()
            .orElseThrow { AssertionError("Expected call screening response") }
        assertEquals(details, responseInput.callDetails)
        assertTrue(responseInput.callResponse.disallowCall)
        assertTrue(responseInput.callResponse.rejectCall)
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Queued call event 1 via screening" })
    }

    @Test
    fun callScreeningServiceOnScreenCallUsesEmptyNumberForMissingHandle() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val service = Robolectric.buildService(ForwardingCallScreeningService::class.java).create().get()

        service.onScreenCall(callDetails(null))

        waitFor { container.sender.requests.size == 1 }
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
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
        val originalConfig = AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body"))
        container.configRepository.saveConfig(originalConfig)
        val service = ForwardingCallScreeningService()

        service.handleCall(container, "", 456L, "screening")

        assertTrue(container.configRepository.getFaultState()!!.reason.contains("Call enqueue failed"))
        assertEquals(originalConfig, container.configRepository.getConfig())
        val response = service.rejectionResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertTrue(response.skipCallLog)
        assertTrue(response.skipNotification)
        assertNull(container.configRepository.getCallScreeningSeenAt()?.takeIf { it == 0L })
    }

    @Test
    fun callStateReceiverIgnoresUnrelatedAction() = runBlocking {
        val receiver = CallStateReceiver()

        receiver.onReceive(ApplicationProvider.getApplicationContext(), Intent("other"))

        assertTrue(testAppContainer().eventRepository.allQueuedEvents().isEmpty())
    }

    @Test
    fun callStateReceiverIgnoresNonRingingState() = runBlocking {
        val container = testAppContainer()
        val receiver = CallStateReceiver()
        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
            putExtra(EXTRA_INCOMING_NUMBER, "+1777")
        }

        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)

        waitFor { runBlocking { container.eventRepository.observeLogs().first().isNotEmpty() } }
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().first().text.contains("state=IDLE"))
    }

    @Test
    fun callStateReceiverQueuesCallAndTracksFallbackStatus() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val receiver = CallStateReceiver()

        receiver.handleIncomingCall(container, "+1777", 789L)

        assertEquals(789L, container.configRepository.getTelephonyCallSeenAt())
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertEquals(1, container.sender.requests.size)
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Queued call event 1 via telephony" })
    }

    @Test
    fun callStateReceiverOnReceiveHandlesRingingIntent() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(AppConfig(call = EventConfig("http://call", "POST", "text/plain", "body")))
        val receiver = CallStateReceiver()
        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
            putExtra(EXTRA_INCOMING_NUMBER, "+1777")
        }

        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)

        waitFor { container.sender.requests.size == 1 }
        assertTrue(container.eventRepository.allQueuedEvents().isEmpty())
        assertTrue(container.eventRepository.observeLogs().first().any { it.text.contains("Telephony broadcast:") })
        assertTrue(container.eventRepository.observeLogs().first().any { it.text == "Queued call event 1 via telephony" })
    }

    private fun callDetails(handle: Uri?): Call.Details = ReflectionHelpers.callConstructor(
        Call.Details::class.java,
        ClassParameter.from(String::class.java, "call-id"),
        ClassParameter.from(Uri::class.java, handle),
        ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
        ClassParameter.from(String::class.java, "caller"),
        ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
        ClassParameter.from(android.telecom.PhoneAccountHandle::class.java, null),
        ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
        ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
        ClassParameter.from(DisconnectCause::class.java, DisconnectCause(DisconnectCause.UNKNOWN)),
        ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
        ClassParameter.from(android.telecom.GatewayInfo::class.java, null),
        ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
        ClassParameter.from(android.telecom.StatusHints::class.java, null),
        ClassParameter.from(Bundle::class.java, Bundle()),
        ClassParameter.from(Bundle::class.java, Bundle()),
        ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
    )

    private fun deliverPdu(originatingAddress: String, message: String): ByteArray {
        val packedUserData = ReflectionHelpers.callStaticMethod<ByteArray>(
            Class.forName("com.android.internal.telephony.GsmAlphabet"),
            "stringToGsm7BitPacked",
            ClassParameter.from(String::class.java, message),
        )
        val output = ByteArrayOutputStream()
        output.write(0x00)
        output.write(0x04)
        writeAddress(output, originatingAddress)
        output.write(0x00)
        output.write(0x00)
        output.write(byteArrayOf(0x42, 0x10, 0x20, 0x30, 0x40, 0x50, 0x00))
        output.write(packedUserData[0].toInt())
        output.write(packedUserData, 1, packedUserData.size - 1)
        return output.toByteArray()
    }

    private fun writeAddress(output: ByteArrayOutputStream, number: String) {
        val digits = number.removePrefix("+")
        output.write(digits.length)
        output.write(if (number.startsWith("+")) 0x91 else 0x81)
        digits.chunked(2).forEach { chunk ->
            val low = chunk[0].digitToInt(16)
            val high = chunk.getOrNull(1)?.digitToInt(16) ?: 0x0F
            output.write((high shl 4) or low)
        }
    }

    companion object {
        private const val EXTRA_INCOMING_NUMBER = "incoming_number"
    }
}
