package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTransportTest {
    private val transport = UrlConnectionTransport()

    @Test
    fun invalidPortsAreRefusedBeforeConnecting() {
        for (url in listOf("http://example.invalid:0/", "https://example.invalid:65536/", "http://[::1]:99999/")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    @Test
    fun unsupportedAndMalformedUrlsAreFailuresNotExceptions() {
        for (url in listOf("file:///synthetic-upload", "not a url", "http://example.invalid:-2/")) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(request(url)))
        }
    }

    private fun request(url: String) = HttpRequest("GET", url, emptyMap())
}
