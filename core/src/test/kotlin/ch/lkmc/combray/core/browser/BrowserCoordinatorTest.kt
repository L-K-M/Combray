package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.TabEntity
import ch.lkmc.combray.core.data.TabState
import ch.lkmc.combray.core.data.VisitEntity
import ch.lkmc.combray.core.data.VisitKind
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.LoadPhase
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun newCoordinator(navigationTimeoutMs: Long = LONG_TIMEOUT_MS): BrowserCoordinator {
        engine = FakeEngine()
        return BrowserCoordinator(
            database, engine, scope, clock = { now }, newId = { "id${ids++}" }, unusedTabs = { unusedTabs },
            navigationTimeoutMs = navigationTimeoutMs,
        ).also { it.start() }
    }

    private fun idle() = runBlocking { browser.awaitIdle() }

    private fun ready() {
        engine.emit(EngineEvent.EngineReady(720, 1280))
        idle()
    }

    private fun state() = browser.browserState.value

    /** What RecentSheet shows for [query]: the depth it subscribes to, filtered and capped. */
    private fun recent(query: String? = null) = runBlocking {
        browser.recent(HistoryDepth.forQuery(query)).first().matching(query)
    }

    /** Simulates Servo committing [url] as [document] in the view the coordinator created for it. */
    private fun commit(native: Long, document: Long, url: String, kind: HistoryKind = HistoryKind.DOCUMENT_COMMITTED, index: Int = 0, length: Int = 1) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), kind, document, url, index, length))
        idle()
    }

    private fun loaded(native: Long, document: Long) {
        engine.emit(EngineEvent.LoadStatus(engine.view(native), document, LoadPhase.COMPLETE, Attribution.CURRENT))
        idle()
    }

    private fun title(native: Long, document: Long, title: String?, attribution: Attribution = Attribution.CURRENT) {
        engine.emit(EngineEvent.TitleChanged(engine.view(native), document, title, attribution))
        idle()
    }

    /**
     * Heartbeats and echoes skip publish()'s Room reads on the serialized queue; an event that
     * can move published state still publishes. Counted as SELECTs, so the gate is pinned by
     * what it saves, not by a copy of its list.
     */
    @Test
    fun heartbeatsAndEchoesSkipThePublishReads() {
        val reads = AtomicInteger()
        database.close()
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java)
            .setQueryCallback({ sql, _ -> if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) reads.incrementAndGet() }, Runnable::run)
            .build()
        browser = newCoordinator()
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        idle() // the view's creation events are queued from inside the work queue
        reads.set(0)

        engine.emit(EngineEvent.Painted(engine.view(1), frames = 3))
        engine.emit(EngineEvent.TabSelected(engine.view(1)))
        engine.emit(EngineEvent.SurfaceUnbound)
        idle()
        assertEquals(0, reads.get())

        engine.emit(EngineEvent.ImeHidden(engine.view(1)))
        idle()
        assertTrue(reads.get() > 0)
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
    fun aViewCreatedBeforeForegroundArrivesStaysThrottled() {
        // The coordinator starts conservatively backgrounded until Android reports visibility.
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        ready()
        assertEquals(listOf(1L to "https://a.test/"), engine.created)
        assertEquals(1L, engine.selected.last())
        assertEquals(1L to true, engine.throttling.last())
        assertEquals("https://a.test/", state().activeTab!!.url)
    }

    @Test
    fun aForegroundSignalBeforeReadinessUnthrottlesTheCreatedView() {
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        assertTrue(engine.created.isEmpty())
        assertTrue(engine.throttling.isEmpty())
        ready()
        assertEquals(listOf(1L to false), engine.throttling)
        assertEquals(1L, engine.selected.last())
        assertEquals("https://a.test/", state().activeTab!!.url)
    }

    @Test
    fun foregroundTogglesOnlyTheShownRenderer() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        ready()
        val tabId = state().activeTab!!.id
        val native = engine.created.single().first
        assertEquals(native to true, engine.throttling.last())

        browser.setAppVisibility(AppVisibility.FOREGROUND)
        idle()
        assertEquals(native to false, engine.throttling.last())
        browser.setAppVisibility(AppVisibility.BACKGROUND)
        idle()
        assertEquals(native to true, engine.throttling.last())

        // Duplicate signals emit no extra commands.
        val size = engine.throttling.size
        browser.setAppVisibility(AppVisibility.BACKGROUND)
        browser.setAppVisibility(AppVisibility.BACKGROUND)
        idle()
        assertEquals(size, engine.throttling.size)
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        idle()
        assertEquals(size + 1, engine.throttling.size)

        assertTrue(engine.closed.isEmpty())
        assertEquals(tabId, state().activeTab!!.id)
        assertEquals("https://a.test/", state().activeTab!!.url)
        assertEquals(tabId, runBlocking { database.browser().openTabs() }.single().id)
    }

    @Test
    fun backgroundedSwitchingNeverUnthrottlesUntilResume() {
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        ready()
        val aTab = state().activeTab!!.id
        val aNative = engine.created.single().first
        browser.openUrl("https://b.test/", Placement.NEW_TAB)
        idle()
        val bNative = engine.created.last().first

        browser.setAppVisibility(AppVisibility.BACKGROUND)
        idle()
        engine.throttling.clear()

        // Switch to the first tab and open a third while backgrounded.
        browser.selectTab(aTab)
        idle()
        browser.openUrl("https://c.test/", Placement.NEW_TAB)
        idle()
        val cNative = engine.created.last().first

        // Everything emitted while backgrounded is a throttle; nothing is released.
        assertTrue(engine.throttling.isNotEmpty())
        assertTrue(engine.throttling.all { it.second })
        assertTrue(engine.closed.isEmpty())

        browser.setAppVisibility(AppVisibility.FOREGROUND)
        idle()
        // Resuming unthrottles only the shown renderer; hidden views stay throttled.
        assertEquals(listOf(cNative to false), engine.throttling.filter { !it.second })
        assertTrue(engine.throttling.none { (it.first == aNative || it.first == bNative) && !it.second })
        assertEquals("https://c.test/", state().activeTab!!.url)
    }

    @Test
    fun visibilityChangesOnTheInternalHomeEmitNoEngineCommands() {
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        ready()
        browser.closeTab(state().activeTab!!.id)
        idle()
        assertNull(state().activeTab?.url)

        // The retired view must not receive throttle commands for visibility changes.
        engine.throttling.clear()
        val created = engine.created.toList()
        val closed = engine.closed.toList()
        val selected = engine.selected.toList()

        browser.setAppVisibility(AppVisibility.BACKGROUND)
        idle()
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        idle()
        browser.setAppVisibility(AppVisibility.BACKGROUND)
        idle()

        assertTrue(engine.throttling.isEmpty())
        assertEquals(created, engine.created)
        assertEquals(closed, engine.closed)
        assertEquals(selected, engine.selected)
    }

    @Test
    fun aViewRestoredWhileBackgroundedStaysThrottledUntilForeground() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        val tabId = state().activeTab!!.id
        // Nothing reaches the engine before readiness, whatever the visibility.
        assertTrue(engine.created.isEmpty())
        assertTrue(engine.throttling.isEmpty())
        assertTrue(engine.selected.isEmpty())

        ready()
        assertEquals(listOf(1L to "https://a.test/"), engine.created)
        assertEquals(1L, engine.selected.last())
        assertEquals(1L to true, engine.throttling.last())
        assertEquals(tabId, state().activeTab!!.id)
        assertEquals("https://a.test/", state().activeTab!!.url)

        browser.setAppVisibility(AppVisibility.FOREGROUND)
        idle()
        assertEquals(listOf(1L to true, 1L to false), engine.throttling)
        assertEquals(tabId, state().activeTab!!.id)
        assertEquals("https://a.test/", state().activeTab!!.url)
    }

    @Test
    fun aPageClosingItselfInTheBackgroundLeavesItsSuccessorThrottled() {
        browser.setAppVisibility(AppVisibility.FOREGROUND)
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        browser.setAppVisibility(AppVisibility.BACKGROUND); idle()

        // The page closes its own window: its successor is shown, but nobody sees it yet (#88).
        engine.emit(EngineEvent.TabClosed(engine.view(2))); idle()
        assertEquals(1L, engine.selected.last())
        assertEquals(1L to true, engine.throttling.last { it.first == 1L })

        browser.setAppVisibility(AppVisibility.FOREGROUND); idle()
        assertEquals(1L to false, engine.throttling.last())
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
    fun aQuickSwitchFocusesTheTabUsedBeforeTheFocusedOneAndBack() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        now += 1_000
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        val tabB = state().activeTab!!.id

        browser.focusPreviousTab(); idle()
        val tabA = state().activeTab!!.id
        assertNotEquals(tabB, tabA)
        assertEquals(1L, engine.selected.last())

        // Back on the older tab, the same shortcut returns to the one just left.
        browser.focusPreviousTab(); idle()
        assertEquals(tabB, state().activeTab?.id)
        assertEquals(2L, engine.selected.last())
    }

    /** Opens a.test, b.test and c.test a second apart; c.test ends focused. Returns their tab ids. */
    private fun openThreeTabs(): List<String> = listOf("a", "b", "c").mapIndexed { index, name ->
        if (index > 0) now += 1_000
        browser.openUrl("https://$name.test/", Placement.NEW_TAB); idle()
        commit(index + 1L, (index + 1L) * 10, "https://$name.test/")
        state().activeTab!!.id
    }

    @Test
    fun aQuickSwitchFollowsFocusOrderNotCreationOrder() {
        ready()
        val (a, b, _) = openThreeTabs()
        now += 1_000
        browser.selectTab(b); idle()
        now += 1_000
        browser.selectTab(a); idle()

        browser.focusPreviousTab(); idle()
        assertEquals(b, state().activeTab?.id)
    }

    @Test
    fun aQuickSwitchWithOneTabIsANoOp() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        val only = state().activeTab!!.id

        browser.focusPreviousTab(); idle()
        assertEquals(only, state().activeTab?.id)
    }

    @Test
    fun aHiddenTabNavigatingDoesNotBecomeThePreviousTab() {
        ready()
        val (_, b, _) = openThreeTabs()
        // a.test loads another page while hidden: that is not using it (#71).
        now += 1_000
        commit(1, 11, "https://a.test/next")

        browser.focusPreviousTab(); idle()
        assertEquals(b, state().activeTab?.id)
    }

    @Test
    fun aRunOfBarSwipesWalksBackThroughRecentOrder() {
        ready()
        val (a, b, c) = openThreeTabs()

        // Each swipe focuses a tab and so reorders Recent; the run keeps walking the old order.
        browser.stepTab(TabStep.OLDER); idle()
        assertEquals(b, state().activeTab?.id)
        browser.stepTab(TabStep.OLDER); idle()
        assertEquals(a, state().activeTab?.id)
        // Nothing older than the oldest tab.
        browser.stepTab(TabStep.OLDER); idle()
        assertEquals(a, state().activeTab?.id)
        browser.stepTab(TabStep.NEWER); idle()
        assertEquals(b, state().activeTab?.id)
        browser.stepTab(TabStep.NEWER); idle()
        assertEquals(c, state().activeTab?.id)
        browser.stepTab(TabStep.NEWER); idle()
        assertEquals(c, state().activeTab?.id)
    }

    @Test
    fun aPauseBetweenSwipesStartsFromTheNewOrder() {
        ready()
        val (_, b, c) = openThreeTabs()
        browser.stepTab(TabStep.OLDER); idle()
        assertEquals(b, state().activeTab?.id)

        // After a pause b.test is the newest tab, so the next swipe goes back to c.test.
        now += 4_000 // longer than a run's pause (SWIPE_RUN_MS)
        browser.stepTab(TabStep.OLDER); idle()
        assertEquals(c, state().activeTab?.id)
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
        assertEquals(Notice.TabClosed(tabC, null, wasActive = true), state().notice)
        // Its page stays in Recent as history.
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://c.test/" })

        browser.reopenTab(tabC, ReopenFocus.FOCUS); idle()
        assertEquals(tabC, state().activeTab?.id)
        assertEquals(4L to "https://c.test/", engine.created.last())
    }

    @Test
    fun undoAfterClosingABackgroundTabKeepsTheCurrentOne() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        val tabA = state().activeTab!!.id
        now += 1_000
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        val tabB = state().activeTab!!.id
        now += 1_000
        browser.openUrl("https://c.test/", Placement.NEW_TAB); idle()
        commit(3, 30, "https://c.test/")
        val tabC = state().activeTab!!.id
        now += 1_000
        browser.selectTab(tabA); idle()

        // Recent's close on a row that is not the current tab.
        browser.closeTab(tabB); idle()
        assertEquals(tabA, state().activeTab?.id)
        assertEquals(Notice.TabClosed(tabB, null, wasActive = false), state().notice)
        now += 1_000
        browser.reopenTab(tabB, ReopenFocus.KEEP_CURRENT); idle()

        assertEquals(tabA, state().activeTab?.id)
        assertEquals(1L, engine.selected.last())
        // Back in its old place among the open tabs, loaded only once it is selected.
        assertEquals(listOf(tabA, tabC, tabB), recent().filterIsInstance<RecentItem.OpenTab>().map { it.tabId })
        assertEquals(3, engine.created.size)
        browser.selectTab(tabB); idle()
        assertEquals(4L to "https://b.test/", engine.created.last())
    }

    @Test
    fun aBackgroundTabKeepsFocusWhereItIsAndLoadsWhenSelected() {
        ready()
        browser.openUrl("https://older.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://older.test/")
        now += 1_000
        browser.openUrl("https://reading.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://reading.test/")
        val reading = state().activeTab!!.id
        now += 1_000

        browser.openUrl("https://later.test/", Placement.BACKGROUND_TAB); idle()
        assertEquals(reading, state().activeTab?.id)
        assertEquals(3, state().openTabCount)
        // No renderer until it is selected, and the page being read stays shown.
        assertEquals(2, engine.created.size)
        assertEquals(2L, engine.selected.last())
        val later = (state().notice as Notice.OpenedInBackground).tabId
        // Right after the focused tab in Recent, before the tabs used earlier.
        val order = recent().filterIsInstance<RecentItem.OpenTab>().map { it.url }
        assertEquals(listOf("https://reading.test/", "https://later.test/", "https://older.test/"), order)

        browser.selectTab(later); idle()
        assertEquals(later, state().activeTab?.id)
        assertEquals(3L to "https://later.test/", engine.created.last())
        assertEquals(3L, engine.selected.last())
    }

    @Test
    fun aBackgroundTabTakesFocusWhenNoTabHasIt() {
        ready()
        browser.openUrl("https://later.test/", Placement.BACKGROUND_TAB); idle()
        assertEquals("https://later.test/", state().activeTab?.url)
        assertEquals(listOf(1L to "https://later.test/"), engine.created)
        assertNull(state().notice)
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
    fun aViewlessTabsPreviousPageStaysInRecentWhileItNavigates() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        val tabA = recent().filterIsInstance<RecentItem.OpenTab>().single().tabId

        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        browser.releaseHiddenViews(); idle()
        assertTrue(1L in engine.closed)
        // Releasing the renderer alone must not detach the page: the tab can still be
        // restored to it, so it stays suppressed as the tab's current page.
        assertTrue(recent().none { it is RecentItem.Visit && it.url == "https://a.test/" })

        // Navigating the renderer-less tab must not hide its old page from Recent: the tab is
        // not showing it while the new address waits for a renderer.
        browser.openUrlInTab(tabA, "https://a2.test/"); idle()
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://a.test/" })
        // The requested address stays the tab's recovery URL until a renderer commits it.
        assertEquals("https://a2.test/", runBlocking { database.browser().tab(tabA) }?.url)

        // Once the new page commits, the tab owns it: suppressed, while the old page stays.
        commit(3, 30, "https://a2.test/")
        assertTrue(recent().none { it is RecentItem.Visit && it.url == "https://a2.test/" })
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://a.test/" })
    }

    @Test
    fun aTabThatReturnsHomeKeepsItsPreviousPageInRecent() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        commit(1, 10, "https://a.test/")
        val tabA = recent().filterIsInstance<RecentItem.OpenTab>().single().tabId

        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        browser.releaseHiddenViews(); idle()

        // An address the engine cannot open returns the tab to the internal home.
        engine.refusedUrls += "https://bad.test/"
        browser.openUrlInTab(tabA, "https://bad.test/"); idle()
        idle() // the refusal event is queued from inside the work queue
        assertNull(state().activeTab?.url)
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://a.test/" })
        // The refused address never committed, so it must not appear as history either.
        assertTrue(recent().none { it is RecentItem.Visit && it.url == "https://bad.test/" })
    }

    @Test
    fun aRestoredPageTheEngineRefusesStaysInRecent() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")

        // After a restart the tab is restored without a renderer; recreating it is refused.
        browser = newCoordinator()
        engine.refusedUrls += "https://a.test/"
        ready()
        idle() // the refusal event is queued from inside the work queue

        assertNull(state().activeTab?.url)
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://a.test/" })
    }

    @Test
    fun aFailedReloadKeepsTheEarlierVisitAndItsTitle() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        title(1, 10, "Article")

        // Reload offline: Servo reports a null, then an empty title for every new document
        // before its own, here the error page's.
        commit(1, 11, "https://a.test/")
        title(1, 11, null, Attribution.HELD_UNTIL_COMMIT)
        title(1, 11, "", Attribution.HELD_UNTIL_COMMIT)
        title(1, 11, "Error loading page", Attribution.HELD_UNTIL_COMMIT)
        assertTrue(state().activeTab!!.loadFailed)
        assertEquals("Error loading page", state().activeTab?.title)

        browser.closeTab(state().activeTab!!.id); idle()
        val visit = recent().filterIsInstance<RecentItem.Visit>().single()
        assertEquals("https://a.test/" to "Article", visit.url to visit.title)
    }

    @Test
    fun aFailedReloadKeepsAnUntitledPageInHistory() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        title(1, 10, null)

        commit(1, 11, "https://a.test/")
        title(1, 11, "Error loading page", Attribution.HELD_UNTIL_COMMIT)
        assertTrue(state().activeTab!!.loadFailed)

        browser.closeTab(state().activeTab!!.id); idle()
        val visit = recent().filterIsInstance<RecentItem.Visit>().single()
        assertEquals("https://a.test/" to null, visit.url to visit.title)
    }

    @Test
    fun aReloadKeepsThePageTitleUntilTheNewDocumentReportsItsOwn() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        title(1, 10, "Article")

        commit(1, 11, "https://a.test/")
        title(1, 11, null, Attribution.HELD_UNTIL_COMMIT)
        title(1, 11, "", Attribution.HELD_UNTIL_COMMIT)
        assertEquals("Article", state().activeTab?.title)
        title(1, 11, "Article, updated")
        assertEquals("Article, updated", state().activeTab?.title)
    }

    @Test
    fun aFailedReloadOfAFailedPageStaysOutOfHistory() {
        ready()
        browser.openUrl("https://down.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://down.test/")
        title(1, 10, null, Attribution.HELD_UNTIL_COMMIT)
        title(1, 10, "Error loading page", Attribution.HELD_UNTIL_COMMIT)
        commit(1, 11, "https://down.test/")
        title(1, 11, null, Attribution.HELD_UNTIL_COMMIT)
        title(1, 11, "Error loading page", Attribution.HELD_UNTIL_COMMIT)

        commit(1, 12, "https://up.test/")
        assertTrue(recent().none { it is RecentItem.Visit })
    }

    @Test
    fun aReloadThatLoadsAFailedPageRecordsItEvenWithoutATitle() {
        ready()
        browser.openUrl("https://down.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://down.test/")
        title(1, 10, "Error loading page")

        // Back online; the page has no <title>, so the error page's title must not stay.
        commit(1, 11, "https://down.test/")
        title(1, 11, null, Attribution.HELD_UNTIL_COMMIT)
        title(1, 11, "", Attribution.HELD_UNTIL_COMMIT)
        assertFalse(state().activeTab!!.loadFailed)
        assertNull(state().activeTab?.title)

        commit(1, 12, "https://up.test/")
        val visit = recent().filterIsInstance<RecentItem.Visit>().single()
        assertEquals("https://down.test/" to null, visit.url to visit.title)
    }

    @Test
    fun aRestoredTabStillKnowsItsLastLoadFailed() {
        ready()
        browser.openUrl("https://down.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://down.test/")
        title(1, 10, "Error loading page")
        assertTrue(state().activeTab!!.loadFailed)

        // Process death: no renderer yet, so the stored visit says the page was an error page.
        browser = newCoordinator()
        idle()
        assertEquals("https://down.test/", state().activeTab?.url)
        assertTrue(state().activeTab!!.loadFailed)
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
    fun searchingRecentLooksAtEveryRetainedVisitNotOnlyTheNewest() {
        idle() // start-up maintenance must not remove the tab before its visits exist
        val dao = database.browser()
        runBlocking {
            dao.insertTab(TabEntity("closed", TabState.CLOSED, null, null, null, START, START, START))
            (0 until 600).forEach { index ->
                val (host, title) = if (index == 0) "recipe.test" to "Lentil soup" else "other.test" to "Page $index"
                dao.insertVisit(VisitEntity("visit$index", "closed", "https://$host/$index", host, title, null, VisitKind.DOCUMENT, START + index, START + index, false))
            }
        }

        assertEquals(500, recent().size)
        assertTrue(recent().none { it.title == "Lentil soup" })
        // The oldest visit is still within retention, so a search finds it.
        assertEquals(listOf("Lentil soup"), recent("lentil").map { it.title })
        assertEquals(500, recent("page").size)
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
    fun aForgottenSitesCurrentPageDoesNotReturnAfterNavigation() {
        ready()
        browser.openUrl("https://example.com/4", Placement.NEW_TAB)
        idle()
        commit(1, 10, "https://example.com/4")
        browser.forgetSite("example.com", includeSubdomains = false)
        idle()
        assertEquals("https://example.com/4", state().activeTab?.url)

        browser.openUrl("https://other.test/", Placement.CURRENT_TAB)
        idle()
        commit(1, 11, "https://other.test/")

        val items = recent()
        assertEquals("https://other.test/", items.filterIsInstance<RecentItem.OpenTab>().single().url)
        assertFalse(items.map { it.url }.contains("https://example.com/4"))
    }

    @Test
    fun aForgottenSitesCurrentPageDoesNotReturnWhenItsTabCloses() {
        ready()
        browser.openUrl("https://sub.example.com/4", Placement.NEW_TAB)
        idle()
        commit(1, 10, "https://sub.example.com/4")
        val tabId = state().activeTab!!.id
        browser.forgetSite("example.com", includeSubdomains = true)
        idle()

        browser.closeTab(tabId)
        idle()

        assertFalse(recent().map { it.url }.contains("https://sub.example.com/4"))
    }

    @Test
    fun aForgottenPageKeepsItsTitleWhileItStaysOpen() {
        ready()
        browser.openUrl("https://example.com/4", Placement.NEW_TAB)
        idle()
        commit(1, 10, "https://example.com/4")
        title(1, 10, "Four")
        browser.forgetSite("example.com", includeSubdomains = false)
        idle()

        commit(1, 10, "https://example.com/4", HistoryKind.LIST_UPDATED)
        assertEquals("Four", state().activeTab?.title)

        commit(1, 10, "https://example.com/4#notes", HistoryKind.SAME_DOCUMENT)
        assertEquals("Four", state().activeTab?.title)

        // Moving within the forgotten page recorded nothing that leaving it could bring back.
        browser.openUrl("https://other.test/", Placement.CURRENT_TAB); idle()
        commit(1, 11, "https://other.test/")
        assertTrue(recent().none { it is RecentItem.Visit && it.url.orEmpty().startsWith("https://example.com/") })
    }

    /** The triage's condition for #41: a new renderer loading the forgotten page records nothing. */
    @Test
    fun aForgottenPageStaysForgottenWhenItsRendererIsRecreated() {
        ready()
        browser.openUrl("https://example.com/4", Placement.NEW_TAB); idle()
        commit(1, 10, "https://example.com/4")
        val forgottenTab = state().activeTab!!.id
        browser.openUrl("https://b.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://b.test/")
        browser.forgetSite("example.com", includeSubdomains = false); idle()

        // Android trims memory: the hidden tab loses its renderer, and returning recreates it.
        browser.releaseHiddenViews(); idle()
        assertTrue(1L in engine.closed)
        browser.selectTab(forgottenTab); idle()
        val recreated = engine.created.last().first
        commit(recreated, 30, "https://example.com/4")
        // Reloading it is loading it again too.
        commit(recreated, 31, "https://example.com/4")

        // Leaving it finds no visit to put back in Recent.
        browser.openUrl("https://c.test/", Placement.CURRENT_TAB); idle()
        commit(recreated, 32, "https://c.test/")
        assertTrue(recent().none { it.url == "https://example.com/4" })
        assertTrue(recent().any { it is RecentItem.OpenTab && it.url == "https://c.test/" })
    }

    @Test
    fun aRestartDoesNotBringAForgottenPageBack() {
        ready()
        browser.openUrl("https://example.com/4", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://example.com/4")
        title(1, 10, "Four")
        browser.forgetSite("example.com", includeSubdomains = false); idle()

        // Process death: the restored tab loads its page in a new renderer.
        browser = newCoordinator()
        ready()
        commit(engine.created.single().first, 10, "https://example.com/4")
        assertEquals("Four", state().activeTab?.title)

        // A link to another page of the site is a new page: that one is recorded.
        commit(engine.created.single().first, 11, "https://example.com/5")
        browser.openUrl("https://c.test/", Placement.CURRENT_TAB); idle()
        commit(engine.created.single().first, 12, "https://c.test/")
        val urls = recent().map { it.url }
        assertFalse("https://example.com/4" in urls)
        assertTrue("https://example.com/5" in urls)
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
    fun theSessionIsRestoringUntilStartPublishesTheStoredTabs() {
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        val active = state().activeTab!!.id

        // Process death: the new coordinator's restore (maintenance, then publish) waits at the gate.
        val gate = CountDownLatch(1)
        engine = FakeEngine()
        browser = BrowserCoordinator(database, engine, scope, clock = { now }, newId = { "id${ids++}" }, unusedTabs = {
            gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            UnusedTabs.KEEP
        })
        assertEquals(SessionState.RESTORING, state().session)
        browser.start()
        assertEquals(SessionState.RESTORING, state().session)
        assertNull(state().activeTab)

        gate.countDown()
        idle()
        assertEquals(SessionState.RESTORED, state().session)
        assertEquals(active, state().activeTab?.id)
    }

    @Test
    fun aFailedRestoreStillEndsRestoring() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB)
        idle()
        browser = BrowserCoordinator(database, FakeEngine(), scope, unusedTabs = { error("settings unreadable") })
        browser.start()
        idle()

        // The screen must not stay blank: the state shows what there is, with the problem.
        assertEquals(SessionState.RESTORED, state().session)
        assertEquals("https://a.test/", state().activeTab?.url)
        // The user did nothing yet: the notice is about the tabs, not "the last action".
        assertEquals(Notice.Problem("Your open tabs could not be fully restored."), state().notice)
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
    fun backOnAPageOpenedWindowClosesItAndReturnsToItsOpener() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val opener = state().activeTab!!.id
        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        commit(PAGE_TAB, 50, "https://popup.test/")
        val popup = state().activeTab!!
        assertTrue(popup.returnsToOpener)

        browser.returnToOpener(popup.id); idle()
        assertEquals(opener, state().activeTab?.id)
        assertFalse(state().activeTab!!.returnsToOpener)
        assertEquals(1, state().openTabCount)
        assertEquals(1L, engine.selected.last())
        // No Undo: Back is not a close the user might regret; the page stays in history.
        assertNull(state().notice)
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://popup.test/" })
    }

    @Test
    fun aLinkOpenedInANewTabFromThePageReturnsToItsOpenerEvenAfterNavigating() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val opener = state().activeTab!!.id

        browser.openUrlFromTab("https://b.test/", opener); idle()
        commit(2, 20, "https://b.test/")
        assertTrue(state().activeTab!!.returnsToOpener)
        // Its own history goes first (the UI checks canGoBack); the way back to the opener stays.
        commit(2, 21, "https://b.test/next", index = 1, length = 2)
        assertTrue(state().activeTab!!.canGoBack)
        assertTrue(state().activeTab!!.returnsToOpener)

        browser.returnToOpener(state().activeTab!!.id); idle()
        assertEquals(opener, state().activeTab?.id)
        assertEquals(1, state().openTabCount)
    }

    @Test
    fun lookingAtAnEarlierTabOfAChainKeepsTheWholeWayBack() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val first = state().activeTab!!.id
        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        commit(PAGE_TAB, 50, "https://b.test/")
        val second = state().activeTab!!.id
        engine.openWindow(opener = PAGE_TAB, tab = PAGE_TAB + 1); idle()
        commit(PAGE_TAB + 1, 60, "https://c.test/")
        val third = state().activeTab!!.id

        // A, which opened B, which opened C: looking at A is inside that chain.
        browser.selectTab(first); idle()
        browser.selectTab(third); idle()
        assertTrue(state().activeTab!!.returnsToOpener)
        browser.returnToOpener(third); idle()
        assertEquals(second, state().activeTab?.id)
        assertTrue(state().activeTab!!.returnsToOpener)
    }

    @Test
    fun focusingAThirdTabEndsTheWayBackToTheOpener() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val opener = state().activeTab!!.id
        browser.openUrl("https://other.test/", Placement.NEW_TAB); idle()
        val other = state().activeTab!!.id
        browser.selectTab(opener); idle()
        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        val popup = state().activeTab!!.id

        // Looking at the opener and coming back keeps the pair.
        browser.selectTab(opener); idle()
        browser.selectTab(popup); idle()
        assertTrue(state().activeTab!!.returnsToOpener)

        browser.selectTab(other); idle()
        browser.selectTab(popup); idle()
        assertFalse(state().activeTab!!.returnsToOpener)
        browser.returnToOpener(popup); idle()
        assertEquals(popup, state().activeTab?.id)
        assertEquals(3, state().openTabCount)
    }

    @Test
    fun aPageOpenedWindowWhoseOpenerClosedStaysOpenOnBack() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val opener = state().activeTab!!.id
        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        val popup = state().activeTab!!.id

        browser.closeTab(opener); idle()
        assertFalse(state().activeTab!!.returnsToOpener)
        browser.returnToOpener(popup); idle()
        assertEquals(popup, state().activeTab?.id)
        assertEquals(1, state().openTabCount)
    }

    @Test
    fun aChainOfPageOpenedWindowsUnwindsOneBackAtATime() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        val first = state().activeTab!!.id
        engine.openWindow(opener = 1, tab = PAGE_TAB); idle()
        commit(PAGE_TAB, 50, "https://b.test/")
        val second = state().activeTab!!.id
        engine.openWindow(opener = PAGE_TAB, tab = PAGE_TAB + 1); idle()
        commit(PAGE_TAB + 1, 60, "https://c.test/")

        browser.returnToOpener(state().activeTab!!.id); idle()
        assertEquals(second, state().activeTab?.id)
        assertTrue(state().activeTab!!.returnsToOpener)
        browser.returnToOpener(second); idle()
        assertEquals(first, state().activeTab?.id)
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
        assertEquals(Notice.TabClosed(tabB, null, wasActive = true), state().notice)
        // The page destroyed the view itself; the coordinator must not close it again.
        assertTrue(engine.closed.isEmpty())
        assertEquals(1L, engine.selected.last())
        // Its page stays in Recent as history, as after a user close.
        assertTrue(recent().any { it is RecentItem.Visit && it.url == "https://b.test/" })
    }

    @Test
    fun anAddressShowsLoadingFromItsRequestNotOnlyFromItsCommit() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        assertTrue("a new tab's first load", state().activeTab!!.loading)
        commit(1, 10, "https://a.test/")
        loaded(1, 10)
        assertFalse(state().activeTab!!.loading)

        browser.openUrl("https://b.test/", Placement.CURRENT_TAB); idle()
        assertTrue("a typed address on a loaded page", state().activeTab!!.loading)
        commit(1, 11, "https://b.test/", index = 1, length = 2)
        loaded(1, 11)
        assertFalse(state().activeTab!!.loading)

        browser.reload(); idle()
        assertTrue("a reload", state().activeTab!!.loading)
        commit(1, 12, "https://b.test/", index = 1, length = 2)
        loaded(1, 12)
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aRequestThatNeverCommitsStopsShowingLoading() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        browser = newCoordinator(navigationTimeoutMs = SHORT_TIMEOUT_MS)
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        loaded(1, 10)

        // A 204 answer, for one, leaves the page as it was and commits nothing.
        browser.openUrl("https://b.test/", Placement.CURRENT_TAB); idle()
        assertTrue(state().activeTab!!.loading)
        Thread.sleep(SHORT_TIMEOUT_MS * 3)
        idle()
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aRefusedAddressStopsShowingLoading() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        loaded(1, 10)

        browser.openUrl("https://b.test/", Placement.CURRENT_TAB); idle()
        val request = engine.navigated.last().second
        engine.emit(EngineEvent.EngineError("not a URL Servo loads", 1, request))
        idle()
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aLateRefusalOfAnEarlierAddressKeepsTheNewerOneLoading() {
        ready()
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        commit(1, 10, "https://a.test/")
        loaded(1, 10)

        browser.openUrl("https://b.test/", Placement.CURRENT_TAB); idle()
        val earlier = engine.navigated.last().second
        browser.openUrl("https://c.test/", Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.EngineError("not a URL Servo loads", 1, earlier))
        idle()
        assertTrue(state().activeTab!!.loading)
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
    fun aDialogFromAPageWithoutAHostNamesNoSite() {
        ready()
        browser.openUrl("https://a.test/", Placement.NEW_TAB); idle()
        // The page moved itself to a data: address that mentions another site.
        commit(1, 10, "data:text/html,<!--://accounts.example.com/-->")

        showControl(1, 5, PageControlContent.Alert("Sign in again"))
        assertNull(state().pageControl!!.site)
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

    @Test
    fun engineLevelErrorBeforeReadyFailsInsteadOfStartingForever() {
        assertEquals(EngineStatus.STARTING, state().engine)

        engine.emit(EngineEvent.EngineError("EGL init failed", tab = null, request = null))
        idle()

        assertEquals(EngineStatus.FAILED, state().engine)
    }

    @Test
    fun engineLevelErrorCarryingARequestStillFailsStartup() {
        assertEquals(EngineStatus.STARTING, state().engine)

        // The contract is "no tab means engine-level"; a request id must not soften it.
        engine.emit(EngineEvent.EngineError("bind failed during load", tab = null, request = 5))
        idle()

        assertEquals(EngineStatus.FAILED, state().engine)
    }

    @Test
    fun engineLevelErrorAfterReadyLeavesTheEngineRunning() {
        ready()

        engine.emit(EngineEvent.EngineError("transient", tab = null, request = null))
        idle()

        assertEquals(EngineStatus.READY, state().engine)
    }

    @Test
    fun anErrorForAnUnknownViewDoesNotFailTheEngine() {
        ready()

        engine.emit(EngineEvent.EngineError("stale view", tab = 99, request = null))
        idle()

        assertEquals(EngineStatus.READY, state().engine)
    }

    private companion object {
        const val NONE = FakeEngine.NONE
        const val PAGE_TAB = 1L shl 52
        const val START = 1_760_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
        const val GATE_TIMEOUT_SECONDS = 10L
        const val SHORT_TIMEOUT_MS = 1_000L
        /** Longer than any test: a request does not time out unless the test means it to. */
        const val LONG_TIMEOUT_MS = 10L * 60 * 1000
    }
}
