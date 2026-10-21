package ch.lkmc.combray.core.integration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KarakeepClientTest {
    private val server = FakeKarakeep()
    private val client = KarakeepClient(UrlConnectionTransport(timeoutMs = 500))

    @After
    fun tearDown() = server.close()

    private fun create(url: String = "https://a.example/", title: String? = "A") =
        client.createLink(server.root, FakeKarakeep.KEY, url, title)

    @Test
    fun newAndExistingLinksAreBothSaved() {
        assertEquals(DispatchResult.Done("bm1"), create())
        assertEquals(DispatchResult.Done("bm1", "existing"), create())
        assertEquals("POST {\"type\":\"link\",\"url\":\"https://a.example/\",\"title\":\"A\"}", server.requests.first())
    }

    @Test
    fun aLostAnswerIsRetriableAndTheRetryFindsTheExistingBookmark() {
        server.fault = FakeKarakeep.Fault.STORE_THEN_HANG
        server.faultCount = 1
        assertEquals(DispatchResult.Retry("timeout"), create())
        assertEquals(DispatchResult.Done("bm1", "existing"), create())
        assertEquals(1, server.bookmarks.size)
    }

    @Test
    fun statusesAreClassified() {
        server.faultCount = 1
        server.fault = FakeKarakeep.Fault.TOO_MANY_REQUESTS
        assertEquals(DispatchResult.Retry("http_429", 120_000), create())
        server.faultCount = 1
        server.fault = FakeKarakeep.Fault.SERVER_ERROR
        assertEquals(DispatchResult.Retry("http_503"), create())
        server.faultCount = 1
        server.fault = FakeKarakeep.Fault.BAD_REQUEST
        assertEquals(DispatchResult.Rejected("http_400"), create())
        server.faultCount = 1
        server.fault = FakeKarakeep.Fault.REDIRECT
        assertEquals(DispatchResult.Rejected("redirect"), create())
        server.acceptedKey = "rotated"
        assertEquals(DispatchResult.AuthRequired("http_401"), create())
    }

    @Test
    fun anUnparseableSuccessIsStillASaveWithoutAnId() {
        server.fault = FakeKarakeep.Fault.MALFORMED
        server.faultCount = 1
        assertEquals(DispatchResult.Done(null, "malformed_response"), create())
    }

    @Test
    fun connectionCheckIsReadOnly() {
        assertTrue(client.checkConnection(server.root, FakeKarakeep.KEY) is ConnectionCheck.Connected)
        assertEquals(ConnectionCheck.Unauthorized, client.checkConnection(server.root, "wrong"))
        assertTrue(server.bookmarks.isEmpty())
        assertTrue(server.requests.all { it.startsWith("GET") })
    }

    @Test
    fun aBookmarkIsAddedToAnExistingListOnly() {
        server.lists["reading"] = mutableSetOf()
        assertEquals(DispatchResult.Rejected("list_not_found"), client.addToList(server.root, FakeKarakeep.KEY, "reading", "bm1"))
        server.bookmarks["https://example.org/"] = "bm1"
        assertEquals(DispatchResult.Done("bm1"), client.addToList(server.root, FakeKarakeep.KEY, "reading", "bm1"))
        assertEquals(DispatchResult.Done("bm1"), client.addToList(server.root, FakeKarakeep.KEY, "reading", "bm1"))
        assertEquals(setOf("bm1"), server.lists["reading"])
        assertEquals(DispatchResult.Rejected("list_not_found"), client.addToList(server.root, FakeKarakeep.KEY, "gone", "bm1"))
        assertEquals(DispatchResult.AuthRequired("http_401"), client.addToList(server.root, "wrong", "reading", "bm1"))
    }

    @Test
    fun onlyManualListsAreOffered() {
        server.lists["b"] = mutableSetOf()
        server.lists["a"] = mutableSetOf()
        val lists = client.lists(server.root, FakeKarakeep.KEY)
        assertEquals(KarakeepRead.Ok(listOf(KarakeepList("a", "📚 List a"), KarakeepList("b", "📚 List b"))), lists)
        assertEquals(KarakeepRead.Unauthorized, client.lists(server.root, "wrong"))
    }

    @Test
    fun bookmarksAreListedNewestFirstAndSearched() {
        create("https://a.example/", "Alpha")
        create("https://b.example/", "Beta")
        val newest = client.bookmarks(server.root, FakeKarakeep.KEY, query = null) as KarakeepRead.Ok
        assertEquals(listOf("https://b.example/", "https://a.example/"), newest.value.bookmarks.map { it.url })
        assertEquals("Beta", newest.value.bookmarks.first().title)
        val found = client.bookmarks(server.root, FakeKarakeep.KEY, query = "alpha") as KarakeepRead.Ok
        assertEquals(listOf(KarakeepBookmark("bm1", "https://a.example/", "Alpha")), found.value.bookmarks)
        assertEquals(KarakeepRead.Unauthorized, client.bookmarks(server.root, "wrong", query = null))
    }

    @Test
    fun unreachableServersAreRetriable() {
        server.close()
        val result = create()
        assertTrue(result is DispatchResult.Retry)
    }
}
