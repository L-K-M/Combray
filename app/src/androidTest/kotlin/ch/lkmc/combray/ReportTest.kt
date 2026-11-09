package ch.lkmc.combray

import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.Configured
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * More › Report this site end to end: the real engine's frame, the sheet, the durable queue and
 * the fake report service, which checks each report with the service's own rules.
 *
 * The sheet is its own window. Every step in it is a semantics action, not an injected touch or
 * keyboard input, because a new window can miss Android's focus deadline under software
 * emulation (docs/BUILDING.md).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ReportTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        // App data outlives a check: an unsent report from an earlier one must not arrive here.
        runBlocking { device.integrations.reports.remove() }
        assertEquals(Configured.Saved, runBlocking { device.integrations.reports.configure(Fixtures.REPORTS_ROOT, Fixtures.REPORTS_TOKEN) })
        // Sheets can outstay Espresso's idle window on the software emulator (docs/BUILDING.md).
        IdlingPolicies.setIdlingResourceTimeout(Device.LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        IdlingPolicies.setMasterPolicyTimeout(Device.LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun aReportCarriesTheScreenshotAddressTitleAndComment() {
        val url = open(Fixtures.solid(SHOWN))
        assertTrue(device.matches(device.awaitScreen(SHOWN), SHOWN))
        startReport()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.report_include_screenshot)) and isOn(), Device.LOAD_TIMEOUT_MS)
        val comment = "The menu covers the text ${UUID.randomUUID()}"
        field(R.string.report_comment).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(comment)) }
        send()

        val report = Fixtures.awaitReport { it.optString("comment") == comment }
        val page = report.getJSONObject("page")
        assertEquals(url, page.getString("url"))
        assertFalse(page.getBoolean("url_edited"))
        assertEquals(Fixtures.title(SHOWN), page.getString("title"))
        assertEquals("page", page.getString("display"))
        val client = report.getJSONObject("client")
        assertEquals(BuildConfig.VERSION_NAME, client.getString("app_version"))
        assertEquals(BuildConfig.SERVO_REVISION, client.getString("engine_revision"))
        assertEquals(Build.VERSION.RELEASE, client.getString("android_release"))
        assertTrue(report.isNull("screenshot_omitted"))

        // The frame that was on screen, as the service received it.
        val screenshot = report.getJSONObject("screenshot")
        val png = Fixtures.reportScreenshot(report.getString("id"))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "the screenshot does not decode" }
        assertEquals(screenshot.getInt("width"), bitmap.width)
        assertEquals(screenshot.getInt("height"), bitmap.height)
        val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and Device.RGB_MASK
        assertTrue("screenshot centre is %06x".format(centre), device.matches(centre, SHOWN))
    }

    @Test
    fun aTrimmedAddressWithoutScreenshotSendsOnlyThat() {
        val page = Fixtures.solid(TRIMMED)
        val url = open("$page?k=report#part")
        startReport()
        val include = hasText(text(R.string.report_include_screenshot))
        compose.waitUntilAtLeastOneExists(include and isOn(), Device.LOAD_TIMEOUT_MS)
        compose.onNode(include).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(include and isOff(), Device.LOAD_TIMEOUT_MS)
        compose.onNode(hasText(text(R.string.report_address_trim)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        field(R.string.report_address).assert(hasText(page))
        send()

        val report = Fixtures.awaitReport { true }
        assertEquals(page, report.getJSONObject("page").getString("url"))
        assertTrue("the tab's address was $url", report.getJSONObject("page").getBoolean("url_edited"))
        assertTrue(report.isNull("screenshot"))
        assertEquals("user", report.getString("screenshot_omitted"))
        assertEquals(1, Fixtures.reports().length())
    }

    @Test
    fun withoutAServiceTheRowAsksForSetup() {
        runBlocking { device.integrations.reports.remove() }
        open(Fixtures.solid(NOT_SET_UP))
        startReport()

        compose.waitUntilAtLeastOneExists(hasText(text(R.string.report_not_set_up)), Device.LOAD_TIMEOUT_MS)
        compose.onNodeWithText(text(R.string.report_include_screenshot)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_send)).assertDoesNotExist()
        val log = Fixtures.state().getJSONArray("log")
        assertTrue((0 until log.length()).none { log.getJSONObject(it).getString("service") == "reports" })
    }

    @Test
    fun aLostAnswerIsNotADuplicate() {
        // The fixture stores the report, then answers after the client's 15 s timeout.
        Fixtures.fault("reports", "store_then_hang", 1)
        open(Fixtures.solid(LOST_ANSWER))
        startReport()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.report_include_screenshot)) and isOn(), Device.LOAD_TIMEOUT_MS)
        send()

        val id = Fixtures.awaitReport { true }.getString("id")
        // Stored at the fixture, so the job is in flight: it ends only when the retry is answered.
        runBlocking { withTimeout(LOST_ANSWER_TIMEOUT_MS) { device.integrations.openJobs(ServiceKind.SITE_REPORTS).first { it.isEmpty() } } }
        assertEquals(listOf(id, id), Fixtures.reportPosts().map { it.getString("id") })
        assertEquals(1, Fixtures.reports().length())
    }

    /** Opens [url] in the current tab and waits for its title; returns the address the tab shows. */
    private fun open(url: String): String {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        val loaded = device.awaitState { it.activeTab?.url?.startsWith(url.substringBefore('?')) == true && it.activeTab?.loading == false }
        device.awaitState { it.activeTab?.title != null }
        return loaded.activeTab!!.url!!
    }

    private fun startReport() {
        device.dismissSystemDialogs()
        // Engine state can arrive before the bar's merged semantics update.
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodesWithContentDescription(MENU).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription(MENU).performClick()
        val row = hasText(text(R.string.action_report_site)) and hasClickAction()
        compose.waitUntilAtLeastOneExists(row, Device.LOAD_TIMEOUT_MS)
        compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Sends once Send is enabled, which waits for the screenshot to be encoded. */
    private fun send() {
        val send: SemanticsMatcher = hasText(text(R.string.action_send)) and hasClickAction() and isEnabled()
        compose.waitUntilAtLeastOneExists(send, Device.LOAD_TIMEOUT_MS)
        compose.onNode(send).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(text(label)))

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private companion object {
        const val MENU = "Menu"

        /** Page colours no other check uses, so a stale frame of another check cannot pass. */
        const val SHOWN = 0x7a2e3b
        const val TRIMMED = 0x2e7a3b
        const val NOT_SET_UP = 0x3b2e7a
        const val LOST_ANSWER = 0x7a7a2e

        /** A hang past the timeout, a backoff and the retry, on a software-emulated device. */
        const val LOST_ANSWER_TIMEOUT_MS = 300_000L
    }
}
