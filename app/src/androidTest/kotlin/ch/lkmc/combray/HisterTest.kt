package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.HisterSearch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun pausedCaptureSendsNothing() {
        runBlocking { hister.setCaptureEnabled(false) }
        val url = "${Fixtures.BASE}/rendered.html?p=paused${System.currentTimeMillis()}"
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        Thread.sleep(15_000)
        assertFalse(documents().has(url))
    }
}
