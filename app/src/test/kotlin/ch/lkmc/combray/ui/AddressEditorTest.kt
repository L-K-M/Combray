package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The address editor as one piece: its draft, its local matches and the panel above the field.
 * The first two tests are the regression for the list that blanked on every key (each keystroke
 * cleared the matches until the next ranking, so the panel collapsed and grew again); the rest
 * pin what the panel offers and where.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp")
class AddressEditorTest {
    @get:Rule val compose = createComposeRule()

    private val decisions = mutableListOf<AddressDecision>()
    private val selected = mutableListOf<String>()
    private var dismissals = 0
    private val recent = MutableStateFlow(listOf<RecentItem>(TAB_MATCH, VISIT_MATCH))

    /** The regression: the next key keeps the rows it still matches, in the very next frame. */
    @Test
    fun typingOnKeepsTheMatchesThatStillMatch() {
        show()
        field().performTextInput("gard")
        compose.waitUntil(TIMEOUT_MS) { exists(row(TAB_TITLE)) && exists(row(VISIT_TITLE)) }

        // Frames after the key, before the pause that ranks again has passed.
        compose.mainClock.autoAdvance = false
        field().performTextInput("e")
        compose.mainClock.advanceTimeBy(BEFORE_RANKING_MS)
        assertTrue("the rows must not blank while the next ranking waits", exists(row(TAB_TITLE)))
        assertTrue(exists(row(VISIT_TITLE)))

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(exists(row(TAB_TITLE)) && exists(row(VISIT_TITLE)))
    }

    /** A row the text no longer matches never stands for it, not even for one frame. */
    @Test
    fun aKeyTheMatchesDoNotContainDropsThemAtOnce() {
        show()
        field().performTextInput("garden")
        compose.waitUntil(TIMEOUT_MS) { exists(row(TAB_TITLE)) }

        compose.mainClock.autoAdvance = false
        field().performTextInput("x")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertFalse(exists(row(TAB_TITLE)))
        assertFalse(exists(row(VISIT_TITLE)))
    }

    /** Recent changes while you type: rows still in it stay, a closed tab's row goes at once. */
    @Test
    fun aRecentChangeKeepsTheRowsStillInItAndDropsAClosedTab() {
        show()
        field().performTextInput("garden")
        compose.waitUntil(TIMEOUT_MS) { exists(row(TAB_TITLE)) && exists(row(VISIT_TITLE)) }

        compose.mainClock.autoAdvance = false
        // A new list: the tab closed, the visit is still there.
        recent.value = listOf(VISIT_MATCH.copy())
        // Delivers the new list (the frozen clock does not move), then frames well within the
        // pause before Recent is ranked again.
        compose.waitForIdle()
        repeat(FRAMES_BEFORE_RANKING) { compose.mainClock.advanceTimeByFrame() }
        assertFalse("a closed tab must not be offered", exists(row(TAB_TITLE)))
        assertTrue("the rows must not blank while Recent is ranked again", exists(row(VISIT_TITLE)))
    }

    /** Bottom up from the field: what Go does, the other reading, then the best match first. */
    @Test
    fun rowsStackUpwardFromTheFieldInOrderOfLikelihood() {
        show()
        field().performTextInput("garden")
        compose.waitUntil(TIMEOUT_MS) { exists(row(VISIT_TITLE)) }

        val field = bounds(hasSetTextAction())
        val search = bounds(row(SEARCH_GARDEN))
        val alternative = bounds(row(GO_TO_GARDEN))
        val tab = bounds(row(TAB_TITLE))
        val visit = bounds(row(VISIT_TITLE))
        assertTrue(search.bottom <= field.top)
        assertTrue(alternative.bottom <= search.top)
        assertTrue("the open tab ranks first, so it sits nearest", tab.bottom <= alternative.top)
        assertTrue(visit.bottom <= tab.top)
    }

    /** The page you are on offers nothing until you change its address: Go there changes nothing. */
    @Test
    fun thePagesOwnAddressOffersNothingUntilEdited() {
        show(initialText = VISIT_URL, target = "edited-tab", prefill = AddressPrefill.CURRENT_PAGE)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Go to", substring = true).assertDoesNotExist()

        field().performTextReplacement("garden")
        compose.onNode(row(SEARCH_GARDEN)).assertIsDisplayed()
    }

    /** Text another app shared is not where you are: what Go will do with it shows at once. */
    @Test
    fun sharedTextShowsWhatGoWillDoAtOnce() {
        show(initialText = "garden tools", prefill = AddressPrefill.DRAFT)
        compose.onNode(row("Search Kagi for “garden tools”")).assertIsDisplayed()
    }

