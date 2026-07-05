package com.example.smsforwarder.util

import android.net.Uri
import com.example.smsforwarder.data.LogEntryEntity
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Pure filter model for on-demand log export requests.
 *
 * All fields are optional; a null field is not applied, so an empty query returns the full table.
 * [since]/[until] are inclusive epoch-millis bounds; [limit] keeps the newest N entries after the
 * time/substring filters are applied.
 */
data class LogQuery(
    val since: Long? = null,
    val until: Long? = null,
    val contains: String? = null,
    val limit: Int? = null,
) {
    companion object {
        fun fromUri(uri: Uri): LogQuery = LogQuery(
            since = uri.getQueryParameter("since").let(::parseTimestamp),
            until = uri.getQueryParameter("until").let(::parseTimestamp),
            contains = uri.getQueryParameter("contains")?.takeIf { it.isNotBlank() },
            limit = uri.getQueryParameter("limit")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
        )

        /** Accepts raw epoch millis, an ISO offset datetime, or an ISO local datetime (system zone). */
        internal fun parseTimestamp(raw: String?): Long? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return it }
            runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            runCatching {
                return LocalDateTime.parse(value)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            return null
        }
    }
}

fun applyLogQuery(logs: List<LogEntryEntity>, query: LogQuery): List<LogEntryEntity> =
    logs.asSequence()
        .filter { query.since == null || it.timestamp >= query.since }
        .filter { query.until == null || it.timestamp <= query.until }
        .filter { query.contains == null || it.text.contains(query.contains, ignoreCase = true) }
        .sortedWith(compareByDescending<LogEntryEntity> { it.timestamp }.thenByDescending { it.id })
        .let { if (query.limit != null) it.take(query.limit) else it }
        .sortedWith(compareBy<LogEntryEntity> { it.timestamp }.thenBy { it.id })
        .toList()
