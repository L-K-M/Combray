package ch.lkmc.combray.core.media

import ch.lkmc.combray.engine.MediaKind
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.Route
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** [HopPolicy] on one hop, with the connection OkHttp would have made faked. */
class HopPolicyTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val everythingOnDevice = { _: InetAddress -> AddressZone.DEVICE }
    private val nothingOnDevice = { _: InetAddress -> AddressZone.ELSEWHERE }

    private fun request(page: String, referrer: String?, media: String) = PlaybackRequest(
        media.toHttpUrl(),
        MediaKind.VIDEO,
        MediaPolicy.origin(page, referrer),
        "Servo/0.7 test",
        "a.mp4",
        "cdn.test",
    )

    @Test
    fun aPageElsewhereCannotReachThisDevice() {
        val chain = FakeChain("http://cdn.test/a.mp4", loopback) { ok(it) }
        val refusal = refusalOf { HopPolicy(request("http://example.org/", "http://example.org", "http://cdn.test/a.mp4"), everythingOnDevice).intercept(chain) }
        assertEquals(MediaRefusal.DEVICE_OR_LINK, refusal)
        assertTrue("nothing was sent", chain.sent.isEmpty())
    }

    @Test
    fun anyPageMayReachAHomeOrTailnetAddress() {
        for ((media, address) in listOf("http://media.tail1234.ts.net/a.mp4" to "100.101.102.103", "http://other.lan/a.mp4" to "192.168.1.20")) {
            val chain = FakeChain(media, InetAddress.getByName(address)) { ok(it) }
            assertEquals(media, 200, HopPolicy(request("http://example.org/", "http://example.org", media)).intercept(chain).code)
            assertEquals(1, chain.sent.size)
        }
    }

    @Test
    fun aPageOnThisDeviceMayReachIt() {
        val chain = FakeChain("http://localhost:8766/a.mp4", loopback) { ok(it) }
        val media = request("http://localhost:8766/video.html", "http://localhost:8766", "http://localhost:8766/a.mp4")
        assertEquals(200, HopPolicy(media).intercept(chain).code)
    }

    @Test
    fun theHopGetsServosUserAgentAndTheReferrersOrigin() {
        val chain = FakeChain("https://cdn.test/a.mp4", loopback) { ok(it) }
        val withHeaders = Request.Builder().url("https://cdn.test/a.mp4").header("Referer", "https://example.org/watch?v=1").build()
        HopPolicy(request("https://example.org/watch", "https://example.org", "https://cdn.test/a.mp4"), nothingOnDevice)
            .intercept(chain.copy(withHeaders))
        val sent = chain.sent.single()
        assertEquals("Servo/0.7 test", sent.header("User-Agent"))
        assertEquals("https://example.org/", sent.header("Referer"))

        val noReferrer = FakeChain("https://cdn.test/a.mp4", loopback) { ok(it) }
        HopPolicy(request("https://example.org/watch", null, "https://cdn.test/a.mp4"), nothingOnDevice).intercept(noReferrer.copy(withHeaders))
        assertNull(noReferrer.sent.single().header("Referer"))
    }

    @Test
    fun aRefusedRedirectIsClosedBeforeOkHttpFollowsIt() {
        for ((location, reason) in listOf(
            "file:///system/etc/hosts" to MediaRefusal.NOT_WEB,
            "http://127.1/" to MediaRefusal.NUMERIC_HOST,
            "http://cdn.test:25/" to MediaRefusal.BAD_PORT,
            "http://localhost/" to MediaRefusal.DEVICE_OR_LINK,
        )) {
            var body: ClosingBody? = null
            val chain = FakeChain("http://cdn.test/a.mp4", loopback) { sent ->
                ClosingBody().let { body = it; it.redirect(sent, location) }
            }
            val refusal = refusalOf { HopPolicy(request("http://example.org/", null, "http://cdn.test/a.mp4"), nothingOnDevice).intercept(chain) }
            assertEquals(location, reason, refusal)
            assertTrue("$location: the redirect response was closed", body!!.closed)
        }
    }

    @Test
    fun anAllowedRedirectIsReturnedForOkHttpToFollow() {
        val chain = FakeChain("http://cdn.test/a.mp4", loopback) { ClosingBody().redirect(it, "/b.mp4") }
        val response = HopPolicy(request("http://example.org/", null, "http://cdn.test/a.mp4"), nothingOnDevice).intercept(chain)
        assertEquals(302, response.code)
        response.close()
    }

    private fun refusalOf(block: () -> Unit): MediaRefusal? {
        try {
            block()
        } catch (refused: MediaRefusedException) {
            return refused.reason
        }
        fail("not refused")
        return null
    }

    private fun ok(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    /** A response body that records whether it was closed. */
    private class ClosingBody {
        var closed = false

        fun redirect(request: Request, location: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("Location", location)
            .body(
                object : ForwardingSource(Buffer()) {
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }.buffer().asResponseBody(null, 0),
            )
            .build()
    }

    /** A chain on a connection to [address], answering with [respond]. */
    private class FakeChain(
        private val request: Request,
        private val address: InetAddress,
        val sent: MutableList<Request> = mutableListOf(),
        private val respond: (Request) -> Response,
    ) : Interceptor.Chain {
        constructor(url: String, address: InetAddress, respond: (Request) -> Response) :
            this(Request.Builder().url(url).build(), address, mutableListOf(), respond)

        fun copy(request: Request) = FakeChain(request, address, sent, respond)

        override fun request() = request

        override fun proceed(request: Request): Response {
            sent += request
            return respond(request)
        }

        override fun connection(): Connection = FakeConnection(
            Route(
                Address(
                    request.url.host, request.url.port, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null,
                    Authenticator.NONE, null, listOf(Protocol.HTTP_1_1), listOf(ConnectionSpec.CLEARTEXT), ProxySelector.getDefault(),
                ),
                java.net.Proxy.NO_PROXY,
                InetSocketAddress(address, request.url.port),
            ),
        )

        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    private class FakeConnection(private val route: Route) : Connection {
        override fun route() = route
        override fun socket(): Socket = throw UnsupportedOperationException()
        override fun handshake(): Handshake? = null
        override fun protocol() = Protocol.HTTP_1_1
    }
}
