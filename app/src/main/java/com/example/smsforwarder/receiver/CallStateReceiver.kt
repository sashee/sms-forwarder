package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.telecom.CallEventHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) {
            return
        }

        val timestamp = System.currentTimeMillis()
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val number = intent.getStringExtra(EXTRA_INCOMING_NUMBER).orEmpty()
        val extrasDump = extrasToLog(intent.extras)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.eventRepository.addLog(
                    "Telephony broadcast: action=${intent.action}, state=$state, number=$number, extras=$extrasDump",
                    timestamp,
                )
                if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                    return@launch
                }
                handleIncomingCall(appContainer, number, timestamp)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    internal suspend fun handleIncomingCall(appContainer: com.example.smsforwarder.AppContainer, number: String, timestamp: Long) {
        CallEventHandler.handleIncomingCall(appContainer, number, timestamp, CallEventHandler.Source.TELEPHONY)
    }

    private fun extrasToLog(extras: Bundle?): String {
        if (extras == null || extras.isEmpty) {
            return "{}"
        }
        @Suppress("DEPRECATION")
        return extras.keySet()
            .sorted()
            .joinToString(prefix = "{", postfix = "}") { key -> "$key=${extras.get(key)}" }
    }

    companion object {
        private const val EXTRA_INCOMING_NUMBER = "incoming_number"
    }
}
