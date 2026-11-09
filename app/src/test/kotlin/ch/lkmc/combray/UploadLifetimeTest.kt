package ch.lkmc.combray

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UploadLifetimeTest {
    @get:Rule val folder = TemporaryFolder()
    private val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver

    @Test
    fun startupCleanupRemovesOldFilesButKeepsThisProcessAttachments() = runBlocking {
        val root = folder.newFolder("uploads")
        val old = File(root, "old-pick/0/attachment").apply { parentFile.mkdirs(); writeText("old") }
        val uploads = UploadStaging(root, resolver)
        val uri = input("one", ByteArrayInputStream("current".toByteArray()))
        val paths = uploads.stage(listOf(uri))!!

        uploads.clear()

        assertFalse(old.exists())
        assertEquals("current", File(paths.single()).readText())
        // A second delayed cleanup must be harmless too.
        uploads.clear()
        assertEquals("current", File(paths.single()).readText())
    }

    @Test
    fun cleanupDuringAProviderCopyCannotDeleteItsReturnedPath() = runBlocking {
        val uploads = UploadStaging(folder.newFolder("uploads"), resolver)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = ByteArrayInputStream("picked bytes".toByteArray())
        val uri = input("blocked", object : InputStream() {
            override fun read(): Int = data.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("test provider timed out")
                return data.read(buffer, offset, length)
            }
        })
        val copying = async(Dispatchers.IO) { uploads.stage(listOf(uri)) }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            uploads.clear()
        } finally {
            release.countDown()
        }

        val paths = copying.await()
        assertNotNull(paths)
        assertEquals("picked bytes", File(paths!!.single()).readText())
    }

    @Test
    fun failedProvidersCannotPutTheirMessagesOrCausesIntoLogs() = runBlocking {
        ShadowLog.clear()
        val uploads = UploadStaging(folder.newFolder("uploads"), resolver)
        val sensitive = "private-attachment?token=synthetic-secret"
        val uri = input("failure", object : InputStream() {
            override fun read(): Int = throw IOException(sensitive)
        })

        assertNull(uploads.stage(listOf(uri)))

        val logs = ShadowLog.getLogsForTag("CombrayUpload")
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.msg.contains(sensitive) || it.throwable != null })
    }

    @Test
    fun separateFilesWithTheSameFallbackNameKeepSeparateBytes() = runBlocking {
        val uploads = UploadStaging(folder.newFolder("uploads"), resolver)
        val first = input("first", ByteArrayInputStream("first".toByteArray()))
        val second = input("second", ByteArrayInputStream("second".toByteArray()))

        val paths = uploads.stage(listOf(first, second))!!
        uploads.clear()

        assertEquals(listOf("first", "second"), paths.map { File(it).readText() })
        assertEquals(2, paths.distinct().size)
    }

    private fun input(name: String, stream: InputStream): Uri = Uri.parse("content://synthetic-uploads/$name").also {
        shadowOf(resolver).registerInputStream(it, stream)
    }
}
