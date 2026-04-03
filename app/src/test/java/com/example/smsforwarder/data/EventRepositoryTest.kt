package com.example.smsforwarder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.model.EventType
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.clearDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class EventRepositoryTest {
    private lateinit var app: TestSmsForwarderApp
    private lateinit var database: AppDatabase
    private lateinit var configRepository: ConfigRepository
    private lateinit var repository: EventRepository

    @Before
    fun setUp() = runBlocking {
        TestEnvironment.reset()
        app = ApplicationProvider.getApplicationContext()
        clearDataStore(app)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        configRepository = ConfigRepository(app)
        repository = EventRepository(app, database, configRepository)
        configRepository.saveConfig(
            AppConfig(
                heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
                sms = EventConfig("http://sms", "POST", "text/plain", "{{sms.number}} {{sms.text}} {{sms.timestamp}} {{unknown}}"),
                call = EventConfig("http://call", "POST", "text/plain", "{{call.number}} {{call.timestamp}}"),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        clearDataStore(app)
        TestEnvironment.reset()
    }

    @Test
    fun enqueueSmsCreatesRenderedQueueEntry() = runBlocking {
        val id = repository.enqueueSms("+1555", "hello", 0)

        val event = repository.getQueuedEvent(id)
        assertNotNull(event)
        assertEquals(EventType.SMS, event?.type)
        assertEquals("+1555", event?.number)
        assertEquals("hello", event?.text)
        assertTrue(event!!.eventId.isNotBlank())
        assertTrue(event.body.contains("+1555 hello 1970-01-01T00:00:00Z {{unknown}}"))
    }

    @Test
    fun enqueueCallCreatesQueueEntry() = runBlocking {
        val id = repository.enqueueCall("+1999", 0)

        val event = repository.getQueuedEvent(id)
        assertEquals(EventType.CALL, event?.type)
        assertEquals("+1999", event?.number)
        assertEquals("", event?.text)
        assertTrue(event!!.body.startsWith("+1999 1970-01-01T00:00:00Z"))
    }

    @Test
    fun markDeliveredAndRetryUpdateQueue() = runBlocking {
        val id = repository.enqueueSms("+1555", "hello", 0)
        repository.scheduleRetry(id, 3, 999L)
        val retried = repository.getQueuedEvent(id)
        assertEquals(3, retried?.attemptCount)
        assertEquals(999L, retried?.nextAttemptAt)

        repository.markDelivered(id)
        assertNull(repository.getQueuedEvent(id))
    }

    @Test
    fun logsAndResetDatabaseWork() = runBlocking {
        repository.addLog("one", 1L)
        repository.enqueueSms("+1555", "hello", 0)

        repository.resetDatabase("broken", 200L)

        assertTrue(repository.allQueuedEvents().isEmpty())
        val logs = repository.observeLogs(10).first()
        assertEquals(1, logs.size)
        assertEquals("Database reset at 200: broken", logs.first().text)
    }

    @Test
    fun heartbeatConfigComesFromPreferences() = runBlocking {
        assertEquals("http://heartbeat", repository.heartbeatConfig().url)
    }
}
