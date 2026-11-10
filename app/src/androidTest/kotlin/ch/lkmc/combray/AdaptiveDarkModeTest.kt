package ch.lkmc.combray

import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.LightPagesInDark
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import org.junit.After
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
 * dark canvas and light text, unless the page opts out, is dark already or the setting is off.
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

    private fun open(mode: String) {
        val url = "${Fixtures.BASE}/adaptive.html?mode=$mode"
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
            device.browser.browserState.value.activeTab?.title == "ADAPTIVE:$mode"
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

    /** Waits until the swatch and canvas satisfy [expected]; a change repaints asynchronously. */
    private fun awaitColors(what: String, expected: (ink: Int, canvas: Int) -> Boolean) {
        val deadline = System.currentTimeMillis() + REPAINT_TIMEOUT_MS
        var seen: Pair<Int, Int>? = null
        while (true) {
            device.dismissSystemDialogs()
            // A screenshot can fail while the surface changes; that is one more poll.
            val ink = device.screenColor(xFraction = INK_X)
            val canvas = device.screenColor(xFraction = CANVAS_X)
            if (ink != null && canvas != null) {
                if (expected(ink, canvas)) return
                seen = ink to canvas
            }
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(POLL_MS)
        }
        val (ink, canvas) = checkNotNull(seen) { "$what: no screenshot" }
        fail("$what: text %06x, canvas %06x".format(ink, canvas))
    }

    private fun luminance(color: Int) = Color.luminance(color or Color.BLACK)

    private fun contrast(first: Int, second: Int): Float {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + LUMINANCE_OFFSET) / (min(a, b) + LUMINANCE_OFFSET)
    }

    private companion object {
        const val INK_X = 0.2f
        const val CANVAS_X = 0.8f
        const val LUMINANCE_OFFSET = 0.05f
        const val MIN_TEXT_CONTRAST = 4.5f
        /** About #303030; the engine paints white as a dark gray near #181818. */
        const val MAX_DARK_CANVAS_LUMINANCE = 0.03f
        const val DARK_PAGE_CANVAS = 0x202124
        const val DARK_PAGE_INK = 0xe8eaed
        const val REPAINT_TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L
    }
}
