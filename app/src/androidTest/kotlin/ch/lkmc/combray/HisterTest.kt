package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.HisterSearch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M3 gate: rendered text reaches Hister and is searchable; excluded sites never leave. */
@RunWith(AndroidJUnit4::class)
class HisterTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val hister get() = device.integrations.hister

    @Before
    fun setUp() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        runBlocking {
            assertEquals(Configured.Saved, hister.configure(Fixtures.HISTER_ROOT, Fixtures.HISTER_TOKEN))
            hister.setCaptureEnabled(true)
        }
    }

    /** Documents the fake Hister received, by key (URL without fragment). */
    private fun documents(): JSONObject = Fixtures.state().getJSONObject("hister")

    private fun awaitDocument(url: String, timeoutMs: Long = Device.LOAD_TIMEOUT_MS): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            documents().optJSONObject(url)?.let { return it }
            Thread.sleep(1_000)
        }
        throw AssertionError("Hister never received $url")
    }

    @Test
    fun aPhraseThatExistsOnlyInTheRenderedPageBecomesSearchable() {
        val parameter = "combray${System.currentTimeMillis()}"
        val phrase = "RENDERED-${parameter.reversed()}"
        val url = "${Fixtures.BASE}/rendered.html?p=$parameter"
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)

        val document = awaitDocument(url)
        assertTrue(document.getString("text").contains(phrase))
        assertFalse(document.getString("text").contains("static placeholder"))
        assertFalse(url.contains(phrase))

        val result = runBlocking { hister.search(phrase) } as HisterSearch.Results
        assertEquals(listOf(url), result.hits.map { it.url })
        // The fixture sends an empty page_key, like the pinned server: no "More results".
        assertNull(result.nextPage)
    }

    @Test
    fun anExcludedSiteIsNeverSent() {
        assertTrue(runBlocking { hister.addExclusion("127.0.0.1", includeSubdomains = false) })
        val excluded = "http://127.0.0.1:8766/private.html"
        device.browser.openUrl(excluded, Placement.CURRENT_TAB)
        device.awaitPage(excluded)

        // An allowed page loaded after it is captured, so capture was running meanwhile.
        val allowed = "${Fixtures.BASE}/rendered.html?p=allowed${System.currentTimeMillis()}"
        device.browser.openUrl(allowed, Placement.NEW_TAB)
        awaitDocument(allowed)
        assertFalse(documents().has(excluded))
        assertFalse(Fixtures.state().getJSONArray("log").toString().contains("private.html"))
    }

    @Test
    fun aFileServoCannotShowIsNotSentAsItsStandInPage() {
        // Servo shows the file as a page of its own at the file's address, saying "Unknown content
        // type (application/octet-stream).": that sentence is not the file's text.
        device.browser.openUrl(Fixtures.UNSHOWN_FILE, Placement.CURRENT_TAB)
        // The stand-in page has committed: a Servo that stopped showing one would not pass for this.
        device.awaitState { it.activeTab?.url == Fixtures.UNSHOWN_FILE && it.activeTab?.page != null && it.activeTab?.loading == false }

        // A page loaded after it is captured, so capture was running meanwhile.
        val later = "${Fixtures.BASE}/rendered.html?p=afterfile${System.currentTimeMillis()}"
        device.browser.openUrl(later, Placement.NEW_TAB)
        awaitDocument(later)
        assertFalse(documents().has(Fixtures.UNSHOWN_FILE))
    }

    @Test
    fun aPdfInTheViewerIsNotSentAsTheViewersLabels() {
        // A damaged PDF: once the viewer has named it, its message ("Loading PDF…", then why the
        // file cannot be shown) is text in the page whenever capture runs, and only capture.js's
        // viewer check keeps it out of Hister. The PDF's text reaches Hister in a later step
        // (docs/PDF.md).
        val theme = device.settings.themeMode.value
        device.settings.setThemeMode(ThemeMode.LIGHT)
        try {
            val pdf = Fixtures.pdf("corrupt.pdf", "hister${System.currentTimeMillis()}")
            device.browser.openUrl(pdf, Placement.CURRENT_TAB)
            device.awaitState { it.activeTab?.url == pdf && it.activeTab?.title == "corrupt.pdf" && it.activeTab?.loading == false }
            // The viewer's background (its light appearance), not Servo's white stand-in page.
            assertTrue(device.matches(device.awaitScreen(Device.PDF_VIEWER_LIGHT), Device.PDF_VIEWER_LIGHT))

            val later = "${Fixtures.BASE}/rendered.html?p=afterpdf${System.currentTimeMillis()}"
            device.browser.openUrl(later, Placement.NEW_TAB)
            awaitDocument(later)
            assertFalse(documents().has(pdf))
        } finally {
            device.settings.setThemeMode(theme)
        }
    }

    @Test
    fun pausedCaptureSendsNothing() {
        runBlocking { hister.setCaptureEnabled(false) }
        val url = "${Fixtures.BASE}/rendered.html?p=paused${System.currentTimeMillis()}"
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        Thread.sleep(15_000)
        assertFalse(documents().has(url))
    }
}
