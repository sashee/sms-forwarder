package com.example.smsforwarder.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeFormatter {
    private val utcFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    private val localFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun toIsoUtc(timestampMillis: Long): String = utcFormatter.format(Instant.ofEpochMilli(timestampMillis))

    fun toIsoLocal(timestampMillis: Long): String = localFormatter.format(
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
    )

    fun toDebugLocal(timestampMillis: Long?): String {
        if (timestampMillis == null) {
            return "none"
        }
        return "${toIsoLocal(timestampMillis)} ($timestampMillis)"
    }
}
