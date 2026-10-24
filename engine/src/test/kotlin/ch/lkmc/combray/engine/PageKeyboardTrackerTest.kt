package ch.lkmc.combray.engine

import ch.lkmc.combray.engine.PageKeyboardTracker.KeyboardChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKeyboardTrackerTest {
    private val tracker = PageKeyboardTracker()

    @Test
    fun theUserClosingAPageFieldsKeyboardIsADismissalOnce() {
        tracker.showRequested()
        tracker.observed(onScreen = true)

        assertEquals(KeyboardChange.DISMISSED, tracker.observed(onScreen = false))
        assertEquals(KeyboardChange.NONE, tracker.observed(onScreen = false))
    }

    /**
     * The keyboard can already be on screen when a page field asks for it (it never left between
     * two requests, or the window reported it before the request): no opening follows, and the
     * user's closing must still reach the page.
     */
    @Test
    fun aKeyboardAlreadyOnScreenIsThePageFieldsAtOnce() {
        tracker.observed(onScreen = true)
        tracker.showRequested()

        assertEquals(KeyboardChange.DISMISSED, tracker.observed(onScreen = false))
    }

    @Test
    fun theAppHidingTheKeyboardIsNoDismissal() {
        tracker.showRequested()
        tracker.observed(onScreen = true)
        tracker.hideRequested()

        assertEquals(KeyboardChange.HIDDEN, tracker.observed(onScreen = false))
    }

    /** Focus moves between fields as a hide and a show; the keyboard may leave and come back. */
    @Test
    fun aKeyboardTheAppHidesAndAsksForAgainIsNoDismissalWhileItComesBack() {
        tracker.showRequested()
        tracker.observed(onScreen = true)
        tracker.hideRequested()
        tracker.showRequested()

        assertEquals(KeyboardChange.HIDDEN, tracker.observed(onScreen = false))
        tracker.observed(onScreen = true)
        assertEquals(KeyboardChange.DISMISSED, tracker.observed(onScreen = false))
    }

    @Test
    fun anAppHideThatFoundNoKeyboardLeavesTheNextOneThePages() {
        tracker.hideRequested()
        tracker.showRequested()
        tracker.observed(onScreen = true)

        assertEquals(KeyboardChange.DISMISSED, tracker.observed(onScreen = false))
    }

    @Test
    fun focusMovingBetweenFieldsKeepsAnOpenKeyboardThePages() {
        tracker.showRequested()
        tracker.observed(onScreen = true)
        tracker.showRequested()

        assertEquals(KeyboardChange.DISMISSED, tracker.observed(onScreen = false))
    }

    @Test
    fun aKeyboardNoPageFieldAskedForIsNoDismissal() {
        tracker.observed(onScreen = true)

        assertEquals(KeyboardChange.HIDDEN, tracker.observed(onScreen = false))
    }

    @Test
    fun resetReportsOnlyAKeyboardAPageFieldAskedFor() {
        assertFalse(tracker.reset())

        tracker.showRequested()
        assertTrue(tracker.reset())
        tracker.observed(onScreen = true)
        assertEquals(KeyboardChange.HIDDEN, tracker.observed(onScreen = false))
    }
}
