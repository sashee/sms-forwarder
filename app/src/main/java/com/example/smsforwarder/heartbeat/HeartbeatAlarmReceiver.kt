package com.example.smsforwarder.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.util.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT_ALARM) {
            return
        }

        val pendingResult = goAsync()
        val appContainer = (context.applicationContext as SmsForwarderApp).appContainer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduledAt = appContainer.configRepository.getHeartbeatAlarmScheduledAt()
                appContainer.configRepository.clearHeartbeatAlarmScheduledAt()
                appContainer.eventRepository.addLog(
                    "Heartbeat recovery alarm fired (previously scheduled for ${TimeFormatter.toDebugLocal(scheduledAt)})",
                )
                HeartbeatSupervisor.run(
                    appContainer = appContainer,
                    reason = "alarm",
                    ensureService = true,
                    allowImmediateHeartbeat = true,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_HEARTBEAT_ALARM = "com.example.smsforwarder.action.HEARTBEAT_ALARM"
    }
}
