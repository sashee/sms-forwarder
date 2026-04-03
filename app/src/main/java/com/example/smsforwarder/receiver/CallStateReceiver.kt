package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.telecom.CallEventHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) {
            return
        }
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) {
            return
        }

        val pendingResult = goAsync()
        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        val timestamp = System.currentTimeMillis()
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleIncomingCall(appContainer, number, timestamp)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    internal suspend fun handleIncomingCall(appContainer: com.example.smsforwarder.AppContainer, number: String, timestamp: Long) {
        CallEventHandler.handleIncomingCall(appContainer, number, timestamp, CallEventHandler.Source.TELEPHONY)
    }
}
