package ch.lkmc.combray

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.ui.AddressSheetContent
import ch.lkmc.combray.ui.CombrayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The address editor's typing layout on real bounds: local matches above the anchored field,
 * its New tab beside it, and only the match rows scrolling when they overflow. [AddressSheetContent]
 * runs without the sheet's window, which software emulation cannot focus (docs/BUILDING.md), so
 * the field below is the one the user types into.
 */
@RunWith(AndroidJUnit4::class)
class AddressSuggestionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun matchesListAboveTheAnchoredField() {
        show(DEFAULT_FONT) {
            Editor(matches = listOf(TAB_MATCH, VISIT_MATCH))
        }

        val field = compose.onNode(FIELD).getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText("On this device").getUnclippedBoundsInRoot()
        assertTrue(heading.bottom <= field.top)
        for (title in listOf("A garden through the seasons", "Garden notes")) {
            assertTrue(row(title).let { compose.onNode(it).getUnclippedBoundsInRoot().bottom <= field.top })
        }

        // New tab beside the field on its own row, at least 48dp to touch.
        val plus = compose.onNodeWithContentDescription("New tab")
        val plusBounds = plus.getUnclippedBoundsInRoot()
        assertTrue(plusBounds.right <= field.left)
        assertTrue(plusBounds.top < field.bottom && plusBounds.bottom > field.top)
        val touch = plus.fetchSemanticsNode().touchBoundsInRoot
        with(compose.density) {
            assertTrue(touch.width.toDp() >= 48.dp && touch.height.toDp() >= 48.dp)
        }

        // The primary submission stays obvious: the Kagi button under the field row.
        compose.onNodeWithText("Search Kagi for", substring = true).assertIsDisplayed()
    }

    @Test
    fun theFieldStaysAnchoredWhenMatchesOverflow() {
        val many = (1..8).map { i ->
            RecentItem.Visit("v$i", "tab", "https://example.org/p$i", "Garden page $i", i.toLong())
        }
        show(LARGE_FONT) {
            // A small window, like the room left above a keyboard at 200% font.
            Box(Modifier.requiredSize(WINDOW_WIDTH, WINDOW_HEIGHT).testTag(WINDOW_TAG)) {
                Editor(matches = many)
            }
        }

        val window = compose.onNodeWithTag(WINDOW_TAG).getUnclippedBoundsInRoot()
        val field = compose.onNode(FIELD)
        field.assertIsDisplayed()
        val bounds = field.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= window.top && bounds.bottom <= window.bottom)
        compose.onNodeWithContentDescription("New tab").assertIsDisplayed()

        // The list outgrew its region above the field: the last row is clipped below the window
        // edge instead of pushing the field or the buttons off.
        val last = compose.onNode(hasText("Garden page 8", substring = true)).getUnclippedBoundsInRoot()
        assertTrue(last.top >= window.bottom)
        compose.onNodeWithText("Search Kagi for", substring = true).assertIsDisplayed()
    }

    @Test
    fun aShortMatchListKeepsTheFieldAtTheSameBottomAnchor() {
        val matches = mutableStateOf(listOf(TAB_MATCH, VISIT_MATCH))
        show(DEFAULT_FONT) {
            Box(Modifier.requiredSize(WINDOW_WIDTH, TALL_WINDOW_HEIGHT).testTag(WINDOW_TAG)) {
                Editor(matches = matches.value)
            }
        }
        val shortGap = compose.onNodeWithTag(WINDOW_TAG).getUnclippedBoundsInRoot().bottom -
            compose.onNode(FIELD).getUnclippedBoundsInRoot().bottom

        compose.runOnIdle {
            matches.value = (1..8).map { RecentItem.Visit("v$it", "tab", "https://example.org/p$it", "Garden page $it", it.toLong()) }
        }
        val longGap = compose.onNodeWithTag(WINDOW_TAG).getUnclippedBoundsInRoot().bottom -
            compose.onNode(FIELD).getUnclippedBoundsInRoot().bottom
        assertEquals("typing must not move the field when more matches arrive", shortGap.value, longGap.value, 1f)
    }

    @Test
    fun tapsReachTheRowTheyWereOn() {
        val decisions = mutableListOf<AddressDecision>()
        val selected = mutableListOf<String>()
        var newTabs = 0
        show(DEFAULT_FONT) {
            Editor(
                matches = listOf(TAB_MATCH, VISIT_MATCH),
                onDecision = decisions::add,
                onSelectTab = selected::add,
                onNewTab = { newTabs++ },
            )
        }

        // A tap on the open-tab row selects that tab, not an equal address elsewhere.
        compose.onNode(row("Garden notes")).performClick()
        assertEquals(listOf("tab-garden"), selected)
        assertTrue(decisions.isEmpty())

        // A history row submits its own address, like the typed text would.
        compose.onNode(row("A garden through the seasons")).performClick()
        assertEquals(listOf(AddressPolicy.interpret("https://example.org/garden").primary), decisions)

        compose.onNodeWithContentDescription("New tab").performClick()
        assertEquals(1, newTabs)
    }

    /** The clickable row that shows [title]: its text is merged into the row's own node. */
    private fun row(title: String): SemanticsMatcher =
        hasClickAction() and hasText(title, substring = true)

    @Composable
    private fun Editor(
        matches: List<RecentItem>,
        target: String? = null,
        onDecision: (AddressDecision) -> Unit = {},
        onSelectTab: (String) -> Unit = {},
        onNewTab: () -> Unit = {},
    ) = AddressSheetContent(
        field = TextFieldValue("garden"),
        onFieldChange = {},
        matches = matches,
        target = target,
        onDecision = onDecision,
        onSelectTab = onSelectTab,
        onNewTab = onNewTab,
    )

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CombrayTheme(content = content)
            }
        }
    }

    private companion object {
        const val DEFAULT_FONT = 1f

        /** Android's largest font size setting. */
        const val LARGE_FONT = 2f
        const val WINDOW_TAG = "window"

        /** A suggestion field: the text field is the node with a set-text action. */
        val FIELD: SemanticsMatcher = hasSetTextAction()

        /** 320dp wide and as short as the space above a keyboard. */
        val WINDOW_WIDTH = 320.dp
        val WINDOW_HEIGHT = 300.dp
        val TALL_WINDOW_HEIGHT = 520.dp

        val TAB_MATCH = RecentItem.OpenTab("tab-garden", "https://example.net/notes", "Garden notes", 2L, active = false)
        val VISIT_MATCH = RecentItem.Visit("visit-1", "tab", "https://example.org/garden", "A garden through the seasons", 1L)
    }
}
