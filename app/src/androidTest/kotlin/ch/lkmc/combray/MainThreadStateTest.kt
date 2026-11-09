package ch.lkmc.combray

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Browser state reaches the screen on the main thread. The coordinator publishes from its
 * Dispatchers.Default queue. A collector without a context resumes on that worker, writes the
 * screen's state there and wakes the recomposer there; under the Compose test clock the worker
 * can then run the frame itself. A BackHandler whose `enabled` follows the state restarts its
 * LifecycleStartEffect in that frame, and LifecycleRegistry throws "Method removeObserver must be
 * called on the main thread" (MediaPlayerTest, Engine run 41023689990, 30 October 2026).
 *
 * The frame itself only moves to the worker in a narrow race, so the check watches the step that
 * always precedes it: snapshot changes applied off the main thread.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MainThreadStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun findOpenedAndClosedByTheBrowserUpdatesBackOnTheMainThread() {
        device.browser.openUrl(Fixtures.EMPTY, Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.EMPTY)
        // The screen shows each toggle, so a check whose toggles never reached it cannot pass.
        val findBar = hasContentDescription(compose.activity.getString(R.string.action_close_find))

        // Where each snapshot change off the main thread was applied, for the failure's cause.
        val offMain = ConcurrentLinkedQueue<Throwable>()
        val observer = Snapshot.registerApplyObserver { changed, _ ->
            if (changed.isNotEmpty() && Looper.myLooper() != Looper.getMainLooper()) {
                offMain += Throwable("applied on ${Thread.currentThread().name}")
            }
        }
        try {
            // Each toggle turns the find bar's BackHandler on or off from the browser's worker.
            repeat(TOGGLES) {
                device.browser.openFind()
                compose.waitUntilAtLeastOneExists(findBar, Device.LOAD_TIMEOUT_MS)
                device.browser.closeFind()
                compose.waitUntilDoesNotExist(findBar, Device.LOAD_TIMEOUT_MS)
            }
        } finally {
            observer.dispose()
        }

        offMain.peek()?.let { first ->
            throw AssertionError("${offMain.size} snapshot changes applied off the main thread", first)
        }
    }

    private companion object {
        const val TOGGLES = 5
    }
}
