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

            EventType.CALL -> template
                .replace("{{call.number}}", number)
                .replace("{{call.timestamp}}", timestamp)

            EventType.HEARTBEAT -> template
        }
    }
}
