package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServiceRoutesTest {
    @Test
    fun aHandlerThatSendsTwiceIsRefusedAfterOneRequest() {
        val requests = mutableListOf<String>()
        val transport = HttpTransport { request ->
            requests += request.url
            HttpOutcome.Response(200, emptyMap(), ByteArray(0))
        }
        val routes = ServiceRoutes(transport)

        assertThrows(IllegalStateException::class.java) {
            routes.call(service()) { attempt, _ ->
                attempt.execute(HttpRequest("GET", "https://a.example/", emptyMap()))
                attempt.execute(HttpRequest("GET", "https://a.example/", emptyMap()))
            }
        }

        assertEquals(listOf("https://a.example/"), requests)
    }

    @Test
    fun aHandlerThatSendsOnceOnTheFirstAddressDoesNotTryTheSecond() {
        val requests = mutableListOf<String>()
        val transport = HttpTransport { request ->
            requests += request.url
            HttpOutcome.Response(200, emptyMap(), ByteArray(0))
        }
        val routes = ServiceRoutes(transport)

        routes.call(service()) { attempt, root -> attempt.execute(HttpRequest("GET", root, emptyMap())) }

        assertEquals(listOf("https://a.example/"), requests)
    }

    @Test
    fun aHandlerThatCannotConnectTriesTheOtherAddressOnce() {
        val requests = mutableListOf<String>()
        val transport = HttpTransport { request ->
            requests += request.url
            HttpOutcome.NotConnected("network")
        }
        val routes = ServiceRoutes(transport)

        routes.call(service()) { attempt, root -> attempt.execute(HttpRequest("GET", root, emptyMap())) }

        assertEquals(listOf("https://a.example/", "https://b.example/"), requests)
    }

    private fun service() = ServiceEntity(
        kind = ServiceKind.KARAKEEP,
        baseUrl = "https://a.example/",
        fingerprint = "a",
        generation = 1,
        captureEnabled = false,
        updatedAt = 0,
        alternateUrl = "https://b.example/",
    )
}
