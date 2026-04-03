package com.example.smsforwarder

import android.Manifest
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.installTestContainer
import com.example.smsforwarder.testing.testAppContainer
import com.example.smsforwarder.testing.waitFor
import com.example.smsforwarder.util.TimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class MainActivityBehaviorTest {
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
    fun bindsConfigAndStatusesOnLaunch() = runBlocking {
        val container = testAppContainer()
        container.configRepository.saveConfig(
            AppConfig(
                heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
                sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
                call = EventConfig("http://call", "PATCH", "text/xml", "call"),
            ),
        )
        container.configRepository.setCallScreeningSeenAt(System.currentTimeMillis())
        ShadowApplication.getInstance().grantPermissions(Manifest.permission.RECEIVE_SMS)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        assertEquals("http://heartbeat", activity.findViewById<EditText>(R.id.heartbeatUrl).text.toString())
        assertEquals("http://sms", activity.findViewById<EditText>(R.id.smsUrl).text.toString())
        assertEquals("SMS permission: OK", activity.findViewById<TextView>(R.id.statusSms).text.toString())
        assertEquals("Call screening enabled: OK", activity.findViewById<TextView>(R.id.statusCallScreening).text.toString())
        assertEquals("Battery optimization exemption: NOK", activity.findViewById<TextView>(R.id.statusBattery).text.toString())
    }

    @Test
    fun clearLogsAndLaunchButtonsWork() = runBlocking {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()
        testAppContainer().eventRepository.addLog("hello", 1L)
        Robolectric.flushForegroundThreadScheduler()

        waitFor {
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<TextView>(R.id.logText).text.toString() == "${TimeFormatter.toIsoUtc(1L)}: hello"
        }
        assertEquals("${TimeFormatter.toIsoUtc(1L)}: hello", activity.findViewById<TextView>(R.id.logText).text.toString())

        activity.findViewById<android.widget.Button>(R.id.buttonClearLogs).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        waitFor { runBlocking { testAppContainer().eventRepository.observeLogs().first().isEmpty() } }

        assertTrue(testAppContainer().eventRepository.observeLogs().first().isEmpty())

        activity.findViewById<android.widget.Button>(R.id.buttonBattery).performClick()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shadowOf(activity).nextStartedActivity.action)

        activity.findViewById<android.widget.Button>(R.id.buttonCallSettings).performClick()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }
}
