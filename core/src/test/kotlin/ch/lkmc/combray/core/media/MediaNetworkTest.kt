package ch.lkmc.combray.core.media

import ch.lkmc.combray.engine.MediaKind
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.util.Collections

/**
 * The player's client against local servers. Test hosts (`*.test`) resolve to this machine's
 * loopback through an injected [Dns]; the address's [AddressZone] is injected too, so loopback can
 * stand for a server elsewhere.
 */
class MediaNetworkTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val servers = mutableListOf<MockWebServer>()

    /** Every address a connection was started to. */
    private val connects = Collections.synchronizedList(mutableListOf<InetSocketAddress>())

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when {
            hostname == MULTI_HOST -> listOf(loopback, InetAddress.getByName("127.0.0.2"))
            hostname.endsWith(".test") -> listOf(loopback)
            else -> throw java.net.UnknownHostException("no test address for this host")
        }
    }

    @After
    fun stopServers() = servers.forEach { it.shutdown() }

    private fun server(tls: HandshakeCertificates? = null) = MockWebServer().also {
        if (tls != null) it.useHttps(tls.sslSocketFactory(), false)
        it.start(loopback, 0)
        servers += it
    }

    private fun MockWebServer.at(host: String, path: String = "/a.mp4"): HttpUrl = url(path).newBuilder().host(host).build()

    /** The servers listen on loopback, so by default no address counts as this device. */
    private fun network(
        zoneOf: (InetAddress) -> AddressZone = { AddressZone.ELSEWHERE },
        client: (OkHttpClient.Builder) -> Unit = {},
    ): MediaNetwork {
        val base = MediaNetwork.baseClient(dns).newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connects += inetSocketAddress
                    }
                },
            )
            .also(client)
            .build()
        return MediaNetwork(base, zoneOf)
    }

    private fun request(url: HttpUrl, origin: MediaOrigin = PUBLIC_PAGE) =
        PlaybackRequest(url, MediaKind.VIDEO, origin, USER_AGENT, "a.mp4", url.host)

    /** Fetches [request] as Media3 would, returning the status code. */
    private fun MediaNetwork.fetch(request: PlaybackRequest): Int =
        callFactory(request).newCall(Request.Builder().url(request.url).build()).execute().use { it.code }

    private fun MediaNetwork.refusal(request: PlaybackRequest): MediaRefusal? {
        try {
            fetch(request)
        } catch (failed: IOException) {
            return refusalIn(failed) ?: throw failed
        }
        fail("fetched")
        return null
    }

    @Test
    fun aRedirectToAFileIsRefusedAfterOneRequest() {
        val media = server()
        media.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "file:///system/etc/hosts"))
        assertEquals(MediaRefusal.NOT_WEB, network().refusal(request(media.at("cdn.test"))))
        assertEquals(1, media.requestCount)
    }

    @Test
    fun aRedirectToANumericHostIsRefusedBeforeConnecting() {
        val media = server()
        val target = server()
        media.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.1:${target.port}/a.mp4"))
        assertEquals(MediaRefusal.NUMERIC_HOST, network().refusal(request(media.at("cdn.test"))))
        assertEquals(0, target.requestCount)
        assertFalse("no connection to the target", connects.any { it.port == target.port })
    }

    @Test
    fun aRedirectToABadPortIsRefusedBeforeConnecting() {
        val media = server()
        media.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "http://cdn.test:25/a.mp4"))
        assertEquals(MediaRefusal.BAD_PORT, network().refusal(request(media.at("cdn.test"))))
        assertFalse(connects.any { it.port == 25 })
    }

    @Test
    fun anHttpsPageCannotBeRedirectedToHttp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(SECURE_HOST).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val secure = server(serverTls)
        val plain = server()
        secure.enqueue(MockResponse().setResponseCode(302).addHeader("Location", plain.at("cdn.test").toString()))
        val network = network { it.sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager) }
        val origin = MediaPolicy.origin("https://example.org/watch", "https://example.org")

        assertEquals(MediaRefusal.MIXED_CONTENT, network.refusal(request(secure.at(SECURE_HOST), origin)))
        assertEquals(1, secure.requestCount)
        assertEquals(0, plain.requestCount)

        // The https hop itself was fine.
        secure.enqueue(MockResponse().setBody("media"))
        assertEquals(200, network.fetch(request(secure.at(SECURE_HOST), origin)))
    }

    @Test
    fun aPageElsewhereCannotReachANameThatResolvesToThisDevice() {
        val media = server()
        assertEquals(MediaRefusal.DEVICE_OR_LINK, network(zoneOf = { if (it.isLoopbackAddress) AddressZone.DEVICE else AddressZone.ELSEWHERE }).refusal(request(media.at("cdn.test"))))
        assertEquals(0, media.requestCount)
    }

    @Test
    fun thePagesOwnHostRebindingToThisDeviceIsRefused() {
        val media = server()
        media.enqueue(MockResponse().setBody("media"))
        val origin = MediaPolicy.origin("http://nas.test/web/", "http://nas.test")
        val network = network(zoneOf = MediaPolicy::zoneOf)
        assertEquals(MediaRefusal.DEVICE_OR_LINK, network.refusal(request(media.at("nas.test"), origin)))
        assertEquals(0, media.requestCount)
    }

    @Test
    fun aPageOnThisDeviceMayReachIt() {
        val media = server()
        media.enqueue(MockResponse().setBody("media"))
        val origin = MediaPolicy.origin("http://localhost:8766/video.html", "http://localhost:8766")
        assertEquals(200, network(zoneOf = MediaPolicy::zoneOf).fetch(request(media.at("cdn.test"), origin)))
    }

    @Test
    fun aRedirectToAnIpv6LoopbackLiteralIsRefusedBeforeConnecting() {
        val media = server()
        media.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://[::1]:${media.port}/a.mp4"))
        assertEquals(MediaRefusal.DEVICE_OR_LINK, network().refusal(request(media.at("cdn.test"))))
        assertEquals(1, media.requestCount)
        assertEquals("only the first hop connected", 1, connects.size)
    }

    @Test
    fun servoCookiesStayOutAndTheHeadersAreServos() {
        val media = server()
        media.enqueue(MockResponse().setResponseCode(302).addHeader("Set-Cookie", "session=1; Path=/").addHeader("Location", "/b.mp4"))
        media.enqueue(MockResponse().setBody("media"))
        val origin = MediaPolicy.origin("http://example.org/watch?v=1", "http://example.org")
        assertEquals(200, network().fetch(request(media.at("cdn.test"), origin)))

        val first = media.takeRequest()
        val second = media.takeRequest()
        assertEquals("/b.mp4", second.path)
        for (sent in listOf(first, second)) {
            assertNull(sent.getHeader("Cookie"))
            assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
            assertEquals("http://example.org/", sent.getHeader("Referer"))
        }
    }

    /** The engine reports no referrer policy, so a cross-host hop gets the origin (docs/VIDEO.md, Referer). */
    @Test
    fun aCrossHostRedirectTargetReceivesTheReferrersOrigin() {
        val media = server()
        val target = server()
        media.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.at("cdn.test").toString()))
        target.enqueue(MockResponse().setBody("media"))
        val origin = MediaPolicy.origin("http://example.org/watch?v=1", "http://example.org")
        assertEquals(200, network().fetch(request(media.at("app.test"), origin)))
        assertEquals("http://example.org/", target.takeRequest().getHeader("Referer"))
    }

    @Test
    fun anHttpsReferrerSendsNoRefererToAnHttpHop() {
        val media = server()
        media.enqueue(MockResponse().setBody("media"))
        val origin = MediaOrigin(secure = false, onDevice = false, referrer = "https://example.org")
        assertEquals(200, network().fetch(request(media.at("cdn.test"), origin)))
        assertNull(media.takeRequest().getHeader("Referer"))
    }

    @Test
    fun theTwentyFirstFollowUpFailsAsANetworkError() {
        val media = server()
        repeat(MAX_FOLLOW_UPS + 1) { media.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/a.mp4")) }
        try {
            network().fetch(request(media.at("cdn.test")))
            fail("followed")
        } catch (failed: ProtocolException) {
            // OkHttp's own limit: not a refusal, so the player shows it as a network error.
            assertNull(refusalIn(failed))
            assertFalse(failed is MediaRefusedException)
        }
        assertEquals(MAX_FOLLOW_UPS + 1, media.requestCount)
    }

    @Test
    fun aRefusedHopIsNotRetriedOnAnotherAddress() {
        val media = server()
        assertEquals(MediaRefusal.DEVICE_OR_LINK, network(zoneOf = { AddressZone.DEVICE }).refusal(request(media.at(MULTI_HOST))))
        assertEquals(1, connects.size)
        assertEquals(0, media.requestCount)
    }

    @Test
    fun theFirstRequestIsCheckedBeforeConnecting() {
        val media = server()
        val origin = MediaPolicy.origin("https://example.org/", "https://example.org")
        assertEquals(MediaRefusal.MIXED_CONTENT, network().refusal(request(media.at("cdn.test"), origin)))
        assertTrue(connects.isEmpty())
        assertEquals(0, media.requestCount)
    }

    @Test
    fun aRefusalIsFoundThroughWrappingExceptions() {
        val wrapped = IOException("open failed", IllegalStateException("inner", MediaRefusedException(MediaRefusal.BAD_PORT)))
        assertEquals(MediaRefusal.BAD_PORT, refusalIn(wrapped))
        assertNull(refusalIn(IOException("timeout")))
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android 11; Mobile) Servo/0.7.0 Firefox/140.0"
        const val SECURE_HOST = "secure.test"
        const val MULTI_HOST = "two.test"
        /** OkHttp's limit (RetryAndFollowUpInterceptor.MAX_FOLLOW_UPS). */
        const val MAX_FOLLOW_UPS = 20
        val PUBLIC_PAGE = MediaPolicy.origin("http://example.org/watch", "http://example.org")
    }
}
