package ch.lkmc.combray.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTest {
    private val tracker = LongPressTracker(timeoutMs = TIMEOUT, slopPx = SLOP)

    @Test
    fun aPressHeldForTheTimeoutIsALongPressOnce() {
        tracker.press(10f, 10f, now = 0)
        assertFalse(tracker.timerFired(now = TIMEOUT - 1))
        assertTrue(tracker.timerFired(now = TIMEOUT))
        assertFalse(tracker.timerFired(now = TIMEOUT + 100))
    }

    /**
     * After a main-thread stall a tap's press and lift are handled late, back to back. Timing from
     * the event's own timestamp (as GestureDetector does) made the timer due before the queued
     * lift was read; timing from handling leaves the lift first.
     */
    @Test
    fun theHoldIsTimedFromWhenThePressWasHandled() {
        tracker.press(10f, 10f, now = 5_000)
        assertFalse(tracker.timerFired(now = 5_000))
        assertTrue(tracker.timerFired(now = 5_000 + TIMEOUT))
    }

    @Test
    fun aLiftHandledBeforeTheTimerKeepsItATap() {
        tracker.press(10f, 10f, now = 0)
        tracker.release()
        assertFalse(tracker.timerFired(now = 2_000))
    }

    @Test
    fun movingBeyondTheSlopIsNotALongPress() {
        tracker.press(10f, 10f, now = 0)
        tracker.move(10f + SLOP / 2, 10f)
        assertTrue(tracker.timerFired(now = TIMEOUT))

        tracker.press(10f, 10f, now = 1_000)
        tracker.move(10f, 10f + SLOP + 1)
        assertFalse(tracker.timerFired(now = 1_000 + TIMEOUT))
    }

    private companion object {
        const val TIMEOUT = 400L
        const val SLOP = 16f
    }
}
