package com.example.smsforwarder.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {
    @Test
    fun formatsEpochInUtc() {
        assertEquals("1970-01-01T00:00:00Z", TimeFormatter.toIsoUtc(0))
    }

    @Test
    fun formatsArbitraryTimestampInUtc() {
        assertEquals("2023-11-14T22:13:20Z", TimeFormatter.toIsoUtc(1_700_000_000_000))
    }
}
