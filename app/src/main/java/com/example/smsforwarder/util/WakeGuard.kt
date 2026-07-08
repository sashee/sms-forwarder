package com.example.smsforwarder.util

import android.content.Context
import android.os.PowerManager

/**
 * Runs [block] while a CPU wakelock is held, so time-based work (retry `delay`s) actually elapses
 * instead of freezing when the device would otherwise sleep. Side effect isolated behind an interface
 * so tests can run [block] with no real wakelock.
 */
interface WakeGuard {
    suspend fun <T> withWakeLock(reason: String, timeoutMillis: Long, block: suspend () -> T): T
}

class AndroidWakeGuard(context: Context) : WakeGuard {
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    override suspend fun <T> withWakeLock(reason: String, timeoutMillis: Long, block: suspend () -> T): T {
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsforwarder:$reason")
            .apply { setReferenceCounted(false) }
        // timeoutMillis is a safety backstop: even if release is somehow skipped, the OS drops the lock.
        lock.acquire(timeoutMillis)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}

object NoopWakeGuard : WakeGuard {
    override suspend fun <T> withWakeLock(reason: String, timeoutMillis: Long, block: suspend () -> T): T = block()
}
