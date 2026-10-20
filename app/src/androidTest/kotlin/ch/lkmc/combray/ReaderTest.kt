package ch.lkmc.combray

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reader view on the real engine: Readability runs in the page and the app shows the article. */
@RunWith(AndroidJUnit4::class)
class ReaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    private fun openReader(url: String): ReaderState {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        device.browser.openReader()
        return device.awaitState { it.reader != null && it.reader !is ReaderState.Loading }.reader!!
    }

    @Test
    fun theArticleIsShownWithoutTheNavigationAroundIt() {
        val reader = openReader(Fixtures.ARTICLE) as ReaderState.Ready
        val text = reader.article.blocks.joinToString("\n") { block -> block.spans.joinToString("") { it.text } }
        for (marker in listOf("READER-BODY-ONE", "READER-HEADING", "READER-BODY-TWO", "READER-ITEM-ONE", "READER-LINK")) {
            assertTrue("$marker missing from:\n$text", marker in text)
        }
        for (marker in listOf("READER-NAV-TEXT", "READER-ASIDE-TEXT", "READER-FOOTER-TEXT")) {
            assertFalse("$marker in:\n$text", marker in text)
        }
        // Relative links come back absolute.
        val link = reader.article.blocks.flatMap { it.spans }.single { it.text == "READER-LINK" }
        assertEquals("${Fixtures.BASE}/solid/2f6b3c.html", link.href)

        compose.onNodeWithText("READER-HEADING").assertIsDisplayed()
        // The system Back key leaves reader view, on the same page.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val state = device.awaitState { it.reader == null }
        assertEquals(Fixtures.ARTICLE, state.activeTab?.url)
    }

    @Test
    fun aLinkInTheArticleOpensInTheTabAndLeavesReaderView() {
        openReader(Fixtures.ARTICLE)
        compose.onNodeWithText("READER-LINK").performClick()
        val target = "${Fixtures.BASE}/solid/2f6b3c.html"
        device.awaitPage(target)
        assertEquals(null, device.browser.browserState.value.reader)
        assertEquals(1, device.browser.browserState.value.openTabCount)
    }

    @Test
    fun aPageWithoutAnArticleSaysSoAndShowsThePageAgain() {
        assertTrue(openReader(Fixtures.solid(Device.GREEN)) is ReaderState.Unavailable)
        compose.onNodeWithText("Show the page").performClick()
        device.awaitState { it.reader == null }
    }
}
