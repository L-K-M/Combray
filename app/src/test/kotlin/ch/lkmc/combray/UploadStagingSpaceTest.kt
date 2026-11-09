package ch.lkmc.combray

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

/** The staging free-space reserve: a pick is refused instead of filling the phone's storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploadStagingSpaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
    private lateinit var root: File
    private lateinit var provider: PickedFileProvider
    private val uri = Uri.parse("content://combray-upload-test/document/1")
    private val streamUri = Uri.parse("content://combray-upload-test/stream/1")
    private val reserve = 1024L * 1024

    @Before
    fun setUp() {
        root = temporary.newFolder("uploads")
        val source = temporary.newFile("source.txt").apply { writeText(CONTENT) }
        val application = RuntimeEnvironment.getApplication()
        provider = PickedFileProvider(source).apply {
            attachInfo(application, ProviderInfo().apply { authority = uri.authority })
        }
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
    }

    @Test
    fun aPickThatFitsIsStagedAsFiles() = runBlocking {
        provider.size = CONTENT.length.toLong()
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { 64L * 1024 * 1024 })

        val staged = staging.stage(listOf(uri))

        val path = (staged as UploadStaging.Staged.Files).paths.single()
        assertEquals(CONTENT, File(path).readText())
        assertEquals("attachment.txt", File(path).name)
    }

    @Test
    fun aProviderSizeThatCutsIntoTheReserveRefusesWithoutOpeningTheStream() = runBlocking {
        provider.size = 2L * 1024 * 1024
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { 2L * 1024 * 1024 })
        ShadowLog.clear()

        val staged = staging.stage(listOf(uri))

        assertEquals(UploadStaging.Staged.NoSpace, staged)
        assertEquals("The provider's stream was opened for a refused pick", 0, provider.opened)
        assertTrue(root.walkBottomUp().none { it.isFile })
        assertTrue("A refusal is not a provider failure", ShadowLog.getLogsForTag(LOG_TAG).isEmpty())
    }

    @Test
    fun anUnknownSizeCopyIntoTheReserveStopsEarlyAsNoSpace() = runBlocking {
        val source = CountingSource(64L * 1024 * 1024)
        shadowOf(resolver).registerInputStream(streamUri, source)
        var free = 64L * 1024 * 1024
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { free -= 32L * 1024 * 1024; free })

        val staged = staging.stage(listOf(streamUri))

        assertEquals(UploadStaging.Staged.NoSpace, staged)
        assertTrue("Read ${source.read} of ${source.total} bytes", source.read < 16L * 1024 * 1024)
        assertTrue(root.walkBottomUp().none { it.isFile })
        assertTrue("A pick directory survived the refusal", root.listFiles()!!.all { it.listFiles().isNullOrEmpty() })
    }

    @Test
    fun anIOFailureBelowTheReserveAnswersNoSpace() = runBlocking {
        shadowOf(resolver).registerInputStream(streamUri, FailingSource(IOException("provider ran out of space")))
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { reserve / 2 })

        val staged = staging.stage(listOf(streamUri))

        assertEquals(UploadStaging.Staged.NoSpace, staged)
        assertTrue(root.walkBottomUp().none { it.isFile })
    }

    @Test
    fun anIOFailureWithPlentyOfSpaceAnswersUnreadable() = runBlocking {
        shadowOf(resolver).registerInputStream(streamUri, FailingSource(IOException("provider broke")))
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { 64L * 1024 * 1024 })

        val staged = staging.stage(listOf(streamUri))

        assertEquals(UploadStaging.Staged.Unreadable, staged)
    }

    @Test
    fun aRefusalLogsOnlyTheExceptionClass() = runBlocking {
        shadowOf(resolver).registerInputStream(
            streamUri,
            FailingSource(IOException(PRIVATE_DETAIL, IllegalArgumentException(PRIVATE_CAUSE))),
        )
        val staging = UploadStaging(root, resolver, reserveBytes = reserve, usableSpace = { reserve / 2 })
        ShadowLog.clear()

        val staged = staging.stage(listOf(streamUri))

        assertEquals(UploadStaging.Staged.NoSpace, staged)
        val logs = ShadowLog.getLogsForTag(LOG_TAG)
        assertEquals(1, logs.size)
        logs.forEach {
            assertTrue(it.msg.contains("java.io.IOException"))
            assertFalse(it.msg.contains(PRIVATE_DETAIL))
            assertFalse(it.msg.contains(PRIVATE_CAUSE))
            assertNull("The provider's throwable must not reach logcat", it.throwable)
        }
    }

    /** Serves the requested columns like a real document provider; [size] null means unknown. */
    private class PickedFileProvider(private val source: File) : ContentProvider() {
        var size: Long? = null
        var opened = 0

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "attachment.txt"
                        OpenableColumns.SIZE -> size
                        else -> throw IllegalArgumentException(column)
                    }
                })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened++
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    /** Produces [total] bytes and counts how many were really consumed. */
    private class CountingSource(val total: Long) : InputStream() {
        var read = 0L
        private var left = total

        override fun read(): Int {
            if (left == 0L) return -1
            left--
            read++
            return 'x'.code
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return -1
            val count = minOf(left, length.toLong()).toInt()
            Arrays.fill(buffer, offset, offset + count, 'x'.code.toByte())
            left -= count
            read += count
            return count
        }
    }

    /** A provider stream that fails on first read, like a revoked document. */
    private class FailingSource(private val failure: IOException) : InputStream() {
        override fun read(): Int = throw failure
    }

    private companion object {
        const val LOG_TAG = "CombrayUpload"
        const val CONTENT = "Synthetic attachment"
        const val PRIVATE_DETAIL = "content://private-upload.example/document/synthetic-secret"
        const val PRIVATE_CAUSE = "synthetic-private-attachment-name.txt"
    }
}
