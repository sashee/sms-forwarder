package com.example.smsforwarder

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
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
        container.configRepository.setTelephonyCallSeenAt(System.currentTimeMillis())
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
        )

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        assertEquals("http://heartbeat", activity.findViewById<EditText>(R.id.heartbeatUrl).text.toString())
        assertEquals("http://sms", activity.findViewById<EditText>(R.id.smsUrl).text.toString())
        assertEquals("SMS permission: OK", activity.findViewById<TextView>(R.id.statusSms).text.toString())
        assertEquals("Call screening enabled: OK", activity.findViewById<TextView>(R.id.statusCallScreening).text.toString())
        assertEquals("Telephony fallback seen: OK", activity.findViewById<TextView>(R.id.statusTelephonyFallback).text.toString())
        assertEquals("Phone permissions: OK", activity.findViewById<TextView>(R.id.statusPhone).text.toString())
        assertEquals("Battery optimization exemption: NOK", activity.findViewById<TextView>(R.id.statusBattery).text.toString())
        assertEquals(
            activity.getString(R.string.heartbeat_body_example),
            activity.findViewById<TextView>(R.id.heartbeatBodyExample).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.sms_body_example),
            activity.findViewById<TextView>(R.id.smsBodyExample).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.call_body_example),
            activity.findViewById<TextView>(R.id.callBodyExample).text.toString(),
        )
    }

    @Test
    fun showsBatteryOkWhenIgnoringOptimizations() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val powerManager = app.getSystemService(PowerManager::class.java)
        shadowOf(powerManager).setIgnoringBatteryOptimizations(app.packageName, true)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        assertEquals("Battery optimization exemption: OK", activity.findViewById<TextView>(R.id.statusBattery).text.toString())
    }

    @Test
    fun showsNokStatusesWhenRequirementsAreMissing() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        assertEquals("SMS permission: NOK", activity.findViewById<TextView>(R.id.statusSms).text.toString())
        assertEquals("Call screening enabled: NOK", activity.findViewById<TextView>(R.id.statusCallScreening).text.toString())
        assertEquals("Telephony fallback seen: NOK", activity.findViewById<TextView>(R.id.statusTelephonyFallback).text.toString())
        assertEquals("Phone permissions: NOK", activity.findViewById<TextView>(R.id.statusPhone).text.toString())
        assertEquals("Battery optimization exemption: NOK", activity.findViewById<TextView>(R.id.statusBattery).text.toString())
    }

    @Test
    fun savePersistsConfigAndLogs() = runBlocking {
        val container = testAppContainer()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        activity.findViewById<EditText>(R.id.heartbeatUrl).setText(" http://heartbeat ")
        activity.findViewById<EditText>(R.id.heartbeatMethod).setText("")
        activity.findViewById<EditText>(R.id.heartbeatContentType).setText("")
        activity.findViewById<EditText>(R.id.heartbeatBody).setText("hb")
        activity.findViewById<EditText>(R.id.smsUrl).setText("http://sms")
        activity.findViewById<EditText>(R.id.smsMethod).setText("PUT")
        activity.findViewById<EditText>(R.id.smsContentType).setText("application/json")
        activity.findViewById<EditText>(R.id.smsBody).setText("sms")
        activity.findViewById<EditText>(R.id.callUrl).setText("http://call")
        activity.findViewById<EditText>(R.id.callMethod).setText("PATCH")
        activity.findViewById<EditText>(R.id.callContentType).setText("text/xml")
        activity.findViewById<EditText>(R.id.callBody).setText("call")

        activity.findViewById<Button>(R.id.buttonSave).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        waitFor {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking {
                container.configRepository.getConfig() == AppConfig(
                    heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
                    sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
                    call = EventConfig("http://call", "PATCH", "text/xml", "call"),
                )
            }
        }

        assertEquals(
            AppConfig(
                heartbeat = EventConfig("http://heartbeat", "POST", "text/plain", "hb"),
                sms = EventConfig("http://sms", "PUT", "application/json", "sms"),
                call = EventConfig("http://call", "PATCH", "text/xml", "call"),
            ),
            container.configRepository.getConfig(),
        )

        waitFor {
            shadowOf(Looper.getMainLooper()).idle()
            container.scheduler.heartbeatScheduledCount == 1
        }
        waitFor {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { container.eventRepository.observeLogs().first().firstOrNull()?.text == "Configuration saved" }
        }

        assertEquals(1, container.scheduler.heartbeatScheduledCount)
        assertEquals("Configuration saved", container.eventRepository.observeLogs().first().first().text)
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

    @Test
    fun logViewShowsOnlyLatestHundredEntries() = runBlocking {
        val container = testAppContainer()
        repeat(105) { index ->
            container.eventRepository.addLog("log-$index", index.toLong())
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        waitFor {
            shadowOf(Looper.getMainLooper()).idle()
            val lines = activity.findViewById<TextView>(R.id.logText).text.toString().lines().filter { it.isNotBlank() }
            lines.size == 100
        }

        val renderedLines = activity.findViewById<TextView>(R.id.logText).text.toString().lines().filter { it.isNotBlank() }
        assertTrue(renderedLines.any { it.endsWith(": log-104") })
        assertTrue(renderedLines.any { it.endsWith(": log-5") })
        assertTrue(renderedLines.none { it.endsWith(": log-4") })
    }

    @Test
    fun smsPermissionButtonRequestsReceiveSmsPermission() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        activity.findViewById<Button>(R.id.buttonRequestSms).performClick()

        val permissionsRequest = shadowOf(activity)
            .javaClass
            .getMethod("getLastRequestedPermission")
            .invoke(shadowOf(activity))
        val permissionsField = permissionsRequest.javaClass.getDeclaredField("requestedPermissions").apply {
            isAccessible = true
        }
        val requestedPermissions = permissionsField.get(permissionsRequest) as Array<*>
        assertEquals(listOf(Manifest.permission.RECEIVE_SMS), requestedPermissions.toList())
    }

    @Test
    fun phonePermissionButtonRequestsPhoneAndCallLogPermissions() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().resume().get()

        activity.findViewById<Button>(R.id.buttonRequestPhone).performClick()

        val permissionsRequest = shadowOf(activity)
            .javaClass
            .getMethod("getLastRequestedPermission")
            .invoke(shadowOf(activity))
        val permissionsField = permissionsRequest.javaClass.getDeclaredField("requestedPermissions").apply {
            isAccessible = true
        }
        val requestedPermissions = permissionsField.get(permissionsRequest) as Array<*>
        assertEquals(
            listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG),
            requestedPermissions.toList(),
        )
    }
}
