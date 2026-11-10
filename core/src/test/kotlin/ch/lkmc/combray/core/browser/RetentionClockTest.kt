package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionClockTest {
    private var wall = START
    private var elapsed = 10_000L
    private var boot: Int? = 3
    private val anchors = MemoryAnchors()
    private val clock = RetentionClock({ wall }, { elapsed }, { boot }, anchors)

    @Test
    fun withoutAReadingTheWallClockIsTrustedAndRecorded() {
        assertEquals(START, clock.now())
        assertEquals(ClockAnchor(START, 10_000L, 3), anchors.stored)
    }

    @Test
    fun aForwardJumpOnTheSameBootIsHeldToTheMeasuredTime() {
        clock.now()
        wall += 365 * DAY
        elapsed += 5 * MINUTE

        assertEquals(START + 5 * MINUTE, clock.now())
    }

    @Test
    fun repeatedPassesUnderAJumpedClockDoNotCreepForward() {
        clock.now()
        wall += 365 * DAY
        repeat(500) {
            elapsed += MINUTE
            clock.now()
        }

        // Exactly the elapsed time: no skew allowance accumulates across passes.
        assertEquals(START + 500 * MINUTE, clock.now())
    }

    @Test
    fun timeThatReallyPassedStillCounts() {
        clock.now()
        wall += 8 * DAY
        elapsed += 8 * DAY

        assertEquals(START + 8 * DAY, clock.now())
    }

    @Test
    fun driftWithinTheSkewIsAccepted() {
        clock.now()
        wall += 30 * MINUTE

        assertEquals(START + 30 * MINUTE, clock.now())
    }

    @Test
    fun aClockCorrectedBackwardsIsAcceptedAtOnce() {
        clock.now()
        wall -= 2 * DAY

        assertEquals(START - 2 * DAY, clock.now())
        // The anchor stays on the measured timeline, so a later correction forward is accepted.
        assertEquals(START, anchors.stored!!.wall)
    }

    @Test
    fun aCorrectedClockEndsTheHold() {
        clock.now()
        wall += 365 * DAY
        elapsed += MINUTE
        clock.now()

        wall = START + 2 * MINUTE // the date is set right again
        elapsed += MINUTE
        assertEquals(START + 2 * MINUTE, clock.now())
    }

    @Test
    fun aBackwardsExcursionFollowedByACorrectionForwardEndsTheHold() {
        clock.now()
        wall -= 2 * DAY
        elapsed += MINUTE
        assertEquals(START - 2 * DAY, clock.now())

        // The date is set right again: the guard ends the hold instead of staying pinned to the
        // corrupted baseline.
        wall = START + MINUTE
        elapsed += MINUTE
        assertEquals(START + MINUTE, clock.now())
    }

    @Test
    fun repeatedSubSkewNudgesDoNotAccumulatePastTheSkew() {
        clock.now()
        repeat(50) {
            wall += RetentionClock.MAX_SKEW_MILLIS / 2
            elapsed += MINUTE
            clock.now()
        }

        // The anchor stays on the measured timeline, so the accumulated wall-clock lead is refused.
        assertEquals(START + 50 * MINUTE, clock.now())
    }

    @Test
    fun anotherBootTrustsTheWallClock() {
        clock.now()
        wall += 365 * DAY
        elapsed = 1_000L
        boot = 4

        // The documented limit: elapsed time restarted, so the wall clock is all there is.
        assertEquals(START + 365 * DAY, clock.now())
        assertEquals(ClockAnchor(START + 365 * DAY, 1_000L, 4), anchors.stored)
    }

    @Test
    fun anUnknownBootTrustsTheWallClockAndRecordsNothing() {
        boot = null

        assertEquals(START, clock.now())
        assertNull(anchors.stored)
    }

    @Test
    fun anElapsedReadingBeforeTheStoredOneIsNotTrusted() {
        anchors.stored = ClockAnchor(START, 50_000L, 3)
        wall += 365 * DAY

        assertEquals(START + 365 * DAY, clock.now())
    }

    @Test
    fun thePreferencesStoreRoundTripsAndIgnoresAPartialReading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(RetentionClock.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = RetentionClock.PreferencesAnchorStore(preferences)
        assertNull(store.read())

        store.write(ClockAnchor(START, 42L, 7))
        assertEquals(ClockAnchor(START, 42L, 7), store.read())

        preferences.edit().remove("boot").commit()
        assertNull(store.read())
    }

    /** The persisted reading, shared by every clock built on it (as by processes on one device). */
    class MemoryAnchors : RetentionClock.AnchorStore {
        var stored: ClockAnchor? = null

        override fun read() = stored

        override fun write(anchor: ClockAnchor) {
            stored = anchor
        }
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
