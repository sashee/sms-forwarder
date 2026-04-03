package com.example.smsforwarder.data

import com.example.smsforwarder.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomConvertersTest {
    private val converters = RoomConverters()

    @Test
    fun roundTripsAllEventTypes() {
        EventType.entries.forEach { type ->
            assertEquals(type, converters.toEventType(converters.fromEventType(type)))
        }
    }
}
