package ch.lkmc.combray

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.AppVisibility
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Throttling on the real engine: a page nobody sees runs its timers about once a second
 * instead of ten times. The fixture counts `setInterval(100 ms)` ticks in its title, which the
 * engine reports for hidden tabs and in the background too.
 */
@RunWith(AndroidJUnit4::class)
class ThrottlingTest {
    // No screen checks here, so no Compose test rule (and its test clock) is needed.
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val device = Device()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun theShownPageSlowsDownWhileTheAppIsInTheBackground() {
        device.browser.openUrl(TICKS, Placement.CURRENT_TAB)
        val foreground = rate(MEASURE_MS) { shownTicks() }

        // Leave like a user; Android reports the stop 700 ms after the Activity stopped.
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        awaitApp(AppVisibility.BACKGROUND)
        SystemClock.sleep(SETTLE_MS)
        val background = rate(BACKGROUND_MS) { shownTicks() }

        returnToTheApp()
        awaitApp(AppVisibility.FOREGROUND)
        SystemClock.sleep(SETTLE_MS)
        val returned = rate(MEASURE_MS) { shownTicks() }

        val rates = "foreground %.1f/s, background %.1f/s, after return %.1f/s".format(foreground, background, returned)
        assertTrue("not throttled in the background: $rates", background * SLOWDOWN < foreground)
        assertTrue("still throttled after return: $rates", background * SLOWDOWN < returned)
    }

    @Test
    fun aHiddenTabStaysThrottledAfterItsPageNavigates() {
        device.browser.openUrl("$TICKS?then=$NAVIGATE_AFTER_S", Placement.CURRENT_TAB)
        device.awaitState { ticks(it.activeTab?.title) != null }
        val hidden = device.browser.browserState.value.activeTab!!.id
        device.browser.openUrl(Fixtures.solid(Device.GREEN), Placement.NEW_TAB)
        device.awaitPage(Fixtures.solid(Device.GREEN))
        assertEquals("the page navigated before its tab was hidden", "$TICKS?then=$NAVIGATE_AFTER_S", openTab(hidden)?.url)

        // While hidden, the page goes on to a new document, which counts from zero.
        val throttled = rate(HIDDEN_MS) { openTab(hidden)?.takeIf { it.url == TICKS }?.let { ticks(it.title) } }

        // Shown again, the same document runs at full speed.
        device.browser.selectTab(hidden)
        device.awaitState { it.activeTab?.id == hidden }
        SystemClock.sleep(SETTLE_MS)
        val shown = rate(MEASURE_MS) { shownTicks() }

        val rates = "hidden after navigating %.1f/s, shown %.1f/s".format(throttled, shown)
        assertTrue("the new document ran unthrottled: $rates", throttled * SLOWDOWN < shown)
    }

    private fun shownTicks(): Int? = ticks(device.browser.browserState.value.activeTab?.title)

    private fun openTab(tabId: String): RecentItem.OpenTab? = device.recent().filterIsInstance<RecentItem.OpenTab>().firstOrNull { it.tabId == tabId }

    private fun ticks(title: String?): Int? = title?.takeIf { it.startsWith(TICKS_TITLE) }?.removePrefix(TICKS_TITLE)?.toIntOrNull()

    /** Ticks per second over [durationMs], once [read] reports a count. */
    private fun rate(durationMs: Long, read: () -> Int?): Double {
        val first = awaitTicks(read)
        val started = SystemClock.uptimeMillis()
        SystemClock.sleep(durationMs)
        val last = awaitTicks(read)
        return (last - first) * MS_PER_SECOND / (SystemClock.uptimeMillis() - started)
    }

    private fun awaitTicks(read: () -> Int?): Int {
        val deadline = SystemClock.uptimeMillis() + Device.LOAD_TIMEOUT_MS
        while (true) {
            read()?.let { return it }
            assertTrue("the page reported no ticks", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Waits until Android's process lifecycle, which the app follows, reports [visibility]. */
    private fun awaitApp(visibility: AppVisibility) {
        val deadline = SystemClock.uptimeMillis() + LIFECYCLE_TIMEOUT_MS
        while (true) {
            var state = Lifecycle.State.INITIALIZED
            instrumentation.runOnMainSync { state = ProcessLifecycleOwner.get().lifecycle.currentState }
            val started = state.isAtLeast(Lifecycle.State.STARTED)
            if (started == (visibility == AppVisibility.FOREGROUND)) return
            assertTrue("the app did not reach $visibility (process $state)", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Through the launcher's intent, from the shell: Android lets it start activities from the background. */
    private fun returnToTheApp() {
        val activity = ComponentName(device.app, MainActivity::class.java).flattenToShortString()
        val output = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $activity",
        )
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val TICKS = "${Fixtures.BASE}/ticks.html"
        const val TICKS_TITLE = "TICKS:"

        /** Long enough to open the covering tab first, even under software emulation. */
        const val NAVIGATE_AFTER_S = 20
        const val MEASURE_MS = 5_000L
        const val BACKGROUND_MS = 10_000L
        const val HIDDEN_MS = 10_000L

        /** Time for a throttling change to reach the page. */
        const val SETTLE_MS = 2_000L

        /** Throttled pages tick about once a second, others about ten times. */
        const val SLOWDOWN = 3.0
        const val LIFECYCLE_TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
        const val MS_PER_SECOND = 1_000.0
    }
}
