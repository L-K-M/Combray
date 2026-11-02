package ch.lkmc.combray

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.FindOutcome
import ch.lkmc.combray.core.browser.Placement
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Find in page on the real engine: find.js counts the rendered matches, selects one (Servo paints
 * document selections) and scrolls to it; closing removes the selection. The fixture reports its
 * scroll offset and selected text in its title.
 */
@RunWith(AndroidJUnit4::class)
class FindTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    /** What the fixture says in its title: how far it scrolled and what is selected. */
    private data class PageReport(val scrollY: Int, val selection: String)

    private fun report(state: BrowserState): PageReport? {
        val match = state.activeTab?.title?.let { REPORT.matchEntire(it) } ?: return null
        return PageReport(match.groupValues[1].toInt(), match.groupValues[2])
    }

    private fun awaitReport(predicate: (PageReport) -> Boolean): PageReport =
        report(device.awaitState { state -> report(state)?.let(predicate) == true })!!

    private fun awaitMatch(index: Int): FindOutcome.Found =
        device.awaitState { (it.find?.outcome as? FindOutcome.Found)?.index == index }.find!!.outcome as FindOutcome.Found

    /** Opens the fixture and find over it, once the page has committed and reports. */
    private fun openFindPage(): PageReport {
        device.browser.openUrl(Fixtures.FIND, Placement.CURRENT_TAB)
        // The report title comes from the engine, so the page has committed (a new tab has its
        // address before).
        val loaded = device.awaitState { it.activeTab?.url == Fixtures.FIND && it.activeTab?.loading == false && report(it) != null }
        device.browser.openFind()
        device.awaitState { it.find != null }
        return report(loaded)!!
    }

    @Test
    fun aWordIsCountedSelectedAndScrolledToAndClosingClearsTheSelection() {
        val top = openFindPage()
        assertEquals(PageReport(0, ""), top)

        compose.onNode(hasSetTextAction()).performTextInput(NEEDLE)
        // Typing searches after a pause: let it pass on the test clock too.
        compose.mainClock.advanceTimeBy(TYPING_PAUSE_MS)
        // The hidden, script and form-field copies are not counted.
        assertEquals(FindOutcome.Found(3, 0, capped = false), awaitMatch(0))
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
        val first = awaitReport { it.selection == "combraneedle" && it.scrollY > 0 }

        // The keyboard's Search key goes to the next match, further down the page.
        compose.onNode(hasSetTextAction()).performImeAction()
        awaitMatch(1)
        val second = awaitReport { it.selection == "CombraNeedle" && it.scrollY > first.scrollY }

        compose.onNodeWithContentDescription("Next match").performClick()
        awaitMatch(2)
        val third = awaitReport { it.selection == "COMBRANEEDLE" && it.scrollY > second.scrollY }

        // Past the last match: back to the first one, up the page.
        compose.onNodeWithContentDescription("Next match").performClick()
        awaitMatch(0)
        awaitReport { it.selection == "combraneedle" && it.scrollY < third.scrollY }
        compose.onNodeWithContentDescription("Previous match").performClick()
        awaitMatch(2)
        awaitReport { it.selection == "COMBRANEEDLE" }

        compose.onNodeWithContentDescription("Close find in page").performClick()
        device.awaitState { it.find == null }
        awaitReport { it.selection.isEmpty() }
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    @Test
    fun backClosesFindBeforeGoingBackInThePage() {
        val earlier = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(earlier, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == earlier && it.activeTab?.title == Fixtures.title(Device.GREEN) }
        openFindPage()
        device.awaitState { it.activeTab?.canGoBack == true }
        // The UI enables its Back handling once it has drawn the find bar, as it has long before a
        // person presses Back. Under the Compose test rule it draws only when the test lets it;
        // before that, Android's default Back closes the activity.
        compose.onNodeWithContentDescription("Close find in page").assertIsDisplayed()

        // The first Back may only hide the keyboard.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (attempt in 1..BACK_ATTEMPTS) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            if (runCatching { device.awaitState(BACK_TIMEOUT_MS) { it.find == null } }.isSuccess) break
        }
        assertEquals(null, device.browser.browserState.value.find)
        // Had Back gone to the page instead, the earlier page would come back.
        Thread.sleep(BACK_TIMEOUT_MS)
        assertEquals(Fixtures.FIND, device.browser.browserState.value.activeTab?.url)
    }

    private companion object {
        const val NEEDLE = "combraneedle"
        val REPORT = Regex("FIND:(\\d+):(.*)")
        const val TYPING_PAUSE_MS = 500L
        const val BACK_ATTEMPTS = 2
        const val BACK_TIMEOUT_MS = 5_000L
    }
}
