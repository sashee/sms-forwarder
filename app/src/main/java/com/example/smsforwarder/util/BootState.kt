package com.example.smsforwarder.util

import android.content.Context
import android.provider.Settings

object BootState {
    fun currentBootCount(context: Context): Long? {
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return bootCount.takeIf { it >= 0 }?.toLong()
    }
}
