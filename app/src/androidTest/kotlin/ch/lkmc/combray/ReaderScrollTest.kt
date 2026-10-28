package ch.lkmc.combray

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Article
import ch.lkmc.combray.core.browser.ArticleBlock
import ch.lkmc.combray.core.browser.ArticleSpan
import ch.lkmc.combray.core.browser.BlockKind
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.TextSize
import ch.lkmc.combray.ui.CombrayTheme
import ch.lkmc.combray.ui.ReaderView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun readerJumpsToBothEndsAndKeepsTheButtonOnTheLeftForRtlArticles() {
        showReader()
        compose.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        val down = compose.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()
        assertTrue(down.fetchSemanticsNode().boundsInRoot.center.x < compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.center.x)
        down.performClick()
        compose.onNodeWithText("Paragraph 79").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("Scroll to top").assertIsDisplayed().performClick()
        compose.onNodeWithText("Article start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll to top").assertDoesNotExist()
    }

    @Test
    fun readerScrollingRenewsTheButtonAndIdleFadesIt() {
        showReader()
        compose.mainClock.autoAdvance = false
        repeat(4) {
            // Short, slow drags leave room for the next one instead of flinging to the end.
            compose.onNode(hasScrollAction()).performTouchInput {
                swipeUp(startY = centerY + height * 0.1f, endY = centerY - height * 0.1f, durationMillis = SWIPE_DURATION_MS)
            }
            compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
            compose.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()
        }

        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performTouchInput {
            swipeDown(startY = centerY - height * 0.1f, endY = centerY + height * 0.1f, durationMillis = SWIPE_DURATION_MS)
        }
        compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
        compose.onNodeWithContentDescription("Scroll to top").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription("Scroll to top").assertDoesNotExist()
    }

    private fun showReader() {
        val article = Article("Article start", null, null, rightToLeft = true,
            blocks = (0..79).map { ArticleBlock(BlockKind.PARAGRAPH, 0, listOf(ArticleSpan("Paragraph $it"))) }, truncated = false)
        compose.setContent {
            CombrayTheme { ReaderView(ReaderState.Ready("tab", "https://fixture.test/", article), TextSize.DEFAULT, {}, {}) }
        }
    }

    private companion object {
        const val SCROLL_INTERVAL_MS = 1_000L
        const val IDLE_WAIT_MS = 3_000L
        const val SWIPE_DURATION_MS = 600L
    }
}