    /** Go and a tap on the tinted row submit the same thing; the other reading is one tap away. */
    @Test
    fun goTheDefaultRowAndTheOtherReadingSubmitWhatTheySay() {
        show()
        field().performTextInput("garden")
        field().performImeAction()
        compose.onNode(row(SEARCH_GARDEN)).performClick()
        compose.onNode(row(GO_TO_GARDEN)).performClick()

        val reading = AddressPolicy.interpret("garden")
        assertEquals(listOf(reading.primary, reading.primary, reading.alternative), decisions)
    }

    /** Matches act as before: a tab row switches to that tab, a history row opens its address. */
    @Test
    fun matchRowsSwitchTabsOrOpenTheirAddress() {
        show()
        field().performTextInput("garden")
        compose.waitUntil(TIMEOUT_MS) { exists(row(VISIT_TITLE)) }

        compose.onNode(row(TAB_TITLE)).performClick()
        compose.onNode(row(VISIT_TITLE)).performClick()
        assertEquals(listOf(TAB_ID), selected)
        assertEquals(listOf(AddressPolicy.interpret(VISIT_URL).primary), decisions)
    }

    /** A refused address says why where the default row would be, and Go does nothing. */
    @Test
    fun aRefusedAddressExplainsItselfAndGoDoesNothing() {
        show()
        field().performTextInput("javascript:alert(1)")
        field().performImeAction()

        compose.onNodeWithText("Combray does not open this kind of address.").assertIsDisplayed()
        assertTrue(decisions.isEmpty())
    }

    /** ✕ empties the field and keeps you editing; ← leaves without submitting. */
    @Test
    fun clearEmptiesTheFieldAndCloseLeaves() {
        show(initialText = VISIT_URL, prefill = AddressPrefill.CURRENT_PAGE)
        compose.onNodeWithContentDescription("Clear").performClick()

        assertEquals("", compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithContentDescription("Clear").assertDoesNotExist()
        assertEquals(0, dismissals)

        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissals)
        assertTrue(decisions.isEmpty())
    }

    /** The bar turning into the editor is announced, as the sheet's window was. */
    @Test
    fun theEditorIsAnAnnouncedPane() {
        show()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Address or search")).assertExists()
    }

    /** ← and + flank the field on its row, and they and ✕ are each at least 48dp to touch. */
    @Test
    fun closeAndNewTabFlankTheField() {
        show(initialText = "garden")
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val close = compose.onNodeWithContentDescription("Close")
        val plus = compose.onNodeWithContentDescription("New tab")
        val clear = compose.onNodeWithContentDescription("Clear")
        assertTrue(close.getUnclippedBoundsInRoot().right <= field.left)
        assertTrue(plus.getUnclippedBoundsInRoot().left >= field.right)
        for (button in listOf(close, plus, clear)) {
            val touch = button.fetchSemanticsNode().touchBoundsInRoot
            with(compose.density) {
                assertTrue(touch.width.toDp() >= MIN_TOUCH && touch.height.toDp() >= MIN_TOUCH)
            }
        }
    }

    private fun show(
        initialText: String = "",
        target: String? = null,
        prefill: AddressPrefill = AddressPrefill.DRAFT,
    ) {
        compose.setContent {
            CombrayTheme {
                // Like the bar: anchored at the bottom, the panel grows upward.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Editor(initialText, target, prefill)
                }
            }
        }
    }

    @Composable
    private fun Editor(initialText: String, target: String?, prefill: AddressPrefill) = AddressEditor(
        target = target,
        initialText = initialText,
        restartKey = 0,
        recent = { recent },
        onDecision = { decisions += it },
        onSelectTab = { selected += it },
        onNewTab = {},
        onDismiss = { dismissals++ },
        prefill = prefill,
        focusKey = null,
    )

    private fun field() = compose.onNode(hasSetTextAction())

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    /** The clickable row that shows [text]: its texts are merged into the row's own node. */
    private fun row(text: String): SemanticsMatcher = hasClickAction() and hasText(text, substring = true)

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** Four frames: well within the editor's 150ms pause before it ranks again. */
        const val BEFORE_RANKING_MS = 64L
        const val FRAMES_BEFORE_RANKING = 4
        val MIN_TOUCH = Dp(48f)
        const val TAB_ID = "tab-garden"
        const val TAB_TITLE = "Garden notes"
        const val VISIT_TITLE = "A garden through the seasons"
        const val VISIT_URL = "https://example.org/garden"
        const val SEARCH_GARDEN = "Search Kagi for “garden”"

        /** The row's words before the host: the host and path are texts of their own. */
        const val GO_TO_GARDEN = "Go to https://"
        val TAB_MATCH = RecentItem.OpenTab(TAB_ID, "https://example.net/notes", TAB_TITLE, 2L, active = false, createdAt = 1L)
        val VISIT_MATCH = RecentItem.Visit("visit-1", "tab", VISIT_URL, VISIT_TITLE, 1L)
    }
}
