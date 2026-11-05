package ch.lkmc.combray

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page wider than the screen can be seen whole, as in mobile browsers: without an initial scale
 * it opens zoomed out to its full width, and a pinch zooms out a page that opens at 100%. Servo's
 * pinch zoom used to stop at 100%, so such pages showed only their left part and scrolled
 * sideways.
 */
@RunWith(AndroidJUnit4::class)
class ZoomOutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun aPageWithoutAnInitialScaleOpensZoomedOutToItsWidth() {
        open(Fixtures.WIDE)
        assertRightEdge(Device.GREEN)
    }

    @Test
    fun aPinchZoomsOutAPageThatOpensAtFullSize() {
        open(Fixtures.WIDE_AT_INITIAL_SCALE)
        assertRightEdge(Device.RED)

        pinchClose()
        assertRightEdge(Device.GREEN)
    }

    private fun open(url: String) {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == url && it.activeTab?.title == WIDE_TITLE }
    }

    /** At 100% the screen's right edge shows the row's red left half; zoomed out, its green right half. */
    private fun assertRightEdge(color: Int) {
        val deadline = System.currentTimeMillis() + Device.SCREEN_TIMEOUT_MS
        var seen = device.screenColor(RIGHT_EDGE, PAGE_ROW)
        while (!device.matches(seen, color) && System.currentTimeMillis() < deadline) {
            device.dismissSystemDialogs()
            Thread.sleep(1_000)
            seen = device.screenColor(RIGHT_EDGE, PAGE_ROW)
        }
        val shown = seen?.let { "%06x".format(it) } ?: "no screenshot"
        assertTrue("the screen's right edge shows $shown, expected %06x".format(color), device.matches(seen, color))
    }

    /** Two fingers move from the screen's sides to its middle: a pinch that zooms out. */
    private fun pinchClose() {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val y = metrics.heightPixels * PAGE_ROW
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun send(action: Int, pointers: Int, spread: Float) {
            val half = metrics.widthPixels * spread / 2
            val coords = Array(pointers) { index ->
                MotionEvent.PointerCoords().apply {
                    x = metrics.widthPixels / 2f + if (index == 0) -half else half
                    this.y = y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        val secondPointer = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(MotionEvent.ACTION_DOWN, 1, PINCH_START)
        send(MotionEvent.ACTION_POINTER_DOWN or secondPointer, 2, PINCH_START)
        for (step in 1..PINCH_STEPS) {
            SystemClock.sleep(PINCH_STEP_MS)
            send(MotionEvent.ACTION_MOVE, 2, PINCH_START + (PINCH_END - PINCH_START) * step / PINCH_STEPS)
        }
        send(MotionEvent.ACTION_POINTER_UP or secondPointer, 2, PINCH_END)
        send(MotionEvent.ACTION_UP, 1, PINCH_END)
    }

    private companion object {
        const val WIDE_TITLE = "WIDE"
        /** Sample points, as fractions of the screen: near its right edge, in the page's row. */
        const val RIGHT_EDGE = 0.85f
        const val PAGE_ROW = 0.35f
        /** The fingers' distance as a fraction of the screen's width: a sixth of it at the end. */
        const val PINCH_START = 0.6f
        const val PINCH_END = 0.1f
        const val PINCH_STEPS = 20
        const val PINCH_STEP_MS = 30L
    }
}
