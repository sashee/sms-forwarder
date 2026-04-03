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
        val response = rejectionResponse()

        CoroutineScope(Dispatchers.IO).launch {
            handleCall(appContainer, number, timestamp, "screening")
            try {
                respond(callDetails, response)
                appContainer.eventRepository.addLog("Rejected call")
            } catch (error: Exception) {
                appContainer.eventRepository.addLog("Call reject failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    internal suspend fun handleCall(
        appContainer: com.example.smsforwarder.AppContainer,
        number: String,
        timestamp: Long,
        source: String,
    ) {
        val callSource = when (source) {
            "telephony" -> CallEventHandler.Source.TELEPHONY
            else -> CallEventHandler.Source.SCREENING
        }
        CallEventHandler.handleIncomingCall(appContainer, number, timestamp, callSource)
    }

    internal fun rejectionResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(true)
        .setRejectCall(true)
        .setSkipCallLog(true)
        .setSkipNotification(true)
        .build()

    internal fun respond(callDetails: Call.Details, response: CallResponse) {
        respondToCall(callDetails, response)
    }
}
