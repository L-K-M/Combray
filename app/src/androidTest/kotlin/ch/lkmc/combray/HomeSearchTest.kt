package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.SessionState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home input regression without fixture services or web navigation. */
@RunWith(AndroidJUnit4::class)
class HomeSearchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitState { it.session == SessionState.RESTORED }
        device.closeAllTabs()
    }

    @Test
    fun newTabFocusesTheExistingHomeField() {
        compose.onNodeWithContentDescription("New tab").performClick()
        val blank = device.awaitState { it.activeTab?.isHome == true }.activeTab!!.id

        compose.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.home_search_hint)).assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()

        // A second + starts fresh in the same blank rather than adding another tab.
        compose.onNode(hasSetTextAction()).performTextInput("garden")
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.onNode(hasSetTextAction() and isFocused()).assertTextEquals("")
        device.idle()
        assertEquals(blank, device.browser.browserState.value.activeTab?.id)
        assertEquals(1, device.browser.browserState.value.openTabCount)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        assertEquals(blank, device.browser.browserState.value.activeTab?.id)
        compose.onNodeWithText("Hister").assertIsDisplayed()
    }

    @Test
    fun tappingTheHomeFieldKeepsItsDraftThroughRotationAndClose() {
        val draft = "madeleine & garden"
        compose.onNode(hasSetTextAction()).performClick().performTextInput(draft)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and isFocused()).assertTextEquals(draft)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextEquals(draft)
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction() and isFocused()).assertTextEquals(draft)
        assertEquals(0, device.browser.browserState.value.openTabCount)
    }

    @Test
    fun sharedSearchTextIsSelectedInHomeWithoutNavigating() {
        val activity = compose.activity
        val originalIntent = activity.intent
        try {
            for (query in listOf("madeleine & garden", "another search")) {
                activity.startActivity(
                    Intent(Intent.ACTION_WEB_SEARCH).setClass(activity, MainActivity::class.java)
                        .putExtra(SearchManager.QUERY, query),
                )
                compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
                    compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().singleOrNull()
                        ?.config?.get(SemanticsProperties.EditableText)?.text == query
                }
                compose.onNode(isDialog()).assertDoesNotExist()
                val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
                assertEquals(TextRange(0, query.length), field[SemanticsProperties.TextSelectionRange])
                device.idle()
                assertEquals(true, device.browser.browserState.value.activeTab?.isHome)
                assertEquals(1, device.browser.browserState.value.openTabCount)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = originalIntent }
        }
    }

    @Test
    fun recentNewTabReturnsToTheHomeField() {
        compose.onNodeWithText("Recent").performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("New tab").performClick()
        device.awaitState { it.activeTab?.isHome == true }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
    }
}
