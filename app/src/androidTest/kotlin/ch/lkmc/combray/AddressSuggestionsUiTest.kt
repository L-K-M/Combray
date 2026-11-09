package ch.lkmc.combray

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
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
import ch.lkmc.combray.ui.AddressEditorContent
import ch.lkmc.combray.ui.CombrayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The address editor's typing layout on real bounds: one panel above the anchored field, with
 * what Go does nearest it, the other reading above that and the local matches above those, best
 * first; Close and New tab flank the field, and only the panel scrolls when it overflows. The
 * editor sits at the bottom like the bar it lives in. [AddressEditorContent] is checked apart
 * from the browser, so the field below is the one the user types into.
 */
@RunWith(AndroidJUnit4::class)
class AddressSuggestionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEditorHasAnAccessibleCloseAction() {
        var dismissals = 0
        val decisions = mutableListOf<AddressDecision>()
        show(DEFAULT_FONT) {
            Editor(matches = emptyList(), onDecision = decisions::add, onDismiss = { dismissals++ })
        }

        val close = compose.onNodeWithContentDescription("Close")
        close.assertIsDisplayed()
        val touch = close.fetchSemanticsNode().touchBoundsInRoot
        with(compose.density) {
            assertTrue(touch.width.toDp() >= 48.dp && touch.height.toDp() >= 48.dp)
        }

        close.performClick()
        assertEquals(1, dismissals)
        assertTrue("closing must not submit the draft", decisions.isEmpty())
    }

    @Test
    fun matchesListAboveTheAnchoredField() {
        show(DEFAULT_FONT) {
            Editor(matches = listOf(TAB_MATCH, VISIT_MATCH))
        }

        val field = compose.onNode(FIELD).getUnclippedBoundsInRoot()
        // Bottom up: what Go does, the other reading, then the matches, the best one lowest.
        val search = compose.onNode(row("Search Kagi for")).getUnclippedBoundsInRoot()
        val other = compose.onNode(row("Go to https://")).getUnclippedBoundsInRoot()
        val tab = compose.onNode(row("Garden notes")).getUnclippedBoundsInRoot()
        val visit = compose.onNode(row("A garden through the seasons")).getUnclippedBoundsInRoot()
        assertTrue(search.bottom <= field.top)
        assertTrue(other.bottom <= search.top)
        assertTrue(tab.bottom <= other.top)
        assertTrue(visit.bottom <= tab.top)

        // New tab beside the field on its row, at least 48dp to touch.
        val plus = compose.onNodeWithContentDescription("New tab")
        val plusBounds = plus.getUnclippedBoundsInRoot()
        assertTrue(plusBounds.left >= field.right)
        assertTrue(plusBounds.top < field.bottom && plusBounds.bottom > field.top)
        val touch = plus.fetchSemanticsNode().touchBoundsInRoot
        with(compose.density) {
            assertTrue(touch.width.toDp() >= 48.dp && touch.height.toDp() >= 48.dp)
        }
    }

    @Test
    fun theCurrentAddressOffersNoActionUntilEdited() {
        show(DEFAULT_FONT) {
            Editor(matches = emptyList(), offersDecision = false)
        }
        compose.onNode(FIELD).assertIsDisplayed()
        compose.onNodeWithText("Search Kagi for", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Go to", substring = true).assertDoesNotExist()
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
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()

        // The panel outgrew its room above the field: it starts at its bottom, so the least
        // likely match leaves at the top instead of pushing the field or the buttons off, and
        // what Go does stays in view next to the field.
        val last = compose.onNode(hasText("Garden page 8", substring = true)).getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= window.top)
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

        // What Go does, and the other reading, each one tap.
        compose.onNode(row("Search Kagi for")).performClick()
        compose.onNode(row("Go to https://")).performClick()
        val reading = AddressPolicy.interpret("garden")
        assertEquals(listOf(AddressPolicy.interpret("https://example.org/garden").primary, reading.primary, reading.alternative), decisions)

        compose.onNodeWithContentDescription("New tab").performClick()
        assertEquals(1, newTabs)
    }

    /** The clickable row that shows [title]: its text is merged into the row's own node. */
    private fun row(title: String): SemanticsMatcher =
        hasClickAction() and hasText(title, substring = true)

    /** At the bottom of the window, as in the bar: the panel grows upward from the field. */
    @Composable
    private fun Editor(
        matches: List<RecentItem>,
        target: String? = null,
        offersDecision: Boolean = true,
        onDecision: (AddressDecision) -> Unit = {},
        onSelectTab: (String) -> Unit = {},
        onNewTab: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Content(matches, target, offersDecision, onDecision, onSelectTab, onNewTab, onDismiss)
    }

    @Composable
    private fun Content(
        matches: List<RecentItem>,
        target: String?,
        offersDecision: Boolean,
        onDecision: (AddressDecision) -> Unit,
        onSelectTab: (String) -> Unit,
        onNewTab: () -> Unit,
        onDismiss: () -> Unit,
    ) = AddressEditorContent(
        field = TextFieldValue("garden"),
        onFieldChange = {},
        matches = matches,
        offersDecision = offersDecision,
        target = target,
        onDecision = onDecision,
        onSelectTab = onSelectTab,
        onNewTab = onNewTab,
        onDismiss = onDismiss,
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

        val TAB_MATCH = RecentItem.OpenTab("tab-garden", "https://example.net/notes", "Garden notes", 2L, active = false, createdAt = 1L)
        val VISIT_MATCH = RecentItem.Visit("visit-1", "tab", "https://example.org/garden", "A garden through the seasons", 1L)
    }
}
