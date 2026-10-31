package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.swipe
import ch.lkmc.combray.core.browser.Article
import ch.lkmc.combray.core.browser.ArticleBlock
import ch.lkmc.combray.core.browser.ArticleSpan
import ch.lkmc.combray.core.browser.BlockKind
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.TextSize
import ch.lkmc.combray.engine.ScrollEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReaderScrollObserverTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dragsReportTheDirectionTheArticleMoves() {
        val reported = mutableListOf<ScrollEdge>()
        showReader { reported += it }

        swipeUp()
        compose.runOnIdle { assertTrue(reported.isNotEmpty() && reported.all { it == ScrollEdge.BOTTOM }) }

        compose.runOnIdle { reported.clear() }
        swipeDown()
        compose.runOnIdle { assertTrue(reported.isNotEmpty() && reported.all { it == ScrollEdge.TOP }) }
    }

    @Test
    fun laterEventsGoOnlyToANewObserver() {
        val first = mutableListOf<ScrollEdge>()
        val second = mutableListOf<ScrollEdge>()
        var observer by mutableStateOf<(ScrollEdge) -> Unit>({ first += it })
        compose.setContent {
            CombrayTheme {
                ReaderView(ReaderState.Ready("tab", URL, ARTICLE), TextSize.DEFAULT, {}, {}, onScroll = observer)
            }
        }

        swipeUp()
        compose.runOnIdle { assertTrue(first.isNotEmpty()) }

        compose.runOnIdle { observer = { second += it } }
        val delivered = first.size

        swipeDown()
        compose.runOnIdle {
            assertTrue(second.isNotEmpty())
            assertEquals(delivered, first.size)
        }
    }

    @Test
    fun hidingControlsNearTheArticleEndDoesNotReverseTheDirection() {
        var direction by mutableStateOf<ScrollEdge?>(null)
        var observe by mutableStateOf(false)
        val reported = mutableListOf<ScrollEdge>()
        compose.setContent {
            CombrayTheme {
                Column(Modifier.requiredHeight(600.dp)) {
                    Box(Modifier.weight(1f)) {
                        ReaderView(ReaderState.Ready("tab", URL, ARTICLE), TextSize.DEFAULT, {}, {}, onScroll = {
                            if (observe) {
                                direction = it
                                reported += it
                            }
                        })
                    }
                    if (bottomBarVisible(direction, BottomBarBehavior.SCROLLING)) {
                        Box(Modifier.fillMaxWidth().height(100.dp).testTag(CONTROLS))
                    }
                }
            }
        }
        compose.onNodeWithTag(CONTROLS).assertExists()
        // Start just above the end, closer than the controls' height.
        compose.onNode(hasScrollAction()).performScrollToIndex(ARTICLE.blocks.size + 1)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -NEAR_END_DISTANCE_PX) }
        compose.waitForIdle()
        compose.onNodeWithText("Paragraph ${ARTICLE.blocks.lastIndex}").assertIsDisplayed()
        compose.runOnIdle { observe = true }

        swipeUp()
        compose.runOnIdle {
            assertTrue(reported.contains(ScrollEdge.BOTTOM))
            assertTrue(reported.none { it == ScrollEdge.TOP })
        }
        compose.onNodeWithTag(CONTROLS).assertDoesNotExist()
    }

    private fun swipeUp() {
        // Short, slow drags leave room for the next one instead of flinging to the end.
        compose.onNode(hasScrollAction()).performTouchInput {
            swipe(center + Offset(0f, height * 0.1f), center - Offset(0f, height * 0.1f), SWIPE_DURATION_MS)
        }
        compose.waitForIdle()
    }

    private fun swipeDown() {
        compose.onNode(hasScrollAction()).performTouchInput {
            swipe(center - Offset(0f, height * 0.1f), center + Offset(0f, height * 0.1f), SWIPE_DURATION_MS)
        }
        compose.waitForIdle()
    }

    private fun showReader(onScroll: (ScrollEdge) -> Unit) {
        compose.setContent {
            CombrayTheme {
                ReaderView(ReaderState.Ready("tab", URL, ARTICLE), TextSize.DEFAULT, {}, {}, onScroll = onScroll)
            }
        }
    }

    private companion object {
        const val URL = "https://fixture.test/"
        const val SWIPE_DURATION_MS = 600L
        const val CONTROLS = "controls"
        const val NEAR_END_DISTANCE_PX = 40f
        val ARTICLE = Article(
            "Article start", null, null, rightToLeft = false,
            blocks = (0..79).map { ArticleBlock(BlockKind.PARAGRAPH, 0, listOf(ArticleSpan("Paragraph $it"))) },
            truncated = false,
        )
    }
}
