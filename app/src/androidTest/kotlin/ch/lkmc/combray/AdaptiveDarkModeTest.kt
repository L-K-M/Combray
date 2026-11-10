package ch.lkmc.combray

import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.LightPagesInDark
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

/**
 * Real Servo pixels: while Combray is dark, Servo's adaptive dark mode paints a light page with a
 * dark canvas and light text, unless the page opts out, is dark already or the setting is off. A
 * dark logo on that page turns light with it; a picture keeps its pixels.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveDarkModeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private lateinit var originalTheme: ThemeMode
    private lateinit var originalLightPages: LightPagesInDark

    @Before
    fun setUp() {
        originalTheme = device.settings.themeMode.value
        originalLightPages = device.settings.lightPagesInDark.value
        device.awaitEngine()
        device.closeAllTabs()
        device.setThemeMode(ThemeMode.DARK)
        device.setLightPagesInDark(LightPagesInDark.DARKEN)
    }

    @After
    fun restoreSettings() {
        device.setLightPagesInDark(originalLightPages)
        device.setThemeMode(originalTheme)
    }

    @Test
    fun aLightPageIsPaintedDarkAndTheSettingRestoresItsColors() {
        open("plain")
        awaitDarkened()

        device.setLightPagesInDark(LightPagesInDark.KEEP)
        awaitAuthored()

        device.setLightPagesInDark(LightPagesInDark.DARKEN)
        awaitDarkened()
    }

    @Test
    fun aLightThemeShowsPagesAsDesigned() {
        open("plain")
        awaitDarkened()

        device.setThemeMode(ThemeMode.LIGHT)
        awaitAuthored()
    }

    @Test
    fun pagesThatOptOutOrAreDarkKeepTheirColors() {
        open("meta-only")
        awaitAuthored()

        open("css-only")
        awaitAuthored()

        open("dark")
        awaitColors("a dark page keeps its colors") { ink, canvas ->
            device.matches(ink, DARK_PAGE_INK) && device.matches(canvas, DARK_PAGE_CANVAS)
        }
    }

    @Test
    fun pagesThatSupportBothSchemesGetTheDefaultsOfThePreferredOne() {
        for (mode in listOf("meta-both", "css-both")) {
            open(mode)
            awaitColors("$mode in a dark theme") { ink, canvas ->
                device.matches(ink, DARK_SCHEME_INK) && device.matches(canvas, DARK_SCHEME_CANVAS)
            }
        }

        device.setThemeMode(ThemeMode.LIGHT)
        awaitAuthored()
    }

    @Test
    fun aDarkLogoTurnsLightAndAPictureKeepsItsPixels() {
        openPage("/adaptive-images.html", "ADAPTIVE:images")
        awaitSamples("darkened", LOGO, PICTURE) { logo, picture ->
            luminance(logo) > MIN_LIGHT_ARTWORK_LUMINANCE && device.matches(picture, PICTURE_COLOR)
        }

        device.setLightPagesInDark(LightPagesInDark.KEEP)
        awaitSamples("as authored", LOGO, PICTURE) { logo, picture ->
            device.matches(logo, Color.BLACK and Device.RGB_MASK) && device.matches(picture, PICTURE_COLOR)
        }
    }

    @Test
    fun aNewTabIsDarkBeforeItsPagePaints() {
        open("plain")
        awaitDarkened()

        // The server answers after several seconds: until then the new tab has no page to paint.
        device.browser.openUrl(Fixtures.SLOW, Placement.NEW_TAB)
        device.awaitState(EMPTY_TAB_MS) { it.activeTab?.loading == true }
        val deadline = SystemClock.uptimeMillis() + EMPTY_TAB_MS
        var samples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            device.dismissSystemDialogs()
            val seen = device.screenColor(xFraction = CANVAS.xFraction)
            if (seen != null) {
                assertTrue("an empty tab is dark: %06x".format(seen), luminance(seen) < MAX_DARK_CANVAS_LUMINANCE)
                samples++
            }
            Thread.sleep(POLL_MS)
        }
        assertTrue("screenshots of the empty tab", samples > 0)
        assertEquals("still waiting for the server", true, device.browser.browserState.value.activeTab?.loading)
        device.awaitPage(Fixtures.SLOW)
    }

    private fun open(mode: String) = openPage("/adaptive.html?mode=$mode", "ADAPTIVE:$mode")

    private fun openPage(path: String, title: String) {
        val url = Fixtures.BASE + path
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
            device.browser.browserState.value.activeTab?.title == title
        }
        val seen = device.awaitScreen(Device.GREEN)
        assertTrue("fixture painted", device.matches(seen, Device.GREEN))
    }

    /** A dark canvas with light text that keeps at least 4.5:1. */
    private fun awaitDarkened() = awaitColors("darkened") { ink, canvas ->
        luminance(canvas) < MAX_DARK_CANVAS_LUMINANCE &&
            luminance(ink) > luminance(canvas) &&
            contrast(ink, canvas) >= MIN_TEXT_CONTRAST
    }

    /** The page's own black text on its white canvas. */
    private fun awaitAuthored() = awaitColors("as authored") { ink, canvas ->
        device.matches(ink, Color.BLACK and Device.RGB_MASK) && device.matches(canvas, Color.WHITE and Device.RGB_MASK)
    }

    /** Waits until the swatch and canvas satisfy [expected]. */
    private fun awaitColors(what: String, expected: (ink: Int, canvas: Int) -> Boolean) =
        awaitSamples(what, INK, CANVAS, expected)

    /** Waits until the colors at two places satisfy [expected]; a change repaints asynchronously. */
    private fun awaitSamples(what: String, first: Sample, second: Sample, expected: (first: Int, second: Int) -> Boolean) {
        val deadline = System.currentTimeMillis() + REPAINT_TIMEOUT_MS
        var seen: Pair<Int, Int>? = null
        while (true) {
            device.dismissSystemDialogs()
            // A screenshot can fail while the surface changes; that is one more poll.
            val firstColor = device.screenColor(xFraction = first.xFraction)
            val secondColor = device.screenColor(xFraction = second.xFraction)
            if (firstColor != null && secondColor != null) {
                if (expected(firstColor, secondColor)) return
                seen = firstColor to secondColor
            }
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(POLL_MS)
        }
        val (firstColor, secondColor) = checkNotNull(seen) { "$what: no screenshot" }
        fail("$what: ${first.name} %06x, ${second.name} %06x".format(firstColor, secondColor))
    }

    private fun luminance(color: Int) = Color.luminance(color or Color.BLACK)

    private fun contrast(first: Int, second: Int): Float {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + LUMINANCE_OFFSET) / (min(a, b) + LUMINANCE_OFFSET)
    }

    /** A place in the page area to read a color from, as a fraction of the screen's width. */
    private class Sample(val name: String, val xFraction: Float)

    private companion object {
        val INK = Sample("text", 0.2f)
        val CANVAS = Sample("canvas", 0.8f)
        /** In the black half of the logo, which spans the left fifth of the page. */
        val LOGO = Sample("logo", 0.1f)
        val PICTURE = Sample("picture", 0.8f)
        const val PICTURE_COLOR = 0x1b2a4a
        /** Well above the darkened canvas, and far above the logo's own black. */
        const val MIN_LIGHT_ARTWORK_LUMINANCE = 0.5f
        const val LUMINANCE_OFFSET = 0.05f
        const val MIN_TEXT_CONTRAST = 4.5f
        /** About #303030; the engine paints white as a dark gray near #181818. */
        const val MAX_DARK_CANVAS_LUMINANCE = 0.03f
        const val DARK_PAGE_CANVAS = 0x202124
        const val DARK_PAGE_INK = 0xe8eaed
        /** Servo's `Canvas` and `CanvasText` system colors for a dark color scheme. */
        const val DARK_SCHEME_CANVAS = 0x1e1e1e
        const val DARK_SCHEME_INK = 0xe8e8e8
        const val REPAINT_TIMEOUT_MS = 30_000L
        /** Well under the fixture's SLOW_PAGE_SECONDS, so the tab is sampled before the answer. */
        const val EMPTY_TAB_MS = 3_000L
        const val POLL_MS = 500L
    }
}
