package com.example.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.HorizontalScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.util.UiLogFormatter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val appContainer by lazy { (application as SmsForwarderApp).appContainer }

    private val requestSmsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshStatus()
    }
    private val requestPhonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.buttonRequestSms).setOnClickListener {
            requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
        }
        findViewById<Button>(R.id.buttonRequestPhone).setOnClickListener {
            requestPhonePermissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG))
        }
        findViewById<Button>(R.id.buttonCallSettings).setOnClickListener {
            openCallScreeningSettings()
        }
        findViewById<Button>(R.id.buttonBattery).setOnClickListener {
            requestBatteryOptimizationExemption()
        }
        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            saveConfig()
        }
        findViewById<Button>(R.id.buttonClearLogs).setOnClickListener {
            lifecycleScope.launch {
                appContainer.eventRepository.clearLogs()
            }
        }

        lifecycleScope.launch {
            appContainer.configRepository.configFlow.collect { config ->
                bindConfig(config)
            }
        }

        lifecycleScope.launch {
            appContainer.eventRepository.observeLogs().collect { logs ->
                val horizontalScroll = findViewById<HorizontalScrollView>(R.id.logHorizontalScroll)
                val preservedScrollX = horizontalScroll.scrollX
                findViewById<TextView>(R.id.logText).text = UiLogFormatter.format(logs)
                horizontalScroll.post { horizontalScroll.scrollTo(preservedScrollX, 0) }
                findViewById<ScrollView>(R.id.logVerticalScroll).post {
                    findViewById<ScrollView>(R.id.logVerticalScroll).scrollTo(0, 0)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        lifecycleScope.launch {
            appContainer.scheduler.ensureHeartbeatScheduled("activity", startServiceIfOverdue = true)
        }
    }

    private fun refreshStatus() {
        val smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val callLogGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true

        findViewById<TextView>(R.id.statusSms).text = "SMS permission: ${statusText(smsGranted)}"
        findViewById<TextView>(R.id.statusPhone).text = "Phone permissions: ${statusText(phoneGranted && callLogGranted)}"
        findViewById<TextView>(R.id.statusBattery).text = "Battery optimization exemption: ${statusText(ignoringOptimizations)}"

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val screeningSeenAt = appContainer.configRepository.getCallScreeningSeenAt()
            val telephonySeenAt = appContainer.configRepository.getTelephonyCallSeenAt()
            val callScreeningEnabled = screeningSeenAt != null && (now - screeningSeenAt) < CALL_STATUS_WINDOW_MILLIS
            val telephonyFallbackEnabled = telephonySeenAt != null && (now - telephonySeenAt) < CALL_STATUS_WINDOW_MILLIS
            findViewById<TextView>(R.id.statusCallScreening).text = "Call screening enabled: ${statusText(callScreeningEnabled)}"
            findViewById<TextView>(R.id.statusTelephonyFallback).text = "Telephony fallback seen: ${statusText(telephonyFallbackEnabled)}"
        }
    }

    private fun saveConfig() {
        lifecycleScope.launch {
            val config = AppConfig(
                heartbeat = readEventConfig(R.id.heartbeatUrl, R.id.heartbeatMethod, R.id.heartbeatContentType, R.id.heartbeatBody),
                sms = readEventConfig(R.id.smsUrl, R.id.smsMethod, R.id.smsContentType, R.id.smsBody),
                call = readEventConfig(R.id.callUrl, R.id.callMethod, R.id.callContentType, R.id.callBody),
            )
            appContainer.configRepository.saveConfig(config)
            appContainer.scheduler.ensureRecurringWork()
            appContainer.eventRepository.addLog("Configuration saved")
        }
    }

    private fun bindConfig(config: AppConfig) {
        bindEventConfig(config.heartbeat, R.id.heartbeatUrl, R.id.heartbeatMethod, R.id.heartbeatContentType, R.id.heartbeatBody)
        bindEventConfig(config.sms, R.id.smsUrl, R.id.smsMethod, R.id.smsContentType, R.id.smsBody)
        bindEventConfig(config.call, R.id.callUrl, R.id.callMethod, R.id.callContentType, R.id.callBody)
    }

    private fun bindEventConfig(config: EventConfig, urlId: Int, methodId: Int, contentTypeId: Int, bodyId: Int) {
        setIfChanged(urlId, config.url)
        setIfChanged(methodId, config.method)
        setIfChanged(contentTypeId, config.contentType)
        setIfChanged(bodyId, config.body)
    }

    private fun setIfChanged(viewId: Int, value: String) {
        val editText = findViewById<EditText>(viewId)
        if (editText.text.toString() != value) {
            editText.setText(value)
        }
    }

    private fun readEventConfig(urlId: Int, methodId: Int, contentTypeId: Int, bodyId: Int): EventConfig = EventConfig(
        url = findViewById<EditText>(urlId).text.toString().trim(),
        method = findViewById<EditText>(methodId).text.toString().trim().ifBlank { "POST" },
        contentType = findViewById<EditText>(contentTypeId).text.toString().trim().ifBlank { "text/plain" },
        body = findViewById<EditText>(bodyId).text.toString(),
    )

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openCallScreeningSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        startActivity(intent)
    }

    private fun statusText(ok: Boolean): String = if (ok) getString(R.string.status_ok) else getString(R.string.status_nok)

    companion object {
        private const val CALL_STATUS_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
