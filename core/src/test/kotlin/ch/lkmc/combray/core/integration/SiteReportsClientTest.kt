package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64

class SiteReportsClientTest {
    private val server = FakeReports()
    private val client = SiteReportsClient(UrlConnectionTransport(timeoutMs = 500))

    @After
    fun tearDown() = server.close()

    private fun submit(payload: ReportPayload = payload(), png: ByteArray? = PNG, token: String = FakeReports.TOKEN) =
        client.submit(server.root, token, SiteReportsClient.body(payload, png))

    /** The contract: the phone's body for the golden payload is the service's golden request. */
    @Test
    fun theBodyIsTheServicesGoldenRequest() {
        val path = System.getProperty(GOLDEN_PROPERTY) ?: error("$GOLDEN_PROPERTY is not set (core/build.gradle.kts)")
        val golden = Json.parseToJsonElement(File(path).readText()).jsonObject
        val png = Base64.getDecoder().decode(golden.getValue("screenshot").jsonObject.getValue("data").jsonPrimitive.content)
        val payload = ReportPayload(
            reportId = "6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d",
            createdAt = Instant.parse("2026-11-04T09:12:44.123Z").toEpochMilli(),
            url = "https://example.org/article?x=1#part",
            urlEdited = false,
            title = "Example article",
            comment = "The menu covers the first paragraph.",
            display = ReportDisplay.PAGE,
            loadFailed = false,
            crashed = false,
            environment = ReportEnvironment("0.1.0", 1, "debug", "0.7.0", "6fbfc3d70ad83df5443445d3ff4c43871aae41dd", "15", 35),
            zoomPercent = 100,
            density = 2.625f,
            screenshot = ScreenshotRef(bytes = png.size, sha256 = sha256(png), width = 3, height = 2, scaled = false),
        )

        val body = Json.parseToJsonElement(SiteReportsClient.body(payload, png).toString(Charsets.UTF_8))

        assertEquals(golden, body)
    }

    @Test
    fun aReportWithoutScreenshotSaysWhyAndLeavesUnknownDetailsOut() {
        val payload = payload(withScreenshot = false).copy(
            title = null, comment = null, display = ReportDisplay.READER, zoomPercent = null, density = null,
            omitted = ScreenshotOmission.READER_VIEW,
        )
        val body = Json.parseToJsonElement(SiteReportsClient.body(payload, null).toString(Charsets.UTF_8)).jsonObject

        assertEquals(JsonNull, body["screenshot"])
        assertEquals("reader_view", body.getValue("screenshot_omitted").jsonPrimitive.content)
        assertEquals(JsonNull, body["comment"])
        assertEquals(JsonNull, body.getValue("page").jsonObject["title"])
        assertEquals("reader", body.getValue("page").jsonObject.getValue("display").jsonPrimitive.content)
        val client = body.getValue("client").jsonObject
        assertFalse("page_zoom_percent" in client || "density" in client)
    }

    @Test
    fun everyOmissionIsOneTheServiceKnows() {
        // services/site-reports/README.md, POST /v1/reports.
        val service = setOf("user", "reader_view", "not_shown", "page_changed", "copy_failed", "too_large")
        assertEquals(service, ScreenshotOmission.entries.map { it.wire }.toSet())
        assertEquals(setOf("page", "reader"), ReportDisplay.entries.map { it.wire }.toSet())
    }

    @Test
    fun aRepeatIsAcknowledgedAndADifferentReportUnderTheSameIdIsRejected() {
        assertEquals(DispatchResult.Done(ID, null), submit())
        assertEquals(DispatchResult.Done(ID, "existing"), submit())
        assertEquals(DispatchResult.Rejected("id_conflict"), submit(payload().copy(comment = "Another report")))
        assertArrayEquals(PNG, server.screenshots[ID])
        assertEquals(1, server.reports.size)
    }

    @Test
    fun aLostAnswerMayHaveArrivedAndTheRetryFindsTheStoredReport() {
        server.fault = FakeReports.Fault.STORE_THEN_HANG
        server.faultCount = 1

        val lost = submit()

        assertEquals(DispatchResult.Retry("timeout", delivery = Delivery.MAY_HAVE_ARRIVED), lost)
        assertEquals(DispatchResult.Done(ID, "existing"), submit())
        assertEquals(1, server.reports.size)
    }

    @Test
    fun anUnreachableServiceWasNotSentAnything() {
        server.close()
        assertEquals(DispatchResult.Retry("network", delivery = Delivery.NOT_SENT), submit())
    }

