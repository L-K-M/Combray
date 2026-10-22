package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.browser.BrowserCoordinator
import ch.lkmc.combray.core.browser.CaptureSink
import ch.lkmc.combray.core.browser.CapturedPage
import ch.lkmc.combray.core.browser.FakeEngine
import ch.lkmc.combray.core.browser.Notice
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.ExclusionEntity
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3: opt-in capture, exclusions, forgetting, ordering and budgets against a fake Hister. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HisterIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var integrations: Integrations
    private val server = FakeHister()
    private val secrets = MemorySecrets()
    private var now = 1_760_000_000_000L
    private var ids = 0

    private val hister get() = integrations.hister

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        // Background kicks never run here: only drain() sends, so queue states are deterministic.
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        integrations = newIntegrations()
        runBlocking { assertEquals(Configured.Saved, hister.configure(server.root, FakeHister.TOKEN)) }
    }

    private fun newIntegrations() = Integrations(
        database = database,
        secrets = secrets,
        transport = UrlConnectionTransport(timeoutMs = 2_000),
        clock = { now },
        newId = { "job${ids++}" },
        scope = scope,
        scheduler = {},
    )

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.close()
    }

    private fun enable() = runBlocking { hister.setCaptureEnabled(true) }

    private fun wants(url: String) = runBlocking { hister.wantsCapture(url) }

    private fun capture(url: String, text: String, generation: Long? = wants(url)) = runBlocking {
        hister.captured(CapturedPage("tab", "visit", url, "Title", text, "sha-$text", false, generation ?: -1))
    }

    private fun drain() = runBlocking { integrations.dispatcher.drain() }

    private fun jobs(): List<OutboxJobEntity> = (0 until ids).mapNotNull { runBlocking { database.integrations().job("job$it") } }

    @Test
    fun captureIsOptInAndSendsRenderedText() {
        assertNull(wants("https://a.example/"))
        enable()
        assertNotNull(wants("https://a.example/"))
        capture("https://a.example/", "RENDERED-PHRASE only in the DOM")
        drain()
        assertTrue(server.added.single().contains("RENDERED-PHRASE only in the DOM"))
    }

    @Test
    fun unchangedContentIsQueuedOnceAndNewerContentReplacesUnsentOlder() {
        enable()
        capture("https://a.example/#top", "v1")
        capture("https://a.example/", "v1")
        assertEquals(1, jobs().size)
        capture("https://a.example/?utm_source=feed", "v2")
        assertEquals(listOf(JobState.SUPERSEDED, JobState.PENDING), jobs().map { it.state })
        drain()
        assertEquals(1, server.added.size)
        assertTrue(server.added.single().contains("\"text\":\"v2\""))
    }

    @Test
    fun anExclusionCancelsUnsentCapturesAndStopsNewOnes() {
        enable()
        capture("https://bank.example/account", "balance")
        runBlocking { hister.addExclusion("bank.example", includeSubdomains = true) }
        assertEquals(JobState.CANCELLED, jobs().single().state)
        assertNull(wants("https://www.bank.example/"))
        assertNotNull(wants("https://notbank.example/"))
        drain()
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun aCaptureMadeUnderAnOlderPolicyIsDropped() {
        enable()
        val generation = wants("https://a.example/")
        runBlocking { hister.addExclusion("other.example", includeSubdomains = false) }
        capture("https://a.example/", "text", generation)
        assertTrue(jobs().isEmpty())
    }

    @Test
    fun forgettingASiteCancelsQueuedCapturesAndOnesAlreadyClaimed() {
        enable()
        capture("https://a.example/1", "first")
        val claim = runBlocking { integrations.outbox.claim("worker", 60_000) }!!
        capture("https://a.example/2", "second")

        runBlocking { hister.forgetHost("a.example", includeSubdomains = false) }
        assertEquals(JobState.CANCELLED, jobs().single { it.id != claim.job.id }.state)
        // The claimed one is rechecked right before sending and not sent.
        assertEquals(DispatchResult.Cancelled("excluded"), runBlocking { hister.dispatch(claim.job) })
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun forgettingAVisitRollsBackBothHistoryAndCancellationWhenInvalidationFails() = runBlocking {
        enable()
        val url = "https://a.example/article"
        capture(url, "forgotten text")
        var fail = true
        val sink = object : CaptureSink by hister {
            override suspend fun forgetUrl(url: String) {
                hister.forgetUrl(url)
                check(!fail) { "invalidation failed" }
            }
        }
        val browser = browserWithHistory(sink, listOf(url, "https://kept.example/"))
        val visit = browser.recent().first().filterIsInstance<RecentItem.Visit>().single()

        browser.forgetVisit(visit.visitId)
        browser.awaitIdle()

        assertNotNull(database.browser().visit(visit.visitId))
        assertTrue(browser.browserState.value.notice is Notice.Problem)
        assertEquals(JobState.PENDING, jobs().single().state)
        assertTrue(jobs().single().payload.contains("forgotten text"))

        fail = false
        browser.forgetVisit(visit.visitId)
        browser.awaitIdle()
        assertNull(database.browser().visit(visit.visitId))
        assertEquals(JobState.CANCELLED, jobs().single().state)
        assertEquals("", jobs().single().payload)

        // New integration objects have no in-memory forget tombstones.
        newIntegrations().dispatcher.drain()
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun forgettingASiteRollsBackBothStoresAndARetrySurvivesRestart() = runBlocking {
        enable()
        val site = "https://a.example/article"
        val subdomain = "https://www.a.example/other"
        val lookalike = "https://nota.example/"
        listOf(site, subdomain, lookalike).forEach { capture(it, "text for $it") }
        var fail = true
        val sink = object : CaptureSink by hister {
            override suspend fun forgetHost(host: String, includeSubdomains: Boolean) {
                hister.forgetHost(host, includeSubdomains)
                check(!fail) { "invalidation failed" }
            }
        }
        val browser = browserWithHistory(sink, listOf(site, subdomain, lookalike, "https://a.example/open"))
        val visits = browser.recent().first().filterIsInstance<RecentItem.Visit>()

        browser.forgetSite("a.example", includeSubdomains = true)
        browser.awaitIdle()

        assertTrue(visits.all { database.browser().visit(it.visitId) != null })
        assertTrue(browser.browserState.value.notice is Notice.Problem)
        assertTrue(jobs().all { it.state == JobState.PENDING && it.payload.isNotEmpty() })

        fail = false
        browser.forgetSite("a.example", includeSubdomains = true)
        browser.awaitIdle()
        assertEquals(listOf(lookalike), browser.recent().first().filterIsInstance<RecentItem.Visit>().map { it.url })
        assertEquals("https://a.example/open", browser.browserState.value.activeTab?.url)
        assertEquals(listOf(JobState.CANCELLED, JobState.CANCELLED, JobState.PENDING), jobs().map { it.state })
        assertTrue(jobs().take(2).all { it.payload.isEmpty() })

        newIntegrations().dispatcher.drain()
        assertEquals(1, server.added.size)
        assertTrue(server.added.single().contains(lookalike))
    }

    private suspend fun browserWithHistory(sink: CaptureSink, urls: List<String>): BrowserCoordinator {
        val engine = FakeEngine()
        // Share the harness Job for cleanup; awaitIdle fences coordinator work on Default.
        val browser = BrowserCoordinator(
            database, engine, CoroutineScope(scope.coroutineContext + Dispatchers.Default),
            clock = { now }, capture = sink,
        )
        browser.start()
        engine.emit(EngineEvent.EngineReady(720, 1280))
        browser.awaitIdle()
        browser.openUrl(urls.first(), Placement.CURRENT_TAB)
        browser.awaitIdle()
        urls.forEachIndexed { index, url ->
            engine.emit(EngineEvent.HistoryChanged(engine.view(1), HistoryKind.DOCUMENT_COMMITTED, index.toLong(), url, index, index + 1))
            browser.awaitIdle()
        }
        return browser
    }

    @Test
    fun pausingCaptureHoldsUnsentCaptures() {
        enable()
        capture("https://a.example/", "text")
        runBlocking { hister.setCaptureEnabled(false) }
        drain()
        assertTrue(server.added.isEmpty())
        assertEquals(JobState.PENDING, jobs().single().state)
        enable()
        drain()
        assertEquals(1, server.added.size)
    }

    @Test
    fun unsentCapturesExpireAfterAWeek() {
        enable()
        capture("https://a.example/", "old text")
        runBlocking { hister.setCaptureEnabled(false) }
        now += 8L * 24 * 60 * 60 * 1000
        drain()
        assertEquals(JobState.EXPIRED, jobs().single().state)
        enable()
        drain()
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun anAbandonedCaptureExpiresInsteadOfBeingSentAfterRestart() = runBlocking {
        enable()
        capture("https://a.example/", "old text")
        val abandoned = integrations.outbox.claim("dead-worker", LEASE_MS)!!

        now += 8 * DAY_MS
        drain()

        val expired = jobs().single()
        assertEquals(JobState.EXPIRED, expired.state)
        assertEquals("", expired.payload)
        assertEquals(0, expired.payloadBytes)
        assertNull(expired.leaseOwner)
        assertNull(expired.leaseUntil)
        assertTrue(server.added.isEmpty())
        assertFalse(integrations.outbox.finish(abandoned, DispatchResult.Done(null)))
    }

    @Test
    fun aCaptureThatAgesDuringADrainIsNeverDispatched() = runBlocking {
        enable()
        capture("https://a.example/first", "fresh text")
        capture("https://a.example/second", "text about to expire")
        val second = jobs().last()
        database.integrations().updateJob(second.copy(createdAt = now - 7 * DAY_MS + 1_000))

        val dispatcher = OutboxDispatcher(
            integrations.outbox,
            mapOf(JobKind.HISTER_ADD to JobHandler { job ->
                // The first request takes the second body past its retention deadline.
                now += 2_000
                hister.dispatch(job)
            }),
            onDone = { _, _ -> },
            scheduler = {},
        )
        dispatcher.drain()

        assertEquals(JobState.EXPIRED, jobs().last().state)
        assertEquals("", jobs().last().payload)
        assertEquals(1, server.added.size)
        assertTrue(server.added.single().contains("fresh text"))
    }

    @Test
    fun expiryDoesNotInvalidateAnOldCaptureWithALiveLease() = runBlocking {
        enable()
        capture("https://a.example/", "in flight")
        now += 7 * DAY_MS - 1_000
        val live = integrations.outbox.claim("live-worker", LEASE_MS)!!
        now += 2_000

        integrations.outbox.prune()

        assertEquals(JobState.RUNNING, jobs().single().state)
        assertTrue(jobs().single().payload.isNotEmpty())
        assertNull(integrations.outbox.claim("other-worker", LEASE_MS))
        assertTrue(integrations.outbox.finish(live, DispatchResult.Done(null)))
    }

    @Test
    fun theContentBudgetPausesCapture() {
        enable()
        val text = "x".repeat(256 * 1024)
        repeat(90) { capture("https://a.example/$it", "$text$it") }
        assertTrue(runBlocking { hister.captureBudgetReached.first() })
        assertTrue(runBlocking { database.integrations().queuedCaptureBytes() } <= HisterIntegration.MAX_QUEUED_CAPTURE_BYTES)
    }

    @Test
    fun theBudgetWarningClearsWhenTheQueueDrainsWithoutNewCaptures() {
        enable()
        val text = "x".repeat(256 * 1024)
        repeat(90) { capture("https://a.example/$it", "$text$it") }
        assertTrue(runBlocking { hister.captureBudgetReached.first() })
        // The queue drains by expiring: no new capture arrives to clear the flag.
        now += 8L * 24 * 60 * 60 * 1000
        drain()
        assertFalse(runBlocking { hister.captureBudgetReached.first() })
    }

    @Test
    fun switchingToAnotherServerCancelsUnsentCaptures() {
        enable()
        capture("https://a.example/", "text")
        FakeHister().use { other ->
            runBlocking { hister.configure(other.root, FakeHister.TOKEN) }
            assertEquals(JobState.CANCELLED, jobs().single().state)
        }
    }

    @Test
    fun theServicesOwnPagesAndCredentialBearingAddressesAreNeverCaptured() {
        enable()
        assertNull(wants("${server.root}/settings"))
        assertNull(wants("https://kagi.com/search?q=x&token=secret"))
        assertNull(wants("https://kagi.com/search?q=private+question"))
        assertNotNull(wants("https://notkagi.com/"))
        assertNull(wants("http://127.0.0.1:1/other"))
    }

    @Test
    fun encodedCredentialNamesAreNeverCaptured() {
        enable()
        // A generation from an ordinary URL exercises URL policy, not staleness.
        val generation = wants("https://a.example/")
        assertNotNull(generation)
        for (url in listOf(
            "https://a.example/?%74oken=abc",
            "https://a.example/?access%5Ftoken=abc",
            "https://a.example/#%73essionid=abc",
            "https://a.example/#access%5Ftoken=abc",
        )) {
            assertNull(wants(url))
            capture(url, "page text", generation)
        }
        drain()
        assertTrue(jobs().isEmpty())
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun theAlternateAddressesHostIsNeverCapturedEither() {
        runBlocking { assertEquals(Configured.Saved, hister.configure(server.root, FakeHister.TOKEN, "https://hister.tailnet.ts.net")) }
        enable()
        assertNull(wants("https://hister.tailnet.ts.net/settings"))
        assertNotNull(wants("https://a.example/"))
    }

    @Test
    fun searchFallsBackToTheAlternateWhenTheMainAddressCannotBeReached() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        runBlocking { assertEquals(Configured.Saved, hister.configure("http://127.0.0.1:$port/h", FakeHister.TOKEN, server.root)) }
        server.searchBody = """{"documents":[{"url":"https://a.example/","title":"A"}],"history":[],"total":1}"""
        val result = runBlocking { hister.search("phrase") } as HisterSearch.Results
        assertEquals("https://a.example/", result.hits.single().url)
    }

    @Test
    fun searchUsesTheConfiguredServerAndToken() {
        server.searchBody = """{"documents":[{"url":"https://a.example/","title":"A"}],"history":[],"total":1}"""
        val result = runBlocking { hister.search("phrase") } as HisterSearch.Results
        assertEquals("https://a.example/", result.hits.single().url)
    }

    @Test
    fun addExclusionAcceptsPastedSiteAddressesAndRejectsNonSites() {
        enable()
        assertTrue(runBlocking { hister.addExclusion("https://Bank.Example:443/login?x=1", includeSubdomains = false) })
        assertEquals(
            listOf(ExclusionEntity("bank.example", includeSubdomains = false, createdAt = now)),
            runBlocking { hister.exclusions.first() },
        )
        assertNull(wants("https://bank.example/login"))
        assertNotNull(wants("https://www.bank.example/login"))

        // A pasted wildcard covers subdomains even with the toggle off; each spelling on its own.
        for (wildcard in listOf("*.bank.example", ".bank.example")) {
            runBlocking { hister.removeExclusion("bank.example") }
            assertNotNull(wants("https://www.bank.example/login"))
            assertTrue(runBlocking { hister.addExclusion(wildcard, includeSubdomains = false) })
            assertEquals(
                listOf(ExclusionEntity("bank.example", includeSubdomains = true, createdAt = now)),
                runBlocking { hister.exclusions.first() },
            )
            assertNull(wants("https://www.bank.example/login"))
        }

        for (invalid in listOf("", "*", "a b", "exa*mple.com")) {
            assertFalse(runBlocking { hister.addExclusion(invalid, includeSubdomains = false) })
        }
        assertEquals(1, runBlocking { hister.exclusions.first() }.size)
    }

    @Test
    fun anUnchangedPageIsCapturedAgainAfterAPolicyChange() {
        enable()
        capture("https://a.example/", "same")
        capture("https://a.example/", "same")
        assertEquals(1, jobs().size)

        // Excluding and re-including clears the content-hash memory: unchanged text is queued again.
        runBlocking { hister.addExclusion("a.example", includeSubdomains = false) }
        runBlocking { hister.removeExclusion("a.example") }
        capture("https://a.example/", "same")
        assertEquals(listOf(JobState.CANCELLED, JobState.PENDING), jobs().map { it.state })
        drain()
        assertEquals(1, server.added.size)

        // Reconfiguring the same server (new token) also clears it: the page goes out again.
        capture("https://a.example/", "same")
        assertEquals(2, jobs().size)
        runBlocking { assertEquals(Configured.Saved, hister.configure(server.root, FakeHister.TOKEN)) }
        capture("https://a.example/", "same")
        assertEquals(JobState.PENDING, jobs().last().state)
        drain()
        assertEquals(2, server.added.size)
    }

    @Test
    fun checkAndConfigureReportAMissingToken() {
        FakeHister().use { other ->
            assertEquals(Configured.MissingCredential, runBlocking { hister.configure(other.root, null) })
        }
        secrets.delete(SecretName.HISTER_TOKEN)
        assertEquals(ConnectionCheck.CredentialMissing, runBlocking { hister.check() })
    }

    private class MemorySecrets : SecretStore {
        private val values = HashMap<SecretName, String>()

        override fun read(name: SecretName) = values[name]

        override fun write(name: SecretName, value: String) {
            values[name] = value
        }

        override fun delete(name: SecretName) {
            values.remove(name)
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val LEASE_MS = 60_000L
    }
}
