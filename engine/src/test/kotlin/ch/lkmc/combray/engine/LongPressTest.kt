package ch.lkmc.combray.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTest {
    @Test
    fun aTimerFiringOnTimeIsALongPress() {
        assertTrue(LongPress.firedOnTime(downTime = 1_000, now = 1_000 + TIMEOUT + 100, timeoutMs = TIMEOUT))
    }

    /** The main thread stalled: the lift of a tap may still be queued, so it must stay a tap. */
    @Test
    fun aLateTimerIsNotTrusted() {
        assertFalse(LongPress.firedOnTime(downTime = 1_000, now = 1_000 + TIMEOUT + 2_000, timeoutMs = TIMEOUT))
    }

    private companion object {
        const val TIMEOUT = 400L
    }
}
