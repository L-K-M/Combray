package ch.lkmc.combray

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PDF viewer on the real engine (docs/PDF.md, phase 1): Servo turns a top-level PDF into a
 * viewer document and native/src/pdf/viewer.mjs draws its pages with PDFium. The generated
 * fixture PDFs (testing/fixtures/server.py) have a band of one colour over the top of each page,
 * so a screenshot shows which page is drawn; the server counts requests and bytes sent.
 *
 * The viewer's messages (too large, damaged, protected) are text in the page, which these checks
 * cannot read: they check that no page and no stand-in is shown, and the host tests in
 * native/src/pdf/test check which message is chosen.
 */
@RunWith(AndroidJUnit4::class)
class PdfTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private lateinit var originalTheme: ThemeMode

    @Before
    fun setUp() {
        originalTheme = device.settings.themeMode.value
        device.awaitEngine()
        device.closeAllTabs()
        // The viewer's background colour is checked in the light appearance.
        device.settings.setThemeMode(ThemeMode.LIGHT)
    }

    @After
    fun restoreTheme() {
        device.settings.setThemeMode(originalTheme)
    }

    private fun key(name: String) = "$name${System.currentTimeMillis()}"

    private fun assertScreen(color: Int) {
        val seen = device.awaitScreen(color)
        assertTrue("screen shows %06x, expected %06x".format(seen ?: -1, color), device.matches(seen, color))
    }

    /** Opens [url] in the current tab and waits until the viewer named it [title] and drew [color]. */
    private fun openPdf(url: String, title: String, color: Int): BrowserState {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        val state = device.awaitState { it.activeTab?.url == url && it.activeTab?.title == title && it.activeTab?.loading == false }
        assertScreen(color)
        return state
    }

    private fun requests(key: String) = Fixtures.pdfServed(key)?.getInt("requests") ?: 0

    @Test
    fun aTappedPdfLinkShowsTheFirstPageAndTheTitleFromOneRequest() {
        val key = key("tap")
        val link = Fixtures.pdfLink(key)
        val pdf = Fixtures.pdf("plain.pdf", key)
        device.browser.openUrl(link, Placement.CURRENT_TAB)
        device.awaitPage(link)
        assertScreen(Device.PDF_LINK_PAGE)

        tapUntil { device.browser.browserState.value.activeTab?.url == pdf }
        // The PDF's metadata title replaces its file name (owner decision, 29 October 2026).
        device.awaitState { it.activeTab?.url == pdf && it.activeTab?.title == Fixtures.PDF_PLAIN_TITLE && it.activeTab?.loading == false }
        assertScreen(Device.PDF_PLUM)
        // The viewer reads the body Servo fetched: no second request.
        assertEquals(1, requests(key))
    }

    @Test
    fun aPdfWithoutATitleIsNamedAfterItsFile() {
        openPdf(Fixtures.pdf("untitled.pdf"), "untitled.pdf", Device.PDF_PLUM)
    }

    @Test
    fun aCookieGatedPdfIsShownWithTheSessionsCookie() {
        val cookie = Fixtures.cookieSet("pdf${System.currentTimeMillis()}")
        device.browser.openUrl(cookie, Placement.CURRENT_TAB)
        device.awaitPage(cookie)
        // Without the cookie the server answers 403 with an HTML page.
        openPdf(Fixtures.pdf("cookie.pdf"), "PDF-COOKIE-TITLE", Device.PDF_PLUM)
    }

    @Test
    fun anAttachmentPdfOpensInTheViewer() {
        // Content-Disposition: attachment; Combray has no downloads yet (owner decision).
        openPdf(Fixtures.pdf("attachment.pdf"), "PDF-ATTACHMENT-TITLE", Device.PDF_PLUM)
    }

    @Test
    fun aTooLargePdfShowsNoPageAndItsDownloadStops() {
        val key = key("large")
        val pdf = Fixtures.pdf("large.pdf", key)
        device.browser.openUrl(pdf, Placement.CURRENT_TAB)
        // Refused at its Content-Length: the document ends with the viewer's message.
        device.awaitState { it.activeTab?.url == pdf && it.activeTab?.title == "large.pdf" && it.activeTab?.loading == false }
        assertScreen(Device.PDF_VIEWER_LIGHT)

        // The engine cancelled the download: the server stopped long before the end.
        val deadline = System.currentTimeMillis() + DOWNLOAD_STOP_MS
        while (Fixtures.pdfServed(key)?.optBoolean("ended") != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000)
        }
        val sent = Fixtures.pdfServed(key)!!.getLong("bytesSent")
        assertTrue("sent $sent of ${Fixtures.PDF_TOO_LARGE_BYTES} bytes", sent < Fixtures.PDF_TOO_LARGE_BYTES / 2)
        assertEquals(1, requests(key))
    }

    @Test
    fun aDamagedPdfShowsNoPageAndNoStandIn() {
        val pdf = Fixtures.pdf("corrupt.pdf")
        device.browser.openUrl(pdf, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == pdf && it.activeTab?.title == "corrupt.pdf" && it.activeTab?.loading == false }
        // The viewer's background, not Servo's white stand-in page; PDFium has had time to open it.
        Thread.sleep(SETTLE_MS)
        assertScreen(Device.PDF_VIEWER_LIGHT)
    }

    @Test
    fun backReturnsToThePdfWithoutAnotherRequest() {
        val key = key("back")
        val pdf = Fixtures.pdf("plain.pdf", key)
        openPdf(pdf, Fixtures.PDF_PLAIN_TITLE, Device.PDF_PLUM)
        val other = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(other, Placement.CURRENT_TAB)
        device.awaitPage(other)
        assertScreen(Device.GREEN)

        // From the back-forward cache: the viewer reads the same body again.
        device.browser.goBack()
        device.awaitPage(pdf)
        assertScreen(Device.PDF_PLUM)
        assertEquals(1, requests(key))

        device.browser.goForward()
        device.awaitPage(other)
        assertScreen(Device.GREEN)
    }

    @Test
    fun aWebLinkInAPdfOpensItsPage() {
        // Links for other schemes on the same page are not links (host tests).
        openPdf(Fixtures.pdf("web-link.pdf"), "PDF-WEB-LINK-TITLE", Device.PDF_TEAL)
        val target = Fixtures.solid(Device.GREEN)
        tapUntil { device.browser.browserState.value.activeTab?.url == target }
        device.awaitPage(target)
        assertScreen(Device.GREEN)
    }

    @Test
    fun aPageLinkInAPdfScrollsToItsPage() {
        val pdf = Fixtures.pdf("page-link.pdf")
        openPdf(pdf, "PDF-PAGE-LINK-TITLE", Device.PDF_TEAL)
        // Page 3 is sand; the address stays the PDF's.
        tapUntil { device.matches(device.screenColor(), Device.PDF_SAND) }
        assertEquals(pdf, device.browser.browserState.value.activeTab?.url)
    }

    @Test
    fun aPdfInAFrameKeepsServosStandInPage() {
        val key = key("frame")
        val frame = Fixtures.pdfFrame(key)
        device.browser.openUrl(frame, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == frame && it.activeTab?.title == "PDF-FRAME:loaded" }
        assertEquals(1, requests(key))
        // The frame fills the screen and shows Servo's white stand-in page. A viewer in it would
        // show its background, a message on it, or the plum band. Every sample is checked: the
        // light home screen matches white too, so one sample could be taken before the frame drew.
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            device.dismissSystemDialogs()
            val seen = device.screenColor()
            assertTrue("the frame does not show Servo's stand-in (%06x)".format(seen ?: -1), device.matches(seen, Device.STAND_IN_WHITE))
            Thread.sleep(1_000)
        }
    }

    /**
     * Taps the middle of the page area until [done]. Under software emulation a tap right after
     * the first paint can arrive before the page can hit-test it, and is then lost.
     */
    private fun tapUntil(done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + Device.LOAD_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            tapPage()
            val waited = SystemClock.uptimeMillis() + TAP_OUTCOME_MS
            while (SystemClock.uptimeMillis() < waited) {
                if (done()) return
                Thread.sleep(500)
            }
        }
        throw AssertionError("tapping had no effect")
    }

    /** Taps where the screen checks look: in the band of a PDF's first page, and on a link over it. */
    private fun tapPage() {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.35f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private companion object {
        /** How long one tap may take to show its effect before the next try. */
        const val TAP_OUTCOME_MS = 15_000L
        /** Long enough for the viewer to open and draw a small PDF on a software-emulated device. */
        const val SETTLE_MS = 15_000L
        /** For the server to see the cancelled download end. */
        const val DOWNLOAD_STOP_MS = 60_000L
    }
}
