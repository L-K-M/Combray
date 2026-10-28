package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import ch.lkmc.combray.engine.ScrollEdge
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ScrollEdgeButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theButtonDisappearsAfterScrollingStops() {
        compose.setContent {
            CombrayTheme { ScrollEdgeButton(ScrollEdge.TOP, {}) }
        }
        compose.mainClock.autoAdvance = false

        compose.onNodeWithContentDescription(TOP).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()
    }

    @Test
    fun continuedScrollingInTheSameDirectionRestartsTheTimeout() {
        var activity by mutableIntStateOf(0)
        compose.setContent {
            CombrayTheme { ScrollEdgeButton(ScrollEdge.TOP, {}, scrollActivity = activity) }
        }
        compose.mainClock.autoAdvance = false

        repeat(3) {
            compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
            compose.onNodeWithContentDescription(TOP).assertIsDisplayed()
            compose.runOnIdle { activity++ }
            compose.mainClock.advanceTimeByFrame()
        }

        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()
    }

    @Test
    fun scrollingAgainRevealsAClickableButton() {
        var activity by mutableIntStateOf(0)
        var clicked = false
        compose.setContent {
            CombrayTheme { ScrollEdgeButton(ScrollEdge.TOP, { clicked = true }, scrollActivity = activity) }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()

        compose.runOnIdle { activity++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SCROLL_INTERVAL_MS)
        compose.onNodeWithContentDescription(TOP).assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun accessibilityCanExtendTheTimeToUseTheButton() {
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ): Long {
                assertTrue(containsIcons && containsControls)
                return ACCESSIBLE_TIMEOUT_MS
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                CombrayTheme { ScrollEdgeButton(ScrollEdge.TOP, {}) }
            }
        }
        compose.mainClock.autoAdvance = false

        compose.mainClock.advanceTimeBy(IDLE_WAIT_MS)
        compose.onNodeWithContentDescription(TOP).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(ACCESSIBLE_TIMEOUT_MS)
        compose.onNodeWithContentDescription(TOP).assertDoesNotExist()
    }

    private companion object {
        const val TOP = "Scroll to top"
        const val IDLE_WAIT_MS = 3_000L
        const val SCROLL_INTERVAL_MS = 1_000L
        const val ACCESSIBLE_TIMEOUT_MS = 6_000L
    }
}
