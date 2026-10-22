package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.engine.CaptureStatus
import ch.lkmc.combray.engine.CapturedContent
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
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

/** Reader view: the article of the document shown when it was asked for, or nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderTest {
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

    private fun commit(native: Long, document: Long, url: String, kind: HistoryKind = HistoryKind.DOCUMENT_COMMITTED) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), kind, document, url, 0, 1))
        idle()
    }

    private fun article(native: Long, document: Long, capture: Long, text: String, status: CaptureStatus = CaptureStatus.CAPTURED) {
        val content = CapturedContent("ignored", "Title", text, text.length, "hash", false)
        engine.emit(EngineEvent.CaptureResult(capture, engine.view(native), document, status, content, null))
        idle()
    }

    private fun openPage(url: String = URL, document: Long = 10) {
        browser.openUrl(url, Placement.NEW_TAB); idle()
        commit(1, document, url)
    }

    @Test
    fun theArticleOfTheShownDocumentIsReadAndShown() {
        openPage()
        browser.openReader(); idle()
        val tab = state().activeTab!!.id
        assertEquals(ReaderState.Loading(tab), state().reader)
        val (document, capture) = engine.articleCaptures.single()
        assertEquals(10L, document)

        article(1, 10, capture, ARTICLE)
        val ready = state().reader as ReaderState.Ready
        assertEquals(URL, ready.url)
        assertEquals("Story", ready.article.title)
        assertEquals(BlockKind.PARAGRAPH, ready.article.blocks.single().kind)
    }

    @Test
    fun aPageWithoutAnArticleOrAReplacedDocumentIsUnavailable() {
        openPage()
        browser.openReader(); idle()
        val tab = state().activeTab!!.id
        article(1, 10, engine.articleCaptures.last().second, "", CaptureStatus.EMPTY)
        assertEquals(ReaderState.Unavailable(tab), state().reader)

        browser.openReader(); idle()
        article(1, 11, engine.articleCaptures.last().second, ARTICLE)
        assertEquals(ReaderState.Unavailable(tab), state().reader)
    }

    @Test
    fun anArticleArrivingAfterTheUserLeftReaderViewIsDropped() {
        openPage()
        browser.openReader(); idle()
        browser.closeReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)
        assertNull(state().reader)
    }

    @Test
    fun movingToAnotherTabLeavesReaderView() {
        openPage()
        val first = state().activeTab!!.id
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)
        browser.newTab(); idle()
        assertNull(state().reader)
        browser.selectTab(first); idle()
        assertNull(state().reader)
    }

    @Test
    fun onlyTheNewestRequestDecidesWhatReaderViewShows() {
        openPage()
        browser.openReader(); idle()
        val first = engine.articleCaptures.last().second
        browser.closeReader(); idle()
        browser.openReader(); idle()
        val second = engine.articleCaptures.last().second

        article(1, 10, first, "", CaptureStatus.TIMED_OUT)
        assertTrue(state().reader is ReaderState.Loading)
        article(1, 10, second, ARTICLE)
        assertTrue(state().reader is ReaderState.Ready)
    }

    @Test
    fun anArticleFromAReplacedRendererIsNotShown() {
        openPage()
        browser.openReader(); idle()
        val capture = engine.articleCaptures.single().second
        engine.emit(EngineEvent.Crashed(engine.view(1), "oom"))
        idle()
        browser.reload(); idle()

        article(1, 10, capture, ARTICLE)
        assertTrue(state().reader !is ReaderState.Ready)
    }

    @Test
    fun aNewPageInTheSameTabLeavesReaderView() {
        openPage()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)
        commit(1, 11, "https://news.test/next")
        assertNull(state().reader)
    }

    @Test
    fun aSameDocumentNavigationLeavesReaderView() {
        openPage()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)

        commit(1, 10, NEXT_URL, HistoryKind.SAME_DOCUMENT)

        assertEquals(NEXT_URL, state().activeTab?.url)
        assertNull(state().reader)
    }

    @Test
    fun aHashRoutedArticleChangeLeavesReaderView() {
        openPage()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)

        commit(1, 10, "$URL#/next-article", HistoryKind.SAME_DOCUMENT)

        assertNull(state().reader)
    }

    @Test
    fun aPendingArticleCannotArriveUnderAnEarlierSpaAddress() {
        openPage()
        browser.openReader(); idle()
        val capture = engine.articleCaptures.single().second

        commit(1, 10, NEXT_URL, HistoryKind.SAME_DOCUMENT)
        assertNull(state().reader)
        article(1, 10, capture, ARTICLE)

        assertNull(state().reader)
        assertEquals(NEXT_URL, state().activeTab?.url)
    }

    @Test
    fun anAddressChangingHistoryUpdateLeavesReaderView() {
        openPage()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)

        commit(1, 10, NEXT_URL, HistoryKind.LIST_UPDATED)

        assertNull(state().reader)
    }

    @Test
    fun aListOnlyUpdateAtTheSameAddressKeepsReaderView() {
        openPage()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)
        val reader = state().reader

        for (kind in listOf(HistoryKind.LIST_UPDATED, HistoryKind.SAME_DOCUMENT)) {
            commit(1, 10, URL, kind)
            assertEquals(reader, state().reader)
        }
    }

    @Test
    fun aHiddenTabsAddressChangeDoesNotDismissReaderView() {
        openPage()
        val first = state().activeTab!!.id
        browser.openUrl("https://other.test/", Placement.NEW_TAB); idle()
        commit(2, 20, "https://other.test/")
        browser.selectTab(first); idle()
        browser.openReader(); idle()
        article(1, 10, engine.articleCaptures.single().second, ARTICLE)
        val reader = state().reader

        commit(2, 20, "https://other.test/next", HistoryKind.SAME_DOCUMENT)

        assertEquals(reader, state().reader)
        assertEquals(URL, state().activeTab?.url)
    }

    @Test
    fun aTabWithoutAPageHasNoReaderView() {
        browser.newTab(); idle()
        browser.openReader(); idle()
        assertTrue(state().reader is ReaderState.Unavailable)
        assertTrue(engine.articleCaptures.isEmpty())
    }

    @Test
    fun theParserKeepsOnlyWhatItUnderstands() {
        val parsed = ArticleJson.parse(
            """
            {"title":"T","rtl":true,"blocks":[
              {"k":"h","l":9,"s":[["Big"]]},
              {"k":"script","s":[["x"]]},
              {"k":"p","s":[["see "],["here","javascript:alert(1)"],["docs","https://d.example/"],[""],[3]]},
              {"k":"li","s":[]}
            ]}
            """.trimIndent(),
            truncated = true,
        )!!
        assertTrue(parsed.rightToLeft)
        assertTrue(parsed.truncated)
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH), parsed.blocks.map { it.kind })
        assertEquals(6, parsed.blocks[0].level)
        assertEquals(listOf(ArticleSpan("see "), ArticleSpan("here"), ArticleSpan("docs", "https://d.example/")), parsed.blocks[1].spans)
        assertNull(ArticleJson.parse("not json", truncated = false))
        assertNull(ArticleJson.parse("""{"blocks":[]}""", truncated = false))
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val URL = "https://news.test/story"
        const val NEXT_URL = "https://news.test/next"
        const val ARTICLE = """{"title":"Story","byline":null,"site":null,"rtl":false,"blocks":[{"k":"p","s":[["Body"]]}]}"""
    }
}
