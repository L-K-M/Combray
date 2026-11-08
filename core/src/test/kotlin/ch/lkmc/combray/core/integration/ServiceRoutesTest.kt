package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One route attempt is one request: a failover must not re-send a request that may already have
 * reached the server, and a handler that misuses the attempt fails loudly instead of returning a
 * retryable outcome the queue would send again.
 */
class ServiceRoutesTest {
    private fun service(alternate: String? = "https://b.example/") = ServiceEntity(
        kind = ServiceKind.KARAKEEP,
        baseUrl = "https://a.example/",
        fingerprint = "fp",
        generation = 1,
        captureEnabled = false,
        updatedAt = 0,
        alternateUrl = alternate,
    )

    private fun request(url: String = "https://a.example/") = HttpRequest("GET", url, emptyMap())

    @Test
    fun aHandlerThatSendsTwiceIsRefusedAfterOneRequest() {
        val requests = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { requests += it.url; HttpOutcome.Response(200, emptyMap(), ByteArray(0)) },
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            routes.call(service()) { attempt, _ ->
                attempt.execute(request())
                attempt.execute(request())
            }
        }

        assertEquals("an attempt makes exactly one request", failure.message)
        assertEquals(listOf("https://a.example/"), requests)
    }

    @Test
    fun anEmptyHandlerIsRefusedWithoutARequest() {
        val requests = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { requests += it.url; HttpOutcome.Response(200, emptyMap(), ByteArray(0)) },
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            routes.call(service()) { _, _ -> HttpOutcome.Response(200, emptyMap(), ByteArray(0)) }
        }

        assertEquals("an attempt makes exactly one request", failure.message)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aHandlerThatConsumesTheAttemptReturnsItsFinalResult() {
        val requests = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { requests += it.url; HttpOutcome.Response(200, emptyMap(), ByteArray(0)) },
        )

        val result = routes.call(service()) { attempt, root -> attempt.execute(request(root)) }

        assertTrue(result is HttpOutcome.Response)
        assertEquals(listOf("https://a.example/"), requests)
    }

    @Test
    fun aHandlerThatCannotConnectTriesTheOtherAddressAndReturnsTheLastResult() {
        val requests = mutableListOf<String>()
        val routes = ServiceRoutes(
            HttpTransport { requests += it.url; HttpOutcome.NotConnected("network") },
        )

        val result = routes.call(service()) { attempt, root -> attempt.execute(request(root)) }

        assertTrue(result is HttpOutcome.NotConnected)
        assertEquals(listOf("https://a.example/", "https://b.example/"), requests)
    }

    @Test
    fun onlyANoConnectionFailureTriesTheOtherAddress() {
        for (outcome in listOf(HttpOutcome.Timeout, HttpOutcome.Failure("network"))) {
            val requests = mutableListOf<String>()
            val routes = ServiceRoutes(HttpTransport { requests += it.url; outcome })

            val result = routes.call(service()) { attempt, root -> attempt.execute(request(root)) }

            assertEquals(outcome, result)
            assertEquals("outcome $outcome", listOf("https://a.example/"), requests)
        }
    }

    @Test
    fun everyOutcomeConsumesTheAttempt() {
        val outcomes = listOf(
            HttpOutcome.Response(200, emptyMap(), ByteArray(0)),
            HttpOutcome.Timeout,
            HttpOutcome.NotConnected("network"),
            HttpOutcome.Failure("network"),
        )
        for (outcome in outcomes) {
            var calls = 0
            val routes = ServiceRoutes(HttpTransport { calls++; outcome })
            assertThrows(IllegalStateException::class.java) {
                routes.call(service()) { attempt, _ ->
                    attempt.execute(request())
                    attempt.execute(request())
                }
            }
            assertEquals("outcome $outcome", 1, calls)
        }
    }

    @Test
    fun aTransportFailureConsumesTheAttempt() {
        var calls = 0
        val routes = ServiceRoutes(HttpTransport { calls++; throw RuntimeException("transport broke") })

        assertThrows(IllegalStateException::class.java) {
            routes.call(service()) { attempt, _ ->
                runCatching { attempt.execute(request()) }
                attempt.execute(request())
            }
        }

        assertEquals(1, calls)
    }

    @Test
    fun theGuardMessageCarriesNoRequestDetails() {
        val secretUrl = "https://user:password@a.example/private?token=secret"
        val routes = ServiceRoutes(
            HttpTransport { HttpOutcome.Response(200, emptyMap(), ByteArray(0)) },
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            routes.call(service()) { attempt, _ ->
                attempt.execute(HttpRequest("GET", secretUrl, mapOf("Authorization" to "Bearer secret")))
                attempt.execute(HttpRequest("GET", secretUrl, emptyMap()))
            }
        }

        assertEquals("an attempt makes exactly one request", failure.message)
        assertFalse(failure.message!!.contains("secret"))
        assertFalse(failure.message!!.contains("password"))
        assertFalse(failure.message!!.contains("a.example"))
    }
}
