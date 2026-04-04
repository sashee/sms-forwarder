package com.example.smsforwarder

import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.testing.TestAppContainer
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
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
class SmsForwarderAppTest {
    @Before
    fun setUp() {
        TestEnvironment.reset()
        TestEnvironment.containerFactory = { context -> TestAppContainer(context) }
    }

    @After
    fun tearDown() {
        TestEnvironment.reset()
    }

    @Test
    fun appStartupDoesNotAutomaticallyArmRecurringHeartbeatWork() {
        val app = ApplicationProvider.getApplicationContext<TestSmsForwarderApp>()
        val container = app.appContainer

        assertTrue(container is TestAppContainer)
        assertEquals(0, (container as TestAppContainer).scheduler.heartbeatScheduledCount)
        container.database.close()
    }
}