    @Test
    fun answersMapAsTheServiceDocumentsThem() {
        val cases = mapOf(
            201 to DispatchResult.Done(ID, null),
            200 to DispatchResult.Done(ID, "existing"),
            302 to DispatchResult.Rejected("redirect"),
            400 to DispatchResult.Rejected("http_400"),
            401 to DispatchResult.AuthRequired("http_401"),
            403 to DispatchResult.AuthRequired("http_403"),
            408 to DispatchResult.Retry("http_408", 30_000),
            409 to DispatchResult.Rejected("id_conflict"),
            413 to DispatchResult.Rejected("too_large"),
            415 to DispatchResult.Rejected("http_415"),
            429 to DispatchResult.Retry("http_429", 30_000),
            500 to DispatchResult.Retry("http_500", 30_000),
            503 to DispatchResult.Retry("http_503", 30_000),
            507 to DispatchResult.Retry("http_507", 30_000),
        )
        for ((status, expected) in cases) {
            val answering = SiteReportsClient(
                HttpTransport {
                    HttpOutcome.Response(status, mapOf("Retry-After" to "30"), """{"id":"$ID","status":"open"}""".toByteArray())
                },
            )
            assertEquals("HTTP $status", expected, answering.submit("https://reports.test", FakeReports.TOKEN, ByteArray(0)))
        }
        val malformed = SiteReportsClient(HttpTransport { HttpOutcome.Response(201, emptyMap(), "stored".toByteArray()) })
        assertEquals(DispatchResult.Done(null, "malformed_response"), malformed.submit("https://reports.test", FakeReports.TOKEN, ByteArray(0)))
    }

    @Test
    fun theServicesFaultsMapToRetriesAndPauses() {
        val faults = mapOf(
            FakeReports.Fault.TOO_MANY_REQUESTS to DispatchResult.Retry("http_429", 120_000),
            FakeReports.Fault.SERVER_ERROR to DispatchResult.Retry("http_507"),
            FakeReports.Fault.UNAUTHORIZED to DispatchResult.AuthRequired("http_401"),
            FakeReports.Fault.CONFLICT to DispatchResult.Rejected("id_conflict"),
            FakeReports.Fault.TOO_LARGE to DispatchResult.Rejected("too_large"),
        )
        for ((fault, expected) in faults) {
            server.fault = fault
            server.faultCount = 1
            assertEquals(fault.name, expected, submit())
        }
        // An agent token reads reports; it cannot send them.
        assertEquals(DispatchResult.AuthRequired("http_403"), submit(token = FakeReports.AGENT_TOKEN))
        assertTrue(server.reports.isEmpty())
    }

    @Test
    fun theTokenTravelsOnlyInTheAuthorizationHeader() {
        submit()
        client.checkConnection(server.root, FakeReports.TOKEN)

        assertEquals(listOf("POST /r/v1/reports", "GET /r/v1/whoami"), server.requests.map { "${it.method} ${it.target}" })
        for (request in server.requests) {
            assertEquals("Bearer ${FakeReports.TOKEN}", request.authorization)
            assertFalse(request.target.contains(FakeReports.TOKEN))
        }
        assertTrue("content-type" in server.requests.first().headerNames)
        assertFalse(SiteReportsClient.body(payload(), PNG).toString(Charsets.UTF_8).contains(FakeReports.TOKEN))
    }

    @Test
    fun theConnectionCheckNeedsAReporterTokenOfThisService() {
        assertEquals(ConnectionCheck.Connected(null), client.checkConnection(server.root, FakeReports.TOKEN))
        assertEquals(ConnectionCheck.Unauthorized, client.checkConnection(server.root, FakeReports.AGENT_TOKEN))
        assertEquals(ConnectionCheck.Unauthorized, client.checkConnection(server.root, "wrong"))
        assertEquals(ConnectionCheck.WrongAddress(404), client.checkConnection("${server.root}/elsewhere", FakeReports.TOKEN))
        server.whoamiBody = """{"service":"another-service","role":"reporter"}"""
        assertEquals(ConnectionCheck.WrongAddress(200), client.checkConnection(server.root, FakeReports.TOKEN))
        server.whoamiBody = "<html>"
        assertEquals(ConnectionCheck.WrongAddress(200), client.checkConnection(server.root, FakeReports.TOKEN))

        // Read-only: nothing was created.
        assertTrue(server.requests.all { it.method == "GET" })
        assertTrue(server.reports.isEmpty())

        server.close()
        assertEquals(ConnectionCheck.Unreachable("network"), client.checkConnection(server.root, FakeReports.TOKEN))
    }

    @Test
    fun aBodyWithoutItsScreenshotBytesIsAProgrammingError() {
        val failure = runCatching { SiteReportsClient.body(payload(), null) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(runCatching { SiteReportsClient.body(payload(withScreenshot = false), null) }.exceptionOrNull())
    }

    private fun payload(withScreenshot: Boolean = true) = ReportPayload(
        reportId = ID,
        createdAt = 1_762_247_564_123L,
        url = "https://example.org/a?b=1",
        urlEdited = false,
        title = "A page",
        comment = "Broken layout",
        display = ReportDisplay.PAGE,
        loadFailed = false,
        crashed = false,
        environment = ENVIRONMENT,
        zoomPercent = 100,
        density = 2f,
        screenshot = if (withScreenshot) ScreenshotRef(PNG.size, sha256(PNG), 3, 2, scaled = false) else null,
        omitted = if (withScreenshot) null else ScreenshotOmission.USER,
    )

    private companion object {
        const val GOLDEN_PROPERTY = "combray.siteReports.golden"
        const val ID = "6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d"
        val ENVIRONMENT = FakeReports.ENVIRONMENT
        val PNG = FakeReports.PNG

        fun sha256(bytes: ByteArray) = FakeReports.sha256(bytes)
    }
}
