package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.smsforwarder.SmsForwarderApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        CoroutineScope(Dispatchers.IO).launch {
            appContainer.configRepository.clearHeartbeatServiceSeenState()
            appContainer.configRepository.clearHeartbeatAlarmScheduledState()
            appContainer.scheduler.ensureRecurringWork()
            appContainer.eventRepository.addLog("Boot completed; rescheduling work")
            appContainer.scheduler.rescheduleQueuedEvents()
        }
    }
}
