package com.example.smsforwarder.data

import android.net.Uri
import com.example.smsforwarder.testing.TestEnvironment
import com.example.smsforwarder.testing.TestSmsForwarderApp
import com.example.smsforwarder.testing.installTestContainer
import com.example.smsforwarder.testing.testAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestSmsForwarderApp::class, sdk = [28])
class LogExportProviderTest {
    private val authority = "com.example.smsforwarder.logs"
    private lateinit var provider: LogExportProvider

    @Before
    fun setUp() {
        installTestContainer()
        provider = Robolectric.setupContentProvider(LogExportProvider::class.java, authority)
    }

    @After
    fun tearDown() {
        TestEnvironment.reset()
    }

    private fun seed(vararg entries: Pair<Long, String>) = runBlocking {
        val repository = testAppContainer().eventRepository
        entries.forEach { (timestamp, text) -> repository.addLog(text, timestamp) }
    }

    // openFile streams the same text over an OS pipe; the pipe transfer is verified on-device, since
    // Robolectric does not stream real pipes. Here we assert the assembled payload directly.
    private fun read(uri: String): String = provider.exportText(Uri.parse(uri))

    @Test
    fun dumpsWholeTableChronologicallyOnePerLine() {
        seed(3_000L to "third", 1_000L to "first", 2_000L to "second")

        val lines = read("content://$authority/logs").lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("\tfirst"))
        assertTrue(lines[1].endsWith("\tsecond"))
        assertTrue(lines[2].endsWith("\tthird"))
    }

    @Test
    fun appliesUriFilters() {
        seed(
            1_000L to "Heartbeat sending",
            2_000L to "Queued SMS event",
            3_000L to "Heartbeat completed",
            4_000L to "Configuration saved",
        )

        val contains = read("content://$authority/logs?contains=heartbeat").lines()
        assertEquals(listOf("Heartbeat sending", "Heartbeat completed"), contains.map { it.substringAfter('\t') })

        val limited = read("content://$authority/logs?limit=2").lines()
        assertEquals(listOf("Heartbeat completed", "Configuration saved"), limited.map { it.substringAfter('\t') })

        val windowed = read("content://$authority/logs?since=2000&until=3000").lines()
        assertEquals(listOf("Queued SMS event", "Heartbeat completed"), windowed.map { it.substringAfter('\t') })
    }

    @Test
    fun assemblesLargeMultiRowPayload() {
        // A full-table export can be multiple megabytes; make sure every row survives assembly and ordering.
        val count = 4_000
        runBlocking {
            val repository = testAppContainer().eventRepository
            repeat(count) { index -> repository.addLog("entry-$index padded to widen the row", index.toLong()) }
        }

        val output = read("content://$authority/logs")
        val lines = output.lines()

        assertTrue("payload should exceed the 64KB pipe buffer", output.toByteArray().size > 64 * 1024)
        assertEquals(count, lines.size)
        assertTrue(lines.first().endsWith("\tentry-0 padded to widen the row"))
        assertTrue(lines.last().endsWith("\tentry-${count - 1} padded to widen the row"))
    }

    @Test
    fun emptyTableStreamsEmptyOutput() {
        assertEquals("", read("content://$authority/logs"))
    }

    @Test
    fun getTypeIsPlainText() {
        assertEquals("text/plain", provider.getType(Uri.parse("content://$authority/logs")))
    }

    @Test
    fun queryReturnsNull() {
        assertNull(provider.query(Uri.parse("content://$authority/logs"), null, null, null, null))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun insertIsUnsupported() {
        provider.insert(Uri.parse("content://$authority/logs"), null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun updateIsUnsupported() {
        provider.update(Uri.parse("content://$authority/logs"), null, null, null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun deleteIsUnsupported() {
        provider.delete(Uri.parse("content://$authority/logs"), null, null)
    }
}
