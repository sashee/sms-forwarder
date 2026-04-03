package com.example.smsforwarder.model

data class EventConfig(
    val url: String = "",
    val method: String = "POST",
    val contentType: String = "text/plain",
    val body: String = "",
)

data class AppConfig(
    val heartbeat: EventConfig = EventConfig(),
    val sms: EventConfig = EventConfig(),
    val call: EventConfig = EventConfig(),
)

data class FaultState(
    val reason: String,
    val timestamp: Long,
)

data class LogEntry(
    val timestamp: Long,
    val text: String,
)
