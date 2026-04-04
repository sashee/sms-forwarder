package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.smsforwarder.SmsForwarderApp

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) {
            return
        }

        val pendingResult = goAsync()
        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleMessages(appContainer, messagesFromIntent(intent))
            } finally {
                pendingResult?.finish()
            }
        }
    }

    internal fun messagesFromIntent(intent: Intent): List<IncomingSms> = Telephony.Sms.Intents.getMessagesFromIntent(intent).filterNotNull().map {
        IncomingSms(
            number = it.displayOriginatingAddress.orEmpty(),
            text = it.messageBody.orEmpty(),
            timestamp = it.timestampMillis,
        )
    }

    internal suspend fun handleMessages(appContainer: com.example.smsforwarder.AppContainer, messages: List<IncomingSms>) {
        try {
            messages.forEach { smsMessage ->
                try {
                    val id = appContainer.eventRepository.enqueueSms(
                        number = smsMessage.number,
                        text = smsMessage.text,
                        timestamp = smsMessage.timestamp,
                    )
                    appContainer.scheduler.enqueueDelivery(id)
                    appContainer.eventRepository.addLog("Queued SMS event $id")
                } catch (error: Exception) {
                    val timestamp = System.currentTimeMillis()
                    appContainer.configRepository.setFaultState(
                        reason = "SMS enqueue failed: ${error.message ?: error::class.java.simpleName}",
                        timestamp = timestamp,
                    )
                }
            }
        } finally {
            appContainer.scheduler.ensureHeartbeatScheduled("sms", startServiceIfOverdue = true)
        }
    }

    internal data class IncomingSms(
        val number: String,
        val text: String,
        val timestamp: Long,
    )
}
