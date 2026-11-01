package ch.lkmc.combray

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Menu gestures in the real activity, above a live Servo page. */
@RunWith(AndroidJUnit4::class)
class ToolbarMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val url = Fixtures.solid(Device.GREEN)

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
        device.browser.openUrl(url, Placement.NEW_TAB)
        device.awaitPage(url)
    }

    @Test
    fun anUpwardDragOpensTextSizeWithoutChangingThePage() {
        val before = device.browser.browserState.value.activeTab!!
        startDrag()
        val target = compose.onNodeWithText(TEXT_SIZE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            moveTo(PRIMARY, target)
            up(PRIMARY)
        }

        compose.onNodeWithText(DONE).assertIsDisplayed().performClick()
        val after = device.browser.browserState.value
        assertEquals(before.id, after.activeTab!!.id)
        assertEquals(before.page, after.activeTab?.page)
        assertEquals(url, after.activeTab?.url)
        assertEquals(1, after.openTabCount)
        compose.onNodeWithContentDescription(MENU).assertIsDisplayed()
    }

    @Test
    fun homeKeepsItsLabeledMenuAction() {
        device.browser.newTab()
        device.awaitState { it.activeTab?.isHome == true }
        compose.onNodeWithText(MENU).assertIsDisplayed().performClick()
        compose.onNodeWithText(SETTINGS).assertIsDisplayed()
        compose.onNodeWithContentDescription(CLOSE_MENU).performClick()
        compose.onNodeWithText(MENU).assertIsDisplayed()
    }

    @Test
    fun shareRemainsInTheMenuRatherThanThePageBar() {
        compose.onNodeWithContentDescription(SHARE).assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU).assertIsDisplayed().performClick()
        compose.onNodeWithText(SHARE).assertIsDisplayed()
        compose.onNodeWithText(RELOAD).assertIsDisplayed()
        compose.onNodeWithContentDescription(CLOSE_MENU).performClick()
        compose.onNodeWithText(SHARE).assertDoesNotExist()
        assertEquals(url, device.browser.browserState.value.activeTab?.url)
    }

    @Test
    fun anotherFingerCancelsTheDragWithoutOpeningSettings() {
        startDrag()
        val target = compose.onNodeWithText(SETTINGS).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(PRIMARY, target) }
        val outside = Offset(with(compose.density) { 8.dp.toPx() }, target.y)
        compose.onRoot().performTouchInput {
            down(SECONDARY, outside)
            up(PRIMARY)
            up(SECONDARY)
        }

        compose.onNodeWithText(SETTINGS).assertDoesNotExist()
        compose.onNodeWithContentDescription(CLOSE_MENU).assertDoesNotExist()
        assertEquals(url, device.browser.browserState.value.activeTab?.url)
        assertEquals(1, device.browser.browserState.value.openTabCount)
    }

    private fun startDrag() {
        val start = compose.onNodeWithContentDescription(MENU).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        val distance = with(compose.density) { OPEN_DRAG.toPx() }
        compose.onRoot().performTouchInput {
            down(PRIMARY, start)
            moveTo(PRIMARY, start - Offset(0f, distance))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(CLOSE_MENU).assertIsDisplayed()
    }

    private companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val MENU = "Menu"
        const val CLOSE_MENU = "Close menu"
        const val TEXT_SIZE = "Text size"
        const val DONE = "Done"
        const val SHARE = "Share"
        const val RELOAD = "Reload"
        const val SETTINGS = "Settings"
        val OPEN_DRAG = 40.dp
    }
}
