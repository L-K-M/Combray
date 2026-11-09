package ch.lkmc.combray.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.engine.ScrollEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BottomBarVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun controlsStartVisibleHideDownAndReturnUp() {
        assertTrue(bottomBarVisible(null, BottomBarBehavior.SCROLLING))
        assertFalse(bottomBarVisible(ScrollEdge.BOTTOM, BottomBarBehavior.SCROLLING))
        assertTrue(bottomBarVisible(ScrollEdge.TOP, BottomBarBehavior.SCROLLING))
    }

    @Test
    fun pinnedControlsIgnoreScrollDirection() {
        for (direction in listOf(null, ScrollEdge.TOP, ScrollEdge.BOTTOM)) {
            assertTrue(bottomBarVisible(direction, BottomBarBehavior.PINNED))
        }
    }

    @Test
    fun aHiddenBarStaysUntilTheEngineAnswersTheUncoveringRequest() {
        // The hide has not reached the engine: the latest request still covers the bar.
        assertTrue(bottomBarHeld(ViewportRequest(id = 4, inset = 290), painted = 4, expired = false))
        // Sent, unanswered: the engine has not drawn the uncovered pixels yet.
        assertTrue(bottomBarHeld(ViewportRequest(id = 5, inset = 0), painted = 4, expired = false))
        assertTrue(bottomBarHeld(ViewportRequest(id = 5, inset = 0), painted = null, expired = false))
        // Answered.
        assertFalse(bottomBarHeld(ViewportRequest(id = 5, inset = 0), painted = 5, expired = false))
    }

    @Test
    fun anAnswerToAnEarlierUncoveringDoesNotReleaseALaterOne() {
        // Hide (5), show (6), hide (7): the engine's late answer to 5 is not one to 7.
        assertTrue(bottomBarHeld(ViewportRequest(id = 7, inset = 0), painted = 5, expired = false))
        assertTrue(bottomBarHeld(ViewportRequest(id = 7, inset = 0), painted = 6, expired = false))
        assertFalse(bottomBarHeld(ViewportRequest(id = 7, inset = 0), painted = 8, expired = false))
    }

    @Test
    fun theHoldEndsWithoutAnEngineToAnswerOrAfterItsTimeout() {
        assertFalse(bottomBarHeld(ViewportRequest(id = null, inset = 0), painted = null, expired = false))
        assertFalse(bottomBarHeld(null, painted = null, expired = false))
        assertFalse(bottomBarHeld(ViewportRequest(id = 9, inset = 0), painted = 3, expired = true))
        assertFalse(bottomBarHeld(ViewportRequest(id = 9, inset = 290), painted = 3, expired = true))
    }

    @Test
    fun enabledServicesPinControlsAndDisablingThemRestoresScrolling() {
        val manager = accessibility()
        val shadow = shadowOf(manager)
        showAccessibilityPolicy()
        compose.onNodeWithText(SCROLLING).assertIsDisplayed()

        compose.runOnIdle {
            shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
            shadow.setEnabled(true)
        }
        compose.onNodeWithText(PINNED).assertIsDisplayed()

        compose.runOnIdle {
            shadow.setEnabledAccessibilityServiceList(emptyList())
            shadow.setEnabled(false)
        }
        compose.onNodeWithText(SCROLLING).assertIsDisplayed()
    }

    @Test
    fun touchExplorationKeepsControlsReachable() {
        shadowOf(accessibility()).setTouchExplorationEnabled(true)
        showAccessibilityPolicy()
        compose.onNodeWithText(PINNED).assertIsDisplayed()
    }

    @Test
    fun automationAloneDoesNotPinControls() {
        shadowOf(accessibility()).setEnabled(true)
        showAccessibilityPolicy()
        compose.onNodeWithText(SCROLLING).assertIsDisplayed()
    }

    private fun accessibility(): AccessibilityManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)

    private fun showAccessibilityPolicy() {
        compose.setContent {
            Text(if (rememberAccessibleControlsRequired()) PINNED else SCROLLING)
        }
    }

    private companion object {
        const val PINNED = "pinned"
        const val SCROLLING = "scrolling"
    }
}
