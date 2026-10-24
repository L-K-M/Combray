package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.TextSize
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text size on the real engine: Servo's page zoom lays the page out again, so at 150% the page's
 * CSS width is two thirds of its width at 100% and its device pixel ratio one and a half times
 * as large: in the open view, after a navigation in it, and in a view created later.
 */
@RunWith(AndroidJUnit4::class)
class TextSizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    private data class Viewport(val width: Int, val ratio: Double)

    @Before
    fun setUp() {
        device.awaitEngine()
        // The setting is persisted: a run that stopped halfway must not change this one.
        setTextSize(TextSize.DEFAULT)
        device.closeAllTabs()
    }

    @After
    fun tearDown() = setTextSize(TextSize.DEFAULT)

    @Test
    fun aLargerTextSizeLaysPagesOutAtItInEveryView() {
        device.browser.openUrl(Fixtures.ZOOM, Placement.CURRENT_TAB)
        val normal = awaitViewport(Fixtures.ZOOM) { true }

        setTextSize(TextSize.PERCENT_150)
        val zoomed = awaitViewport(Fixtures.ZOOM) { it.width < normal.width }
        assertEquals(normal.width / ZOOM_150, zoomed.width.toDouble(), WIDTH_TOLERANCE)
        assertEquals(normal.ratio * ZOOM_150, zoomed.ratio, RATIO_TOLERANCE)

        // Servo keeps a view's zoom across its navigations.
        val next = "${Fixtures.ZOOM}?next"
        device.browser.openUrl(next, Placement.CURRENT_TAB)
        awaitViewport(next) { it == zoomed }

        // A new view starts at the setting (its first layout may still be at 100%).
        val newTab = "${Fixtures.ZOOM}?new"
        device.browser.openUrl(newTab, Placement.NEW_TAB)
        awaitViewport(newTab) { it == zoomed }

        setTextSize(TextSize.DEFAULT)
        awaitViewport(newTab) { it == normal }
    }

    private fun setTextSize(size: TextSize) {
        device.app.graph.settings.setTextSize(size)
        device.browser.applyTextSize()
        device.idle()
    }

    /** Waits until the focused tab shows [url] and its page reports a viewport [expected] accepts. */
    private fun awaitViewport(url: String, expected: (Viewport) -> Boolean): Viewport {
        val state = try {
            device.awaitState { state ->
                val tab = state.activeTab
                tab?.url == url && viewport(tab.title)?.let(expected) == true
            }
        } catch (timeout: TimeoutCancellationException) {
            val tab = device.browser.browserState.value.activeTab
            throw AssertionError("$url did not report the expected viewport; the tab shows ${tab?.url} titled ${tab?.title}", timeout)
        }
        return viewport(state.activeTab?.title)!!
    }

    /** Parses the fixture's `ZOOM:<innerWidth>:<devicePixelRatio>` title. */
    private fun viewport(title: String?): Viewport? {
        val parts = title?.removePrefix(TITLE_PREFIX)?.takeIf { it != title }?.split(':') ?: return null
        val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val ratio = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Viewport(width, ratio)
    }

    private companion object {
        const val TITLE_PREFIX = "ZOOM:"
        const val ZOOM_150 = 1.5

        /** innerWidth is a whole number of CSS pixels. */
        const val WIDTH_TOLERANCE = 1.0
        const val RATIO_TOLERANCE = 0.01
    }
}
