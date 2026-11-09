package ch.lkmc.combray

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.ui.NoticesDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * The notices bundled in the APK load on a device and their dialog shows a license. The dialog runs
 * on a plain Compose host: under software emulation the browser's main thread is too slow to take
 * a sheet's window focus within Android's input timeout (docs/BUILDING.md), and the Settings entry
 * point is a single button. A failure leaves the screen and window list for scripts/check-app.sh.
 */
@RunWith(AndroidJUnit4::class)
class AboutTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    // Inside the Compose rule, so the dialog's window still exists when the evidence is taken.
    @get:Rule(order = 1)
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) = recordFailureEvidence()
    }

    @Test
    fun theEngineLicenseCanBeRead() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        var closed = false
        compose.setContent { NoticesDialog(ThirdPartyNotices(assets)) { closed = true } }

        val servo = "servo ${BuildConfig.SERVO_VERSION}"
        val intro = hasText("Combray includes the following software", substring = true)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(intro).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(servo))
        compose.onNodeWithText(servo).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Mozilla Public License", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Third-party notices").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    private fun recordFailureEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, FAILURE_EVIDENCE).apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { screen ->
            File(directory, "screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
        val windows = instrumentation.uiAutomation.executeShellCommand("dumpsys window windows")
        ParcelFileDescriptor.AutoCloseInputStream(windows).use { input ->
            File(directory, "windows.txt").outputStream().use { input.copyTo(it) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        /** Under the app's files directory; check-app.sh moves it into the failed run's results. */
        const val FAILURE_EVIDENCE = "failure-evidence"
    }
}
