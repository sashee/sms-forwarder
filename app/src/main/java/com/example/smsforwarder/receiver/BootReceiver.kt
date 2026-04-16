package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.work.QueuedEventProcessor

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val pendingResult = goAsync()
        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.configRepository.clearHeartbeatServiceSeenState()
                appContainer.configRepository.clearHeartbeatAlarmScheduledState()
                appContainer.eventRepository.addLog("Boot completed; draining queued events")
                val deliveredCount = QueuedEventProcessor.drainAll(appContainer)
                appContainer.eventRepository.addLog("Boot drain processed $deliveredCount queued event(s)")
                appContainer.scheduler.ensureRecurringWork()
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
