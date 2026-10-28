package ch.lkmc.combray

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Touch direction and root-page endpoints against real Servo, including CSS smooth scrolling. */
@RunWith(AndroidJUnit4::class)
class ScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
        device.browser.openUrl(URL, Placement.CURRENT_TAB)
        awaitTitle("SCROLL-TOP")
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))
    }

    @Test
    fun verticalSwipesOfferTheMatchingEdgeAndTheButtonJumpsThere() {
        // Keep the button available while waiting for Servo to paint each endpoint.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(BOTTOM).assertDoesNotExist()
        swipe(0.65f, 0.35f)
        awaitTitle("SCROLL-MIDDLE")
        val down = compose.onNodeWithContentDescription(BOTTOM).assertIsDisplayed()
        val bounds = down.fetchSemanticsNode().boundsInRoot
        assertTrue("button is not at the bottom left", bounds.center.x < compose.activity.resources.displayMetrics.widthPixels / 2)
        down.performClick()
        // Servo paints programmatic root jumps without refreshing window.scrollY.
        // Viewport-high endpoint sections verify what is actually on screen instead.
        assertTrue(device.matches(device.awaitScreen(Device.BLUE), Device.BLUE))
        compose.onNodeWithContentDescription(BOTTOM).assertDoesNotExist()

        val previousTitle = device.browser.browserState.value.activeTab!!.title
        swipe(0.35f, 0.65f)
        device.awaitState { it.activeTab?.title != previousTitle && it.activeTab?.title?.substringBefore(':') == "SCROLL-MIDDLE" }
        compose.onNodeWithContentDescription(TOP).assertIsDisplayed().performClick()
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()
    }

    @Test
    fun aSnackbarOverThePageDoesNotTakeTheButtonsTap() {
        // Closing another tab shows "Tab closed" with Undo over the page's bottom-left corner; the
        // test clock keeps it up for the whole check, which does not rely on an earlier class's tabs.
        val scrollTab = device.browser.browserState.value.activeTab!!.id
        device.browser.openUrl(Fixtures.EMPTY, Placement.NEW_TAB)
        device.awaitPage(Fixtures.EMPTY)
        device.browser.closeTab(device.browser.browserState.value.activeTab!!.id)
        device.awaitState { it.activeTab?.id == scrollTab }
        awaitTitle("SCROLL-TOP")
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))

        swipe(0.65f, 0.35f)
        awaitTitle("SCROLL-MIDDLE")
        compose.onNodeWithText(compose.activity.getString(R.string.action_undo)).assertIsDisplayed()
        compose.onNodeWithContentDescription(BOTTOM).assertIsDisplayed().performClick()
        assertTrue(device.matches(device.awaitScreen(Device.BLUE), Device.BLUE))
    }

    @Test
    fun reloadingTheSameAddressClearsTheOldButton() {
        swipe(0.65f, 0.35f)
        awaitTitle("SCROLL-MIDDLE")
        compose.onNodeWithContentDescription(BOTTOM).assertIsDisplayed()
        val oldPage = device.browser.browserState.value.activeTab!!.page
        device.browser.reload()
        device.awaitState { it.activeTab?.page != oldPage && it.activeTab?.title?.substringBefore(':') == "SCROLL-TOP" }
        compose.onNodeWithContentDescription(BOTTOM).assertDoesNotExist()
    }

    @Test
    fun scrollingRenewsTheButtonAndIdleFadesItInBothDirections() {
        compose.mainClock.autoAdvance = false
        swipe(0.65f, 0.35f)
        awaitTitle("SCROLL-MIDDLE")
        compose.onNodeWithContentDescription(BOTTOM).assertIsDisplayed()

        repeat(3) {
            compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
            swipe(0.65f, 0.35f)
            compose.onNodeWithContentDescription(BOTTOM).assertIsDisplayed()
        }

        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(BOTTOM).assertDoesNotExist()

        swipe(0.35f, 0.65f)
        compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
        compose.onNodeWithContentDescription(TOP).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()
    }

    private fun swipe(start: Float, end: Float) {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, metrics.heightPixels * fraction, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        for (step in 1..SWIPE_STEPS) {
            SystemClock.sleep(SWIPE_STEP_MS)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * step / SWIPE_STEPS)
        }
        send(MotionEvent.ACTION_UP, end)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun awaitTitle(expected: String) {
        try {
            device.awaitState { it.activeTab?.title?.substringBefore(':') == expected && it.activeTab?.loading == false }
        } catch (timeout: TimeoutCancellationException) {
            val state = device.browser.browserState.value
            throw AssertionError("Expected $expected; engine=${state.engine}, title=${state.activeTab?.title}, loading=${state.activeTab?.loading}", timeout)
        }
    }

    private companion object {
        const val URL = "${Fixtures.BASE}/scroll.html"
        const val TOP = "Scroll to top"
        const val BOTTOM = "Scroll to bottom"
        const val SWIPE_STEPS = 20
        const val SWIPE_STEP_MS = 30L
        const val SCROLL_INTERVAL_MS = 1_000L
        const val IDLE_WAIT_MS = 3_000L
    }
}
