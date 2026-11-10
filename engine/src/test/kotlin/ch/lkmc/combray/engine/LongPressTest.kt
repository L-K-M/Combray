package ch.lkmc.combray.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTest {
    private val tracker = LongPressTracker(timeoutMs = TIMEOUT, slopPx = SLOP)

    @Test
    fun aPressHeldForTheTimeoutIsALongPressOnce() {
        tracker.press(10f, 10f, now = 0, PAGE)
        assertFalse(tracker.timerFired(now = TIMEOUT - 1, PAGE))
        assertTrue(tracker.timerFired(now = TIMEOUT, PAGE))
        assertFalse(tracker.timerFired(now = TIMEOUT + 100, PAGE))
    }

    /**
     * After a main-thread stall a tap's press and lift are handled late, back to back. Timing from
     * the event's own timestamp (as GestureDetector does) made the timer due before the queued
     * lift was read; timing from handling leaves the lift first.
     */
    @Test
    fun theHoldIsTimedFromWhenThePressWasHandled() {
        tracker.press(10f, 10f, now = 5_000, PAGE)
        assertFalse(tracker.timerFired(now = 5_000, PAGE))
        assertTrue(tracker.timerFired(now = 5_000 + TIMEOUT, PAGE))
    }

    @Test
    fun aLiftHandledBeforeTheTimerKeepsItATap() {
        tracker.press(10f, 10f, now = 0, PAGE)
        tracker.release()
        assertFalse(tracker.timerFired(now = 2_000, PAGE))
    }

    @Test
    fun movingBeyondTheSlopIsNotALongPress() {
        tracker.press(10f, 10f, now = 0, PAGE)
        tracker.move(10f + SLOP / 2, 10f)
        assertTrue(tracker.timerFired(now = TIMEOUT, PAGE))

        tracker.press(10f, 10f, now = 1_000, PAGE)
        tracker.move(10f, 10f + SLOP + 1)
        assertFalse(tracker.timerFired(now = 1_000 + TIMEOUT, PAGE))
    }

    /**
     * The timer acts on whatever page is shown when it runs. A page that replaced the pressed one
     * under a still finger (a redirect, a refresh, another tab) was never pressed: a menu there
     * would be for something the user did not choose.
     */
    @Test
    fun aPressDoesNotCarryOverToThePageThatReplacedIt() {
        tracker.press(10f, 10f, now = 0, PAGE)
        assertFalse(tracker.timerFired(now = TIMEOUT, PAGE.copy(document = 8)))
        // Nor once the first page is back: that press is over.
        assertFalse(tracker.timerFired(now = TIMEOUT + 1, PAGE))

        tracker.press(10f, 10f, now = 1_000, PAGE)
        assertFalse(tracker.timerFired(now = 1_000 + TIMEOUT, PageRef(ViewRef(tab = 2, generation = 1), document = 7)))

        // A press before the tab's first page is not one on that page.
        tracker.press(10f, 10f, now = 2_000, page = null)
        assertFalse(tracker.timerFired(now = 2_000 + TIMEOUT, PAGE))
    }

    private companion object {
        const val TIMEOUT = 400L
        const val SLOP = 16f
        val PAGE = PageRef(ViewRef(tab = 1, generation = 1), document = 7)
    }
}
