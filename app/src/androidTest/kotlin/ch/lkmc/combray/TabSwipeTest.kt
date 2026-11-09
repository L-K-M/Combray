package ch.lkmc.combray

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.TabStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A toolbar swipe selects the logical tab and repaints its real Servo view. */
@RunWith(AndroidJUnit4::class)
class TabSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val swipeDistancePx get() = with(compose.density) { 96.dp.toPx() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun swipingTheAddressShowsThePreviousTabsPage() {
        switchFrom("Edit address")
    }

    @Test
    fun swipingAButtonSwitchesInsteadOfPressingIt() {
        switchFrom("New tab")
    }

    @Test
    fun swipesWalkTheStaticTabOrderWithoutWrapping() {
        device.browser.openUrl(Fixtures.solid(Device.GREEN), Placement.NEW_TAB)
        val a = device.awaitPage(Fixtures.solid(Device.GREEN)).activeTab!!.id
        device.browser.openUrl(Fixtures.solid(Device.BLUE), Placement.NEW_TAB)
        val b = device.awaitPage(Fixtures.solid(Device.BLUE)).activeTab!!.id
        device.browser.openUrl(Fixtures.solid(Device.RED), Placement.NEW_TAB)
        val c = device.awaitPage(Fixtures.solid(Device.RED)).activeTab!!.id
        awaitTab(c, Device.RED)

        swipeBar(TabStep.OLDER)
        awaitTab(b, Device.BLUE)
        // Longer than the old swipe run: the fixed order must still reach A, not restart at C.
        Thread.sleep(ORDER_STABILITY_PAUSE_MS)
        swipeBar(TabStep.OLDER)
        awaitTab(a, Device.GREEN)
        // Older than the oldest tab there is nothing: the walk ends, it does not wrap.
        swipeBar(TabStep.OLDER)
        awaitTab(a, Device.GREEN)
        swipeBar(TabStep.NEWER)
        awaitTab(b, Device.BLUE)
        swipeBar(TabStep.NEWER)
        awaitTab(c, Device.RED)
        // Nor does it wrap past the newest tab back to the oldest.
        swipeBar(TabStep.NEWER)
        awaitTab(c, Device.RED)
    }

    private fun switchFrom(label: String) {
        device.browser.openUrl(Fixtures.solid(Device.GREEN), Placement.NEW_TAB)
        val first = device.awaitPage(Fixtures.solid(Device.GREEN)).activeTab!!.id
        device.browser.openUrl(Fixtures.solid(Device.BLUE), Placement.NEW_TAB)
        val second = device.awaitPage(Fixtures.solid(Device.BLUE)).activeTab!!.id
        assertTrue(device.matches(device.awaitScreen(Device.BLUE), Device.BLUE))

        compose.onNodeWithContentDescription(label, substring = true).performTouchInput {
            down(center)
            moveBy(Offset(swipeDistancePx, 0f))
        }
        compose.onNodeWithContentDescription("Older tab").assertIsDisplayed()
        assertEquals(second, device.browser.browserState.value.activeTab?.id)
        assertTrue(device.matches(device.awaitScreen(Device.BLUE), Device.BLUE))
        compose.onRoot().performTouchInput { up() }

        val state = device.awaitState { it.activeTab?.id == first }
        assertEquals(2, state.openTabCount)
        compose.onNodeWithContentDescription("Older tab").assertDoesNotExist()
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))
    }

    /** A full swipe along the bottom bar: drag past the switch distance, then release. */
    private fun swipeBar(step: TabStep) {
        val offset = if (step == TabStep.OLDER) swipeDistancePx else -swipeDistancePx
        compose.onNodeWithContentDescription("Edit address", substring = true).performTouchInput {
            down(center)
            moveBy(Offset(offset, 0f))
            up()
        }
    }

    /** After the swipe settled: the focused tab's identity, the open count and the painted colour. */
    private fun awaitTab(id: String, color: Int) {
        device.idle()
        val state = device.awaitState { it.activeTab?.id == id }
        assertEquals(3, state.openTabCount)
        assertTrue(device.matches(device.awaitScreen(color), color))
    }

    private companion object {
        /** Exceeds the removed 3 s swipe run: a pause between swipes must not reorder the walk. */
        const val ORDER_STABILITY_PAUSE_MS = 4_000L
    }
}
