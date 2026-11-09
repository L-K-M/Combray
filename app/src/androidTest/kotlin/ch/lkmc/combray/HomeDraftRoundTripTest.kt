package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home draft round trips through the real browser screen and the real bottom bar.
 *
 * The Home search field shares the address editor. Its draft is held by a
 * `SaveableStateProvider` in the bottom bar's home slot, and the editor's saver validates a
 * restored snapshot against the current `(target, restartKey)` identity. These checks pin both
 * halves on a device: a typed draft survives a page-tab round trip in the same entry, and a
 * one-shot shared-search prefill does not come back in a later Home entry opened by the bar's
 * actual + action.
 */
@RunWith(AndroidJUnit4::class)
class HomeDraftRoundTripTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    /**
     * A draft typed on Home must survive a trip to a real loaded page tab and back to the same
     * Home tab. The page check waits for the editable field to leave the composition, so the
     * retained draft is asserted on a field that was actually detached and recomposed.
     */
    @Test
    fun typedHomeDraftSurvivesAPageTabRoundTrip() {
        compose.onNodeWithContentDescription("New tab").performClick()
        val home = device.awaitState { it.activeTab?.isHome == true }.activeTab!!.id
        assertEquals(1, device.browser.browserState.value.openTabCount)

        val draft = "madeleine & garden"
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { fieldTextOrNull() == draft }
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)

        device.browser.openUrl(Fixtures.ARTICLE, Placement.NEW_TAB)
        device.awaitPage(Fixtures.ARTICLE)
        device.awaitState { it.activeTab?.title == "ARTICLE" }

        // The Home field is not composed while the page tab is shown.
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "the page tab still composes an editable field",
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("localhost").assertIsDisplayed()
        assertEquals(Fixtures.ARTICLE, device.browser.browserState.value.activeTab?.url)

        device.browser.selectTab(home)
        device.awaitState { it.activeTab?.id == home && it.activeTab?.isHome == true }
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { fieldTextOrNull() != null }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals(draft)
        assertEquals(home, device.browser.browserState.value.activeTab?.id)
        assertEquals(2, device.browser.browserState.value.openTabCount)
    }

    /**
     * A one-shot shared-search prefill reaches Home through the real BrowserScreen entry intent.
     * After a page-tab detour, the bar's actual + action starts a new logical Home entry: the
     * shared query must not be resurrected in the field. The new entry reuses the same blank tab,
     * so only `homeEntry` moved; an empty field is the saver's identity check rejecting the old
     * snapshot.
     */
    @Test
    fun aSharedSearchPrefillDoesNotResurrectInANewHomeEntry() {
        val activity = compose.activity
        val originalIntent = activity.intent
        val query = "one-time shared search"
        try {
            activity.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).setClass(activity, MainActivity::class.java)
                    .putExtra(SearchManager.QUERY, query),
            )
            compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
                compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().singleOrNull()
                    ?.config?.get(SemanticsProperties.EditableText)?.text == query
            }
            val prefilled = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
            assertEquals(TextRange(0, query.length), prefilled[SemanticsProperties.TextSelectionRange])
            val home = device.awaitState { it.activeTab?.isHome == true }.activeTab!!.id
            assertEquals(1, device.browser.browserState.value.openTabCount)

            device.browser.openUrl(Fixtures.ARTICLE, Placement.NEW_TAB)
            device.awaitPage(Fixtures.ARTICLE)
            device.awaitState { it.activeTab?.title == "ARTICLE" }

            compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "the page tab still composes an editable field",
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithText("localhost").assertIsDisplayed()

            // The bar's actual + path increments homeEntry; browser.newTab() alone would not.
            compose.onNodeWithContentDescription("New tab").performClick()
            device.awaitState { it.activeTab?.isHome == true && it.activeTab?.id == home }
            compose.waitUntil(Device.LOAD_TIMEOUT_MS) { fieldTextOrNull() != null }
            compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals("")
            assertEquals("", fieldTextOrNull())
            assertEquals(home, device.browser.browserState.value.activeTab?.id)
            assertEquals(2, device.browser.browserState.value.openTabCount)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = originalIntent }
        }
    }

    private fun fieldTextOrNull(): String? =
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().singleOrNull()
            ?.config?.get(SemanticsProperties.EditableText)?.text
}
