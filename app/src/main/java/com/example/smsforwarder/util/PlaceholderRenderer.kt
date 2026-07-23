package com.example.smsforwarder.util

import com.example.smsforwarder.model.EventType

object PlaceholderRenderer {
    fun render(
        type: EventType,
        template: String,
        number: String,
        text: String,
        timestampMillis: Long,
    ): String {
        val timestamp = TimeFormatter.toIsoUtc(timestampMillis)
        return when (type) {
            EventType.SMS -> template
                .replace("{{sms.number}}", number)
                .replace("{{sms.text}}", text)
                .replace("{{sms.timestamp}}", timestamp)

            EventType.CALL -> {
                val body = template
                    .replace("{{call.number}}", number)
                    .replace("{{call.timestamp}}", timestamp)
                appendLookupLink(body, number)
            }

            EventType.HEARTBEAT -> template
        }
    }

    /**
     * Hungarian caller-ID sites index numbers in national 06… form; the foreign subsite uses
     * digits only. Incoming numbers arrive as +36… ; private callers are blank (no link).
     */
    private fun lookupUrl(number: String): String? {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("+36")) {
            "https://www.kihivott.hu/telefonszam/06" +
                trimmed.removePrefix("+36").filter(Char::isDigit)
        } else {
            "https://kulfold.kihivott.hu/telefonszam/" + trimmed.filter(Char::isDigit)
        }
    }

    private fun appendLookupLink(body: String, number: String): String {
        val url = lookupUrl(number) ?: return body
        return if (body.isEmpty()) url else "$body\n$url"
    }
}
