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

/** Real staging IO, with a synthetic document provider and no engine or external service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploadStagingIoTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var provider: PickedFileProvider
    private lateinit var staging: UploadStaging
    private val uri = Uri.parse("content://combray-upload-test/document/1")

    @Before
    fun setUp() {
        root = temporary.newFolder("uploads")
        val source = temporary.newFile("source.txt").apply { writeText("Synthetic attachment") }
        val application = RuntimeEnvironment.getApplication()
        provider = PickedFileProvider(source).apply {
            attachInfo(application, ProviderInfo().apply { authority = uri.authority })
        }
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
        staging = UploadStaging(root, application.contentResolver, reserveBytes = 0)
    }

    @Test
    fun aPickSurvivesStartupCleanupThatRunsLate() = runBlocking {
        val picked = File(stagedPaths(listOf(uri)).single())
        assertEquals("Synthetic attachment", picked.readText())

        // The Application's IO startup task was delayed until after the picker returned.
        staging.clear()
        assertTrue("Late startup cleanup deleted the current attachment", picked.exists())
        assertEquals("Synthetic attachment", picked.readText())
    }

    @Test
    fun stagingRemovesPreviousProcessFilesEvenBeforeTheStartupTaskRuns() = runBlocking {
        val stale = File(root, "old-process.txt").apply { writeText("Unused old copy") }
        val paths = stagedPaths(listOf(uri))
        assertFalse("Staging did not finish its one-time startup cleanup", stale.exists())
        assertEquals("Synthetic attachment", File(paths.single()).readText())
    }

    @Test
    fun duplicateNamesAndLaterPicksKeepIndependentCopies() = runBlocking {
        staging.clear()
        val first = stagedPaths(listOf(uri, uri))
        val second = stagedPaths(listOf(uri))
        staging.clear()
        val paths = first + second
        assertEquals(paths.size, paths.distinct().size)
        paths.forEach { assertEquals("Synthetic attachment", File(it).readText()) }
    }

    @Test
    fun anotherInstanceInThisProcessKeepsTheExistingPick() = runBlocking {
        val picked = File(stagedPaths(listOf(uri)).single())
        // Constructing another wrapper is not a process restart and must not invalidate Servo's path.
        UploadStaging(root, RuntimeEnvironment.getApplication().contentResolver, reserveBytes = 0).clear()
        assertTrue(picked.exists())
    }

    @Test
    fun aProviderFailureIsRedactedAndDoesNotDeleteAnEarlierPick() = runBlocking {
        staging.clear()
        val earlier = File(stagedPaths(listOf(uri)).single())
        provider.failure = IllegalStateException(PRIVATE_DETAIL, IllegalArgumentException(PRIVATE_CAUSE))
        ShadowLog.clear()

        assertEquals(UploadStaging.Staged.Unreadable, staging.stage(listOf(uri)))
        assertEquals("Synthetic attachment", earlier.readText())
        assertEquals(1, root.listFiles()!!.size)
        val logs = ShadowLog.getLogsForTag("CombrayUpload")
        assertEquals(1, logs.size)
        logs.forEach {
            assertFalse(it.msg.contains(PRIVATE_DETAIL))
            assertFalse(it.msg.contains(PRIVATE_CAUSE))
            assertNull("The provider's throwable must not reach logcat", it.throwable)
        }
    }

    private suspend fun stagedPaths(uris: List<Uri>): List<String> =
        (staging.stage(uris) as UploadStaging.Staged.Files).paths

    private class PickedFileProvider(private val source: File) : ContentProvider() {
        var failure: RuntimeException? = null

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            failure?.let { throw it }
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "attachment.txt"
                        OpenableColumns.SIZE -> null
                        else -> throw IllegalArgumentException(column)
                    }
                })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val PRIVATE_DETAIL = "content://private-upload.example/document/synthetic-secret"
        const val PRIVATE_CAUSE = "synthetic-private-attachment-name.txt"
    }
}
