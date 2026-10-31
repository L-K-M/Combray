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
