package ch.lkmc.combray.engine

/**
 * Android's long-press is a main-thread timer. If that thread stalls (start-up, a loaded
 * device), the timer can run before the lift of a quick tap is delivered, turning the tap into
 * a long-press. A timer that fired late cannot tell the two apart, so it does not count.
 */
internal object LongPress {
    /** Lateness still treated as on time: the detector itself adds a short tap delay. */
    private const val TOLERANCE_MS = 300L

    fun firedOnTime(downTime: Long, now: Long, timeoutMs: Long): Boolean = now - downTime - timeoutMs <= TOLERANCE_MS
}
