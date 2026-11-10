package ch.lkmc.combray.core.browser

import android.content.SharedPreferences

/**
 * The wall-clock time that retention may act on.
 *
 * Maintenance deletes history, and may close unused tabs, by comparing stored wall-clock stamps
 * with "now". A device clock set far forward (a manual date, a bad network time) would make one
 * pass delete everything at once. Within one boot the elapsed-time clock cannot jump, so [now]
 * refuses to let the wall clock run more than [maxSkewMillis] ahead of the measured time: the
 * last trusted reading plus the time that measurably passed since it. That reading persists, so
 * the guard also holds after the process dies and restarts on the same boot.
 *
 * Deliberate limits:
 * - After a reboot (another boot count) elapsed time restarts and the wall clock is trusted
 *   again, as it was before this guard. A clock that is still wrong across a reboot, or set
 *   wrong while the device was off, can still expire history.
 * - A clock corrected backwards is accepted at once: retention then waits instead of deleting,
 *   and the anchor stays on the measured timeline, so a later correction forward is accepted.
 * - Rows stamped while the clock ran ahead keep those stamps and expire late.
 *
 * Not thread-safe: the coordinator calls it only from its single work queue.
 */
class RetentionClock(
    private val wall: Clock,
    private val elapsedMillis: () -> Long,
    /** The device's boot count, or null when it cannot be read; then the wall clock is trusted. */
    private val bootCount: () -> Int?,
    private val store: AnchorStore,
    private val maxSkewMillis: Long = MAX_SKEW_MILLIS,
) {
    /** The time retention may use now; also records it as the next trusted reading. */
    fun now(): Long {
        val raw = wall.now()
        val elapsed = elapsedMillis()
        val boot = bootCount()
        val anchor = store.read()
        val trusted = trustedNow(raw, elapsed, boot, anchor, maxSkewMillis)
        // Keep the anchor on the measured timeline: anchoring on the accepted wall time would let
        // repeated sub-skew jumps accumulate without bound, and would hold retention down after a
        // clock that drifted backwards is corrected forward again.
        val measured = if (anchor != null && anchor.boot == boot && elapsed >= anchor.elapsed) {
            anchor.wall + (elapsed - anchor.elapsed)
        } else {
            trusted
        }
        if (boot != null) store.write(ClockAnchor(measured, elapsed, boot))
        return trusted
    }

    /** Where the last trusted reading survives the process. */
    interface AnchorStore {
        fun read(): ClockAnchor?

        fun write(anchor: ClockAnchor)
    }

    /** A private preferences file; a reading with any field missing counts as none. */
    class PreferencesAnchorStore(private val preferences: SharedPreferences) : AnchorStore {
        override fun read(): ClockAnchor? {
            if (KEYS.any { !preferences.contains(it) }) return null
            return ClockAnchor(
                wall = preferences.getLong(KEY_WALL, 0L),
                elapsed = preferences.getLong(KEY_ELAPSED, 0L),
                boot = preferences.getInt(KEY_BOOT, 0),
            )
        }

        // apply() is enough: a lost write only means the next process trusts the wall clock once.
        override fun write(anchor: ClockAnchor) {
            preferences.edit()
                .putLong(KEY_WALL, anchor.wall)
                .putLong(KEY_ELAPSED, anchor.elapsed)
                .putInt(KEY_BOOT, anchor.boot)
                .apply()
        }

        private companion object {
            const val KEY_WALL = "wall"
            const val KEY_ELAPSED = "elapsed"
            const val KEY_BOOT = "boot"
            val KEYS = listOf(KEY_WALL, KEY_ELAPSED, KEY_BOOT)
        }
    }

    companion object {
        /** The preferences file of [PreferencesAnchorStore]. */
        const val FILE_NAME = "retention_clock"

        /** How far the wall clock may run ahead of measured time: generous for network time slew. */
        const val MAX_SKEW_MILLIS = 60L * 60 * 1000

        /**
         * Pure rule behind [now]. Without a reading from this boot the wall clock is trusted.
         * Otherwise the measured time is the reading plus the elapsed time since it; a wall clock
         * more than [maxSkew] ahead of that is a jump, and the measured time is used instead.
         * Returning the measured time (not measured plus [maxSkew]) keeps repeated passes under
         * a jumped clock from creeping forward by [maxSkew] each.
         */
        internal fun trustedNow(raw: Long, elapsed: Long, boot: Int?, anchor: ClockAnchor?, maxSkew: Long): Long {
            if (boot == null || anchor == null || anchor.boot != boot || elapsed < anchor.elapsed) return raw
            val measured = anchor.wall + (elapsed - anchor.elapsed)
            return if (raw > measured + maxSkew) measured else raw
        }
    }
}

/** The measured wall-clock time, the elapsed-time reading taken with it, and its boot. */
data class ClockAnchor(val wall: Long, val elapsed: Long, val boot: Int)
