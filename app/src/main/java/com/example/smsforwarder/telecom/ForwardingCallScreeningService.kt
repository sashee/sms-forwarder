package com.example.smsforwarder.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.smsforwarder.SmsForwarderApp

class ForwardingCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val timestamp = System.currentTimeMillis()
        val appContainer = (applicationContext as SmsForwarderApp).appContainer

        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.configRepository.setCallScreeningSeenAt(timestamp)
                val id = appContainer.eventRepository.enqueueCall(number, timestamp)
                appContainer.scheduler.enqueueDelivery(id)
                appContainer.eventRepository.addLog("Queued call event $id")
            } catch (error: Exception) {
                appContainer.configRepository.setFaultState(
                    reason = "Call enqueue failed: ${error.message ?: error::class.java.simpleName}",
                    timestamp = timestamp,
                )
            }
        }

        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(true)
                .setSkipNotification(true)
                .build(),
        )
    }
}
