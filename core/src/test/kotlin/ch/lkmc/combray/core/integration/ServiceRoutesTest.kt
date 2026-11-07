package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRoutesTest {
    private fun service(alternate: String? = null) = ServiceEntity(
        kind = ServiceKind.KARAKEEP,
        baseUrl = "https://keep.example",
        fingerprint = "fp",
        generation = 1,
        captureEnabled = false,
        updatedAt = 0,
        alternateUrl = alternate,
    )

    @Test
    fun oneAttemptServesOneRequest() {
        val sent = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { request ->
                sent += request.url
                HttpOutcome.Response(200, emptyMap(), ByteArray(0))
            },
        )
        val result = routes.call(service()) { http, _ ->
            http.execute(HttpRequest("GET", "https://keep.example/a", emptyMap()))
            http.execute(HttpRequest("GET", "https://keep.example/b", emptyMap()))
        }
        assertTrue(result is HttpOutcome.Failure)
        assertEquals(listOf("https://keep.example/a"), sent)
    }

    @Test
    fun aRequestThatCannotConnectStillTriesTheAlternateAddress() {
        val sent = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { request ->
                sent += request.url
                if (request.url.contains("keep.example")) HttpOutcome.NotConnected("network") else HttpOutcome.Response(200, emptyMap(), ByteArray(0))
            },
        )
        val result = routes.call(service(alternate = "https://keep.tailnet.ts.net")) { http, root ->
            http.execute(HttpRequest("GET", "$root/api/v1/bookmarks", emptyMap()))
        }
        assertTrue(result is HttpOutcome.Response)
        assertEquals(listOf("https://keep.example/api/v1/bookmarks", "https://keep.tailnet.ts.net/api/v1/bookmarks"), sent)
    }
}
