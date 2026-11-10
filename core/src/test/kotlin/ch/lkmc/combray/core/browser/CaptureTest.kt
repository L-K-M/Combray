package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.engine.CaptureStatus
import ch.lkmc.combray.engine.CapturedContent
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.LoadPhase
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.ViewRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Capture attribution: text only ever reaches the sink for the document it came from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private val engine = FakeEngine()
    private val sink = RecordingSink()
    private lateinit var browser: BrowserCoordinator
    private var ids = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        browser = BrowserCoordinator(
            database, engine, scope,
            clock = { 1_760_000_000_000L },
            newId = { "id${ids++}" },
            capture = sink,
            captureTiming = CaptureTiming(settleMs = 50, fallbackMs = 400, debounceMs = 50),
        )
        browser.start()
        engine.emit(EngineEvent.EngineReady(720, 1280, FakeEngine.USER_AGENT))
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun idle() = runBlocking { browser.awaitIdle() }

    private fun settle() {
        Thread.sleep(200)
        idle()
    }

    private fun load(native: Long, document: Long, url: String) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), HistoryKind.DOCUMENT_COMMITTED, document, url, 0, 1))
        engine.emit(EngineEvent.LoadStatus(engine.view(native), document, LoadPhase.COMPLETE, Attribution.CURRENT))
        idle()
    }

    /** The pages Recent lists as history (open tabs excluded). */
    private fun history() = runBlocking { browser.recent().first() }.filterIsInstance<RecentItem.Visit>().map { it.url }

    private fun result(native: Long, document: Long, capture: Long, text: String, status: CaptureStatus = CaptureStatus.CAPTURED) {
        val content = if (status == CaptureStatus.CAPTURED) CapturedContent("ignored-page-url", "Title", text, text.length, "hash-$text", false) else null
        engine.emit(EngineEvent.CaptureResult(capture, engine.view(native), document, status, content, null))
        idle()
    }

    @Test
    fun aLoadedDocumentIsCapturedAfterItSettlesAndAttributedToItsCommittedAddress() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        val (document, capture) = engine.captures.single()
        assertEquals(10L, document)

        result(1, 10, capture, "A text")
        val page = sink.pages.single()
        // The engine's committed address, not what the page reported.
        assertEquals("https://a.test/", page.url)
        assertEquals("A text", page.text)
    }

    @Test
    fun aForgottenPageLoadedAgainIsNotCapturedAgain() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        assertEquals(1, engine.captures.size)

        browser.forgetSite("a.test", includeSubdomains = false); idle()
        // A reload of the page that is still open.
        load(1, 11, "https://a.test/")
        settle()
        assertEquals(1, engine.captures.size)
    }

    /** A request that never commits (a refusal, a 204, a download) leaves the forgotten page shown. */
    @Test
    fun aForgottenPageStaysForgottenAfterANavigationThatDoesNotCommit() {
        browser.openUrl("https://a.test/secret", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/secret")
        settle()
        browser.forgetSite("a.test", includeSubdomains = false); idle()

        browser.openUrl("https://b.test/no-content", Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.EngineError("not a URL Servo loads", 1, engine.navigated.last().second)); idle()
        // Still on the forgotten page, the user reloads it.
        load(1, 11, "https://a.test/secret")
        settle()
        assertEquals(1, engine.captures.size)

        browser.openUrl("https://c.test/", Placement.CURRENT_TAB); idle()
        load(1, 12, "https://c.test/")
        assertTrue("https://a.test/secret" !in history())
    }

    /** Until the requested page commits, the forgotten one is current and can change its own address. */
    @Test
    fun aForgottenPageMovingWithinItselfWhileANavigationIsPendingRecordsNothing() {
        browser.openUrl("https://a.test/app", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/app")
        settle()
        browser.forgetSite("a.test", includeSubdomains = false); idle()

        browser.openUrl("https://b.test/slow", Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.SAME_DOCUMENT, 10, "https://a.test/app/inbox", 0, 1))
        settle()
        assertEquals(1, engine.captures.size)

        // The requested page committing ends it: that page is captured.
        load(1, 11, "https://b.test/slow")
        settle()
        assertEquals(11L, engine.captures.last().first)
        assertTrue(history().none { it.startsWith("https://a.test/") })
    }

    @Test
    fun aCaptureOnItsWayWhenThePageIsForgottenIsDropped() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        val capture = engine.captures.single().second

        browser.forgetSite("a.test", includeSubdomains = false); idle()
        result(1, 10, capture, "A text")
        assertTrue(sink.pages.isEmpty())
    }

    @Test
    fun aCrashDropsOnlyTheCrashedTabsCapture() {
        // Two tabs at the same address, each with a capture on the way.
        browser.openUrl("https://same.test/", Placement.NEW_TAB); idle()
        load(1, 10, "https://same.test/")
        settle()
        browser.openUrl("https://same.test/", Placement.NEW_TAB); idle()
        load(2, 20, "https://same.test/")
        settle()
        assertEquals(2, engine.captures.size)
        val first = engine.captures[0].second
        val second = engine.captures[1].second

        // Crash the second view: only its own capture is dropped.
        engine.emit(EngineEvent.Crashed(engine.view(2), "test")); idle()
        result(1, 10, first, "first")
        assertEquals("first", sink.pages.single().text)

        // The crashed view's late result is ignored.
        result(2, 20, second, "second")
        assertEquals(1, sink.pages.size)
    }

    @Test
    fun aStaleCrashForAReplacedGenerationDropsNothing() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        val capture = engine.captures.single().second

        // A crash carrying a generation this view never had is stale: the capture is still pending.
        engine.emit(EngineEvent.Crashed(ViewRef(1, -1), "stale")); idle()
        result(1, 10, capture, "A text")
        assertEquals("A text", sink.pages.single().text)
    }

    @Test
    fun aResultFromAReplacedDocumentIsDropped() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        val capture = engine.captures.single().second
        load(1, 11, "https://b.test/")
        // Even a successful result for document 10 must not become B's content.
        result(1, 10, capture, "A text")
        assertTrue(sink.pages.isEmpty())
    }

    @Test
    fun aSinglePageAppAddressChangeDuringCaptureDropsTheResult() {
        browser.openUrl("https://app.test/one", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://app.test/one")
        settle()
        val capture = engine.captures.single().second
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.SAME_DOCUMENT, 10, "https://app.test/two", 1, 2))
        idle()
        result(1, 10, capture, "text of one or two")
        assertTrue(sink.pages.isEmpty())
        // The address change schedules its own capture.
        settle()
        assertEquals(2, engine.captures.size)
    }

    @Test
    fun closedTabsAndRefusedCapturesProduceNothing() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        val capture = engine.captures.single().second
        result(1, 10, capture, "", CaptureStatus.SCRIPT_ERROR)
        browser.closeTab(browser.browserState.value.activeTab!!.id); idle()
        result(1, 10, capture, "late text")
        assertTrue(sink.pages.isEmpty())
    }

    @Test
    fun theSinkDecidesWhetherToCapture() {
        sink.allow = false
        browser.openUrl("https://excluded.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://excluded.test/")
        settle()
        assertTrue(engine.captures.isEmpty())
    }

    @Test
    fun pagesThatNeverFinishLoadingAreCapturedAfterTheFallbackDelay() {
        browser.openUrl("https://slow.test/", Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 10, "https://slow.test/", 0, 1))
        idle()
        settle()
        assertTrue(engine.captures.isEmpty())
        // The timer runs on real time: wait for it rather than for as long as it should take.
        assertTrue(
            "the fallback capture fired; engine saw ${engine.captures}",
            awaitUntil(FALLBACK_WINDOW_MS) { engine.captures.any { it.first == 10L } },
        )
        assertEquals(10L, engine.captures.single().first)
    }

    @Test
    fun aDocumentShowingServosErrorPageIsNeverCaptured() {
        browser.openUrl("https://down.test/", Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 10, "https://down.test/", 0, 1))
        engine.emit(EngineEvent.TitleChanged(engine.view(1), 10, "Error loading page", Attribution.CURRENT))
        idle()
        engine.emit(EngineEvent.LoadStatus(engine.view(1), 10, LoadPhase.COMPLETE, Attribution.CURRENT))
        idle()
        settle()
        assertTrue(engine.captures.isEmpty())
    }

    // --- Rolled-back commits ------------------------------------------------------------------------

    /**
     * The commit transaction failed after [BrowserCoordinator.onHistory] had already made document
     * 20 current and scheduled its capture. The tab row still names document 10's address and visit,
     * so no capture request may be made for the uncommitted document under them.
     */
    @Test
    fun aRolledBackCommitCannotCaptureTheUncommittedDocumentAtAnotherAddress() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()
        assertEquals(listOf(10L), engine.captures.map { it.first })

        failTabUpdates()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 20, "https://b.test/", 0, 1))
        idle()

        // The transaction rolled back: the tab and its visit still name document 10's page.
        assertEquals(listOf("https://a.test/"), storedTabUrls())
        assertEquals(listOf("https://a.test/"), storedVisitUrls())
        assertNoCaptureFor(20)
        // Even a request that was made must not queue the uncommitted document's text under it.
        engine.captures.filter { it.first == 20L }.forEach { (_, capture) ->
            result(1, 20, capture, "B text")
        }
        assertTrue(
            "uncommitted document 20's text must not be queued under the rolled-back address",
            sink.pages.none { it.url == "https://a.test/" && it.text == "B text" },
        )
    }

    /**
     * A reload at the same address is still a different document. When its commit rolls back, the
     * uncommitted document must not be captured under the committed document's address and visit:
     * URL equality is not document identity.
     */
    @Test
    fun aRolledBackReloadAtTheSameAddressCannotCaptureTheUncommittedDocument() {
        browser.openUrl("https://a.test/page", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/page")
        settle()
        assertEquals(listOf(10L), engine.captures.map { it.first })

        failTabUpdates()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 20, "https://a.test/page", 0, 1))
        idle()

        assertEquals(listOf("https://a.test/page"), storedTabUrls())
        assertEquals(listOf("https://a.test/page"), storedVisitUrls())
        assertNoCaptureFor(20)
        engine.captures.filter { it.first == 20L }.forEach { (_, capture) ->
            result(1, 20, capture, "B text")
        }
        assertTrue(
            "uncommitted document 20's text must not be queued under the committed address",
            sink.pages.none { it.url == "https://a.test/page" && it.text == "B text" },
        )
    }

    /** The fixture's failure is not permanent: after the trigger is gone, a committed document captures normally. */
    @Test
    fun aDocumentCommittingAfterTheTriggerIsRemovedIsCapturedNormally() {
        browser.openUrl("https://a.test/", Placement.CURRENT_TAB); idle()
        load(1, 10, "https://a.test/")
        settle()

        failTabUpdates()
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 20, "https://b.test/", 0, 1))
        idle()
        allowTabUpdates()

        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, 30, "https://c.test/", 0, 1))
        idle()
        assertTrue(
            "the committed document's fallback capture fired; engine saw ${engine.captures}",
            awaitUntil(FALLBACK_WINDOW_MS) { engine.captures.any { it.first == 30L } },
        )
        val (_, capture) = engine.captures.last()
        result(1, 30, capture, "C text")
        val page = sink.pages.single()
        assertEquals("https://c.test/", page.url)
        assertEquals("C text", page.text)
    }

    /**
     * Fails the final `UPDATE tabs` of a commit transaction with SQLite's ABORT: the runtime
     * mutation and capture scheduling [BrowserCoordinator.onHistory] already did stay, and the
     * transaction rolls back. [allowTabUpdates] ends the failure for the recovery guard.
     */
    private fun failTabUpdates() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $SYNTHETIC_TRIGGER BEFORE UPDATE ON tabs " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
        )
    }

    private fun allowTabUpdates() {
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $SYNTHETIC_TRIGGER")
    }

    /** Waits out the fallback window for a capture request naming [document], then asserts none was made. */
    private fun assertNoCaptureFor(document: Long) {
        awaitUntil(FALLBACK_WINDOW_MS) { engine.captures.any { it.first == document } }
        assertTrue(
            "no capture request may be made for uncommitted document $document under the rolled-back tab; " +
                "engine saw ${engine.captures}",
            engine.captures.none { it.first == document },
        )
    }

    /** Polls [condition] in bounded slices until it holds or [timeoutMs] passes. */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        idle()
        return condition()
    }

    private fun storedTabUrls() = database.openHelper.readableDatabase.query("SELECT url FROM tabs").use { rows ->
        generateSequence { if (rows.moveToNext()) rows.getString(0) else null }.toList()
    }

    private fun storedVisitUrls() = database.openHelper.readableDatabase.query("SELECT url FROM visits").use { rows ->
        generateSequence { if (rows.moveToNext()) rows.getString(0) else null }.toList()
    }

    private class RecordingSink : CaptureSink {
        val pages = CopyOnWriteArrayList<CapturedPage>()
        @Volatile var allow = true

        override suspend fun wantsCapture(url: String): Long? = if (allow) 1 else null

        override suspend fun captured(page: CapturedPage) {
            pages += page
        }

        override suspend fun forgetHost(host: String, includeSubdomains: Boolean) = Unit

        override suspend fun forgetUrl(url: String) = Unit
    }

    private companion object {
        /** Longer than the fallback capture delay (400 ms), so a scheduled request has its window. */
        const val FALLBACK_WINDOW_MS = 1_500L
        const val POLL_MS = 20L
        const val SYNTHETIC_TRIGGER = "synthetic_tab_update_rollback"
    }
}
