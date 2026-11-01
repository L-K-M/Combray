package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.LoadPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StopLoadingTest {
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var engine: FakeEngine
    private lateinit var browser: BrowserCoordinator
    private var ids = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = FakeEngine()
        browser = BrowserCoordinator(database, engine, scope, clock = { START }, newId = { "tab${ids++}" }).also { it.start() }
        engine.emit(EngineEvent.EngineReady(720, 1280, FakeEngine.USER_AGENT))
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun idle() = runBlocking { browser.awaitIdle() }
    private fun state() = browser.browserState.value

    private fun commit(native: Long, document: Long, url: String = URL) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), HistoryKind.DOCUMENT_COMMITTED, document, url, 0, 1))
        idle()
    }

    private fun openLoadingPage(): String {
        browser.openUrl(URL, Placement.NEW_TAB); idle()
        commit(1, DOCUMENT)
        engine.emit(EngineEvent.LoadStatus(engine.view(1), DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()
        return state().activeTab!!.id
    }

    private fun acknowledge(document: Long = DOCUMENT, request: FakeEngine.Stop = engine.stopRequests.last()) {
        engine.emit(EngineEvent.LoadingStopped(request.view, request.request, document)); idle()
    }

    @Test
    fun stopWaitsForTheEngineAndKeepsThePageAndTab() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        browser.stopLoading(tab); idle()
        assertEquals(1, engine.stopRequests.size)
        assertTrue(state().activeTab!!.loading)

        acknowledge()
        assertFalse(state().activeTab!!.loading)
        assertEquals(tab, state().activeTab!!.id)
        assertEquals(URL, state().activeTab!!.url)
        assertEquals(1, state().openTabCount)
        assertTrue(engine.closed.isEmpty())

        for (phase in LoadPhase.entries) {
            engine.emit(EngineEvent.LoadStatus(engine.view(1), DOCUMENT, phase, Attribution.CURRENT)); idle()
            assertFalse(state().activeTab!!.loading)
        }
    }

    @Test
    fun stopBeforeTheFirstCommitAcceptsTheBlankDocumentReply() {
        browser.openUrl(URL, Placement.NEW_TAB); idle()
        browser.stopLoading(state().activeTab!!.id); idle()
        acknowledge(document = BLANK_DOCUMENT)
        assertFalse(state().activeTab!!.loading)

        commit(1, BLANK_DOCUMENT, BLANK_URL)
        engine.emit(EngineEvent.LoadStatus(engine.view(1), BLANK_DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aBlankCommitOvertakingTheReplyDoesNotInvalidateStop() {
        browser.openUrl(URL, Placement.NEW_TAB); idle()
        browser.stopLoading(state().activeTab!!.id); idle()
        commit(1, BLANK_DOCUMENT, BLANK_URL)
        engine.emit(EngineEvent.LoadStatus(engine.view(1), BLANK_DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()

        acknowledge(document = BLANK_DOCUMENT)
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aDelayedTapCannotStopAnotherTabOrAClosedTab() {
        val tab = openLoadingPage()
        browser.openUrl(NEXT_URL, Placement.NEW_TAB); idle()
        browser.stopLoading(tab); idle()
        browser.closeTab(tab); idle()
        browser.stopLoading(tab); idle()
        assertTrue(engine.stopRequests.isEmpty())
        assertEquals(NEXT_URL, state().activeTab!!.url)
    }

    @Test
    fun aLateReplyCannotEndANewerNavigationWait() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        val stop = engine.stopRequests.single()
        browser.openUrl(NEXT_URL, Placement.CURRENT_TAB); idle()

        acknowledge(request = stop)
        assertTrue(state().activeTab!!.loading)
        commit(1, NEXT_DOCUMENT, NEXT_URL)
        engine.emit(EngineEvent.LoadStatus(engine.view(1), NEXT_DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()
        acknowledge(request = stop)
        assertTrue(state().activeTab!!.loading)
        assertEquals(NEXT_URL, state().activeTab!!.url)
    }

    @Test
    fun anEqualUrlDoesNotMakeANewerDocumentTheStoppedOne() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        commit(1, NEXT_DOCUMENT)
        engine.emit(EngineEvent.LoadStatus(engine.view(1), NEXT_DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()

        acknowledge()
        assertTrue(state().activeTab!!.loading)
        browser.stopLoading(tab); idle()
        assertEquals(2, engine.stopRequests.size)
        acknowledge(document = NEXT_DOCUMENT)
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aReplyFromAReplacedRendererCannotStopItsReplacement() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        val stop = engine.stopRequests.single()
        engine.emit(EngineEvent.Crashed(engine.view(1), "test")); idle()
        browser.reload(); idle()
        commit(2, NEXT_DOCUMENT)
        engine.emit(EngineEvent.LoadStatus(engine.view(2), NEXT_DOCUMENT, LoadPhase.STARTED, Attribution.CURRENT)); idle()

        acknowledge(request = stop)
        assertTrue(state().activeTab!!.loading)
    }

    @Test
    fun stopWaitsForADelayedViewCreationReply() {
        engine.reportCreated = false
        browser.openUrl(URL, Placement.NEW_TAB); idle()
        browser.stopLoading(state().activeTab!!.id); idle()
        assertTrue(engine.stopRequests.isEmpty())

        engine.emit(EngineEvent.TabCreated(engine.view(1))); idle()
        assertEquals(1, engine.stopRequests.size)
        acknowledge(document = BLANK_DOCUMENT)
        assertFalse(state().activeTab!!.loading)
    }

    @Test
    fun aCancelledTargetCannotReplaceTheCommittedAddress() {
        val tab = openLoadingPage()
        browser.openUrl(NEXT_URL, Placement.CURRENT_TAB); idle()
        browser.stopLoading(tab); idle()
        acknowledge()

        assertFalse(state().activeTab!!.loading)
        assertEquals(URL, state().activeTab!!.url)
        assertEquals(listOf(URL), runBlocking { database.browser().observeRecentVisits(START, 2).first() }.map { it.url })
    }

    @Test
    fun aLateFailureCannotBecomeANewNavigationsNotice() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        val stop = engine.stopRequests.single()
        browser.openUrl(NEXT_URL, Placement.CURRENT_TAB); idle()
        engine.emit(EngineEvent.LoadingStopFailed(stop.view, stop.request)); idle()

        assertEquals(null, state().notice)
        assertTrue(state().activeTab!!.loading)
    }

    @Test
    fun aFailedStopIsVisibleAndCanBeRetried() {
        val tab = openLoadingPage()
        browser.stopLoading(tab); idle()
        val stop = engine.stopRequests.single()
        engine.emit(EngineEvent.LoadingStopFailed(stop.view, stop.request)); idle()

        assertEquals(Notice.StopFailed, state().notice)
        assertTrue(state().activeTab!!.loading)
        browser.stopLoading(tab); idle()
        assertEquals(2, engine.stopRequests.size)
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val URL = "https://page.test/article"
        const val NEXT_URL = "https://page.test/next"
        const val DOCUMENT = 10L
        const val NEXT_DOCUMENT = 11L
        const val BLANK_DOCUMENT = 12L
        const val BLANK_URL = "about:blank"
    }
}
