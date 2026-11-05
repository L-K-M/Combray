package ch.lkmc.combray.ui

import android.app.Application
import android.graphics.Bitmap
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.integration.ScreenshotOmission
import ch.lkmc.combray.engine.PageRef
import ch.lkmc.combray.engine.ViewRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReportCaptureTest {
    @Test
    fun aShownPageCanBeCopied() {
        assertNull(screenshotBlocker(SHOWN, surface = true))
        // The engine's error page is on the surface too, and worth a screenshot.
        assertNull(screenshotBlocker(SHOWN.copy(activeTab = PAGE.copy(loadFailed = true)), surface = true))
    }

    /** Reader view is drawn over the surface: a copy would show the page the user did not see. */
    @Test
    fun readerViewHasNoScreenshot() {
        assertEquals(ScreenshotOmission.READER_VIEW, screenshotBlocker(SHOWN.copy(reader = ReaderState.Loading("tab")), surface = true))
    }

    @Test
    fun withoutAPageOnTheSurfaceThereIsNoScreenshot() {
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(engine = EngineStatus.STARTING), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(engine = EngineStatus.FAILED), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(session = SessionState.RESTORING), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(activeTab = PAGE.copy(crashed = true)), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(activeTab = PAGE.copy(page = null)), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN.copy(activeTab = null), surface = true))
        assertEquals(ScreenshotOmission.NOT_SHOWN, screenshotBlocker(SHOWN, surface = false))
    }

    /** The core checks the signature and that the header's size is the declared one. */
    @Test
    fun theDefaultEncoderWritesAPngOfTheFramesSize() {
        val encoded = checkNotNull(encodeScreenshot(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)))
        val png = encoded.png
        assertEquals(PNG_SIGNATURE.toList(), png.take(PNG_SIGNATURE.size))
        assertEquals("IHDR", String(png, 12, 4, Charsets.US_ASCII))
        assertEquals(40 to 80, java.nio.ByteBuffer.wrap(png, 16, 8).let { it.int to it.int })
        assertEquals(40 to 80, encoded.width to encoded.height)
        assertFalse(encoded.scaled)
    }

    @Test
    fun aScreenshotWithinTheLimitIsNotScaled() {
        val encoded = checkNotNull(encodeScreenshot(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888), maxBytes = 100 * 200, encode = ::fakePng))
        assertEquals(100 to 200, encoded.width to encoded.height)
        assertFalse(encoded.scaled)
        assertEquals(100 * 200, encoded.png.size)
    }

    @Test
    fun aLargeScreenshotIsHalvedUntilItFits() {
        val encoded = checkNotNull(encodeScreenshot(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888), maxBytes = 25 * 50, encode = ::fakePng))
        assertEquals(25 to 50, encoded.width to encoded.height)
        assertTrue(encoded.scaled)
    }

    @Test
    fun aScreenshotStillTooLargeAfterTwoHalvingsIsDropped() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        val encoded = encodeScreenshot(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888), maxBytes = 25 * 50 - 1) {
            sizes += it.width to it.height
            fakePng(it)
        }
        assertNull(encoded)
        assertEquals(listOf(100 to 200, 50 to 100, 25 to 50), sizes)
    }

    @Test
    fun thePreviewIsAtMostItsHeightWithTheFramesShape() {
        val tall = previewOf(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888), maxHeight = 720)
        assertEquals(324 to 720, tall.width to tall.height)
        val small = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        assertSame(small, previewOf(small, maxHeight = 720))
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** One byte per pixel: the size, not the content, decides the scaling. */
        fun fakePng(bitmap: Bitmap) = ByteArray(bitmap.width * bitmap.height)

        val PAGE = ActiveTab(
            id = "tab",
            url = "https://example.org/article",
            title = "Article",
            loading = false,
            canGoBack = false,
            canGoForward = false,
            crashed = false,
            hasView = true,
            page = PageRef(ViewRef(tab = 1, generation = 1), document = 1),
        )
        val SHOWN = BrowserState(engine = EngineStatus.READY, session = SessionState.RESTORED, activeTab = PAGE)
    }
}
