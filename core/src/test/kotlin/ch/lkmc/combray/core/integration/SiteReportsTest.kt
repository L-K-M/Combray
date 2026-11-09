package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Report this site end to end against a local fake report service: durable submission (file
 * first, then the job), delivery of exactly the stored screenshot, the outbox's parking, pausing,
 * waiting and restart behaviour, and the file sweep (docs/IMPLEMENTATION_PLAN.md section 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SiteReportsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule val folder = TemporaryFolder()
    @get:Rule val unreachableRoots = UnreachableRoots()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var integrations: Integrations
    private val server = FakeReports()
    private val secrets = MemorySecrets()

    /** Like a phone away from home: no request connects, so nothing is sent. */
    @Volatile private var offline = false
    @Volatile private var now = 1_762_247_564_123L
    private val ids = AtomicInteger()

    private val reportDirectory get() = File(folder.root, "reports")
    private val reports get() = integrations.reports

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        // Kicks drain in the background, as in the app; assertions follow an explicit drain(),
        // which waits for any drain in progress (one at a time per process).
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        integrations = newIntegrations(database, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.close()
    }

    private fun newIntegrations(database: CombrayDatabase, scope: CoroutineScope) = Integrations(
        database = database,
        secrets = secrets,
        transport = UrlConnectionTransport(timeoutMs = 500, connectTimeoutMs = 500).let { http ->
            HttpTransport { request -> if (offline) HttpOutcome.NotConnected("network") else http.execute(request) }
        },
        clock = { now },
        newId = { "job${ids.getAndIncrement()}" },
        scope = scope,
        scheduler = {},
        reportDirectory = reportDirectory,
        reportEnvironment = FakeReports.ENVIRONMENT,
    )

    private fun configure(root: String = server.root, token: String? = FakeReports.TOKEN, alternate: String? = null) =
        runBlocking { reports.configure(root, token, alternate) }

    private fun draft(url: String = URL, withScreenshot: Boolean = true) = ReportDraft(
        id = UUID.randomUUID().toString(),
        createdAt = now,
        url = url,
        urlEdited = false,
        title = TITLE,
        comment = COMMENT,
        display = ReportDisplay.PAGE,
        loadFailed = false,
        crashed = false,
        zoomPercent = 100,
        density = 2.625f,
        screenshot = if (withScreenshot) EncodedScreenshot(FakeReports.PNG, 3, 2, scaled = false) else null,
        omitted = if (withScreenshot) null else ScreenshotOmission.USER,
    )

    private fun submit(draft: ReportDraft) = runBlocking { reports.submit(draft) }

    /** Waits for every kick and user action launched so far (a drain does not launch more). */
    private fun settle() = runBlocking { scope.coroutineContext.job.children.toList().joinAll() }

    private fun drain() {
        settle()
        runBlocking { integrations.dispatcher.drain() }
    }

    private fun jobs(): List<OutboxJobEntity> = (0 until ids.get()).mapNotNull { runBlocking { database.integrations().job("job$it") } }

    private fun job(report: ReportDraft) = jobs().single { it.dedupeKey == report.id }

    private fun files() = reportDirectory.list().orEmpty().sorted()

    private fun file(report: ReportDraft) = File(reportDirectory, "${report.id}.png")

    private fun posts() = server.requests.filter { it.method == "POST" }

    /** Makes every waiting job due, past any backoff. */
    private fun makeDue() {
        settle()
        now = maxOf(now, jobs().maxOfOrNull { it.nextAttemptAt } ?: now)
    }

    @Test
    fun aReportIsStoredAsAFileAndOneJobAndEachReportIsItsOwnJob() {
        configure()
        offline = true
        val first = draft()
        val second = draft()
        val withoutScreenshot = draft(withScreenshot = false)

        assertEquals(ReportRequest.Queued, submit(first))
        assertEquals(ReportRequest.Queued, submit(second))
        assertEquals(ReportRequest.Queued, submit(withoutScreenshot))
        settle()

        // Two reports of one address are two reports; one without a screenshot has no file.
        assertEquals(listOf(first, second).map { "${it.id}.png" }.sorted(), files())
        assertArrayEquals(FakeReports.PNG, file(first).readBytes())
        assertEquals(listOf(first.id, second.id, withoutScreenshot.id), jobs().map { it.dedupeKey })
        assertTrue(jobs().all { it.kind == JobKind.SITE_REPORT && it.service == ServiceKind.SITE_REPORTS && it.state.isActive })
        assertEquals(URL, job(first).url)
        assertEquals(URL, integrations.jobUrl(job(first)))

        // A repeated tap on Send does not queue the same report again.
        assertEquals(ReportRequest.Queued, submit(first))
        assertEquals(3, jobs().size)
    }

    @Test
    fun nothingIsStoredWithoutAServiceOrForAnInvalidDraft() {
        assertEquals(ReportRequest.NotConfigured, submit(draft()))
        configure()
        offline = true
        val invalid = mapOf(
            draft(url = " ") to ReportField.URL,
            draft(url = "https://a.example/\nnext") to ReportField.URL,
            draft(url = "https://a.example/" + "a".repeat(SiteReports.MAX_URL_BYTES)) to ReportField.URL,
            draft().copy(id = "not-a-report-id") to ReportField.ID,
            draft().copy(omitted = ScreenshotOmission.USER) to ReportField.SCREENSHOT,
            draft().copy(screenshot = null) to ReportField.SCREENSHOT,
            draft().copy(screenshot = EncodedScreenshot(FakeReports.PNG, 4, 2, scaled = false)) to ReportField.SCREENSHOT,
            draft().copy(screenshot = EncodedScreenshot("not a png".toByteArray(), 3, 2, scaled = false)) to ReportField.SCREENSHOT,
            draft().copy(comment = "x".repeat(SiteReports.MAX_COMMENT_CHARS + 1)) to ReportField.COMMENT,
            draft().copy(density = Float.NaN) to ReportField.DETAILS,
        )
        for ((report, field) in invalid) assertEquals(field.name, ReportRequest.Invalid(field), submit(report))

        assertTrue(jobs().isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun theTwentyFirstUnsentReportIsRefusedUntilOthersAreSent() {
        configure()
        offline = true
        repeat(SiteReports.MAX_UNSENT_REPORTS) { assertEquals(ReportRequest.Queued, submit(draft())) }
        val extra = draft()

        assertEquals(ReportRequest.TooMany, submit(extra))
        assertFalse(file(extra).exists())
        assertEquals(SiteReports.MAX_UNSENT_REPORTS, files().size)

        offline = false
        makeDue()
        drain()
        assertEquals(SiteReports.MAX_UNSENT_REPORTS, server.reports.size)
        assertEquals(ReportRequest.Queued, submit(extra))
    }

    @Test
    fun aReportThatCannotBeStoredLeavesNothingBehind() {
        configure()
        // The screenshot cannot be written: the directory's place is taken.
        reportDirectory.writeText("not a directory")
        assertEquals(ReportRequest.NotStored, submit(draft()))
        assertTrue(jobs().isEmpty())
        reportDirectory.delete()

        // The screenshot is written, then the job cannot be stored: the file goes again.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_jobs BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )
        assertEquals(ReportRequest.NotStored, submit(draft()))
        assertTrue(jobs().isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun deliverySendsTheStoredScreenshotAndThenForgetsTheReport() {
        configure()
        val report = draft().copy(title = "A\u0007title ", comment = "first\r\nsecond\u000c")
        assertEquals(ReportRequest.Queued, submit(report))
        drain()

        val job = job(report)
        assertEquals(JobState.SUCCEEDED, job.state)
        assertEquals(report.id, job.remoteId)
        assertArrayEquals(FakeReports.PNG, server.screenshots[report.id])
        val stored = server.reports.getValue(report.id)
        val page = stored.getValue("page").jsonObject
        assertEquals(URL, page.getValue("url").jsonPrimitive.content)
        // A page title is cleaned; a comment keeps its line breaks.
        assertEquals("A title", page.getValue("title").jsonPrimitive.content)
        assertEquals("first\nsecond", stored.getValue("comment").jsonPrimitive.content)
        assertEquals("0.1.0", (stored.getValue("client") as JsonObject).getValue("app_version").jsonPrimitive.content)

        // Delivered: the address, title and comment leave the database, the file the device.
        assertEquals("", job.payload)
        assertNull(integrations.jobUrl(job))
        assertTrue(files().isEmpty())
    }

    @Test
    fun aMissingOrChangedScreenshotIsNeverSent() {
        configure()
        offline = true
        val missing = draft()
        val changed = draft()
        submit(missing)
        submit(changed)
        file(missing).delete()
        file(changed).writeBytes(FakeReports.PNG.copyOf().also { it[it.lastIndex] = 0 })

        offline = false
        makeDue()
        drain()

        assertEquals(JobState.FAILED, job(missing).state)
        assertEquals("screenshot_missing", job(missing).error)
        assertEquals(JobState.FAILED, job(changed).state)
        assertEquals("screenshot_corrupt", job(changed).error)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun aLostAnswerIsRetriedWithoutStoringTheReportTwice() {
        configure()
        server.fault = FakeReports.Fault.STORE_THEN_HANG
        server.faultCount = 1
        val report = draft()
        submit(report)
        drain()
        assertEquals(JobState.RETRY_WAIT, job(report).state)
        assertTrue(file(report).exists())

        now += 10 * 60_000
        drain()

        assertEquals(JobState.SUCCEEDED, job(report).state)
        assertEquals(2, posts().size)
        assertEquals(1, server.reports.size)
        assertTrue(files().isEmpty())
    }

    @Test
    fun aNewMainAddressParksReportsUntilTheUserSendsThemThere() {
        configure()
        offline = true
        val report = draft()
        submit(report)

        FakeReports().use { other ->
            assertEquals(Configured.Saved, configure(root = other.root))
            offline = false
            assertEquals(JobState.DESTINATION_CHANGED, job(report).state)
            now += DAY_MS
            drain()
            assertEquals(JobState.DESTINATION_CHANGED, job(report).state)
            assertTrue(file(report).exists())
            assertTrue(other.requests.isEmpty())

            runBlocking { integrations.sendParkedToCurrent(ServiceKind.SITE_REPORTS).join() }
            drain()

            assertEquals(JobState.SUCCEEDED, job(report).state)
            assertArrayEquals(FakeReports.PNG, other.screenshots[report.id])
            assertTrue(files().isEmpty())
        }
        assertTrue(posts().isEmpty())
    }

    @Test
    fun discardingOrRemovingDeletesTheScreenshots() {
        configure()
        offline = true
        val parked = draft()
        submit(parked)
        FakeReports().use { other -> configure(root = other.root) }
        // No drain may run after the discard: it alone must drop the report's contents.
        settle()
        runBlocking { integrations.discardParked(ServiceKind.SITE_REPORTS).join() }
        assertEquals(JobState.CANCELLED, job(parked).state)
        assertEquals("", job(parked).payload)
        assertTrue(files().isEmpty())

        val discarded = draft()
        submit(discarded)
        // A job being sent cannot be discarded: wait for the kick's attempt to end.
        settle()
        runBlocking { integrations.discard(job(discarded).id).join() }
        assertEquals(JobState.CANCELLED, job(discarded).state)
        assertTrue(files().isEmpty())

        val removed = draft()
        submit(removed)
        runBlocking { reports.remove() }
        assertEquals(JobState.CANCELLED, job(removed).state)
        assertTrue(files().isEmpty())
        assertNull(runBlocking { reports.check() })
    }

    @Test
    fun aNewTokenPausesReportsUntilResumed() {
        configure()
        offline = true
        val report = draft()
        submit(report)
        server.acceptedToken = NEW_TOKEN

        assertEquals(Configured.Saved, configure(token = NEW_TOKEN))
        offline = false
        makeDue()
        drain()
        assertEquals(JobState.AUTH_REQUIRED, job(report).state)
        assertEquals("credential_changed", job(report).error)
        assertTrue(posts().isEmpty())

        runBlocking { integrations.resume(ServiceKind.SITE_REPORTS).join() }
        drain()

        assertEquals(JobState.SUCCEEDED, job(report).state)
        assertEquals(listOf("Bearer $NEW_TOKEN"), posts().map { it.authorization })
    }

    @Test
    fun aReportThatCannotConnectForAWeekNeedsAttentionAndRetryStartsAnotherWeek() {
        configure(root = unreachableRoots.root("/r"))
        val created = now
        val report = draft()
        submit(report)
        // About 50 drains; the bound turns a regression into a failure, not a hang.
        var drains = 0
        while (job(report).state.let { it == JobState.RETRY_WAIT || it == JobState.PENDING } && drains++ < 1_000) {
            makeDue()
            drain()
        }

        assertEquals(JobState.FAILED, job(report).state)
        assertTrue(
            job(report).error,
            job(report).error in setOf("unreachable_network_for_7_days", "unreachable_timeout_for_7_days"),
        )
        assertTrue(now - created >= 7 * DAY_MS)
        // A failed report keeps its screenshot: Retry can still send it.
        assertTrue(file(report).exists())

        runBlocking { integrations.retry(job(report).id).join() }

        assertEquals(now, job(report).createdAt)
        assertEquals(JobState.RETRY_WAIT, job(report).state)
    }

    @Test
    fun aReportQueuedBeforeProcessDeathIsDeliveredByTheNextProcess() {
        val path = File(folder.root, "process.db").path
        val firstDatabase = Room.databaseBuilder(context, CombrayDatabase::class.java, path).build()
        // The first process never gets to send: its kicks do not run.
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val first = newIntegrations(firstDatabase, firstScope)
        runBlocking { first.reports.configure(server.root, FakeReports.TOKEN) }
        val report = draft()
        assertEquals(ReportRequest.Queued, runBlocking { first.reports.submit(report) })
        // It dies while sending: a claim is left behind that nobody finishes.
        runBlocking { first.outbox.claim("dead-process", OutboxDispatcher.LEASE_MS) }
        firstScope.cancel()
        firstDatabase.close()

        val secondDatabase = Room.databaseBuilder(context, CombrayDatabase::class.java, path).build()
        try {
            val second = newIntegrations(secondDatabase, scope)
            now += OutboxDispatcher.LEASE_MS + 1
            runBlocking { second.dispatcher.drain() }

            val delivered = runBlocking { secondDatabase.integrations().job("job0") }!!
            assertEquals(JobState.SUCCEEDED, delivered.state)
            assertArrayEquals(FakeReports.PNG, server.screenshots[report.id])
            assertTrue(files().isEmpty())
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun theTokenNeverReachesTheQueueTheAddressOrTheLog() {
        configure()
        // A storage failure is logged; it must name no private detail either.
        reportDirectory.writeText("not a directory")
        assertEquals(ReportRequest.NotStored, submit(draft()))
        reportDirectory.delete()
        offline = true
        val report = draft()
        submit(report)
        assertFalse(job(report).payload.contains(FakeReports.TOKEN))

        offline = false
        makeDue()
        drain()

        assertEquals(JobState.SUCCEEDED, job(report).state)
        for (request in server.requests) {
            assertFalse(request.target.contains(FakeReports.TOKEN))
            assertEquals("Bearer ${FakeReports.TOKEN}", request.authorization)
        }
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { it.tag == "CombrayReports" })
        for (private in listOf(FakeReports.TOKEN, URL, TITLE, COMMENT, report.id)) {
            assertTrue(private, logs.none { "${it.msg} ${it.throwable}".contains(private) })
        }
    }

    @Test
    fun theConnectionCheckNeedsAReporterToken() {
        assertNull(runBlocking { reports.check() })
        // An address pasted with the API's version suffix.
        assertEquals(Configured.Saved, configure(root = "${server.root}/v1"))
        assertEquals(server.root, runBlocking { database.integrations().service(ServiceKind.SITE_REPORTS) }!!.baseUrl)
        assertEquals(ConnectionCheck.Connected(null), runBlocking { reports.check() })

        configure(token = FakeReports.AGENT_TOKEN)
        assertEquals(ConnectionCheck.Unauthorized, runBlocking { reports.check() })

        secrets.delete(SecretName.SITE_REPORTS_TOKEN)
        assertEquals(ConnectionCheck.CredentialMissing, runBlocking { reports.check() })
        assertEquals(Configured.MissingCredential, configure(token = null))
    }

    private class MemorySecrets : SecretStore {
        private val values = HashMap<SecretName, String>()

        override fun read(name: SecretName) = synchronized(values) { values[name] }

        override fun write(name: SecretName, value: String) {
            synchronized(values) { values[name] = value }
        }

        override fun delete(name: SecretName) {
            synchronized(values) { values.remove(name) }
        }
    }

    private companion object {
        const val URL = "https://news.example/article?id=7#comments"
        const val TITLE = "Distinctive article title"
        const val COMMENT = "The distinctive menu covers the text."
        const val NEW_TOKEN = "rotated-reporter-token"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
