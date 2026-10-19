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
    fun unreachableServersAreRetriable() {
        server.close()
        val result = create()
        assertTrue(result is DispatchResult.Retry)
    }
}
