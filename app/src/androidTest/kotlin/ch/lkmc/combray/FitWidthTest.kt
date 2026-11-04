package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pages fit the screen on the real engine: a flex row of an icon that cannot shrink and a long
 * sentence, in an auto grid column, wraps the sentence. Servo used to give such a row the whole
 * sentence on one line as its min-content width, so the column, and the page, became wider than
 * the screen and scrolled sideways.
 */
@RunWith(AndroidJUnit4::class)
class FitWidthTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    private data class Widths(val scroll: Int, val viewport: Int)

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun aRowOfAnIconAndASentenceInAGridColumnFitsTheScreen() {
        device.browser.openUrl(Fixtures.FIT_WIDTH, Placement.CURRENT_TAB)
        val state = try {
            device.awaitState { state ->
                val tab = state.activeTab
                tab?.url == Fixtures.FIT_WIDTH && widths(tab.title) != null
            }
        } catch (timeout: TimeoutCancellationException) {
            val tab = device.browser.browserState.value.activeTab
            throw AssertionError("the page did not report its width; the tab shows ${tab?.url} titled ${tab?.title}", timeout)
        }

        val widths = widths(state.activeTab?.title)!!
        assertTrue(
            "the page is ${widths.scroll} CSS px wide in a ${widths.viewport} CSS px viewport",
            widths.scroll <= widths.viewport,
        )
    }

    /** Parses the fixture's `FIT:<scrollWidth>:<innerWidth>` title. */
    private fun widths(title: String?): Widths? {
        val parts = title?.removePrefix(TITLE_PREFIX)?.takeIf { it != title }?.split(':') ?: return null
        val scroll = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val viewport = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return Widths(scroll, viewport)
    }

    private companion object {
        const val TITLE_PREFIX = "FIT:"
    }
}
