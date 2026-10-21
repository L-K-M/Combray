package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.TabState
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.PageControlContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Logical-state invariants (docs/IMPLEMENTATION_PLAN.md section 4) against a fake engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var engine: FakeEngine
    private lateinit var browser: BrowserCoordinator
    private var now = START
    private var ids = 0
    private var unusedTabs = UnusedTabs.KEEP

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        browser = newCoordinator()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun newCoordinator(): BrowserCoordinator {
        engine = FakeEngine()
        return BrowserCoordinator(database, engine, scope, clock = { now }, newId = { "id${ids++}" }, unusedTabs = { unusedTabs }).also { it.start() }
    }

    private fun idle() = runBlocking { browser.awaitIdle() }

    private fun ready() {
        engine.emit(EngineEvent.EngineReady(720, 1280))
        idle()
    }

    private fun state() = browser.browserState.value

    private fun recent(query: String? = null) = runBlocking { browser.recent(query).first() }

    /** Simulates Servo committing [url] as [document] in the view the coordinator created for it. */
    private fun commit(native: Long, document: Long, url: String, kind: HistoryKind = HistoryKind.DOCUMENT_COMMITTED, index: Int = 0, length: Int = 1) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), kind, document, url, index, length))
        idle()
    }

    private fun title(native: Long, document: Long, title: String, attribution: Attribution = Attribution.CURRENT) {
        engine.emit(EngineEvent.TitleChanged(engine.view(native), document, title, attribution))
        idle()
    }

    @Test
    fun viewsAreCreatedOnlyOnceTheEngineIsReady() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        assertTrue(engine.created.isEmpty())
        assertEquals("https://a.test/", state().activeTab?.url)

        ready()
        assertEquals(listOf(1L to "https://a.test/"), engine.created)
        assertEquals(1L, engine.selected.last())
    }

    @Test
    fun navigatingKeepsTheOldPageAsHistoryAndTheTabAsOneRow() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        commit(1, 10, "https://a.test/")
        title(1, 10, "Page A")
        browser.openUrl("https://b.test/", Placement.CURRENT_TAB)
        idle()
        assertEquals(1L to "https://b.test/", engine.navigated.last().let { it.first to it.third })
        commit(1, 11, "https://b.test/", index = 1, length = 2)
        title(1, 11, "Page B")

        val items = recent()
        assertEquals(2, items.size)
        val tab = items.filterIsInstance<RecentItem.OpenTab>().single()
        assertEquals("Page B", tab.title)
        assertEquals("Page A", items.filterIsInstance<RecentItem.Visit>().single().title)
        assertTrue(state().activeTab!!.canGoBack)
    }

    @Test
    fun twoTabsAtTheSameAddressStayDistinct() {
        ready()
        browser.openUrl("https://same.test/", Placement.NEW_TAB)
        idle()
        commit(1, 10, "https://same.test/")
        browser.openUrl("https://same.test/", Placement.NEW_TAB)
        idle()
        commit(2, 20, "https://same.test/")

        val tabs = recent().filterIsInstance<RecentItem.OpenTab>()
        assertEquals(2, tabs.size)
        assertNotEquals(tabs[0].tabId, tabs[1].tabId)
        val second = state().activeTab!!.id

        // Selecting the first by identity shows its own view, not the newest with that URL.
        val first = tabs.single { it.tabId != second }.tabId
        browser.selectTab(first)
        idle()
        assertEquals(1L, engine.selected.last())

        browser.closeTab(second)
        idle()
        assertEquals(listOf(2L), engine.closed)
        assertEquals(first, state().activeTab?.id)
        assertEquals(1, state().openTabCount)
    }

    @Test
    fun closingTheActiveTabFocusesTheMostRecentlyUsedOneAndUndoReopensIt() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        now += 1_000
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        now += 1_000
        browser.openUrl("https://c.test/", Placement.NEW_TAB); idle()
        commit(3, 30, "https://c.test/")
        val tabA = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == "https://a.test/" }.tabId
        now += 1_000
        browser.selectTab(tabA); idle()
        now += 1_000
        val tabC = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == "https://c.test/" }.tabId
        browser.selectTab(tabC); idle()

        browser.closeTab(tabC); idle()
        // A was focused after B, so A is the successor.
        assertEquals(tabA, state().activeTab?.id)
        assertEquals(Notice.TabClosed(tabC, null), state().notice)
        // Its page stays in Recent as history.
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://c.test/" })

        browser.reopenTab(tabC); idle()
        assertEquals(tabC, state().activeTab?.id)
        assertEquals(4L to "https://c.test/", engine.created.last())
    }

    @Test
    fun closingTheLastTabShowsHomeAndReleasesTheView() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.closeTab(state().activeTab!!.id); idle()
        assertNull(state().activeTab)
        assertEquals(listOf(1L), engine.closed)

        // Late events from the closed view change nothing.
        commit(1, 11, "https://late.test/")
        assertTrue(recent().none { it.url == "https://late.test/" })
    }

    @Test
    fun staleTitlesNeverRelabelTheCurrentPage() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        commit(1, 11, "https://b.test/")
        title(1, 10, "Old A title", Attribution.STALE)
        title(1, 11, "B")
        assertEquals("B", state().activeTab?.title)
        assertTrue(recent().none { it.title == "Old A title" })
    }

    @Test
    fun backToACachedDocumentReusesItsVisitAndReloadRefreshesIt() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        commit(1, 11, "https://b.test/", index = 1, length = 2)
        commit(1, 10, "https://a.test/", index = 0, length = 2)
        assertEquals(1, recent().count { it is RecentItem.Visit })

        // Same-URL reload: a new document, but the same visit.
        commit(1, 12, "https://a.test/", index = 0, length = 2)
        assertEquals(1, recent().count { it is RecentItem.Visit })
    }

    @Test
    fun fragmentChangesUpdateTheVisitAndPushStateToANewPathAddsOne() {
        ready()
        browser.openUrl("https://a.test/doc", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/doc")
        commit(1, 10, "https://a.test/doc#part", HistoryKind.SAME_DOCUMENT)
        assertEquals("https://a.test/doc#part", state().activeTab?.url)
        assertEquals(0, recent().count { it is RecentItem.Visit })

        commit(1, 10, "https://a.test/other", HistoryKind.SAME_DOCUMENT)
        assertEquals(listOf("https://a.test/doc#part"), recent().filterIsInstance<RecentItem.Visit>().map { it.url })
    }

    @Test
    fun servoErrorPagesAreKeptForTheTabButHiddenFromHistory() {
        ready()
        browser.openUrl("https://down.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://down.test/")
        title(1, 10, "Error loading page")
        commit(1, 11, "https://up.test/")
        assertTrue(recent().none { it is RecentItem.Visit })
    }

    @Test
    fun closedHistoryExpiresButOpenTabsDoNot() {
        ready()
        browser.openUrl("https://old-open.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://old-open.test/")
        browser.openUrl("https://old-closed.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://old-closed.test/")
        browser.closeTab(state().activeTab!!.id); idle()

        now += 8 * DAY
        browser.runMaintenance(); idle()

        val items = recent()
        assertTrue(items.any { it is RecentItem.OpenTab && it.url == "https://old-open.test/" })
        assertTrue(items.none { it.url == "https://old-closed.test/" })
        assertEquals(1, runBlocking { database.browser().openTabs() }.size)
    }

    @Test
    fun aClosedTabTakesItsPlaceInHistoryByLastUseNotByClosing() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        val tabA = state().activeTab!!.id
        now += DAY
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        now += DAY

        browser.closeTab(tabA); idle()
        val visit = recent().filterIsInstance<RecentItem.Visit>().single { it.url == "https://a.test/" }
        // A was in use until B took focus, not until it closed.
        assertEquals(START + DAY - 1, visit.time)
        assertTrue(recent().first() is RecentItem.OpenTab)
    }

    @Test
    fun closingTheTabInUseForDaysKeepsItsPageInHistory() {
        ready()
        browser.openUrl("https://other.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://other.test/")
        browser.openUrl("https://long-read.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://long-read.test/")
        now += 8 * DAY

        browser.closeTab(state().activeTab!!.id); idle()
        browser.runMaintenance(); idle()
        // It was in use until it closed; the tab focused next is in use from now.
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://long-read.test/" })
        unusedTabs = UnusedTabs.CLOSE
        browser.runMaintenance(); idle()
        assertEquals("https://other.test/", state().activeTab?.url)
        browser.newTab(); idle()
        browser.runMaintenance(); idle()
        assertEquals(2, state().openTabCount)
    }

    @Test
    fun theFocusedTabStaysAboveThePagesItNavigatedAwayFrom() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        now += DAY
        commit(1, 11, "https://b.test/", index = 1, length = 2)
        now += 1_000
        commit(1, 12, "https://c.test/", index = 2, length = 3)

        val items = recent()
        // Browsing in the focused tab is using it: its row leads, on the day of its last navigation.
        assertEquals(listOf("https://c.test/", "https://b.test/", "https://a.test/"), items.map { it.url })
        assertTrue(items.first() is RecentItem.OpenTab)
        assertEquals(now, items.first().time)
    }

    @Test
    fun aHiddenTabLoadingAPageIsNotInUse() {
        unusedTabs = UnusedTabs.CLOSE
        ready()
        browser.openUrl("https://hidden.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://hidden.test/")
        browser.openUrl("https://focused.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://focused.test/")
        now += 8 * DAY

        // A script navigates the hidden tab: that is not the user using it.
        commit(1, 11, "https://hidden.test/next", index = 1, length = 2)
        browser.runMaintenance(); idle()
        assertEquals(1, state().openTabCount)
        assertEquals("https://focused.test/", state().activeTab?.url)
    }

    @Test
    fun aPageLeftAfterDaysOfUseCountsItsRetentionFromThen() {
        ready()
        browser.openUrl("https://long-read.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://long-read.test/")
        now += 6 * DAY
        commit(1, 11, "https://next.test/", index = 1, length = 2)
        now += 2 * DAY

        browser.runMaintenance(); idle()
        // It was in use until the tab left it six days in: retention counts from then, not from loading.
        val visit = recent().filterIsInstance<RecentItem.Visit>().single()
        assertEquals("https://long-read.test/", visit.url)
        assertEquals(START + 6 * DAY - 1, visit.time)
    }

    @Test
    fun aPageLeftForANonWebDocumentCountsItsRetentionFromThen() {
        ready()
        browser.openUrl("https://long-read.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://long-read.test/")
        now += 6 * DAY
        commit(1, 11, "about:blank", index = 1, length = 2)
        now += 2 * DAY

        browser.runMaintenance(); idle()
        assertEquals(listOf("https://long-read.test/"), recent().filterIsInstance<RecentItem.Visit>().map { it.url })
    }

    @Test
    fun unusedTabsCloseOnlyWhenChosenAndNeverTheFocusedOne() {
        ready()
        browser.openUrl("https://old.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://old.test/")
        browser.openUrl("https://focused.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://focused.test/")
        now += 8 * DAY

        browser.runMaintenance(); idle()
        assertEquals(2, state().openTabCount)

        unusedTabs = UnusedTabs.CLOSE
        browser.runMaintenance(); idle()
        assertEquals(1, state().openTabCount)
        assertEquals("https://focused.test/", state().activeTab?.url)
        assertTrue(1L in engine.closed)
        // Its page was last used as long ago: it leaves history too.
        assertTrue(recent().none { it.url == "https://old.test/" })
        assertNull(state().notice)
    }

    @Test
    fun aTabInUseForDaysIsNotUnusedWhenTheUserSwitchesAway() {
        unusedTabs = UnusedTabs.CLOSE
        ready()
        browser.openUrl("https://long-read.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://long-read.test/")
        now += 8 * DAY
        browser.openUrl("https://next.test/", Placement.NEW_TAB); idle()

        browser.runMaintenance(); idle()
        assertEquals(2, state().openTabCount)
    }

    @Test
    fun backFromAnotherAppsLinkLeavesItsPageInHistoryOnly() {
        ready()
        browser.openUrl("https://mine.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://mine.test/")
        val mine = state().activeTab!!.id

        browser.openFromOtherApp("https://link.test/"); idle()
        commit(2, 20, "https://link.test/")
        val linked = state().activeTab!!
        assertTrue(linked.openedByOtherApp)

        browser.returnToOtherApp(linked.id); idle()
        assertEquals(mine, state().activeTab?.id)
        assertEquals(1, state().openTabCount)
        // No Undo: the user left for the other app.
        assertNull(state().notice)
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://link.test/" })
    }

    @Test
    fun anotherAppsLinkBecomesAnOrdinaryTabOnceTheUserSwitchesTabs() {
        ready()
        browser.openUrl("https://mine.test/", Placement.NEW_TAB); idle()
        val mine = state().activeTab!!.id
        browser.openFromOtherApp("https://link.test/"); idle()
        val linked = state().activeTab!!.id

        browser.selectTab(mine); idle()
        browser.selectTab(linked); idle()
        assertFalse(state().activeTab!!.openedByOtherApp)
        browser.returnToOtherApp(linked); idle()
        assertEquals(2, state().openTabCount)
    }

    @Test
    fun forgettingASiteMatchesHostsByLabelAndKeepsOpenTabs() {
        ready()
        browser.openUrl("https://example.com/1", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://example.com/1")
        commit(1, 11, "https://www.example.com/2")
        commit(1, 12, "https://badexample.com/3")
        commit(1, 13, "https://example.com/4")

        browser.forgetSite("example.com", includeSubdomains = true); idle()
        val urls = recent().map { it.url }
        assertTrue("https://badexample.com/3" in urls)
        assertTrue("https://www.example.com/2" !in urls && "https://example.com/1" !in urls)
        // The open tab still shows its page.
        assertEquals("https://example.com/4", state().activeTab?.url)
    }

    @Test
    fun aRestartRestoresOpenTabsAndRecreatesOnlyTheFocusedView() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        val active = state().activeTab!!.id

        // Process death: a new coordinator and engine over the same database.
        browser = newCoordinator()
        idle()
        assertEquals(active, state().activeTab?.id)
        assertEquals(2, state().openTabCount)
        ready()
        assertEquals(listOf(1L to "https://b.test/"), engine.created)
    }

    @Test
    fun aCrashedViewIsReplacedOnReloadAndItsLateEventsAreIgnored() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        engine.emit(EngineEvent.Crashed(engine.view(1), "test"))
        idle()
        assertTrue(state().activeTab!!.crashed)

        browser.reload(); idle()
        assertEquals(listOf(1L), engine.closed)
        assertEquals(2L to "https://a.test/", engine.created.last())
        commit(1, 11, "https://ghost.test/")
        assertEquals("https://a.test/", state().activeTab?.url)
    }

    @Test
    fun theHomeScreenShowsNoPageUnderIt() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.newTab(); idle()
        assertTrue(state().activeTab!!.isHome)
        assertEquals(NONE, engine.selected.last())

        // Back to the page: its view is selected again, not recreated.
        val pageTab = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == "https://a.test/" }.tabId
        browser.selectTab(pageTab); idle()
        assertEquals(1L, engine.selected.last())
        assertEquals(1, engine.created.size)
    }

    @Test
    fun windowsOpenedByAPageBecomeFocusedTabsAndClosedOpenersAreRefused() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val opener = state().activeTab!!.id

        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        val popup = state().activeTab!!
        assertNotEquals(opener, popup.id)
        assertTrue(!popup.isHome)
        assertEquals(PAGE_TAB, engine.selected.last())
        commit(PAGE_TAB, 50, "https://popup.test/")
        assertEquals("https://popup.test/", state().activeTab?.url)
        assertEquals(2, state().openTabCount)

        browser.closeTab(opener); idle()
        engine.openWindow(opener = 1, tab = PAGE_TAB + 1); idle()
        assertTrue(PAGE_TAB + 1 in engine.closed)
        assertEquals(1, state().openTabCount)
    }

    @Test
    fun profileIsPersistedOnlyOnceTheEngineRuns() {
        browser.persistProfile(); idle()
        assertEquals(0, engine.persisted)
        ready()
        browser.persistProfile(); idle()
        assertEquals(1, engine.persisted)
    }

    @Test
    fun hiddenRenderersBeyondTheLimitAreReleasedAndRecreatedOnSelection() {
        ready()
        val urls = (1..8).map { "https://t$it.test/" }
        urls.forEachIndexed { index, url ->
            now += 1_000
            browser.openUrl(url, if (index == 0) Placement.CURRENT_TAB else Placement.NEW_TAB); idle()
            commit((index + 1).toLong(), 10L + index, url)
        }
        // Six renderers stay: the two least recently focused tabs released theirs.
        assertEquals(listOf(1L, 2L), engine.closed)
        assertEquals(8, state().openTabCount)

        val first = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == urls.first() }.tabId
        browser.selectTab(first); idle()
        assertEquals(9L to urls.first(), engine.created.last())
        assertEquals(9L, engine.selected.last())
    }

    @Test
    fun memoryPressureKeepsOnlyTheFocusedRenderer() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        browser.releaseHiddenViews(); idle()
        assertEquals(listOf(1L), engine.closed)
        assertEquals("https://b.test/", state().activeTab?.url)
    }

    @Test
    fun newTabReusesAnUnusedBlankTab() {
        ready()
        browser.newTab(); idle()
        browser.newTab(); idle()
        assertEquals(1, state().openTabCount)
        assertTrue(state().activeTab!!.isHome)
        assertEquals(TabState.OPEN, runBlocking { database.browser().openTabs() }.single().state)
    }

    @Test
    fun aRefusedAddressReturnsTheTabToHomeWithANotice() {
        ready()
        engine.refusedUrls += "not an address"
        browser.openUrl("not an address", Placement.CURRENT_TAB); idle()
        idle() // the refusal event is queued from inside the work queue
        assertTrue(state().activeTab!!.isHome)
        assertEquals(Notice.AddressNotOpened, state().notice)
        assertEquals(NONE, engine.selected.last())
    }

    @Test
    fun aPageClosingItsOwnTabClosesItLikeAUserClose() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        val tabA = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == "https://a.test/" }.tabId
        val tabB = state().activeTab!!.id

        engine.emit(EngineEvent.TabClosed(engine.view(2))); idle()
        assertEquals(1, state().openTabCount)
        assertEquals(tabA, state().activeTab?.id)
        assertEquals(Notice.TabClosed(tabB, null), state().notice)
        // The page destroyed the view itself; the coordinator must not close it again.
        assertTrue(engine.closed.isEmpty())
        assertEquals(1L, engine.selected.last())
        // Its page stays in Recent as history, as after a user close.
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://b.test/" })
    }

    @Test
    fun goingBackToACachedDocumentDoesNotMarkTheTabAsLoading() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        assertTrue(state().activeTab!!.loading)
        commit(1, 11, "https://b.test/", index = 1, length = 2)
        assertTrue(state().activeTab!!.loading)

        // A bfcache return reports no load progress: the document is already loaded.
        commit(1, 10, "https://a.test/", index = 0, length = 2)
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun theKeyboardRequestFollowsImeEventsUntilHandled() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")

        engine.emit(EngineEvent.ImeShown(engine.view(1), multiline = false, kind = "password")); idle()
        assertEquals(KeyboardRequest.Show("password"), state().keyboard)
        browser.keyboardHandled(state().keyboard!!)
        assertNull(state().keyboard)
        engine.emit(EngineEvent.ImeHidden(engine.view(1))); idle()
        assertEquals(KeyboardRequest.Hide, state().keyboard)
    }

    @Test
    fun aKeyboardRequestArrivingWhileTheUiHandlesTheLastOneIsKept() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")

        // Focus moves between fields: hide, then show for the next one, before the UI is done.
        engine.emit(EngineEvent.ImeHidden(engine.view(1))); idle()
        val handling = state().keyboard!!
        engine.emit(EngineEvent.ImeShown(engine.view(1), multiline = false, kind = "password")); idle()
        browser.keyboardHandled(handling)
        assertEquals(KeyboardRequest.Show("password"), state().keyboard)
    }

    @Test
    fun openUrlInTabNavigatesThatTabNotTheActiveOne() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        val tabA = recent().filterIsInstance<RecentItem.OpenTab>().single { it.url == "https://a.test/" }.tabId
        assertNotEquals(tabA, state().activeTab!!.id)

        browser.openUrlInTab(tabA, "https://a-next.test/"); idle()
        assertEquals(tabA, state().activeTab?.id)
        assertEquals(1L to "https://a-next.test/", engine.navigated.single().let { it.first to it.third })
        assertEquals(1L, engine.selected.last())
    }

    private fun showControl(native: Long, control: Long, content: PageControlContent) {
        engine.emit(EngineEvent.ControlShown(engine.view(native), control, content))
        idle()
    }

    @Test
    fun aPageControlIsShownForTheFocusedTabOnlyAndAnswered() {
        ready()
        browser.openUrl("https://a.test/x", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/x")
        val tabA = state().activeTab!!.id
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")

        // A hidden tab's dialog waits until that tab is focused.
        showControl(1, 5, PageControlContent.Confirm("Leave?"))
        assertNull(state().pageControl)

        browser.selectTab(tabA); idle()
        val shown = state().pageControl!!
        assertEquals(5L, shown.id)
        assertEquals("a.test", shown.site)
        assertFalse(shown.offerBlocking)

        browser.answerPageControl(5, ControlResponse.Accept); idle()
        assertEquals(listOf(5L to ControlResponse.Accept), engine.responses)
        assertNull(state().pageControl)
    }

    @Test
    fun aWithdrawnControlDisappearsAndClosingTheTabDropsItsControls() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        showControl(1, 5, PageControlContent.Select(emptyList(), emptyList(), false))
        engine.emit(EngineEvent.ControlHidden(engine.view(1), 5)); idle()
        assertNull(state().pageControl)

        showControl(1, 6, PageControlContent.Alert("hi"))
        browser.closeTab(state().activeTab!!.id); idle()
        browser.answerPageControl(6, ControlResponse.Accept); idle()
        assertTrue(engine.responses.isEmpty())
    }

    @Test
    fun repeatedDialogsCanBeBlockedUntilThePageNavigates() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")

        showControl(1, 1, PageControlContent.Alert("one"))
        assertFalse(state().pageControl!!.offerBlocking)
        browser.answerPageControl(1, ControlResponse.Accept); idle()

        showControl(1, 2, PageControlContent.Alert("two"))
        assertTrue(state().pageControl!!.offerBlocking)
        browser.answerPageControl(2, ControlResponse.Dismiss, DialogBlocking.BLOCK_FOR_PAGE); idle()

        // Blocked: answered without being shown. Select pickers and link menus are not dialogs.
        showControl(1, 3, PageControlContent.Confirm("three"))
        assertNull(state().pageControl)
        assertEquals(3L to ControlResponse.Dismiss, engine.responses.last())
        showControl(1, 4, PageControlContent.Select(emptyList(), emptyList(), false))
        assertEquals(4L, state().pageControl?.id)
        browser.answerPageControl(4, ControlResponse.Dismiss); idle()
        showControl(1, 6, PageControlContent.LinkMenu("https://a.test/l", null))
        assertEquals(6L, state().pageControl?.id)
        browser.answerPageControl(6, ControlResponse.Dismiss); idle()

        // A new document may ask again.
        commit(1, 11, "https://a.test/next", index = 1, length = 2)
        showControl(1, 5, PageControlContent.Alert("five"))
        assertEquals(5L, state().pageControl?.id)
        assertFalse(state().pageControl!!.offerBlocking)
    }

    @Test
    fun blockingIsOnlyHonouredWhereItWasOffered() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")

        // Neither a first dialog nor a select picker offers blocking.
        showControl(1, 1, PageControlContent.Alert("one"))
        browser.answerPageControl(1, ControlResponse.Accept, DialogBlocking.BLOCK_FOR_PAGE); idle()
        showControl(1, 2, PageControlContent.Select(emptyList(), emptyList(), false))
        browser.answerPageControl(2, ControlResponse.Dismiss, DialogBlocking.BLOCK_FOR_PAGE); idle()

        showControl(1, 3, PageControlContent.Alert("three"))
        assertEquals(3L, state().pageControl?.id)
    }

    @Test
    fun aCrashDropsPendingControlsAndTheDialogBlock() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        showControl(1, 1, PageControlContent.Alert("one"))
        browser.answerPageControl(1, ControlResponse.Accept); idle()
        showControl(1, 2, PageControlContent.Alert("two"))
        browser.answerPageControl(2, ControlResponse.Dismiss, DialogBlocking.BLOCK_FOR_PAGE); idle()
        showControl(1, 3, PageControlContent.Alert("pending"))

        engine.emit(EngineEvent.Crashed(engine.view(1), "test")); idle()
        assertNull(state().pageControl)
        val answered = engine.responses.size
        browser.answerPageControl(3, ControlResponse.Accept); idle()
        assertEquals(answered, engine.responses.size)

        showControl(1, 4, PageControlContent.Alert("after"))
        assertEquals(4L, state().pageControl?.id)
    }

    @Test
    fun repeatedSignInsAreNotCountedAsDialogs() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        showControl(1, 1, PageControlContent.SignIn("https://a.test", proxy = false))
        browser.answerPageControl(1, ControlResponse.Credentials("ada", "wrong")); idle()

        // A rejected password asks again; it must not be offered for blocking or be suppressed.
        showControl(1, 2, PageControlContent.SignIn("https://a.test", proxy = false))
        assertEquals(2L, state().pageControl?.id)
        assertFalse(state().pageControl!!.offerBlocking)
        assertEquals(1L to ControlResponse.Credentials("ada", "wrong"), engine.responses.last())
    }

    @Test
    fun aHiddenTabWaitingForAnAnswerKeepsItsRenderer() {
        engine = FakeEngine()
        browser = BrowserCoordinator(database, engine, scope, clock = { now }, newId = { "id${ids++}" }, maxLiveViews = 1).also { it.start() }
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        showControl(1, 5, PageControlContent.Alert("wait"))

        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        assertTrue(1L !in engine.closed)
    }

    private companion object {
        const val NONE = FakeEngine.NONE
        const val PAGE_TAB = 1L shl 52
        const val START = 1_760_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
