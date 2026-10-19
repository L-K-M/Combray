package ch.lkmc.combray

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
import org.junit.runner.RunWith

/**
 * The notices bundled in the APK load on a device and their dialog shows a license. The dialog runs
 * on a plain Compose host: under software emulation the browser's main thread is too slow to take
 * a sheet's window focus within Android's input timeout (docs/BUILDING.md), and the Settings entry
 * point is a single button.
 */
@RunWith(AndroidJUnit4::class)
class AboutTest {
    @get:Rule
    val compose = createComposeRule()

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

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
