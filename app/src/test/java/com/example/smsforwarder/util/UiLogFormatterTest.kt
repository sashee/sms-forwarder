package com.example.smsforwarder.util

import com.example.smsforwarder.data.LogEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class UiLogFormatterTest {
    @Test
    fun formatsAlignedLocalLogLinesWithDeltaSeconds() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        try {
            val formatted = UiLogFormatter.format(
                listOf(
                    LogEntryEntity(id = 2, timestamp = 1_500L, text = "second"),
                    LogEntryEntity(id = 1, timestamp = 0L, text = "first"),
                ),
            )

            val lines = formatted.lines()
            assertEquals("+0s     1970-01-01T01:00:01.5+01:00  second", lines[0])
            assertEquals("+1s     1970-01-01T01:00:00+01:00  first", lines[1])
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun replacesEmbeddedNewlinesInMessages() {
        val line = UiLogFormatter.formatLine(
            LogEntryEntity(id = 1, timestamp = 0L, text = "a\nb\rc"),
            previousTimestamp = null,
        )

        assertTrue(line.endsWith("a b c"))
    }
}
