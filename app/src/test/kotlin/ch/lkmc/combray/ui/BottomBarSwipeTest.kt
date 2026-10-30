package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.TabCounter
import ch.lkmc.combray.core.browser.TabStep
import ch.lkmc.combray.core.integration.SaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class BottomBarSwipeTest {
    @get:Rule val compose = createComposeRule()

    private val steps = mutableListOf<TabStep>()
    private val clicks = mutableListOf<String>()

    @Test
    fun swipesStartOnTheAddressAndEveryButtonInTheStackedBar() {
        showBar(360.dp)
        swipeEveryControl()
    }

    @Test
    fun swipesStartOnTheAddressAndEveryButtonInTheSingleRowBar() {
        showBar(600.dp)
        swipeEveryControl()
    }

    @Test
    fun physicalSwipeDirectionsStayTheSameInRtl() {
        showBar(direction = LayoutDirection.Rtl)
        swipeEveryControl()
    }

    @Test
    fun theIdleHomeDockAlsoAcceptsSwipes() {
        showBar(tab = PAGE.copy(url = null, hasView = false))
        address().performTouchInput { swipe(center, center + Offset(swipeDistance(), 0f)) }

        compose.runOnIdle { assertEquals(listOf(TabStep.OLDER), steps) }
    }

    @Test
    fun editingHomeDoesNotSwitchTabs() {
        showBar(tab = PAGE.copy(url = null, hasView = false), mode = AddressEditorMode.EDITING)
        address().performTouchInput { swipe(center, center + Offset(swipeDistance(), 0f)) }

        compose.runOnIdle { assertTrue(steps.isEmpty()) }
    }

    @Test
    fun aCancelledSwipeDoesNotSwitchTabs() {
        showBar()
        address().performTouchInput {
            down(center)
            moveBy(Offset(swipeDistance(), 0f))
            cancel()
        }

        compose.runOnIdle {
            assertTrue(steps.isEmpty())
            assertTrue(clicks.isEmpty())
        }
    }

    @Test
    fun aSecondFingerCancelsTheTabSwipe() {
        showBar()
        address().performTouchInput {
            down(0, center)
            moveBy(0, Offset(swipeDistance(), 0f))
            down(1, center - Offset(20f, 0f))
            up(1)
            up(0)
        }

        compose.runOnIdle {
            assertTrue(steps.isEmpty())
            assertTrue(clicks.isEmpty())
        }
    }

    @Test
    fun aVerticalDragCannotBecomeATabSwipe() {
        showBar()
        address().performTouchInput {
            down(center)
            moveBy(Offset(0f, swipeDistance() / 2))
            moveBy(Offset(swipeDistance(), 0f))
            up()
        }

        compose.runOnIdle { assertTrue(steps.isEmpty()) }
    }

    @Test
    fun aShortDragDoesNotSwitchAndTapsStillWork() {
        showBar()
        address().performTouchInput { swipe(center, center + Offset(swipeDistance() / 3, 0f)) }
        compose.runOnIdle { assertTrue(steps.isEmpty()) }

        address().performTouchInput { click() }
        compose.onNodeWithContentDescription("New tab").performTouchInput { click() }
        compose.onNodeWithContentDescription("Recent, 3 open tabs").performTouchInput { click() }

        compose.runOnIdle { assertEquals(listOf("address", "new", "recent"), clicks) }
    }

    @Test
    fun longPressShortcutsStillWork() {
        showBar()
        address().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Recent, 3 open tabs").performTouchInput { longClick() }

        compose.runOnIdle {
            assertEquals(listOf("copy", "previous"), clicks)
            assertTrue(steps.isEmpty())
        }
    }

    private fun swipeEveryControl() {
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val controls = listOf(address()) + listOf("Back", "Save", "Share", "New tab", "Recent, 3 open tabs", "More")
            .map { compose.onNodeWithContentDescription(it) }
        val expected = mutableListOf<TabStep>()
        controls.forEach { control ->
            val start = control.fetchSemanticsNode().boundsInRoot.center
            val right = start.x < bar.center.x
            val distance = if (right) swipeDistance() else -swipeDistance()
            compose.onRoot().performTouchInput { swipe(start, start + Offset(distance, 0f)) }
            expected += if (right) TabStep.OLDER else TabStep.NEWER
            compose.runOnIdle {
                assertEquals(expected, steps)
                assertTrue(clicks.isEmpty())
            }
        }
    }

    private fun address(): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription("Edit address", substring = true)

    private fun swipeDistance() = with(compose.density) { 96.dp.toPx() }

    private fun showBar(
        width: Dp = 360.dp,
        tab: ActiveTab = PAGE,
        mode: AddressEditorMode = AddressEditorMode.IDLE,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                CombrayTheme {
                    Box(Modifier.width(width).testTag("bar")) {
                        BottomBar(
                            tab = tab,
                            openTabs = 3,
                            tabCounter = TabCounter.HIDDEN,
                            saveStatus = PageSaveStatus(tab.url, SaveStatus.Unsaved),
                            histerStored = false,
                            backEnabled = true,
                            onBack = { clicks += "back" },
                            onAddress = { clicks += "address" },
                            onSave = { clicks += "save" },
                            onShare = { clicks += "share" },
                            onHister = { clicks += "hister" },
                            onKarakeep = { clicks += "karakeep" },
                            onNewTab = { clicks += "new" },
                            onRecent = { clicks += "recent" },
                            onRecentLongPress = { clicks += "previous" },
                            onMore = { clicks += "more" },
                            onCopyAddress = { clicks += "copy" },
                            onSwipeTab = { steps += it },
                            homeMode = mode,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val PAGE = ActiveTab("tab", "https://example.org/", "Example", false, true, false, false, true)
    }
}
