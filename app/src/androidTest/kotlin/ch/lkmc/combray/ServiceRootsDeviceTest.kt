package ch.lkmc.combray

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.integration.HttpOutcome
import ch.lkmc.combray.core.integration.HttpRequest
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ServiceRoot
import ch.lkmc.combray.core.integration.ServiceRootProblem
import ch.lkmc.combray.core.integration.ServiceUrls
import ch.lkmc.combray.core.integration.UrlConnectionTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android's URI, IDN and URL implementations, not the desktop JVM's. No network is needed. */
@RunWith(AndroidJUnit4::class)
class ServiceRootsDeviceTest {
    @Test
    fun equivalentAddressesKeepTheirNormalizedDestination() {
        for ((written, normalized) in listOf(
            "https://bücher.example.:443/k/api/v1" to "https://xn--bcher-kva.example/k",
            "https://[::1]:443/" to "https://[::1]",
            "http://192.0.2.1.:80/" to "http://192.0.2.1",
        )) {
            val result = ServiceUrls.normalizeRoot(written, Integrations.KARAKEEP_SUFFIXES)
            assertEquals(written, ServiceRoot.Valid(normalized), result)
            assertEquals(ServiceUrls.fingerprint(normalized), ServiceUrls.fingerprint((result as ServiceRoot.Valid).root))
        }
    }

    @Test
    fun explicitPortsMustBeUsableBeforeAServiceIsConfigured() {
        for (host in listOf("example.invalid", "[::1]")) {
            for (port in listOf("0", "65536", "-1", "+80", "")) {
                val input = "https://$host:$port/"
                assertEquals(
                    input,
                    ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL),
                    ServiceUrls.normalizeRoot(input, Integrations.KARAKEEP_SUFFIXES),
                )
            }
        }
    }

    @Test
    fun invalidRequestsFailBeforeOpeningAConnection() {
        val transport = UrlConnectionTransport()
        for (url in listOf(
            "http:/relative", "https:relative", "ftp:/relative", "file:///synthetic-upload",
            "http://example.invalid:0/", "https://example.invalid:65536/",
            "http://example.invalid:/", "http://[::1]:/", "http://[::1]:-1/", "http://example.invalid:+80/",
        )) {
            assertEquals(url, HttpOutcome.Failure("bad_url"), transport.execute(HttpRequest("GET", url, emptyMap())))
        }
    }
}
