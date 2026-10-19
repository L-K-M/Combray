package ch.lkmc.combray.engine

import kotlin.math.hypot

/**
 * Decides when a held finger is a long-press. The hold is timed from when the app handled the
 * press, not from the event's timestamp: after a main-thread stall a tap's press and lift are
 * handled late, back to back, and a timer timed from the event (as GestureDetector's is) was
 * already due before the queued lift was read. Timed from handling, the lift comes first and
 * cancels it.
 */
internal class LongPressTracker(private val timeoutMs: Long, private val slopPx: Float) {
    private var pressedAt: Long? = null
    private var startX = 0f
    private var startY = 0f

    fun press(x: Float, y: Float, now: Long) {
        pressedAt = now
        startX = x
        startY = y
    }

    /** A finger that wanders is scrolling, not pressing. */
    fun move(x: Float, y: Float) {
        if (hypot(x - startX, y - startY) > slopPx) pressedAt = null
    }

    /** A lift, a second finger or a cancelled gesture. */
    fun release() {
        pressedAt = null
    }

    /** True once, when the timer runs and the press has been held long enough. */
    fun timerFired(now: Long): Boolean {
        val pressed = pressedAt ?: return false
        if (now - pressed < timeoutMs) return false
        pressedAt = null
        return true
    }
}
