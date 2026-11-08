package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.core.browser.Article
import ch.lkmc.combray.core.browser.ArticleBlock
import ch.lkmc.combray.core.browser.ArticleSpan
import ch.lkmc.combray.core.browser.BlockKind
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.TextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A reader-view block's text is built once per block, not on every recomposition (Phase 1
 * finding: a screen recomposition rebuilt every visible block's AnnotatedString).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class ReaderBlockStabilityTest {
    @get:Rule val compose = createComposeRule()

    private var tick by mutableStateOf(0)
    private val opened = mutableListOf<String>()

    @Test
    fun blocksDoNotRebuildTheirTextWhenTheScreenRecomposes() {
        compose.setContent {
            // Read here: bumping it recomposes this scope and hands ReaderView a fresh lambda
            // (the capture below takes the value, as BrowserScreen's captures change per pass).
            val currentTick = tick
            CombrayTheme {
                Box(Modifier.width(600.dp).height(900.dp)) {
                    ReaderView(
                        state = ReaderState.Ready("tab", "https://a.test/", ARTICLE),
                        textSize = TextSize.DEFAULT,
                        // A fresh lambda per recomposition with a changing capture, as
                        // BrowserScreen builds its reader callbacks.
                        onOpenLink = { url -> opened += "$url at $currentTick" },
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val builds = annotatedBuilds
        assertTrue("the article must have been built", builds > 0)

        compose.runOnIdle { tick++ }
        compose.waitForIdle()

        assertEquals("a recomposition must not rebuild block text", builds, annotatedBuilds)
    }

    private companion object {
        val ARTICLE = Article(
            title = "A title",
            byline = null,
            site = null,
            rightToLeft = false,
            blocks = listOf(
                ArticleBlock(BlockKind.HEADING, 1, listOf(ArticleSpan("A title"))),
                ArticleBlock(BlockKind.PARAGRAPH, 0, listOf(ArticleSpan("Some text with a "), ArticleSpan("link", "https://b.test/"), ArticleSpan("."))),
                ArticleBlock(BlockKind.PARAGRAPH, 0, listOf(ArticleSpan("Second paragraph."))),
            ),
            truncated = false,
        )
    }
}
