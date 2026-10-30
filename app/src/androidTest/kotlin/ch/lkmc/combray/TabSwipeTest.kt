package ch.lkmc.combray

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
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

    private fun switchFrom(label: String) {
        device.browser.openUrl(Fixtures.solid(Device.GREEN), Placement.NEW_TAB)
        val first = device.awaitPage(Fixtures.solid(Device.GREEN)).activeTab!!.id
        device.browser.openUrl(Fixtures.solid(Device.BLUE), Placement.NEW_TAB)
        device.awaitPage(Fixtures.solid(Device.BLUE))
        assertTrue(device.matches(device.awaitScreen(Device.BLUE), Device.BLUE))

        // End a previous test's 3 s swipe run before walking these newly created tabs.
        Thread.sleep(SWIPE_RUN_RESET_MS)
        val distance = with(compose.density) { 96.dp.toPx() }
        compose.onNodeWithContentDescription(label, substring = true).performTouchInput {
            swipe(center, center + Offset(distance, 0f))
        }

        val state = device.awaitState { it.activeTab?.id == first }
        assertEquals(2, state.openTabCount)
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))
    }

    private companion object {
        const val SWIPE_RUN_RESET_MS = 4_000L
    }
}
