package ch.lkmc.combray

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.engine.ScrollEdge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Process death, in two instrumentation runs: Android kills the process when the first run ends,
 * without a clean shutdown, as it kills background apps. Open tabs, the focused tab, a queued
 * save and a login cookie must survive, and a PDF read down to its last page opens again on its
 * first (owner decision, 29 October 2026). scripts/check-app.sh runs [prepare] then [verify] with
 * the same `-e value <alnum>` argument.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val value get() = InstrumentationRegistry.getArguments().getString("value") ?: "unset"

    @Test
    fun prepare() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        assertEquals(Configured.Saved, runBlocking { device.integrations.configureKarakeep(Fixtures.KARAKEEP_ROOT, Fixtures.KARAKEEP_KEY) })

        val cookiePage = "${Fixtures.BASE}/cookie-set?v=$value"
        device.browser.openUrl(cookiePage, Placement.CURRENT_TAB)
        device.awaitPage(cookiePage)
        device.browser.openUrl(Fixtures.solid(Device.RED), Placement.NEW_TAB)
        device.awaitPage(Fixtures.solid(Device.RED))

        // A long PDF, scrolled to its last page (navy; the first is sand).
        device.browser.openUrl(PDF, Placement.NEW_TAB)
        val pdf = device.awaitState { it.activeTab?.url == PDF && it.activeTab?.title == PDF_TITLE && it.activeTab?.loading == false }
        assertTrue(device.matches(device.awaitScreen(Device.PDF_SAND), Device.PDF_SAND))
        device.browser.scrollToEdge(pdf.activeTab!!.page!!, ScrollEdge.BOTTOM)
        assertTrue(device.matches(device.awaitScreen(Device.PDF_NAVY), Device.PDF_NAVY))

        device.browser.openUrl(Fixtures.solid(Device.GREEN), Placement.NEW_TAB)
        device.awaitPage(Fixtures.solid(Device.GREEN))

        // A save that cannot be delivered yet: it must still be there after the kill.
        Fixtures.fault("karakeep", "server_error", 1000)
        runBlocking { device.integrations.save(Fixtures.solid(Device.GREEN), "SOLID-2e6b4f") }
        runBlocking { withTimeout(60_000) { device.integrations.saveStatus(Fixtures.solid(Device.GREEN)).first { it == SaveStatus.Pending } } }
        device.idle()

        // Leave the app like a user (Home): Combray then writes the cookie jar (Servo patch 0004).
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val jar = File(device.app.filesDir, "servo-profile/cookie_jar.json")
        val deadline = System.currentTimeMillis() + 60_000
        while (!(jar.exists() && jar.readText().contains(value)) && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertTrue("cookie jar not written after Home", jar.exists() && jar.readText().contains(value))
    }

    @Test
    fun verify() {
        // Restored from the database before the engine has a surface.
        val state = device.awaitState { it.openTabCount == 4 }
        assertEquals(Fixtures.solid(Device.GREEN), state.activeTab?.url)
        assertTrue(device.recent().filterIsInstance<RecentItem.OpenTab>().any { it.url == Fixtures.solid(Device.RED) })
        device.awaitEngine()
        device.awaitPage(Fixtures.solid(Device.GREEN))
        assertTrue(device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))

        // The queued save goes out once the server works again.
        Fixtures.fault("karakeep", "none", 0)
        device.integrations.kick()
        runBlocking { withTimeout(Device.LOAD_TIMEOUT_MS) { device.integrations.saveStatus(Fixtures.solid(Device.GREEN)).first { it == SaveStatus.Saved } } }
        // The server got it: prepare reset the fake server, so a stale local "saved" cannot pass.
        assertTrue(Fixtures.state().getJSONObject("bookmarks").has(Fixtures.solid(Device.GREEN)))

        // The cookie set before the kill is still sent (Servo patch 0004 writes it in time).
        val echo = "${Fixtures.BASE}/cookie-echo"
        device.browser.openUrl(echo, Placement.NEW_TAB)
        val echoed = device.awaitState { it.activeTab?.title?.startsWith("COOKIE:") == true }
        assertTrue("cookie header was ${echoed.activeTab?.title}", echoed.activeTab?.title?.contains("combray=$value") == true)

        // The PDF opens again from a new request, on its first page.
        val pdfTab = device.recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == PDF }
        device.browser.selectTab(pdfTab.tabId)
        device.awaitState { it.activeTab?.url == PDF && it.activeTab?.title == PDF_TITLE && it.activeTab?.loading == false }
        assertTrue(device.matches(device.awaitScreen(Device.PDF_SAND), Device.PDF_SAND))
    }

    private companion object {
        val PDF = Fixtures.pdf("pages.pdf")
        const val PDF_TITLE = "PDF-PAGES-TITLE"
    }
}
