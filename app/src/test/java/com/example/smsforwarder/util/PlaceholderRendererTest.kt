package com.example.smsforwarder.util

import com.example.smsforwarder.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderRendererTest {
    @Test
    fun rendersSmsPlaceholders() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.SMS,
            template = "from={{sms.number}} body={{sms.text}} at={{sms.timestamp}}",
            number = "+15551234",
            text = "hello",
            timestampMillis = 0,
        )

        assertEquals("from=+15551234 body=hello at=1970-01-01T00:00:00Z", rendered)
    }

    @Test
    fun leavesUnknownPlaceholdersUnchanged() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.CALL,
            template = "{{call.number}} {{unknown}}",
            number = "",
            text = "",
            timestampMillis = 0,
        )

        assertEquals(" {{unknown}}", rendered)
    }
}
