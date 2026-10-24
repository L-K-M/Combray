package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.FindStatus
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.PageRef
import ch.lkmc.combray.engine.ViewRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Find in page: only the newest answer for the page still shown counts, and find ends with the page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FindTest {
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
        engine.emit(EngineEvent.EngineReady(720, 1280))
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun idle() = runBlocking { browser.awaitIdle() }

    private fun state() = browser.browserState.value

    private fun commit(native: Long, document: Long, url: String) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), HistoryKind.DOCUMENT_COMMITTED, document, url, 0, 1))
        idle()
    }

    private fun openPage(url: String = URL, document: Long = DOCUMENT) {
        browser.openUrl(url, Placement.NEW_TAB); idle()
        commit(engine.created.last().first, document, url)
    }

    private fun find(query: String, step: FindStep = FindStep.FIRST): FakeEngine.Find {
        browser.find(query, step); idle()
        return engine.finds.last()
    }

    private fun answer(
        request: FakeEngine.Find,
        count: Int,
        index: Int?,
        capped: Boolean = false,
        status: FindStatus = FindStatus.FOUND,
        document: Long? = DOCUMENT,
        view: ViewRef = engine.view(request.tab),
    ) {
        engine.emit(EngineEvent.FindResult(view, document, request.request, status, count, index, capped))
        idle()
    }

    @Test
    fun theShownPageIsSearchedAndItsAnswerShown() {
        openPage()
        browser.openFind(); idle()
        assertEquals(FindState("", null), state().find)

        val request = find("needle")
        // Bound to the document on screen: the engine refuses it once another one is shown.
        assertEquals(FakeEngine.Find(PageRef(engine.view(1), DOCUMENT), request.request, "needle", 0), request)
        assertEquals(FindState("needle", null), state().find)

        answer(request, count = 12, index = 0)
        assertEquals(FindState("needle", FindOutcome.Found(12, 0, capped = false)), state().find)
    }

    @Test
    fun answersForAnOlderRequestAnotherViewOrAReplacedDocumentAreIgnored() {
        openPage()
        browser.openFind(); idle()
        val older = find("nee")
        val newer = find("needle")

        answer(older, count = 9, index = 0)
        assertNull(state().find?.outcome)
        answer(newer, count = 3, index = 0, document = DOCUMENT + 1)
        assertNull(state().find?.outcome)
        answer(newer, count = 3, index = 0, view = ViewRef(newer.tab, 99))
        assertNull(state().find?.outcome)

        answer(newer, count = 3, index = 0)
        assertEquals(FindOutcome.Found(3, 0, capped = false), state().find?.outcome)
        // A late answer to the older request still changes nothing.
        answer(older, count = 9, index = 4)
        assertEquals(FindOutcome.Found(3, 0, capped = false), state().find?.outcome)
    }

    @Test
    fun nextAndPreviousStepThroughTheMatchesAndWrapAround() {
        openPage()
        browser.openFind(); idle()
        answer(find("needle"), count = 3, index = 0)

        var request = find("needle", FindStep.NEXT)
        assertEquals(1, request.index)
        answer(request, count = 3, index = 1)
        // Stepping keeps the last answer until the page sends the next one.
        request = find("needle", FindStep.NEXT)
        assertEquals(2, request.index)
        assertEquals(FindOutcome.Found(3, 1, capped = false), state().find?.outcome)
        answer(request, count = 3, index = 2)

        request = find("needle", FindStep.NEXT)
        assertEquals(0, request.index)
        answer(request, count = 3, index = 0)
        // Before the first match: the page picks its last one.
        assertEquals(-1, find("needle", FindStep.PREVIOUS).index)
    }

    @Test
    fun stepIndexes() {
        assertEquals(0, findIndex(FindStep.FIRST, FindOutcome.Found(5, 3, capped = false)))
        assertEquals(4, findIndex(FindStep.NEXT, FindOutcome.Found(5, 3, capped = false)))
        assertEquals(0, findIndex(FindStep.NEXT, FindOutcome.Found(5, 4, capped = false)))
        assertEquals(2, findIndex(FindStep.PREVIOUS, FindOutcome.Found(5, 3, capped = false)))
        assertEquals(-1, findIndex(FindStep.PREVIOUS, FindOutcome.Found(5, 0, capped = false)))
        // Only a lower bound: Next goes on, and the page wraps once it has no more.
        assertEquals(999, findIndex(FindStep.NEXT, FindOutcome.Found(999, 998, capped = true)))
        assertEquals(0, findIndex(FindStep.NEXT, null))
        assertEquals(-1, findIndex(FindStep.PREVIOUS, null))
    }

    @Test
    fun aNewQueryStartsOverAndShowsNothingUntilAnswered() {
        openPage()
        browser.openFind(); idle()
        answer(find("need"), count = 4, index = 2)

        val request = find("needle", FindStep.NEXT)
        assertEquals(0, request.index)
        assertEquals(FindState("needle", null), state().find)
    }

    @Test
    fun noMatchesAndFailuresAreShownAsSuch() {
        openPage()
        browser.openFind(); idle()
        answer(find("absent"), count = 0, index = null)
        assertEquals(FindOutcome.NotFound(capped = false), state().find?.outcome)

        answer(find("huge"), count = 0, index = null, capped = true)
        assertEquals(FindOutcome.NotFound(capped = true), state().find?.outcome)

        answer(find("broken"), count = 0, index = null, status = FindStatus.FAILED)
        assertEquals(FindOutcome.Failed, state().find?.outcome)

        // An index outside the count is not a match to show.
        answer(find("odd"), count = 2, index = 5)
        assertEquals(FindOutcome.Failed, state().find?.outcome)
    }

    @Test
    fun aBlankQueryClearsTheSelectionAndAsksNothing() {
        openPage()
        browser.openFind(); idle()
        answer(find("needle"), count = 3, index = 0)
        val asked = engine.finds.size

        browser.find("  ", FindStep.FIRST); idle()
        assertEquals(asked, engine.finds.size)
        assertEquals(listOf(PageRef(engine.view(1), DOCUMENT)), engine.findClears)
        assertEquals(FindState("  ", null), state().find)
    }

    @Test
    fun aLongQueryIsCutWithoutSplittingACharacter() {
        openPage()
        browser.openFind(); idle()
        val query = "a".repeat(MAX_FIND_QUERY_LENGTH - 1) + "😀" + "b"
        assertEquals("a".repeat(MAX_FIND_QUERY_LENGTH - 1), find(query).query)
    }

    @Test
    fun closingClearsTheSelectionOnlyAfterASearch() {
        openPage()
        browser.openFind(); idle()
        browser.closeFind(); idle()
        assertNull(state().find)
        assertTrue(engine.findClears.isEmpty())

        browser.openFind(); idle()
        val request = find("needle")
        browser.closeFind(); idle()
        assertNull(state().find)
        assertEquals(listOf(PageRef(engine.view(1), DOCUMENT)), engine.findClears)
        // An answer after closing is dropped.
        answer(request, count = 3, index = 0)
        assertNull(state().find)
    }

    @Test
    fun aNewDocumentEndsFindWithoutTouchingIt() {
        openPage()
        browser.openFind(); idle()
        val request = find("needle")
        commit(1, DOCUMENT + 1, "https://docs.test/next")
        assertNull(state().find)
        assertTrue(engine.findClears.isEmpty())

        answer(request, count = 3, index = 0)
        assertNull(state().find)
    }

    @Test
    fun aSameDocumentNavigationKeepsFind() {
        openPage()
        browser.openFind(); idle()
        answer(find("needle"), count = 3, index = 0)
        engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.SAME_DOCUMENT, DOCUMENT, "$URL#part", 0, 1))
        idle()
        assertEquals(FindOutcome.Found(3, 0, capped = false), state().find?.outcome)
    }

    @Test
    fun movingToAnotherTabEndsFindAndClearsItsPage() {
        openPage()
        val first = state().activeTab!!.id
        browser.openFind(); idle()
        find("needle")

        browser.newTab(); idle()
        assertNull(state().find)
        assertEquals(listOf(PageRef(engine.view(1), DOCUMENT)), engine.findClears)
        browser.selectTab(first); idle()
        assertNull(state().find)
    }

    @Test
    fun readerViewEndsFindAndFindWaitsForReaderView() {
        openPage()
        browser.openFind(); idle()
        find("needle")
        browser.openReader(); idle()
        assertNull(state().find)
        assertEquals(listOf(PageRef(engine.view(1), DOCUMENT)), engine.findClears)

        browser.openFind(); idle()
        assertNull(state().find)
        browser.closeReader(); idle()
        browser.openFind(); idle()
        assertEquals(FindState("", null), state().find)
    }

    @Test
    fun aCrashedPageEndsFind() {
        openPage()
        browser.openFind(); idle()
        find("needle")
        engine.emit(EngineEvent.Crashed(engine.view(1), "test"))
        idle()
        assertNull(state().find)
        assertTrue(engine.findClears.isEmpty())
    }

    @Test
    fun thereIsNoFindWithoutAPage() {
        browser.newTab(); idle()
        browser.openFind(); idle()
        assertNull(state().find)

        // A view that has not committed a document yet has nothing to search either.
        browser.openUrl(URL, Placement.CURRENT_TAB); idle()
        browser.openFind(); idle()
        assertNull(state().find)
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val URL = "https://docs.test/guide"
        const val DOCUMENT = 10L
    }
}
