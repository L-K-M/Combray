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

    private fun commit(native: Long, document: Long, url: String) {
        engine.emit(EngineEvent.HistoryChanged(engine.view(native), HistoryKind.DOCUMENT_COMMITTED, document, url, 0, 1))
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
        const val ARTICLE = """{"title":"Story","byline":null,"site":null,"rtl":false,"blocks":[{"k":"p","s":[["Body"]]}]}"""
    }
}
