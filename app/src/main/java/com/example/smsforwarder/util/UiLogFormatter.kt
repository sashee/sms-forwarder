package com.example.smsforwarder.util

import com.example.smsforwarder.data.LogEntryEntity
import java.util.Locale

object UiLogFormatter {
    data class Row(
        val id: Long,
        val delta: String,
        val timestamp: String,
        val message: String,
    )

    fun format(logs: List<LogEntryEntity>): String {
        return rows(logs).joinToString("\n") { "${it.delta}  ${it.timestamp}  ${it.message}" }
    }

    fun rows(logs: List<LogEntryEntity>): List<Row> {
        val orderedLogs = logs.sortedWith(compareByDescending<LogEntryEntity> { it.timestamp }.thenByDescending { it.id })
        return orderedLogs.mapIndexed { index, log ->
            val previousTimestamp = orderedLogs.getOrNull(index - 1)?.timestamp
            toRow(log, previousTimestamp)
        }
    }

    fun formatLine(log: LogEntryEntity, previousTimestamp: Long?): String {
        val row = toRow(log, previousTimestamp)
        return "${row.delta}  ${row.timestamp}  ${row.message}"
    }

    private fun toRow(log: LogEntryEntity, previousTimestamp: Long?): Row {
        val deltaSeconds = previousTimestamp
            ?.let { kotlin.math.abs(log.timestamp - it) / 1000L }
            ?: 0L
        val sanitizedMessage = log.text.replace("\r", " ").replace("\n", " ")
        return Row(
            id = log.id,
            delta = String.format(Locale.US, "%-6s", "+${deltaSeconds}s"),
            timestamp = TimeFormatter.toIsoLocal(log.timestamp),
            message = sanitizedMessage,
        )
    }
}
