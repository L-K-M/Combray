package ch.lkmc.combray.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class VideoFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val provider = DownloadsProvider(File(context.cacheDir, "video-output.mp4"))
    private val files = VideoFiles(context)

    @Before
    fun setUp() {
        provider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @Test
    fun publicationKeepsTheEntryPendingUntilAllBytesAreCopied() = runBlocking {
        val file = files.prepare().apply { writeBytes(VIDEO) }
        assertEquals(file.name, files.publish(file))
        assertEquals("video/mp4", provider.inserted?.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download", provider.inserted?.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, provider.inserted?.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertTrue(provider.completed)
        assertTrue(provider.output.readBytes().contentEquals(VIDEO))
        assertTrue(provider.deleted.isEmpty())
        files.discard(file)
        assertFalse(file.exists())
    }

    @Test
    fun aFailedPublicationDeletesThePendingEntry() = runBlocking {
        val file = files.prepare().apply { writeBytes(VIDEO) }
        provider.rejectPublication = true
        assertThrows(IOException::class.java) { runBlocking { files.publish(file) } }
        assertEquals(listOf(NEW_ID), provider.deleted)
        assertFalse(provider.completed)
        files.discard(file)
    }

    @Test
    fun cleanupRunsOnceAndNeverDeletesThisProcesssActiveExport() = runBlocking {
        provider.abandoned += 7L
        val first = files.prepare().apply { writeBytes(VIDEO) }
        val second = files.prepare()
        assertNotEquals(first.name, second.name)
        assertTrue(first.exists())
        assertEquals(1, provider.queries)
        assertEquals(listOf(7L), provider.deleted)
        assertTrue(provider.selection!!.contains(MediaStore.Downloads.IS_PENDING))
        assertEquals(context.packageName, provider.arguments!![0])
        assertEquals("combray-video-%.mp4", provider.arguments!![1])
        // A fresh process cleans private staging, not completed public files.
        VideoFiles(context).prepare()
        assertFalse(first.exists())
        assertFalse(provider.completed)
    }

    private class DownloadsProvider(val output: File) : ContentProvider() {
        var inserted: ContentValues? = null
        var completed = false
        var rejectPublication = false
        var queries = 0
        var selection: String? = null
        var arguments: Array<out String>? = null
        val abandoned = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            this.selection = selection
            arguments = selectionArgs
            return MatrixCursor(arrayOf(MediaStore.Downloads._ID)).apply { abandoned.toList().forEach { addRow(arrayOf(it)) } }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return ContentUris.withAppendedId(uri, NEW_ID)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertTrue("published before the copy finished", output.readBytes().contentEquals(VIDEO))
            if (rejectPublication) return 0
            assertEquals(0, values?.getAsInteger(MediaStore.Downloads.IS_PENDING))
            completed = true
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            deleted += id
            abandoned.remove(id)
            return 1
        }
    }

    private companion object {
        const val NEW_ID = 8L
        val VIDEO = byteArrayOf(1, 2, 3, 4)
    }
}
