package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.KarakeepRead
import ch.lkmc.combray.core.integration.SaveStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M2 gate: one-tap Karakeep saves through the UI, against the fake Karakeep fixture. */
@RunWith(AndroidJUnit4::class)
class SaveTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        assertEquals(Configured.Saved, runBlocking { device.integrations.configureKarakeep(Fixtures.KARAKEEP_ROOT, Fixtures.KARAKEEP_KEY) })
        // App data outlives a check: a list chosen by an earlier one must not apply here.
        chooseList(null)
    }

    private fun chooseList(id: String?) = runBlocking {
        val lists = device.integrations.karakeepLists()!!
        val list = id?.let { wanted -> (lists.read as KarakeepRead.Ok).value.single { it.id == wanted } }
        assertTrue(device.integrations.setKarakeepList(lists.fingerprint, list))
    }

    private fun open(color: Int, placement: Placement = Placement.CURRENT_TAB): String {
        val url = Fixtures.solid(color)
        device.browser.openUrl(url, placement)
        device.awaitPage(url)
        device.awaitState { it.activeTab?.title != null }
        return url
    }

    private fun awaitSaveStatus(url: String, timeoutMs: Long = Device.LOAD_TIMEOUT_MS, expected: (SaveStatus) -> Boolean) =
        runBlocking { withTimeout(timeoutMs) { device.integrations.saveStatus(url).first(expected) } }

    private fun bookmarks(): JSONObject = Fixtures.state().getJSONObject("bookmarks")

    private fun posts() = Fixtures.state().getJSONArray("log").let { log ->
        (0 until log.length()).map { log.getJSONObject(it) }.count { it.getString("service") == "karakeep" && it.getString("method") == "POST" }
    }

    private fun tapSave() {
        device.dismissSystemDialogs()
        compose.onNodeWithContentDescription("Save").performClick()
    }

    @Test
    fun oneTapSavesThePageWithItsTitle() {
        val url = open(Device.GREEN)
        tapSave()
        awaitSaveStatus(url) { it == SaveStatus.Saved }
        val saved = bookmarks()
        assertEquals(1, saved.length())
        assertEquals("SOLID-2e6b4f", saved.getJSONObject(url).getString("title"))
        compose.onNodeWithContentDescription("Saved to Karakeep").assertExists()
    }

    @Test
    fun switchingTabsRightAfterTheTapStillSavesTheTappedPage() {
        // Pages no other check saves: app data persists across the checks of one run, and an
        // already saved page has no "Save" button to tap.
        val first = open(UNSAVED_FIRST)
        val firstTab = device.browser.browserState.value.activeTab!!.id
        val second = open(UNSAVED_SECOND, Placement.NEW_TAB)
        tapSave()
        device.browser.selectTab(firstTab)
        awaitSaveStatus(second) { it == SaveStatus.Saved }
        val saved = bookmarks()
        assertEquals(1, saved.length())
        assertEquals(true, saved.has(second))
        assertEquals(false, saved.has(first))
    }

    @Test
    fun aLostAnswerIsRetriedAndMakesOneBookmark() {
        // The fixture stores the link, then answers after the client's 15 s timeout.
        Fixtures.fault("karakeep", "store_then_hang", 1)
        val url = open(Device.OCHRE)
        tapSave()
        awaitSaveStatus(url) { it == SaveStatus.Pending }
        awaitSaveStatus(url, timeoutMs = 300_000) { it == SaveStatus.Saved }
        assertEquals(1, bookmarks().length())
        assertEquals(2, posts())
    }

    @Test
    fun aRejectedKeyPausesTheSaveUntilItIsResumedWithAWorkingKey() {
        runBlocking { device.integrations.configureKarakeep(Fixtures.KARAKEEP_ROOT, "wrong-key") }
        val url = open(Device.BLUE)
        tapSave()
        awaitSaveStatus(url) { it is SaveStatus.NeedsAttention }
        assertEquals(0, bookmarks().length())

        runBlocking { device.integrations.configureKarakeep(Fixtures.KARAKEEP_ROOT, Fixtures.KARAKEEP_KEY) }
        runBlocking { device.integrations.resume(ServiceKind.KARAKEEP).join() }
        awaitSaveStatus(url) { it == SaveStatus.Saved }
        assertEquals(1, bookmarks().length())
    }

    @Test
    fun aSaveGoesIntoTheChosenList() {
        chooseList("reading")
        val url = open(LISTED)
        tapSave()
        awaitSaveStatus(url) { it == SaveStatus.Saved }
        val id = bookmarks().getJSONObject(url).getString("id")
        val reading = Fixtures.state().getJSONObject("lists").getJSONArray("reading")
        assertEquals(listOf(id), (0 until reading.length()).map(reading::getString))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recentOpensOnTabsAndItsKarakeepScopeOpensASavedPage() {
        val url = open(SHOWN_IN_RECENT)
        tapSave()
        awaitSaveStatus(url) { it == SaveStatus.Saved }

        device.dismissSystemDialogs()
        compose.onNodeWithContentDescription("Recent", substring = true).performClick()
        compose.onNode(hasText("Open tabs") and isSelected()).assertExists()
        compose.onNodeWithText("Karakeep").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Search your Karakeep bookmarks"), Device.LOAD_TIMEOUT_MS)
        // The bookmark row: in this scope no tab row shows the page's title.
        val title = "SOLID-%06x".format(SHOWN_IN_RECENT)
        compose.waitUntilAtLeastOneExists(hasText(title), Device.LOAD_TIMEOUT_MS)
        compose.onNodeWithText(title).performClick()
        device.awaitState { it.openTabCount == 2 && it.activeTab?.url == url }
    }

    private companion object {
        const val UNSAVED_FIRST = 0x6b2e5f
        const val UNSAVED_SECOND = 0x2e5f6b
        const val LISTED = 0x5f2e6b
        const val SHOWN_IN_RECENT = 0x2e6b6b
    }
}
