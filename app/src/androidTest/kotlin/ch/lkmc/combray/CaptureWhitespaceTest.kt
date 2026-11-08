package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.HisterSearch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The existing article fixture has two anchors separated by a whitespace-only text node. */
@RunWith(AndroidJUnit4::class)
class CaptureWhitespaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @After
    fun stopCapture() {
        runBlocking { device.integrations.hister.setCaptureEnabled(false) }
    }

    @Test
    fun separatedInlineWordsRemainSearchableAfterRealEngineCapture() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        val hister = device.integrations.hister
        runBlocking {
            assertEquals(Configured.Saved, hister.configure(Fixtures.HISTER_ROOT, Fixtures.HISTER_TOKEN))
            hister.setCaptureEnabled(true)
        }
        device.browser.openUrl(Fixtures.ARTICLE, Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.ARTICLE)

        val text = awaitCapturedText()
        val phrase = "READER-NAV-TEXT Section"
        assertTrue("Inline whitespace must separate the fixture's anchor text", text.contains(phrase))
        val result = runBlocking { hister.search(phrase) } as HisterSearch.Results
        assertEquals(listOf(Fixtures.ARTICLE), result.hits.map { it.url })
    }

    private fun awaitCapturedText(): String {
        val deadline = System.currentTimeMillis() + Device.LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Fixtures.state().optJSONObject("hister")?.optJSONObject(Fixtures.ARTICLE)?.let { return it.getString("text") }
            Thread.sleep(200)
        }
        throw AssertionError("The synthetic article was never captured")
    }
}
