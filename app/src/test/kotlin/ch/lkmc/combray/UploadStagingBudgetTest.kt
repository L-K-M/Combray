package ch.lkmc.combray

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A pick is bounded, and a failed pick leaves nothing behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploadStagingBudgetTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPickWithinTheBudgetIsCopied() = runBlocking {
        val staging = UploadStaging(folder.root, context.contentResolver, maxPickBytes = 8)
        val small = File(folder.root, "small.txt").apply { writeText("hi") }

        val paths = staging.stage(listOf(Uri.fromFile(small)))

        assertEquals(1, paths?.size)
        assertEquals("hi", File(paths!!.single()).readText())
    }

    @Test
    fun aPickOverTheBudgetFailsAndLeavesNothing() = runBlocking {
        val staging = UploadStaging(folder.root, context.contentResolver, maxPickBytes = 8)
        val big = File(folder.root, "big.bin").apply { writeBytes(ByteArray(9)) }

        assertNull(staging.stage(listOf(Uri.fromFile(big))))
        assertTrue(folder.root.listFiles().orEmpty().none { it.isDirectory })
    }
}
