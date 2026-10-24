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

/** A fixed drawer must not trap page scrolling, even before the page's load event. */
@RunWith(AndroidJUnit4::class)
class FixedLayerScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun anEmptyFixedDrawerPassesSwipesToThePage() {
        device.browser.openUrl(Fixtures.FIXED_SCROLL, Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.FIXED_SCROLL)
        assertScreen(Device.RED)

        swipeUp()
        assertScreen(Device.GREEN)
    }

    @Test
    fun thePageScrollsWhileAnImageIsStillLoading() {
        Fixtures.reset()
        val url = "${Fixtures.FIXED_SCROLL}?loading=1"
        try {
            device.browser.openUrl(url, Placement.CURRENT_TAB)
            device.awaitState { it.activeTab?.url == url && it.activeTab?.title == "FIXED-SCROLL" }
            assertScreen(Device.RED)
            assertTrue("the image must still be pending", device.browser.browserState.value.activeTab!!.loading)

            swipeUp()
            assertScreen(Device.GREEN)
            assertTrue("scrolling must not pretend the load finished", device.browser.browserState.value.activeTab!!.loading)

            Fixtures.finishScrollLoad()
            device.awaitPage(url)
        } finally {
            Fixtures.finishScrollLoad()
        }
    }

    private fun assertScreen(color: Int) {
        val seen = device.awaitScreen(color)
        assertTrue("screen shows %06x, expected %06x".format(seen ?: -1, color), device.matches(seen, color))
    }

    private fun swipeUp() {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, metrics.widthPixels / 2f, metrics.heightPixels * fraction, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0.65f)
        for (step in 1..SWIPE_STEPS) {
            SystemClock.sleep(SWIPE_STEP_MS)
            send(MotionEvent.ACTION_MOVE, 0.65f - 0.3f * step / SWIPE_STEPS)
        }
        send(MotionEvent.ACTION_UP, 0.35f)
    }

    private companion object {
        const val SWIPE_STEPS = 20
        const val SWIPE_STEP_MS = 30L
    }
}
