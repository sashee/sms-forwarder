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

    @Test
    fun rendersCallPlaceholdersAndPrivateNumberAsEmpty() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.CALL,
            template = "from={{call.number}} at={{call.timestamp}}",
            number = "",
            text = "ignored",
            timestampMillis = 1_234L,
        )

        assertEquals("from= at=1970-01-01T00:00:01.234Z", rendered)
    }

    @Test
    fun appendsHungarianLookupLinkForCall() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.CALL,
            template = "from={{call.number}}",
            number = "+3616464734",
            text = "",
            timestampMillis = 0,
        )

        assertEquals(
            "from=+3616464734\nhttps://www.kihivott.hu/telefonszam/0616464734",
            rendered,
        )
    }

    @Test
    fun appendsForeignLookupLinkForCall() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.CALL,
            template = "from={{call.number}}",
            number = "+48699741575",
            text = "",
            timestampMillis = 0,
        )

        assertEquals(
            "from=+48699741575\nhttps://kulfold.kihivott.hu/telefonszam/48699741575",
            rendered,
        )
    }

    @Test
    fun emptyCallBodyRendersLookupLinkOnly() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.CALL,
            template = "",
            number = "+3616464734",
            text = "",
            timestampMillis = 0,
        )

        assertEquals("https://www.kihivott.hu/telefonszam/0616464734", rendered)
    }

    @Test
    fun leavesHeartbeatBodyUnchanged() {
        val rendered = PlaceholderRenderer.render(
            type = EventType.HEARTBEAT,
            template = "alive {{sms.text}} {{call.number}}",
            number = "+1555",
            text = "hello",
            timestampMillis = 0,
        )

        assertEquals("alive {{sms.text}} {{call.number}}", rendered)
    }
}
