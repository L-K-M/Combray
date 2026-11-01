package ch.lkmc.combray.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.TabCounter
import ch.lkmc.combray.core.browser.TabStep
import ch.lkmc.combray.core.integration.SaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Menu button's in-window overlay: a tap opens it on the spot, an upward drag highlights
 * the row under the thumb and releases on it. Sideways drags stay the toolbar's tab-switch
 * gesture (covered by [BottomBarSwipeTest]); here only that they never open the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class ToolbarMenuTest {
    @get:Rule val compose = createComposeRule()

    private val selected = mutableListOf<MoreAction>()
    private val steps = mutableListOf<TabStep>()
    private lateinit var state: ToolbarMenuState
    private lateinit var back: OnBackPressedDispatcher

    @Test
    fun aTapOpensTheMenuWithoutWaitingForAnimations() {
        showMenu()
        compose.mainClock.autoAdvance = false
        try {
            menu().performTouchInput { click() }
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = true
        }

        compose.runOnIdle { assertTrue(state.expanded) }
        scrim().assertIsDisplayed()
        row("Reload").assertIsDisplayed()
    }

    @Test
    fun aRowTapPerformsItsActionOnce() {
        showMenu()
        menu().performTouchInput { click() }

        row("Reload").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MoreAction.RELOAD), selected)
            assertFalse(state.expanded)
        }

        // The same tap reopens; the next row acts once too.
        menu().performTouchInput { click() }
        row("Settings").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MoreAction.RELOAD, MoreAction.SETTINGS), selected)
            assertFalse(state.expanded)
        }
    }

    @Test
    fun tappingOutsideTheMenuDismissesIt() {
        showMenu()
        menu().performTouchInput { click() }
        scrim().assertIsDisplayed()

        // Top-left of the window: the menu surface is at bottom-end, only the scrim is here.
        compose.onRoot().performTouchInput { click(Offset(40f, 40f)) }

        compose.runOnIdle {
            assertFalse(state.expanded)
            assertTrue(selected.isEmpty())
        }
        scrim().assertDoesNotExist()
    }

    @Test
    fun backDismissesTheMenu() {
        showMenu()
        menu().performTouchInput { click() }
        scrim().assertIsDisplayed()

        compose.runOnIdle { back.onBackPressed() }

        compose.runOnIdle {
            assertFalse(state.expanded)
            assertTrue(selected.isEmpty())
        }
        scrim().assertDoesNotExist()
    }

    @Test
    fun anUpwardDragSelectsTheRowUnderTheThumbOnRelease() {
        showMenu()
        startDrag()

        val target = row("Reload").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(0, target) }
        compose.runOnIdle { assertEquals(MoreAction.RELOAD, state.selected) }

        compose.onRoot().performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals(listOf(MoreAction.RELOAD), selected)
            assertFalse(state.expanded)
            assertTrue(steps.isEmpty())
        }
        scrim().assertDoesNotExist()
    }

    @Test
    fun releasingOutsideTheRowsSelectsNothing() {
        showMenu()
        startDrag()
        row("Reload").assertIsDisplayed()

        compose.onRoot().performTouchInput { moveTo(0, Offset(40f, 40f)) }
        compose.runOnIdle { assertNull(state.selected) }
        compose.onRoot().performTouchInput { up(0) }

        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertFalse(state.expanded)
        }
        scrim().assertDoesNotExist()
    }

    @Test
    fun aCancelledDragNeverSelectsTheHighlightedRow() {
        showMenu()
        startDrag()

        val target = row("Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            moveTo(0, target)
            cancel()
        }

        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertFalse(state.expanded)
        }
        scrim().assertDoesNotExist()
    }

    @Test
    fun aSecondFingerDoesNotTriggerTheHighlightedRow() {
        showMenu()
        startDrag()

        val target = row("Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(0, target) }
        compose.runOnIdle { assertEquals(MoreAction.SETTINGS, state.selected) }

        // The scrim owns the window while the menu is open, so a second finger can only tap
        // it: that dismisses, and the drag's highlighted row must not fire on release.
        compose.onRoot().performTouchInput {
            down(1, Offset(40f, 40f))
            up(1)
        }
        compose.runOnIdle {
            assertFalse(state.expanded)
            assertTrue(selected.isEmpty())
        }

        compose.onRoot().performTouchInput { up(0) }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        scrim().assertDoesNotExist()
    }

    @Test
    fun aSecondFingerDownCancelsTheHeldMenuGesture() {
        showMenu()
        startDrag()
        val target = row("Settings").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(0, target) }
        compose.runOnIdle { assertEquals(MoreAction.SETTINGS, state.selected) }

        compose.onRoot().performTouchInput {
            down(1, Offset(40f, 40f))
            up(0)
            up(1)
        }
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertFalse(state.expanded)
        }
    }

    @Test
    fun backCancelsTheDragEvenWhenTheFirstFingerMovesAgain() {
        showMenu()
        startDrag()
        val target = row("Settings").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(0, target) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertFalse(state.expanded) }

        compose.onRoot().performTouchInput { moveBy(0, Offset(0f, -1f)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up(0) }
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertFalse(state.expanded)
        }
    }

    @Test
    fun aSidewaysDragDoesNotOpenTheMenu() {
        showMenu()
        menu().performTouchInput { down(0, center) }
        // Below the tab-switch distance, so no tab step fires either; the bar covers that side.
        compose.onRoot().performTouchInput {
            moveBy(0, Offset(horizontalDrag(), 0f))
            up(0)
        }

        compose.runOnIdle {
            assertFalse(state.expanded)
            assertTrue(selected.isEmpty())
        }
        scrim().assertDoesNotExist()
    }

    /** Presses Menu and drags upward past slop, then waits until the overlay is composed. */
    private fun startDrag() {
        menu().performTouchInput { down(0, center) }
        compose.onRoot().performTouchInput { moveBy(0, Offset(0f, -with(compose.density) { 40.dp.toPx() })) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.expanded) }
    }

    private fun horizontalDrag() = with(compose.density) { 40.dp.toPx() }

    private fun menu() = compose.onNodeWithContentDescription("Menu")

    private fun scrim() = compose.onNodeWithContentDescription("Close menu")

    private fun row(label: String) = compose.onNodeWithText(label)

    private fun showMenu(tab: ActiveTab = PAGE) {
        state = ToolbarMenuState()
        compose.setContent {
            val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            SideEffect { back = owner.onBackPressedDispatcher }
            CombrayTheme {
                Box(Modifier.fillMaxSize()) {
                    BottomBar(
                        tab = tab,
                        openTabs = 3,
                        tabCounter = TabCounter.HIDDEN,
                        saveStatus = PageSaveStatus(tab.url, SaveStatus.Unsaved),
                        histerStored = false,
                        backEnabled = true,
                        onBack = {},
                        onAddress = {},
                        onSave = {},
                        onCloseTab = {},
                        readerDisplay = ReaderDisplay.PAGE,
                        onStop = {},
                        onReader = {},
                        onHister = {},
                        onKarakeep = {},
                        onNewTab = {},
                        onRecent = {},
                        onRecentLongPress = {},
                        menuButton = { ToolbarMenuButton(state, onSelect) },
                        onCopyAddress = {},
                        onSwipeTab = { steps += it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    // Like BrowserScreen: the selection callback dismisses first, then acts.
                    ToolbarMenu(state, tab, ReaderDisplay.PAGE, onSelect)
                }
            }
        }
    }

    private val onSelect: (MoreAction) -> Unit = { action ->
        state.dismiss()
        selected += action
    }

    private companion object {
        val PAGE = ActiveTab("tab", "https://example.org/", "Example", false, true, false, false, true)
    }
}
