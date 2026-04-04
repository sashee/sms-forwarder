package com.example.smsforwarder.data

import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.model.FaultState
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.clearDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class ConfigRepositoryTest {
    private lateinit var repository: ConfigRepository
    private lateinit var app: TestSmsForwarderApp

    @Before
    fun setUp() {
        TestEnvironment.reset()
        app = ApplicationProvider.getApplicationContext()
        clearDataStore(app)
        repository = ConfigRepository(app)
        runBlocking {
            repository.saveConfig(AppConfig())
            repository.clearFaultState()
            repository.clearCallScreeningSeenAt()
            repository.clearTelephonyCallSeenAt()
            repository.clearHeartbeatLastAttemptAt()
            repository.clearHeartbeatLastSuccessAt()
            repository.clearHeartbeatServiceSeenAt()
            repository.clearHeartbeatAlarmScheduledAt()
            repository.clearLogLastTrimAt()
        }
    }

    @After
    fun tearDown() {
        clearDataStore(app)
        TestEnvironment.reset()
    }

    @Test
    fun returnsDefaultsWhenUnset() = runBlocking {
        assertEquals(AppConfig(), repository.getConfig())
        assertNull(repository.getFaultState())
        assertNull(repository.getCallScreeningSeenAt())
        assertNull(repository.getTelephonyCallSeenAt())
        assertNull(repository.getHeartbeatLastAttemptAt())
        assertNull(repository.getHeartbeatLastSuccessAt())
        assertNull(repository.getHeartbeatServiceSeenAt())
        assertNull(repository.getHeartbeatAlarmScheduledAt())
        assertNull(repository.getLogLastTrimAt())
    }

    @Test
    fun savesAndLoadsConfigAndFaultState() = runBlocking {
        val config = AppConfig(
            heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "heartbeat"),
            sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
            call = EventConfig("http://call", "PATCH", "text/xml", "call"),
        )

        repository.saveConfig(config)
        repository.setFaultState("broken", 123L)
        repository.setCallScreeningSeenAt(456L)
        repository.setTelephonyCallSeenAt(654L)
        repository.setHeartbeatLastAttemptAt(789L)
        repository.setHeartbeatLastSuccessAt(987L)
        repository.setHeartbeatServiceSeenAt(159L)
        repository.setHeartbeatAlarmScheduledAt(753L)
        repository.setLogLastTrimAt(852L)

        assertEquals(config, repository.getConfig())
        assertEquals(FaultState("broken", 123L), repository.getFaultState())
        assertEquals(456L, repository.getCallScreeningSeenAt())
        assertEquals(654L, repository.getTelephonyCallSeenAt())
        assertEquals(789L, repository.getHeartbeatLastAttemptAt())
        assertEquals(987L, repository.getHeartbeatLastSuccessAt())
        assertEquals(159L, repository.getHeartbeatServiceSeenAt())
        assertEquals(753L, repository.getHeartbeatAlarmScheduledAt())
        assertEquals(852L, repository.getLogLastTrimAt())

        repository.clearFaultState()

        assertNull(repository.getFaultState())
        assertEquals(config, repository.getConfig())
    }

    @Test
    fun savedStatePersistsAcrossRepositoryRecreation() = runBlocking {
        val config = AppConfig(
            heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
            sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
            call = EventConfig("http://call", "PATCH", "text/xml", "call"),
        )

        repository.saveConfig(config)
        repository.setFaultState("broken", 123L)
        repository.setLogLastTrimAt(456L)

        repository = ConfigRepository(app)

        assertEquals(config, repository.getConfig())
        assertEquals(FaultState("broken", 123L), repository.getFaultState())
        assertEquals(456L, repository.getLogLastTrimAt())
    }

    @Test
    fun claimHeartbeatSlotOnlyClaimsWhenIntervalIsDue() = runBlocking {
        assertTrue(repository.claimHeartbeatSlot(now = 1_000L, intervalMillis = 1_800_000L))
        assertEquals(1_000L, repository.getHeartbeatLastAttemptAt())

        assertFalse(repository.claimHeartbeatSlot(now = 2_000L, intervalMillis = 1_800_000L))
        assertEquals(1_000L, repository.getHeartbeatLastAttemptAt())

        assertTrue(repository.claimHeartbeatSlot(now = 1_801_000L, intervalMillis = 1_800_000L))
        assertEquals(1_801_000L, repository.getHeartbeatLastAttemptAt())
    }
}
