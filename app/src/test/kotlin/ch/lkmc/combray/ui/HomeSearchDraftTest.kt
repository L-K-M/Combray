package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.TabCounter
import ch.lkmc.combray.core.integration.SaveStatus
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's search field shares the address editor with the page's address sheet. The editor's
 * draft is `rememberSaveable` keyed by `(target, restartKey)`, so it survives rotation but not
 * a tab switch: the home slot leaves composition with the bottom bar, and the draft goes with
 * it. PR 283 wraps the home slot in a `SaveableStateProvider` to keep it.
 *
 * These tests pin both halves of that change against the real [BottomBar] and the real
 * [AddressEditor]. The first is the regression: a draft typed on Home must survive a page-tab
 * round trip. The second is the positive control, applying the proposed holder in the test so
 * the assertion is shown to detect retention. The rest pin the fresh-entry rule the fix must
 * not break: a new logical Home entry (a `restartKey` increment, as `editNewTabWith` does)
 * starts empty, whether `newTab()` reuses the blank tab or creates a new identity. The last two
 * characterize the shared-prefill reset that [ch.lkmc.combray.HomeSearchTest] guards on a
 * device: the editor's Close clears the launch prefill, and closing the tab then clears the
 * field.
 *
 * The holder is test scaffolding only, never production logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class HomeSearchDraftTest {
    @get:Rule val compose = createComposeRule()

    private var shownTab by mutableStateOf<ActiveTab?>(HOME)
    private var homeText by mutableStateOf("")
    private var restartKey by mutableIntStateOf(0)

    /** The regression: a draft typed on Home is lost when the user switches to a page and back. */
    @Test
    fun homesTypedDraftSurvivesLeavingAndReturning() {
        showBar(hold = false)
        typeDraft("madeleine")

        compose.runOnIdle { shownTab = PAGE }
        compose.runOnIdle { shownTab = HOME }

        assertEquals("madeleine", fieldText())
    }

    /** Positive control: the same assertion passes when the slot's state is held, as PR 283 does. */
    @Test
    fun aHeldHomeSlotRetainsTheDraftAcrossTabSwitches() {
        showBar(hold = true)
        typeDraft("madeleine")

        compose.runOnIdle { shownTab = PAGE }
        compose.runOnIdle { shownTab = HOME }

        assertEquals("madeleine", fieldText())
    }

    /** A new logical Home entry starts empty on current main, before any fix. */
    @Test
    fun aFreshHomeEntryStartsEmptyOnCurrentMain() {
        showBar(hold = false)
        typeDraft("madeleine")

        compose.runOnIdle { shownTab = PAGE }
        enterNewHome(HOME, key = 1)

        assertEquals("", fieldText())
    }

    /**
     * The blocker for PR 283's constant holder key: after a page-tab detour, a new logical Home
     * entry restores the earlier draft. `newTab()` reuses the unused blank home tab, so the tab
     * id is unchanged and only `homeEntry` moves; the editor's `(target, restartKey)` inputs are
     * not compared against the restored state on first composition, so the holder's saved value
     * wins over the fresh entry's empty prefill.
     */
    @Test
    fun aHeldHomeSlotResurrectsTheDraftInANewHomeEntry() {
        showBar(hold = true)
        typeDraft("madeleine")

        compose.runOnIdle { shownTab = PAGE }
        enterNewHome(HOME, key = 1)

        assertEquals("", fieldText())
    }

    /** The same blocker when no blank tab is available and `newTab()` creates a new identity. */
    @Test
    fun aHeldHomeSlotResurrectsTheDraftWhenANewTabIsCreated() {
        showBar(hold = true)
        typeDraft("madeleine")

        compose.runOnIdle { shownTab = PAGE }
        enterNewHome(NEW_HOME, key = 1)

        assertEquals("", fieldText())
    }

    /**
     * The privacy form of the blocker: a one-time shared prefill followed by a page-tab detour
     * and a new logical Home entry resurrects the shared query, because the constant holder key
     * restores the saved field over the fresh entry's empty prefill.
     */
    @Test
    fun aHeldHomeSlotResurrectsTheSharedPrefillInANewHomeEntry() {
        homeText = SHARED
        showBar(hold = true)
        assertEquals(SHARED, fieldText())

        compose.runOnIdle { shownTab = PAGE }
        enterNewHome(HOME, key = 1)

        assertEquals("", fieldText())
    }

    /**
     * The existing reset: the editor's Close clears the launch prefill while the field keeps its
     * draft, and closing the tab then clears the field. Closing the home slot's target changes
     * the editor's `(target, restartKey)` inputs, so it re-initializes from the cleared prefill.
     */
    @Test
    fun closingTheHomeTabClearsTheSharedPrefill() {
        homeText = SHARED
        showBar(hold = false)
        assertEquals(SHARED, fieldText())

        compose.runOnIdle { homeText = "" }
        assertEquals(SHARED, fieldText())

        compose.runOnIdle { shownTab = null }
        assertEquals("", fieldText())
    }

    /**
     * The proposed holder does not break that reset: closing the home tab keeps the home slot
     * composed (its target changes, it does not leave composition), so the editor's input change
     * still re-initializes the field from the cleared prefill.
     */
    @Test
    fun aHeldHomeSlotStillClearsTheSharedPrefillWhenTheTabCloses() {
        homeText = SHARED
        showBar(hold = true)
        assertEquals(SHARED, fieldText())

        compose.runOnIdle { homeText = "" }
        assertEquals(SHARED, fieldText())

        compose.runOnIdle { shownTab = null }
        assertEquals("", fieldText())
    }

    /** A new logical Home entry: `homeText` cleared and `homeEntry` incremented, as + does. */
    private fun enterNewHome(tab: ActiveTab, key: Int) {
        compose.runOnIdle {
            shownTab = tab
            restartKey = key
            homeText = ""
        }
    }

    private fun typeDraft(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    private fun fieldText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun showBar(hold: Boolean) {
        compose.setContent {
            CombrayTheme {
                val holder = rememberSaveableStateHolder()
                Box(Modifier.width(600.dp)) {
                    BottomBar(
                        tab = shownTab,
                        openTabs = 2,
                        tabCounter = TabCounter.HIDDEN,
                        saveStatus = PageSaveStatus(shownTab?.url, SaveStatus.Unsaved),
                        histerStored = false,
                        backEnabled = false,
                        onBack = {},
                        onAddress = {},
                        onSave = {},
                        onCloseTab = {},
                        readerDisplay = ReaderDisplay.PAGE,
                        onStop = {},
                        onReader = {},
                        onPlayMedia = null,
                        onHister = {},
                        onKarakeep = {},
                        onNewTab = {},
                        onRecent = {},
                        onRecentLongPress = {},
                        menuButton = {},
                        onCopyAddress = {},
                        onSwipeTab = {},
                        homeMode = AddressEditorMode.EDITING,
                        homeSearch = {
                            val editor: @Composable () -> Unit = {
                                AddressEditor(
                                    target = shownTab?.id,
                                    initialText = homeText,
                                    restartKey = restartKey,
                                    recent = { flowOf(emptyList()) },
                                    onDecision = {},
                                    onSelectTab = {},
                                    onNewTab = {},
                                    onDismiss = {},
                                    mode = AddressEditorMode.EDITING,
                                    placeholder = R.string.home_search_hint,
                                )
                            }
                            if (hold) holder.SaveableStateProvider(HOME_SLOT) { editor() } else editor()
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val HOME_SLOT = "home_search"
        const val SHARED = "one-time shared search"
        val HOME = ActiveTab("home", null, null, false, false, false, false, false)
        val NEW_HOME = ActiveTab("home2", null, null, false, false, false, false, false)
        val PAGE = ActiveTab("page", "https://example.org/", "Example", false, true, false, false, true)
    }
}
