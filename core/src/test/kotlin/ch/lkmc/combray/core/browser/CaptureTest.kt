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
        Thread.sleep(400)
        idle()
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
}
