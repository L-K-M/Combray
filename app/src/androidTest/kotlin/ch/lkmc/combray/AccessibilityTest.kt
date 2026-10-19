package ch.lkmc.combray

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.ui.BottomBar
import ch.lkmc.combray.ui.CombrayTheme
import ch.lkmc.combray.ui.MoreActions
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every tappable element of the browser chrome is at least 48dp each way and has a label TalkBack
 * reads, at the narrowest common phone width and at Android's largest font size.
 *
 * Checked on Compose semantics: Android's Accessibility Test Framework skips Compose content on
 * this emulator ("Composable content is not evaluated in this environment"). The chrome runs on a
 * plain Compose host and the More sheet's rows without the sheet: under software emulation a
 * sheet's window does not take focus within Android's input timeout (docs/BUILDING.md).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBottomBarOnAPageIsAccessible() {
        show(LARGE_FONT) {
            BottomBar(PAGE, openTabs = 3, SaveStatus.Pending, {}, {}, {}, {}, {}, Modifier.width(NARROW_WIDTH))
        }

        assertTargetsAreAccessible()
        for (label in listOf("Back", "Saving to Karakeep", "Recent, 3 open tabs", "More")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("example.org", substring = true).assertIsDisplayed()
    }

    @Test
    fun theBottomBarOnANewTabIsAccessible() {
        show(DEFAULT_FONT) {
            BottomBar(null, openTabs = 0, SaveStatus.NotConfigured, {}, {}, {}, {}, {}, Modifier.width(NARROW_WIDTH))
        }

        assertTargetsAreAccessible()
    }

    @Test
    fun theMoreActionsAreAccessible() {
        show(LARGE_FONT) {
            Column(Modifier.width(NARROW_WIDTH)) {
                MoreActions(PAGE.copy(canGoForward = true), {}, {}, {}, {}, {}, {}, captureEnabled = true, {})
            }
        }

        assertTargetsAreAccessible()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    /** The check itself: a small target without a label must fail it. */
    @Test
    fun aSmallUnlabeledTargetFails() {
        show(DEFAULT_FONT) { Box(Modifier.size(20.dp).clickable {}) }

        assertThrows(AssertionError::class.java) { assertTargetsAreAccessible() }
    }

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CombrayTheme(content)
            }
        }
    }

    private fun assertTargetsAreAccessible() {
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        if (targets.isEmpty()) throw AssertionError("nothing to tap")
        val problems = targets.mapNotNull(::problem)
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
    }

    /** What is wrong with one tappable element, or null. Its touch area counts, not its drawing. */
    private fun problem(node: SemanticsNode): String? {
        val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val bounds = node.touchBoundsInRoot
        val (width, height) = with(compose.density) { bounds.width.toDp() to bounds.height.toDp() }
        if (label.none { it.isNotBlank() }) return "unlabeled target at $bounds"
        if (width < MIN_TARGET || height < MIN_TARGET) return "\"${label.first()}\" is ${width}x$height"
        return null
    }

    private companion object {
        const val DEFAULT_FONT = 1f

        /** Android's largest font size setting. */
        const val LARGE_FONT = 2f

        /** Small phones are 320dp wide. */
        val NARROW_WIDTH = 320.dp

        /** Android's and Material's minimum touch target. */
        val MIN_TARGET = 48.dp

        val PAGE = ActiveTab(
            id = "tab",
            url = "https://example.org/article",
            title = "Article",
            loading = true,
            canGoBack = true,
            canGoForward = false,
            crashed = false,
            hasView = true,
        )
    }
}
