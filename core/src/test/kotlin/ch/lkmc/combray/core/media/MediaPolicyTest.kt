package ch.lkmc.combray.core.media

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MediaPolicyTest {
    private val publicPage = MediaPolicy.origin("https://example.org/watch?v=1", "https://example.org")
    private val plainPage = MediaPolicy.origin("http://example.org/watch", "http://example.org")
    private val localPage = MediaPolicy.origin("http://192.168.1.10:8096/web/", "http://192.168.1.10:8096")

    @Test
    fun anAddressIsAtTheOriginOfItsSchemeHostAndPort() {
        assertTrue(MediaPolicy.isAt("https://example.org/watch?v=1#t=3", "https://example.org"))
        assertTrue(MediaPolicy.isAt("http://localhost:8766/media.html", "http://localhost:8766"))
        assertTrue(MediaPolicy.isAt("https://example.org:443/", "https://example.org"))
        assertFalse(MediaPolicy.isAt("http://example.org/", "https://example.org"))
        assertFalse(MediaPolicy.isAt("https://cdn.example.org/", "https://example.org"))
        assertFalse(MediaPolicy.isAt("http://localhost:8767/", "http://localhost:8766"))
        assertFalse(MediaPolicy.isAt("data:text/html,a", "http://localhost:8766"))
        assertFalse(MediaPolicy.isAt("https://example.org/", "null"))
    }

    private fun check(origin: MediaOrigin, url: String) = MediaPolicy.check(origin, url)

    @Test
    fun onlyWebAddressesWithoutCredentialsPlay() {
        assertNull(check(publicPage, "https://cdn.example.org/a.mp4"))
        for (url in listOf("file:///sdcard/a.mp4", "content://media/1", "data:video/mp4;base64,AAAA", "intent://x#Intent;end", "blob:https://example.org/1")) {
            assertEquals(url, MediaRefusal.NOT_WEB, check(publicPage, url))
        }
        assertEquals(MediaRefusal.UNPARSEABLE, check(publicPage, "https://exa mple.org/a.mp4"))
        assertEquals(MediaRefusal.CREDENTIALS, check(publicPage, "https://user:pw@cdn.example.org/a.mp4"))
        assertEquals(MediaRefusal.CREDENTIALS, check(publicPage, "https://user@cdn.example.org/a.mp4"))
    }

    @Test
    fun theFetchBadPortsAreRefused() {
        assertEquals(MediaRefusal.BAD_PORT, check(plainPage, "http://cdn.example.org:25/a.mp4"))
        assertEquals(MediaRefusal.BAD_PORT, check(plainPage, "http://cdn.example.org:6667/a.mp4"))
        // OkHttp's parser has no port 0.
        assertEquals(MediaRefusal.UNPARSEABLE, check(plainPage, "http://cdn.example.org:0/a.mp4"))
        assertNull(check(plainPage, "http://cdn.example.org:8080/a.mp4"))
        assertNull(check(plainPage, "http://cdn.example.org:443/a.mp4"))
    }

    /** InetAddress reads these as addresses without OkHttp asking its Dns: WHATWG URL's "ends in a number". */
    @Test
    fun hostsEndingInANumberAreRefusedUnlessDottedQuads() {
        for (host in listOf("127.1", "2130706433", "0177.0.0.1", "0x7f.1", "example.0x10", "127.0.0.1.", "1.2.3.4.5", "example.08")) {
            assertEquals(host, MediaRefusal.NUMERIC_HOST, check(plainPage, "http://$host/a.mp4"))
        }
        assertNull(check(plainPage, "http://93.184.215.14/a.mp4"))
        assertNull(check(plainPage, "http://[2001:db8::1]/a.mp4"))
        assertNull(check(plainPage, "http://cdn0x.example.org/a.mp4"))
        assertNull(check(plainPage, "http://v1.example.org/a.mp4"))
    }

    @Test
    fun aSecurePageOrReferrerPlaysHttpsOnly() {
        assertEquals(MediaRefusal.MIXED_CONTENT, check(publicPage, "http://cdn.example.org/a.mp4"))
        // A data: page counts as secure when its referrer is.
        val secureReferrer = MediaPolicy.origin("data:text/html,<video>", "https://example.org")
        assertTrue(secureReferrer.secure)
        assertEquals(MediaRefusal.MIXED_CONTENT, check(secureReferrer, "http://cdn.example.org/a.mp4"))
        // An https page with no referrer is still secure.
        assertEquals(MediaRefusal.MIXED_CONTENT, check(MediaPolicy.origin("https://example.org/", null), "http://cdn.example.org/a.mp4"))
        assertNull(check(plainPage, "http://cdn.example.org/a.mp4"))
        assertNull(check(plainPage, "https://cdn.example.org/a.mp4"))
    }

    @Test
    fun aPublicPageCannotReachLocalAddressesOrNames() {
        val local = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0", "100.64.0.1", "100.127.255.254",
            "[::1]", "[::]", "[fd7a:115c:a1e0::1]", "[fe80::1]", "[::ffff:192.168.1.1]", "[64:ff9b::7f00:1]", "[64:ff9b::a00:1]",
            "localhost", "media.localhost", "nas.local", "nas.lan", "router.home.arpa", "nas", "nas.",
        )
        for (host in local) {
            assertEquals(host, MediaRefusal.PRIVATE_NETWORK, check(plainPage, "http://$host/a.mp4"))
        }
        for (host in listOf("93.184.215.14", "100.128.0.1", "[2001:db8::1]", "[64:ff9b::5db8:d70e]", "cdn.example.org", "nas.test")) {
            assertNull(host, check(plainPage, "http://$host/a.mp4"))
        }
    }

    @Test
    fun aLocalPageMayUseLocalAddresses() {
        assertTrue(localPage.local)
        assertNull(check(localPage, "http://192.168.1.10:8096/videos/1/stream.mp4"))
        assertNull(check(localPage, "http://nas.lan/a.mp4"))
        assertNull(check(MediaPolicy.origin("http://localhost:8766/video.html", "http://localhost:8766"), "http://localhost:8766/media/clip.mp4"))
        assertTrue(MediaPolicy.origin("http://nas/", "http://nas").local)
        assertTrue(MediaPolicy.origin("http://[fd7a:115c:a1e0::5]/", "http://[fd7a:115c:a1e0::5]").local)
    }

    /** A report can land on the wrong page; without a local referrer a local page is judged as public. */
    @Test
    fun aLocalPageNeedsALocalReferrer() {
        assertFalse(MediaPolicy.origin("http://192.168.1.10/", null).local)
        assertFalse(MediaPolicy.origin("http://192.168.1.10/", "https://example.org").local)
        assertFalse(MediaPolicy.origin("https://example.org/", "http://192.168.1.10").local)
        assertEquals(MediaRefusal.PRIVATE_NETWORK, check(MediaPolicy.origin("http://192.168.1.10/", null), "http://192.168.1.10/a.mp4"))
    }

    @Test
    fun pagesWithoutAHostArePublic() {
        for (page in listOf("data:text/html,<video src=x>", "about:blank", "blob:http://localhost:8766/1")) {
            val origin = MediaPolicy.origin(page, null)
            assertNull(origin.pageHost)
            assertFalse(page, origin.local)
            assertFalse(page, origin.secure)
            assertEquals(page, MediaRefusal.PRIVATE_NETWORK, check(origin, "http://localhost:8766/media/clip.mp4"))
        }
        assertFalse(MediaPolicy.origin("data:text/html,x", "http://localhost:8766").local)
    }

    @Test
    fun theSameHostExceptionAppliesToTheConnectedAddressOnly() {
        val tailnet = MediaPolicy.origin("https://media.tail1234.ts.net/web/", "https://media.tail1234.ts.net")
        val tailscale = InetAddress.getByName("100.101.102.103")
        assertNull(MediaPolicy.checkAddress(tailnet, "media.tail1234.ts.net", tailscale))
        assertNull(MediaPolicy.checkAddress(tailnet, "MEDIA.tail1234.ts.net", tailscale))
        assertEquals(MediaRefusal.PRIVATE_NETWORK, MediaPolicy.checkAddress(tailnet, "other.tail1234.ts.net", tailscale))
        assertNull(MediaPolicy.checkAddress(tailnet, "cdn.example.org", InetAddress.getByName("93.184.215.14")))
        // The URL rules have no such exception: a local name is refused from a public page, its own included.
        val publicOnLocalName = MediaPolicy.origin("http://nas.lan/", null)
        assertEquals(MediaRefusal.PRIVATE_NETWORK, check(publicOnLocalName, "http://nas.lan/a.mp4"))
        // A local page reaches local addresses on any host.
        assertNull(MediaPolicy.checkAddress(localPage, "other.lan", InetAddress.getByName("192.168.1.20")))
    }

    @Test
    fun theAddressRuleUsesTheInjectedClassification() {
        val everythingLocal = { _: InetAddress -> true }
        assertEquals(
            MediaRefusal.PRIVATE_NETWORK,
            MediaPolicy.checkAddress(plainPage, "cdn.test", InetAddress.getByName("93.184.215.14"), everythingLocal),
        )
        assertNull(MediaPolicy.checkAddress(plainPage, "example.org", InetAddress.getByName("93.184.215.14"), everythingLocal))
    }

    @Test
    fun localAddressesIncludeTheMediaOnlyRanges() {
        for (address in listOf("0.0.0.0", "0.1.2.3", "100.64.0.0", "100.127.255.255", "::", "::1", "64:ff9b::c0a8:101", "::ffff:10.0.0.1", "fc00::1")) {
            assertTrue(address, MediaPolicy.isLocalAddress(InetAddress.getByName(address)))
        }
        for (address in listOf("1.0.0.0", "100.63.255.255", "100.128.0.0", "64:ff9b::808:808", "2001:db8::1", "::7f00:1")) {
            assertFalse(address, MediaPolicy.isLocalAddress(InetAddress.getByName(address)))
        }
    }

    @Test
    fun theRefererIsTheReferrersOriginAndNoneOnADowngrade() {
        val origin = MediaPolicy.origin("http://example.org/watch", "http://example.org:8080/path?q=1")
        assertEquals("http://example.org:8080", origin.referrer)
        assertEquals("http://example.org:8080/", MediaPolicy.referer(origin, "http://cdn.example.org/a.mp4".toHttpUrl()))
        assertEquals("https://example.org/", MediaPolicy.referer(publicPage, "https://cdn.example.org/a.mp4".toHttpUrl()))
        assertEquals("http://[::1]:8766/", MediaPolicy.referer(MediaPolicy.origin("http://[::1]:8766/", "http://[::1]:8766"), "http://[::1]:8766/a".toHttpUrl()))

        // An https referrer sends none to an http hop, as strict-origin-when-cross-origin does.
        val downgrade = MediaOrigin(pageHost = "example.org", secure = false, local = false, referrer = "https://example.org")
        assertNull(MediaPolicy.referer(downgrade, "http://cdn.example.org/a.mp4".toHttpUrl()))
        assertNull(MediaPolicy.referer(MediaPolicy.origin("https://example.org/", null), "https://cdn.example.org/a.mp4".toHttpUrl()))
    }

    @Test
    fun originsAndRequestsDoNotPrintAddresses() {
        assertFalse(publicPage.toString().contains("example.org"))
        val request = PlaybackRequest(
            "https://cdn.example.org/a.mp4?sig=secret".toHttpUrl(),
            ch.lkmc.combray.engine.MediaKind.VIDEO,
            publicPage,
            "Servo/1.0",
            "a.mp4",
            "cdn.example.org",
        )
        assertFalse(request.toString().contains("example.org"))
        assertFalse(request.toString().contains("secret"))
        assertEquals("NUMERIC_HOST", MediaRefusedException(MediaRefusal.NUMERIC_HOST).message)
    }
}
