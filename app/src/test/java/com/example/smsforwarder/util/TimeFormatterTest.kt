package com.example.smsforwarder.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TimeFormatterTest {
    @Test
    fun formatsEpochInUtc() {
        assertEquals("1970-01-01T00:00:00Z", TimeFormatter.toIsoUtc(0))
    }

    @Test
    fun formatsArbitraryTimestampInUtc() {
        assertEquals("2023-11-14T22:13:20Z", TimeFormatter.toIsoUtc(1_700_000_000_000))
    }

    @Test
    fun formatsEpochInLocalTimezone() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        try {
            assertEquals("1970-01-01T01:00:00+01:00", TimeFormatter.toIsoLocal(0))
            assertEquals("1970-01-01T01:00:00+01:00 (0)", TimeFormatter.toDebugLocal(0))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun formatsNullDebugTimestampAsNone() {
        assertEquals("none", TimeFormatter.toDebugLocal(null))
    }
}
