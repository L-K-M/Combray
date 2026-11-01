package ch.lkmc.combray

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

/** Real Servo pixels: a dark preference must not put default light text on a white canvas. */
@RunWith(AndroidJUnit4::class)
class PageContrastTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private lateinit var originalTheme: ThemeMode

    @Before
    fun setUp() {
        originalTheme = device.settings.themeMode.value
        device.awaitEngine()
        device.closeAllTabs()
        device.settings.setThemeMode(ThemeMode.DARK)
    }

    @After
    fun restoreTheme() {
        device.settings.setThemeMode(originalTheme)
    }

    @Test
    fun defaultTextStaysReadableAcrossThemeChangesReloadsAndNewTabs() {
        open(CONTRAST)
        assertColors(Color.BLACK, Color.WHITE)

        device.settings.setThemeMode(ThemeMode.LIGHT)
        awaitScheme("light")
        assertColors(Color.BLACK, Color.WHITE)

        device.settings.setThemeMode(ThemeMode.DARK)
        awaitScheme("dark")
        assertColors(Color.BLACK, Color.WHITE)
        val previousPage = device.browser.browserState.value.activeTab!!.page
        device.browser.reload()
        device.awaitState { it.activeTab?.page != previousPage && it.activeTab?.loading == false }
        awaitScheme("dark")
        // The title arrives before the reloaded page paints; sample only once its marker shows.
        assertMarker(Device.GREEN)
        assertColors(Color.BLACK, Color.WHITE)

        open("$CONTRAST?new=1", Placement.NEW_TAB)
        assertColors(Color.BLACK, Color.WHITE)
    }

    @Test
    fun defaultTextOnAnAuthoredWhiteBackgroundStaysReadable() {
        open("$CONTRAST?mode=white")
        assertColors(Color.BLACK, Color.WHITE)
    }

    @Test
    fun authorDarkSchemesAndExplicitColorsArePreserved() {
        open("$CONTRAST?mode=dark", marker = Device.BLUE)
        assertDarkColors()

        open("$CONTRAST?mode=meta", marker = Device.RED)
        assertDarkColors()

        open("$CONTRAST?mode=authored", marker = Device.OCHRE)
        assertColors(Color.WHITE, Device.BLUE)
    }

    @Test
    fun metadataCanSwitchAnAlreadyPaintedPageAndBeRemoved() {
        open("$CONTRAST?mode=meta", marker = Device.RED)
        assertDarkColors()

        tapMarker()
        awaitScheme("light")
        assertMarker(Device.GREEN)
        assertColors(Color.BLACK, Color.WHITE)

        tapMarker()
        awaitScheme("dark")
        assertMarker(Device.BLUE)
        assertDarkColors()

        // Removing metadata keeps the dark preference but restores light page defaults.
        tapMarker()
        assertMarker(Device.OCHRE)
        awaitScheme("dark")
        assertColors(Color.BLACK, Color.WHITE)
    }

    private fun open(url: String, placement: Placement = Placement.CURRENT_TAB, marker: Int = Device.GREEN) {
        device.browser.openUrl(url, placement)
        device.awaitPage(url)
        awaitScheme("dark")
        assertMarker(marker)
    }

    private fun assertMarker(marker: Int) {
        val seen = device.awaitScreen(marker)
        assertTrue("fixture painted", device.matches(seen, marker))
    }

    private fun tapMarker() {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.35f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun awaitScheme(scheme: String) {
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
            device.browser.browserState.value.activeTab?.title == "CONTRAST:$scheme"
        }
    }

    private fun assertColors(expectedInk: Int, expectedCanvas: Int) {
        val (ink, canvas) = paintedColors()
        val sampled = "text color %06x, canvas color %06x".format(ink, canvas)
        assertTrue(sampled, device.matches(ink, expectedInk))
        assertTrue(sampled, device.matches(canvas, expectedCanvas))
        assertContrast(ink, canvas)
    }

    private fun paintedColors(): Pair<Int, Int> {
        return checkNotNull(device.screenColor(xFraction = INK_X)) to
            checkNotNull(device.screenColor(xFraction = CANVAS_X))
    }

    private fun assertDarkColors() {
        val (ink, canvas) = paintedColors()
        assertTrue("dark scheme has light text on a dark canvas", Color.luminance(ink) > Color.luminance(canvas))
        assertContrast(ink, canvas)
    }

    private fun assertContrast(ink: Int, canvas: Int) {
        val inkLuminance = Color.luminance(ink or Color.BLACK)
        val canvasLuminance = Color.luminance(canvas or Color.BLACK)
        val ratio = (max(inkLuminance, canvasLuminance) + LUMINANCE_OFFSET) /
            (min(inkLuminance, canvasLuminance) + LUMINANCE_OFFSET)
        assertTrue("text contrast $ratio, expected at least $MIN_TEXT_CONTRAST", ratio >= MIN_TEXT_CONTRAST)
    }

    private companion object {
        const val CONTRAST = "${Fixtures.BASE}/contrast.html"
        const val INK_X = 0.2f
        const val CANVAS_X = 0.8f
        const val LUMINANCE_OFFSET = 0.05f
        const val MIN_TEXT_CONTRAST = 4.5f
    }
}
