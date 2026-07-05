package com.example.smsforwarder.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.util.LogQuery
import com.example.smsforwarder.util.UiLogFormatter
import com.example.smsforwarder.util.applyLogQuery
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Read-only provider that streams the logs table to a caller (e.g. adb) as clean, formatted text.
 *
 *   adb shell content read --uri "content://com.example.smsforwarder.logs/logs?since=...&limit=...&contains=..."
 *
 * Exported without a permission on purpose: the device is dedicated to this app and shell (UID 2000)
 * cannot hold an app-defined permission, so gating would also lock out adb.
 */
class LogExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val text = exportText(uri)
        val pipe = ParcelFileDescriptor.createPipe()
        // Write on a separate thread: the reader (adb) has not started draining yet, so writing more
        // than the pipe buffer (~64KB) from this thread before returning the read end would deadlock.
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(text.toByteArray()) }
        }
        return pipe[0]
    }

    /** Assembles the filtered, formatted export payload. Extracted from [openFile] so it is unit-testable
     * without a real OS pipe (which Robolectric does not stream through). */
    internal fun exportText(uri: Uri): String {
        val repository = (context!!.applicationContext as SmsForwarderApp).appContainer.eventRepository
        return runBlocking {
            UiLogFormatter.formatExport(applyLogQuery(repository.getAllLogs(), LogQuery.fromUri(uri)))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("LogExportProvider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("LogExportProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("LogExportProvider is read-only")
}
