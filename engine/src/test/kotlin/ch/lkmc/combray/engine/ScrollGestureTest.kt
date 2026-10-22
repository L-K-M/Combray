package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollGestureTest {
    private val tracker = ScrollGestureTracker(slopPx = 16f)

    @Test
    fun swipingUpOffersTheBottomAndReversingOffersTheTop() {
        tracker.press(50f, 100f)
        assertEquals(ScrollEdge.BOTTOM, tracker.move(50f, 50f))
        assertEquals(ScrollEdge.TOP, tracker.move(50f, 100f))
    }

    @Test
    fun jitterDoesNotChangeDirectionButSmallMovementsAccumulate() {
        tracker.press(50f, 100f)
        assertNull(tracker.move(50f, 95f))
        assertNull(tracker.move(50f, 90f))
        assertEquals(ScrollEdge.BOTTOM, tracker.move(50f, 80f))
        assertNull(tracker.move(50f, 85f))
        assertEquals(ScrollEdge.TOP, tracker.move(50f, 100f))
    }

    @Test
    fun aHorizontalDragDoesNotBecomeAScrollWhenTheFingerDrifts() {
        tracker.press(50f, 100f)
        assertNull(tracker.move(100f, 105f))
        assertNull(tracker.move(100f, 200f))
    }

    @Test
    fun aCancelledOrMultiTouchGestureStaysCancelledUntilTheNextPress() {
        tracker.press(50f, 100f)
        tracker.release()
        assertNull(tracker.move(50f, 20f))
        tracker.press(50f, 100f)
        assertEquals(ScrollEdge.BOTTOM, tracker.move(50f, 20f))
    }

    @Test
    fun aTapDoesNotOfferAnEdge() {
        tracker.press(50f, 100f)
        assertNull(tracker.move(52f, 102f))
        tracker.release()
        assertNull(tracker.move(50f, 20f))
    }
}
