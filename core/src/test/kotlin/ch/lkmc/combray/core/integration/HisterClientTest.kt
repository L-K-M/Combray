package ch.lkmc.combray.core.integration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HisterClientTest {
    private val server = FakeHister()
    private val client = HisterClient(UrlConnectionTransport(timeoutMs = 2_000))

    @After
    fun tearDown() = server.close()

    @Test
    fun addSendsTextWithTheNativeOriginAndToken() {
        assertEquals(DispatchResult.Done(null), client.add(server.root, FakeHister.TOKEN, "https://a.example/", "A", "Rendered text"))
        assertEquals("""{"url":"https://a.example/","title":"A","text":"Rendered text"}""", server.added.single())
        assertEquals(DispatchResult.AuthRequired("http_401"), client.add(server.root, "wrong", "https://a.example/", "A", "x"))
    }

    @Test
    fun theRequestBodyStaysBoundedEvenWhenEscapingGrowsIt() {
        // Every quote doubles in JSON: 600 KiB of quotes would be 1.2 MB escaped.
        val text = "\"".repeat(600 * 1024) + "😀"
        assertEquals(DispatchResult.Done(null), client.add(server.root, FakeHister.TOKEN, "https://a.example/", null, text))
        assertTrue(server.requestSizes.single() <= HisterClient.MAX_REQUEST_BYTES)
    }

    @Test
    fun searchMergesPromotedHistoryAndDocumentsWithoutDroppingEither() {
        server.searchBody = """
            {"query":"servo","total":3,"page_key":"next-1",
             "history":[{"url":"https://b.example/","title":"From history"}],
             "documents":[{"url":"https://a.example/","title":"Doc A","text":"<b>servo</b> engine"},
                          {"url":"https://b.example/","title":"Duplicate of history"}]}
        """.trimIndent()
        val results = client.search(server.root, FakeHister.TOKEN, "servo") as HisterSearch.Results
        assertEquals(listOf("https://b.example/", "https://a.example/"), results.hits.map { it.url })
        assertTrue(results.hits.first().fromHistory)
        // Excerpts stay plain text; the UI never renders them as HTML.
        assertEquals("<b>servo</b> engine", results.hits[1].excerpt)
        assertEquals("next-1", results.nextPage)
        assertEquals(3, results.total)
    }

    @Test
    fun searchStatesAreDistinct() {
        assertEquals(HisterSearch.Unauthorized, client.search(server.root, "wrong", "x"))
        server.searchBody = "<html>login</html>"
        assertEquals(HisterSearch.Malformed, client.search(server.root, FakeHister.TOKEN, "x"))
        server.close()
        assertTrue(client.search(server.root, FakeHister.TOKEN, "x") is HisterSearch.Unreachable)
    }

    @Test
    fun searchListsEachUrlOnceWithHistoryFirst() {
        server.searchBody = """
            {"total":5,
             "history":[{"url":"https://b.example/"},{"url":"https://b.example/"}],
             "documents":[{"url":"https://a.example/"},{"url":"https://a.example/"},
                          {"url":"https://b.example/"}]}
        """.trimIndent()
        val results = client.search(server.root, FakeHister.TOKEN, "x") as HisterSearch.Results
        assertEquals(listOf("https://b.example/", "https://a.example/"), results.hits.map { it.url })
        assertEquals(listOf(true, false), results.hits.map { it.fromHistory })
    }

    @Test
    fun documentKeysFollowHisterNormalization() {
        assertEquals("https://a.example/p?id=3", HisterIntegration.documentKey("https://A.example/p?utm_source=x&id=3#frag"))
        assertEquals("https://a.example/caf%C3%A9", HisterIntegration.documentKey("https://a.example/caf%C3%A9"))
    }
}
