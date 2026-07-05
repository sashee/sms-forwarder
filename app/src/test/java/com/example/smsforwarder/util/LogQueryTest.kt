package com.example.smsforwarder.util

import android.net.Uri
import com.example.smsforwarder.data.LogEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogQueryTest {
    private val logs = listOf(
        LogEntryEntity(id = 1, timestamp = 1_000L, text = "Heartbeat sending"),
        LogEntryEntity(id = 2, timestamp = 2_000L, text = "Queued SMS event"),
        LogEntryEntity(id = 3, timestamp = 3_000L, text = "Heartbeat completed"),
        LogEntryEntity(id = 4, timestamp = 4_000L, text = "Configuration saved"),
    )

    @Test
    fun emptyQueryReturnsWholeTableChronologically() {
        val result = applyLogQuery(logs.shuffled(), LogQuery())
        assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun sinceAndUntilAreInclusiveBounds() {
        val result = applyLogQuery(logs, LogQuery(since = 2_000L, until = 3_000L))
        assertEquals(listOf(2L, 3L), result.map { it.id })
    }

    @Test
    fun containsMatchesCaseInsensitively() {
        val result = applyLogQuery(logs, LogQuery(contains = "heartbeat"))
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun limitKeepsNewestButOutputsChronologically() {
        val result = applyLogQuery(logs, LogQuery(limit = 2))
        assertEquals(listOf(3L, 4L), result.map { it.id })
    }

    @Test
    fun fromUriParsesEpochMillisContainsAndLimit() {
        val query = LogQuery.fromUri(
            Uri.parse("content://com.example.smsforwarder.logs/logs?since=2000&until=3000&contains=SMS&limit=10"),
        )
        assertEquals(2_000L, query.since)
        assertEquals(3_000L, query.until)
        assertEquals("SMS", query.contains)
        assertEquals(10, query.limit)
    }

    @Test
    fun fromUriParsesIsoLocalAndOffsetTimestamps() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        try {
            val offset = LogQuery.parseTimestamp("1970-01-01T01:00:01+01:00")
            val local = LogQuery.parseTimestamp("1970-01-01T01:00:01")
            assertEquals(1_000L, offset)
            assertEquals(1_000L, local)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun fromUriIgnoresMissingAndBlankParams() {
        val query = LogQuery.fromUri(Uri.parse("content://com.example.smsforwarder.logs/logs?contains="))
        assertNull(query.since)
        assertNull(query.until)
        assertNull(query.contains)
        assertNull(query.limit)
    }
}
