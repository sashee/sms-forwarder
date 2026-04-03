package com.example.smsforwarder.work

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDeliveryWorkerUnitTest {
    @Test
    fun retryDelayStartsAtFifteenMinutes() {
        assertEquals(15L * 60_000L, EventDeliveryWorker.retryDelayMillisForAttempt(1))
    }

    @Test
    fun retryDelayDoublesUntilCapped() {
        assertEquals(30L * 60_000L, EventDeliveryWorker.retryDelayMillisForAttempt(2))
        assertEquals(60L * 60_000L, EventDeliveryWorker.retryDelayMillisForAttempt(3))
    }

    @Test
    fun retryDelayCapsAtOneDay() {
        assertEquals(24L * 60L * 60L * 1000L, EventDeliveryWorker.retryDelayMillisForAttempt(20))
    }
}
