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
    private val lanPage = MediaPolicy.origin("http://192.168.1.10:8096/web/", "http://192.168.1.10:8096")
    private val devicePage = MediaPolicy.origin("http://localhost:8766/video.html", "http://localhost:8766")

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

    /** Owner decision, 29 October 2026: home networks and tailnets are open to any page; this device and the link are not. */
    @Test
    fun aPageElsewhereCannotReachThisDeviceOrTheLink() {
        val deviceOrLink = listOf(
            "127.0.0.1", "127.1.2.3", "169.254.1.1", "169.254.169.254", "0.0.0.0", "[::1]", "[::]", "[fe80::1]",
            "[::ffff:127.0.0.1]", "[64:ff9b::7f00:1]", "[64:ff9b::a9fe:a9fe]", "localhost", "LOCALHOST.", "media.localhost",
        )
        for (page in listOf(plainPage, lanPage)) {
            for (host in deviceOrLink) {
                assertEquals(host, MediaRefusal.DEVICE_OR_LINK, check(page, "http://$host/a.mp4"))
            }
        }
        val elsewhere = listOf(
            "10.1.2.3", "172.16.0.1", "192.168.1.1", "100.64.0.1", "100.127.255.254", "[fd7a:115c:a1e0::1]", "[::ffff:192.168.1.1]",
            "[64:ff9b::a00:1]", "nas.local", "nas.lan", "router.home.arpa", "nas", "nas.", "media.tail1234.ts.net",
            "93.184.215.14", "[2001:db8::1]", "cdn.example.org",
        )
        for (host in elsewhere) {
            assertNull(host, check(plainPage, "http://$host/a.mp4"))
        }
    }

    @Test
    fun aPageOnThisDeviceMayUseIt() {
        assertTrue(devicePage.onDevice)
        assertNull(check(devicePage, "http://localhost:8766/media/clip.mp4"))
        assertNull(check(devicePage, "http://127.0.0.1:8766/media/clip.mp4"))
        assertNull(check(devicePage, "http://[::1]:8766/media/clip.mp4"))
        assertTrue(MediaPolicy.origin("http://127.0.0.1:8080/", "http://127.0.0.1:8080").onDevice)
        assertTrue(MediaPolicy.origin("http://[::1]/", "http://[::1]").onDevice)
        assertTrue(MediaPolicy.origin("http://127.0.0.1:8080/", "http://localhost:8080").onDevice)
        assertFalse(lanPage.onDevice)
        assertFalse(MediaPolicy.origin("http://nas/", "http://nas").onDevice)
        // This device is one host; a link-local host is another.
        for (host in listOf("169.254.169.254", "[fe80::1]", "[::ffff:169.254.1.1]")) {
            assertEquals(host, MediaRefusal.DEVICE_OR_LINK, check(devicePage, "http://$host/a.mp4"))
        }
    }

    /** `localhost.` and `*.localhost` miss Android's hosts file and resolve through DNS, so a page there is elsewhere. */
    @Test
    fun onlyExactlyLocalhostIsAPageOnThisDevice() {
        for (host in listOf("media.localhost", "localhost.", "media.localhost.")) {
            val origin = MediaPolicy.origin("http://$host/v.html", "http://$host")
            assertFalse(host, origin.onDevice)
            assertEquals(host, MediaRefusal.DEVICE_OR_LINK, check(origin, "http://127.0.0.1:8766/a.mp4"))
            assertEquals(host, MediaRefusal.DEVICE_OR_LINK, check(origin, "http://$host/a.mp4"))
        }
        assertTrue(MediaPolicy.origin("http://LOCALHOST:8766/", "http://localhost:8766").onDevice)
    }

    /** Owner decision, 29 October 2026: a link-local host only from a page at that same address. */
    @Test
    fun aPageOnALinkLocalHostMayReachThatHostOnly() {
        val camera = MediaPolicy.origin("http://169.254.10.20/live.html", "http://169.254.10.20")
        assertFalse(camera.onDevice)
        assertNull(check(camera, "http://169.254.10.20/stream.mp4"))
        assertNull(check(camera, "http://[::ffff:169.254.10.20]/stream.mp4"))
        assertNull(MediaPolicy.checkAddress(camera, "169.254.10.20", InetAddress.getByName("169.254.10.20")))
        assertNull(MediaPolicy.checkAddress(camera, "camera.test", InetAddress.getByName("64:ff9b::a9fe:a14")))
        for (host in listOf("169.254.10.21", "169.254.169.254", "127.0.0.1", "localhost", "[fe80::1]")) {
            assertEquals(host, MediaRefusal.DEVICE_OR_LINK, check(camera, "http://$host/a.mp4"))
        }
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(camera, "localhost", InetAddress.getByName("127.0.0.1")))
        assertNull(check(camera, "http://192.168.1.10/a.mp4"))

        val ipv6 = MediaPolicy.origin("http://[fe80::2]/", "http://[fe80::2]")
        assertNull(check(ipv6, "http://[fe80::2]/a.mp4"))
        assertEquals(MediaRefusal.DEVICE_OR_LINK, check(ipv6, "http://[fe80::3]/a.mp4"))
        // The referrer must be at the same address.
        assertNull(MediaPolicy.origin("http://169.254.10.20/", "http://169.254.10.21").linkHost)
        assertNull(MediaPolicy.origin("http://169.254.10.20/", null).linkHost)
        assertNull(MediaPolicy.origin("http://localhost/", "http://localhost").linkHost)
    }

    /** Only the local link answers an mDNS `.local` name, so a page there is on the host its own name reaches. */
    @Test
    fun anMdnsPageMayReachTheLinkLocalAddressesOfItsOwnName() {
        val printer = MediaPolicy.origin("http://printer.local/status.html", "http://printer.local")
        assertEquals("printer.local", printer.linkHost)
        assertNull(MediaPolicy.checkAddress(printer, "printer.local", InetAddress.getByName("169.254.7.7")))
        assertNull(MediaPolicy.checkAddress(printer, "PRINTER.local.", InetAddress.getByName("fe80::7")))
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(printer, "other.local", InetAddress.getByName("169.254.7.7")))
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(printer, "printer.local", InetAddress.getByName("127.0.0.1")))
        // A literal is not known to be the page's own host.
        assertEquals(MediaRefusal.DEVICE_OR_LINK, check(printer, "http://169.254.7.7/a.mp4"))

        // A name outside .local is not link-scoped: its own name rebinding to the link stays closed.
        val public = MediaPolicy.origin("https://media.example.org/", "https://media.example.org")
        assertNull(public.linkHost)
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(public, "media.example.org", InetAddress.getByName("169.254.7.7")))
        assertNull(MediaPolicy.origin("http://nas.lan/", "http://nas.lan").linkHost)
        // The referrer must be on the same host.
        assertNull(MediaPolicy.origin("http://printer.local/", "http://other.local").linkHost)
        assertNull(MediaPolicy.origin("http://printer.local/", "http://169.254.7.7").linkHost)
        assertNull(MediaPolicy.origin("http://printer.local/", null).linkHost)
    }

    /** A report can land on the wrong page; without a referrer there too, a page on this device is judged as elsewhere. */
    @Test
    fun aPageOnThisDeviceNeedsAReferrerThere() {
        assertFalse(MediaPolicy.origin("http://localhost:8766/", null).onDevice)
        assertFalse(MediaPolicy.origin("http://localhost:8766/", "https://example.org").onDevice)
        assertFalse(MediaPolicy.origin("http://localhost:8766/", "http://192.168.1.10").onDevice)
        assertFalse(MediaPolicy.origin("https://example.org/", "http://localhost:8766").onDevice)
        assertEquals(MediaRefusal.DEVICE_OR_LINK, check(MediaPolicy.origin("http://localhost:8766/", null), "http://localhost:8766/a.mp4"))
        // A home network page that withholds its referrer still plays its own media.
        assertNull(check(MediaPolicy.origin("http://192.168.1.10/", null), "http://192.168.1.10/a.mp4"))
    }

    @Test
    fun pagesWithoutAHostAreElsewhere() {
        for (page in listOf("data:text/html,<video src=x>", "about:blank", "blob:http://localhost:8766/1")) {
            val origin = MediaPolicy.origin(page, null)
            assertFalse(page, origin.onDevice)
            assertFalse(page, origin.secure)
            assertEquals(page, MediaRefusal.DEVICE_OR_LINK, check(origin, "http://localhost:8766/media/clip.mp4"))
            assertNull(page, check(origin, "http://192.168.1.10/a.mp4"))
        }
        assertFalse(MediaPolicy.origin("data:text/html,x", "http://localhost:8766").onDevice)
    }

    @Test
    fun theAddressRuleUsesTheInjectedClassification() {
        val everythingOnDevice = { _: InetAddress -> AddressZone.DEVICE }
        val address = InetAddress.getByName("93.184.215.14")
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(plainPage, "cdn.test", address, everythingOnDevice))
        assertNull(MediaPolicy.checkAddress(devicePage, "cdn.test", address, everythingOnDevice))
        assertEquals(MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(devicePage, "cdn.test", address) { AddressZone.LINK })
        assertNull(MediaPolicy.checkAddress(plainPage, "cdn.test", InetAddress.getByName("127.0.0.1")) { AddressZone.ELSEWHERE })
    }

    /** A name, the page's own included, that resolves or rebinds to this device or the link; a home or tailnet address is fine. */
    @Test
    fun aConnectionElsewhereNeverReachesThisDeviceOrTheLink() {
        val page = MediaPolicy.origin("https://attacker.test/", "https://attacker.test")
        for (address in listOf("127.0.0.1", "127.1.2.3", "::1", "169.254.169.254", "fe80::1", "0.0.0.0", "::", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe")) {
            assertEquals(address, MediaRefusal.DEVICE_OR_LINK, MediaPolicy.checkAddress(page, "attacker.test", InetAddress.getByName(address)))
        }
        for (address in listOf("127.0.0.1", "127.1.2.3", "::1", "0.0.0.0", "::", "::ffff:127.0.0.1", "64:ff9b::7f00:1")) {
            assertNull(address, MediaPolicy.checkAddress(devicePage, "localhost", InetAddress.getByName(address)))
        }
        for (address in listOf("100.64.1.2", "100.101.102.103", "192.168.1.1", "10.0.0.5", "fd7a:115c:a1e0::1", "93.184.215.14")) {
            assertNull(address, MediaPolicy.checkAddress(page, "attacker.test", InetAddress.getByName(address)))
        }
    }

    /** Servo judges a blob: page by the origin that made it, and its loads carry no referrer. */
    @Test
    fun aBlobPageIsAsSecureAsThePageThatMadeIt() {
        val blob = MediaPolicy.origin("blob:https://example.org/7f0c", null)
        assertTrue(blob.secure)
        assertFalse(blob.onDevice)
        assertEquals(MediaRefusal.MIXED_CONTENT, check(blob, "http://93.184.215.14/a.mp4"))
        assertNull(check(blob, "https://cdn.example.org/a.mp4"))
        assertFalse(MediaPolicy.origin("blob:http://example.org/7f0c", null).secure)
    }

    @Test
    fun zonesIncludeMappedAndNat64Forms() {
        val zones = mapOf(
            AddressZone.DEVICE to listOf(
                "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.255.255", "::", "::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
            ),
            AddressZone.LINK to listOf(
                "169.254.0.0", "169.254.255.255", "fe80::1", "febf::1", "::ffff:169.254.1.1", "64:ff9b::a9fe:a9fe",
            ),
            AddressZone.ELSEWHERE to listOf(
                "1.0.0.0", "126.255.255.255", "128.0.0.0", "169.253.255.255", "169.255.0.0", "10.0.0.1", "100.64.0.1", "192.168.1.1",
                "fc00::1", "fe7f::1", "fec0::1", "2001:db8::1", "64:ff9b::808:808", "::ffff:10.0.0.1", "::7f00:1",
            ),
        )
        for ((zone, addresses) in zones) {
            for (address in addresses) {
                assertEquals(address, zone, MediaPolicy.zoneOf(InetAddress.getByName(address)))
            }
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
        val downgrade = MediaOrigin(secure = false, onDevice = false, referrer = "https://example.org")
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
