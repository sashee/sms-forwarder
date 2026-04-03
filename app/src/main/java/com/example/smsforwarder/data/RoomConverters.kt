package com.example.smsforwarder.data

import androidx.room.TypeConverter
import com.example.smsforwarder.model.EventType

class RoomConverters {
    @TypeConverter
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): EventType = EventType.valueOf(value)
}
