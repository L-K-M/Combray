package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A page's title and address reach the database only within [StoredText]'s bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoredTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var engine: FakeEngine
    private lateinit var browser: BrowserCoordinator
    private var ids = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = FakeEngine()
        browser = BrowserCoordinator(database, engine, scope, clock = { START }, newId = { "id${ids++}" }).also { it.start() }
        engine.emit(EngineEvent.EngineReady(720, 1280, FakeEngine.USER_AGENT))
        idle()
    }

    @After
    fun tearDown() {
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        database.close()
    }

    private fun idle() = runBlocking { browser.awaitIdle() }

    private fun state() = browser.browserState.value

    private fun commit(document: Long, url: String, kind: HistoryKind = HistoryKind.DOCUMENT_COMMITTED) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), kind, document, url, 0, 1))
        idle()
    }

    private fun open(url: String) {
        browser.openUrl(url, Placement.CURRENT_TAB); idle()
        commit(10, url)
    }

    private fun tab() = runBlocking { database.browser().openTabs() }.single()

    private fun visitUrl(id: String?) = runBlocking { id?.let { database.browser().visit(it) } }?.url

    private fun history() = runBlocking { browser.recent().first() }.filterIsInstance<RecentItem.Visit>().map { it.url }

    /** [prefix] grown one character past the longest address that is kept. */
    private fun tooLong(prefix: String) = prefix + "x".repeat(StoredText.MAX_ADDRESS_CHARS + 1 - prefix.length)

    @Test
    fun aTitleWithinTheBoundIsKeptWhole() {
        val title = "t".repeat(StoredText.MAX_TITLE_CHARS)
        assertEquals(title, StoredText.title(title))
    }

    @Test
    fun aLongTitleIsCutButNeverThroughASurrogatePair() {
        assertEquals(StoredText.MAX_TITLE_CHARS, StoredText.title("t".repeat(StoredText.MAX_TITLE_CHARS + 1)).length)

        // The pair straddles the bound: it goes whole rather than leave a lone surrogate.
        val straddling = "t".repeat(StoredText.MAX_TITLE_CHARS - 1) + "😀" + "tail"
        assertEquals("t".repeat(StoredText.MAX_TITLE_CHARS - 1), StoredText.title(straddling))

        // A pair that ends on the bound fits.
        val fitting = "t".repeat(StoredText.MAX_TITLE_CHARS - 2) + "😀" + "tail"
        assertEquals(fitting.dropLast(4), StoredText.title(fitting))
    }

    @Test
    fun aTitleLongerThanACursorWindowIsStoredCut() {
        open("https://a.test/")

        // Stored whole, this row could be written but never read again.
        engine.emit(EngineEvent.TitleChanged(engine.view(1), 10, "t".repeat(3 * 1024 * 1024), Attribution.CURRENT))
        idle()

        val tab = tab()
        assertEquals(StoredText.MAX_TITLE_CHARS, tab.title?.length)
        val visit = runBlocking { database.browser().visit(tab.currentVisitId!!) }
        assertEquals(StoredText.MAX_TITLE_CHARS, visit?.title?.length)
        assertEquals(StoredText.MAX_TITLE_CHARS, state().activeTab?.title?.length)
    }

    @Test
    fun theLongestKeptAddressIsStoredWhole() {
        val longest = tooLong("https://a.test/?q=").dropLast(1)
        assertEquals(StoredText.MAX_ADDRESS_CHARS, longest.length)

        open(longest)

        assertEquals(longest, tab().url)
        assertEquals(longest, visitUrl(tab().currentVisitId))
    }

    @Test
    fun aDocumentAtAnAddressTooLongToKeepIsShownButNotStored() {
        open("https://a.test/")

        // The page went there itself: the request was not the app's to refuse.
        commit(11, tooLong("https://a.test/?q="))

        assertNull(tab().url)
        assertNull(tab().currentVisitId)
        // Still a page: a tab with a renderer and no address is not the new-tab page.
        assertFalse(state().activeTab!!.isHome)
        // The page it left is history; the over-long one is not.
        assertEquals(listOf("https://a.test/"), history())
    }

    @Test
    fun aPageGrowingItsOwnAddressKeepsTheLastOneThatFit() {
        open("https://a.test/app")

        commit(10, tooLong("https://a.test/app#"), HistoryKind.SAME_DOCUMENT)
        assertEquals("https://a.test/app", tab().url)
        commit(10, tooLong("https://a.test/other?"), HistoryKind.SAME_DOCUMENT)
        assertEquals("https://a.test/app", tab().url)
        commit(10, tooLong("https://a.test/app?"), HistoryKind.LIST_UPDATED)

        assertEquals("https://a.test/app", tab().url)
        assertEquals("https://a.test/app", visitUrl(tab().currentVisitId))
        assertTrue(history().isEmpty())
    }

    @Test
    fun anAddressTooLongToKeepIsNotOpened() {
        open("https://a.test/")
        val tabId = tab().id
        val address = tooLong("https://a.test/?q=")

        browser.openUrl(address, Placement.CURRENT_TAB); idle()
        assertEquals(Notice.Problem("That address is too long to open."), state().notice)

        browser.openUrl(address, Placement.NEW_TAB); idle()
        browser.openUrl(address, Placement.BACKGROUND_TAB); idle()
        browser.openUrlInTab(tabId, address); idle()
        browser.openUrlFromTab(address, tabId); idle()
        browser.openFromOtherApp(address); idle()

        assertTrue(engine.navigated.isEmpty())
        assertEquals(listOf(1L to "https://a.test/"), engine.created)
        assertEquals("https://a.test/", tab().url)
    }

    private companion object {
        const val START = 1_760_000_000_000L
    }
}
