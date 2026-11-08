package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpTransportTest {
    private val transport = UrlConnectionTransport()

    @Test
    fun theOpenedUrlUsesTheValidatedNontransitionalHost() {
        val url = transport.requestUrl("https://straße.example:8443/a%2Fb?q=%23value#local")!!
        assertEquals("xn--strae-oqa.example", url.host)
        assertEquals(8443, url.port)
        assertEquals("/a%2Fb?q=%23value", url.file)
    }

    @Test
    fun invalidUnicodeCannotReachAConnection() {
        for (host in listOf("a\u200Db.example", "a\u0378b.example")) {
            assertEquals(HttpOutcome.Failure("bad_url"), transport.execute(request("https://$host/")))
        }
    }

    @Test
    fun invalidPortsAreRefusedBeforeConnecting() {
        for (url in listOf("http://example.invalid:0/", "https://example.invalid:65536/", "http://[::1]:99999/")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    @Test
    fun emptyOrMalformedExplicitPortsAreRefusedBeforeConnecting() {
        for (url in listOf("http://example.invalid:/", "http://[::1]:/", "http://[::1]:-1/", "http://example.invalid:+80/")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    @Test
    fun unsupportedAndMalformedUrlsAreFailuresNotExceptions() {
        for (url in listOf("file:///synthetic-upload", "not a url", "http://example.invalid:-2/", "ftp://example.invalid/")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    @Test
    fun aMissingAuthorityIsRejectedBeforeOpeningAConnection() {
        for (url in listOf("http:/relative", "https:relative", "ftp:/relative")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    private fun request(url: String) = HttpRequest("GET", url, emptyMap())
}
