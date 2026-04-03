package com.example.smsforwarder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.model.EventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun queueDaoPersistsAndOrdersEntries() = runBlocking {
        val queueDao = database.queueDao()
        val firstId = queueDao.insert(baseEvent(nextAttemptAt = 200L))
        val secondId = queueDao.insert(baseEvent(nextAttemptAt = 100L, eventId = "second"))

        val ordered = queueDao.getAll()
        assertEquals(listOf(secondId, firstId), ordered.map { it.id })

        queueDao.updateAttempt(firstId, 5, 300L)
        assertEquals(5, queueDao.getById(firstId)?.attemptCount)

        queueDao.deleteById(secondId)
        assertNull(queueDao.getById(secondId))

        queueDao.deleteAll()
        assertEquals(emptyList<QueuedEventEntity>(), queueDao.getAll())
    }

    @Test
    fun logDaoReturnsLatestFirstAndClears() = runBlocking {
        val logDao = database.logDao()
        logDao.insert(LogEntryEntity(timestamp = 1L, text = "one"))
        logDao.insert(LogEntryEntity(timestamp = 2L, text = "two"))
        logDao.insert(LogEntryEntity(timestamp = 3L, text = "three"))

        assertEquals(listOf("three", "two", "one"), logDao.observeLatest(10).first().map { it.text })
        assertEquals(listOf("three", "two"), logDao.observeLatest(2).first().map { it.text })

        logDao.deleteAll()
        assertEquals(emptyList<LogEntryEntity>(), logDao.observeLatest(10).first())
    }

    private fun baseEvent(
        nextAttemptAt: Long,
        eventId: String = "first",
    ) = QueuedEventEntity(
        eventId = eventId,
        type = EventType.SMS,
        number = "+1555",
        text = "hello",
        eventTimestamp = 0,
        url = "http://example.com",
        method = "POST",
        contentType = "text/plain",
        body = "body",
        attemptCount = 0,
        nextAttemptAt = nextAttemptAt,
        createdAt = 0,
    )
}
