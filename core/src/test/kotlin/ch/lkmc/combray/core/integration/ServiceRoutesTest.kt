package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One route attempt is one request: a failover must not re-send an ambiguous request. */
class ServiceRoutesTest {
    @Test
    fun aLambdaThatSendsTwiceFailsWithoutASecondRequest() {
        var calls = 0
        val transport = HttpTransport { _ -> calls++; HttpOutcome.NotConnected("network") }
        val routes = ServiceRoutes(transport)
        val service = ServiceEntity(
            ServiceKind.KARAKEEP, "https://a.example", "fp", 1, false, 0,
            alternateUrl = "https://b.example",
        )

        val result = runCatching {
            routes.call(service) { attempt, _ ->
                attempt.execute(HttpRequest("GET", "https://a.example/", emptyMap()))
                attempt.execute(HttpRequest("GET", "https://a.example/", emptyMap()))
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun aFailedConnectionTriesTheNextRootOnce() {
        var calls = 0
        val transport = HttpTransport { _ -> calls++; HttpOutcome.NotConnected("network") }
        val routes = ServiceRoutes(transport)
        val service = ServiceEntity(
            ServiceKind.KARAKEEP, "https://a.example", "fp", 1, false, 0,
            alternateUrl = "https://b.example",
        )

        val result = routes.call(service) { attempt, root -> attempt.execute(HttpRequest("GET", "$root/", emptyMap())) }

        assertEquals(2, calls)
        assertTrue(result is HttpOutcome.NotConnected)
    }
}
