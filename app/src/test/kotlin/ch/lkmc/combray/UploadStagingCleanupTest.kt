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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

/** Real staging IO against a synthetic document provider, with no engine or external service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploadStagingCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var provider: PickedFileProvider
    private lateinit var staging: UploadStaging
    private val uri = Uri.parse("content://combray-upload-test/document/1")

    @Before
    fun setUp() {
        root = temporary.newFolder("uploads")
        val source = temporary.newFile("source.txt").apply { writeText(CONTENT) }
        val application = RuntimeEnvironment.getApplication()
        provider = PickedFileProvider(source).apply {
            attachInfo(application, ProviderInfo().apply { authority = uri.authority })
        }
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
        staging = UploadStaging(root, application.contentResolver, reserveBytes = 0)
    }

    @Test
    fun cleanupRunsBeforeTheFirstPick() = runBlocking {
        val stale = staleCopy(root, "previous-process/0/attachment.txt")

        val paths = staging.stagedPaths(listOf(uri))

        assertFalse("Staging did not finish its one-time startup cleanup", stale.exists())
        assertEquals(CONTENT, File(paths.single()).readText())
    }

    @Test
    fun aDelayedCleanupKeepsACompletedPick() = runBlocking {
        val paths = staging.stagedPaths(listOf(uri))
        val picked = File(paths.single())

        // The process-start cleanup was delayed until after the pick was staged and answered.
        staging.clear()

        assertTrue("A late startup cleanup deleted the current pick", picked.exists())
        assertEquals(CONTENT, picked.readText())
    }

    @Test
    fun repeatedCleanupDoesNotDeleteTheCurrentPick() = runBlocking {
        val paths = staging.stagedPaths(listOf(uri))

        staging.clear()
        staging.clear()

        assertTrue(File(paths.single()).exists())
    }

    @Test
    fun twoConcurrentPicksSurviveCleanup() = runBlocking {
        val picks = coroutineScope {
            val first = async(Dispatchers.IO) { staging.stagedPaths(listOf(uri, uri)) }
            val second = async(Dispatchers.IO) { staging.stagedPaths(listOf(uri)) }
            awaitAll(first, second)
        }

        staging.clear()

        val paths = picks.flatten()
        assertEquals(3, paths.size)
        assertEquals(3, paths.distinct().size)
        paths.forEach { assertEquals(CONTENT, File(it).readText()) }
    }

    @Test
    fun twoInstancesDoNotDeleteEachOtherPicks() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val other = UploadStaging(root, application.contentResolver, reserveBytes = 0)
        val first = staging.stagedPaths(listOf(uri))
        val second = other.stagedPaths(listOf(uri))

        other.clear()
        staging.clear()

        assertTrue("One instance's cleanup deleted the other's pick", File(first.single()).exists())
        assertTrue(File(second.single()).exists())
    }

    @Test
    fun cleanupDuringAProviderCopyCannotDeleteTheReturnedPath() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.reading = reading
        provider.release = release
        val copying = async(Dispatchers.IO) { staging.stagedPaths(listOf(uri)) }

        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            staging.clear()
        } finally {
            release.countDown()
        }

        val paths = copying.await()
        assertEquals(CONTENT, File(paths.single()).readText())
    }

    @Test
    fun aProviderFailureIsRedactedAndKeepsAnEarlierPick() = runBlocking {
        staging.clear()
        val earlier = staging.stagedPaths(listOf(uri))
        provider.failure = IllegalStateException(PRIVATE_DETAIL, IllegalArgumentException(PRIVATE_CAUSE))
        ShadowLog.clear()

        assertEquals(UploadStaging.Staged.Unreadable, staging.stage(listOf(uri)))

        assertEquals(CONTENT, File(earlier.single()).readText())
        assertEquals(1, root.listFiles()!!.size)
        val logs = ShadowLog.getLogsForTag("CombrayUpload")
        assertEquals(1, logs.size)
        logs.forEach {
            assertFalse(it.msg.contains(PRIVATE_DETAIL))
            assertFalse(it.msg.contains(PRIVATE_CAUSE))
            assertNull("The provider's throwable must not reach logcat", it.throwable)
        }
    }

    @Test
    fun cancellationIsNotReportedAsAFailure() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.reading = reading
        provider.release = release
        ShadowLog.clear()
        val copying = launch(Dispatchers.IO) { staging.stage(listOf(uri)) }

        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            copying.cancel()
        } finally {
            release.countDown()
        }
        copying.join()

        assertTrue(copying.isCancelled)
        assertTrue(ShadowLog.getLogsForTag("CombrayUpload").isEmpty())
    }

    @Test
    fun aNewProcessRemovesCopiesThePreviousProcessLeft() = runBlocking {
        val freshRoot = temporary.newFolder("uploads-new-process")
        val stale = staleCopy(freshRoot, "old-process/0/attachment.txt")

        UploadStaging(freshRoot, RuntimeEnvironment.getApplication().contentResolver, reserveBytes = 0).clear()

        assertFalse(stale.exists())
    }

    private suspend fun UploadStaging.stagedPaths(uris: List<Uri>): List<String> =
        (stage(uris) as UploadStaging.Staged.Files).paths

    private fun staleCopy(folder: File, relative: String): File {
        val file = File(folder, relative)
        file.parentFile!!.mkdirs()
        file.writeText("Unused old copy")
        return file
    }

    private class PickedFileProvider(private val source: File) : ContentProvider() {
        var failure: RuntimeException? = null
        var reading: CountDownLatch? = null
        var release: CountDownLatch? = null

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            failure?.let { throw it }
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "synthetic-attachment.txt"
                        OpenableColumns.SIZE -> null
                        else -> throw IllegalArgumentException(column)
                    }
                })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            failure?.let { throw it }
            reading?.countDown()
            release?.await(5, TimeUnit.SECONDS)
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val CONTENT = "Synthetic attachment"
        const val PRIVATE_DETAIL = "content://private-upload.example/document/synthetic-secret"
        const val PRIVATE_CAUSE = "synthetic-private-attachment-name.txt"
    }
}
