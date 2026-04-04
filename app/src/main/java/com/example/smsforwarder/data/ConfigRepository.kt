package com.example.smsforwarder.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smsforwarder.model.AppConfig
import com.example.smsforwarder.model.EventConfig
import com.example.smsforwarder.model.FaultState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app-config")

class ConfigRepository(private val context: Context) {
    val configFlow: Flow<AppConfig> = context.dataStore.data.map { preferences ->
        AppConfig(
            heartbeat = preferences.toEventConfig("heartbeat"),
            sms = preferences.toEventConfig("sms"),
            call = preferences.toEventConfig("call"),
        )
    }

    val faultStateFlow: Flow<FaultState?> = context.dataStore.data.map { preferences ->
        val reason = preferences[stringPreferencesKey("fault.reason")] ?: return@map null
        val timestamp = preferences[longPreferencesKey("fault.timestamp")] ?: return@map null
        FaultState(reason = reason, timestamp = timestamp)
    }

    suspend fun getConfig(): AppConfig = configFlow.map { it }.firstValue()

    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { preferences ->
            preferences.putEventConfig("heartbeat", config.heartbeat)
            preferences.putEventConfig("sms", config.sms)
            preferences.putEventConfig("call", config.call)
        }
    }

    suspend fun setFaultState(reason: String, timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("fault.reason")] = reason
            preferences[longPreferencesKey("fault.timestamp")] = timestamp
        }
    }

    suspend fun clearFaultState() {
        context.dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey("fault.reason"))
            preferences.remove(longPreferencesKey("fault.timestamp"))
        }
    }

    suspend fun getFaultState(): FaultState? = faultStateFlow.map { it }.firstValue()

    val callScreeningSeenAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("call_screening.last_seen_at")]
    }

    val telephonyCallSeenAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("telephony_call.last_seen_at")]
    }

    suspend fun setCallScreeningSeenAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("call_screening.last_seen_at")] = timestamp
        }
    }

    suspend fun clearCallScreeningSeenAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("call_screening.last_seen_at"))
        }
    }

    suspend fun getCallScreeningSeenAt(): Long? = callScreeningSeenAtFlow.firstValue()

    suspend fun setTelephonyCallSeenAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("telephony_call.last_seen_at")] = timestamp
        }
    }

    suspend fun clearTelephonyCallSeenAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("telephony_call.last_seen_at"))
        }
    }

    suspend fun getTelephonyCallSeenAt(): Long? = telephonyCallSeenAtFlow.firstValue()

    val heartbeatLastAttemptAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("heartbeat.last_attempt_at")]
    }

    val heartbeatLastSuccessAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("heartbeat.last_success_at")]
    }

    val heartbeatServiceSeenAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("heartbeat.service_seen_at")]
    }

    val heartbeatAlarmScheduledAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("heartbeat.alarm_scheduled_at")]
    }

    val logLastTrimAtFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[longPreferencesKey("logs.last_trim_at")]
    }

    suspend fun setHeartbeatLastAttemptAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("heartbeat.last_attempt_at")] = timestamp
        }
    }

    suspend fun clearHeartbeatLastAttemptAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("heartbeat.last_attempt_at"))
        }
    }

    suspend fun getHeartbeatLastAttemptAt(): Long? = heartbeatLastAttemptAtFlow.firstValue()

    suspend fun setHeartbeatLastSuccessAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("heartbeat.last_success_at")] = timestamp
        }
    }

    suspend fun clearHeartbeatLastSuccessAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("heartbeat.last_success_at"))
        }
    }

    suspend fun getHeartbeatLastSuccessAt(): Long? = heartbeatLastSuccessAtFlow.firstValue()

    suspend fun setHeartbeatServiceSeenAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("heartbeat.service_seen_at")] = timestamp
        }
    }

    suspend fun clearHeartbeatServiceSeenAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("heartbeat.service_seen_at"))
        }
    }

    suspend fun getHeartbeatServiceSeenAt(): Long? = heartbeatServiceSeenAtFlow.firstValue()

    suspend fun setHeartbeatAlarmScheduledAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("heartbeat.alarm_scheduled_at")] = timestamp
        }
    }

    suspend fun clearHeartbeatAlarmScheduledAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("heartbeat.alarm_scheduled_at"))
        }
    }

    suspend fun getHeartbeatAlarmScheduledAt(): Long? = heartbeatAlarmScheduledAtFlow.firstValue()

    suspend fun setLogLastTrimAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("logs.last_trim_at")] = timestamp
        }
    }

    suspend fun clearLogLastTrimAt() {
        context.dataStore.edit { preferences ->
            preferences.remove(longPreferencesKey("logs.last_trim_at"))
        }
    }

    suspend fun getLogLastTrimAt(): Long? = logLastTrimAtFlow.firstValue()

    private fun Preferences.toEventConfig(prefix: String): EventConfig = EventConfig(
        url = this[stringPreferencesKey("$prefix.url")] ?: "",
        method = this[stringPreferencesKey("$prefix.method")] ?: "POST",
        contentType = this[stringPreferencesKey("$prefix.content_type")] ?: "text/plain",
        body = this[stringPreferencesKey("$prefix.body")] ?: "",
    )

    private fun MutablePreferences.putEventConfig(prefix: String, config: EventConfig) {
        this[stringPreferencesKey("$prefix.url")] = config.url
        this[stringPreferencesKey("$prefix.method")] = config.method
        this[stringPreferencesKey("$prefix.content_type")] = config.contentType.ifBlank { "text/plain" }
        this[stringPreferencesKey("$prefix.body")] = config.body
    }
}

private suspend fun <T> Flow<T>.firstValue(): T = first()
