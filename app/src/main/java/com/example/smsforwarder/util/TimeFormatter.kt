package com.example.smsforwarder.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeFormatter {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun toIsoUtc(timestampMillis: Long): String = formatter.format(Instant.ofEpochMilli(timestampMillis))
}
