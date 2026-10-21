package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Durable saves end to end against a local fake Karakeep (docs/IMPLEMENTATION_PLAN.md M2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntegrationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var integrations: Integrations
    private val server = FakeKarakeep()
    private val alternateServer = FakeKarakeep()

    /** Every request's address, in order. */
    private val sent = CopyOnWriteArrayList<String>()

    /** Like a phone away from home: no request connects, so nothing is sent. */
    @Volatile private var offline = false
    private val secrets = MemorySecrets()
    private var now = 1_760_000_000_000L
    private var ids = 0
    private var scheduled: Long? = null

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        integrations = Integrations(
            database = database,
            secrets = secrets,
            transport = UrlConnectionTransport(timeoutMs = 500).let { http ->
                HttpTransport { request ->
                    if (offline) return@HttpTransport HttpOutcome.NotConnected("network")
                    sent += request.url
                    http.execute(request)
                }
            },
            clock = { now },
            newId = { "job${ids++}" },
            scope = scope,
            scheduler = { scheduled = it },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.close()
        alternateServer.close()
    }

    private fun configure(root: String = server.root, key: String? = FakeKarakeep.KEY, alternate: String? = null) =
        runBlocking { integrations.configureKarakeep(root, key, alternate) }

    private fun save(url: String = URL, title: String? = "Article") = runBlocking { integrations.save(url, title) }

    private fun drain() = runBlocking { integrations.dispatcher.drain() }

    private fun status(url: String = URL) = runBlocking { integrations.saveStatus(url).first() }

    private fun job(id: String = "job0") = runBlocking { database.integrations().job(id) }!!

    @Test
    fun oneTapSaveIsDurableAndRepeatedTapsMakeOneBookmark() {
        assertEquals(SaveStatus.NotConfigured, status())
        assertEquals(SaveRequest.NotConfigured, save())
        assertEquals(Configured.Saved, configure())

        // The kick may already be sending; a repeated tap never makes a second job.
        assertEquals(SaveRequest.Queued, save())
        assertTrue(save() in setOf(SaveRequest.AlreadyQueued, SaveRequest.Queued))
        drain()
        drain()

        assertEquals(1, server.bookmarks.size)
        assertEquals(SaveStatus.Saved, status())
        assertEquals(SaveStatus.Unsaved, status("https://other.example/"))
    }

    @Test
    fun aLostAnswerAfterTheServerStoredTheLinkIsRetriedWithoutADuplicate() {
        configure()
        server.fault = FakeKarakeep.Fault.STORE_THEN_HANG
        server.faultCount = 1
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        assertEquals(SaveStatus.Pending, status())

        now += 10 * 60_000
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals("bm1", job().remoteId)
        assertEquals(1, server.bookmarks.size)
        assertEquals(SaveStatus.Saved, status())
    }

    @Test
    fun retryAfterIsHonoured() {
        configure()
        server.fault = FakeKarakeep.Fault.TOO_MANY_REQUESTS
        server.faultCount = 1
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertTrue(job().nextAttemptAt >= now + 120_000)
        assertEquals(job().nextAttemptAt, scheduled)

        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        now = job().nextAttemptAt
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
    }

    @Test
    fun anExpiredKeyPausesSavesUntilTheUserResumesThemWithANewKey() {
        configure()
        server.acceptedKey = "rotated"
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.AUTH_REQUIRED, job().state)
        assertEquals(SaveStatus.NeedsAttention("http_401"), status())

        configure(key = "rotated")
        drain()
        assertEquals(JobState.AUTH_REQUIRED, job().state)
        runBlocking { integrations.resume(ServiceKind.KARAKEEP).join() }
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals(1, server.bookmarks.size)
    }

    @Test
    fun switchingToAnotherServerCancelsUnsentSaves() {
        configure()
        server.fault = FakeKarakeep.Fault.SERVER_ERROR
        server.faultCount = 100
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        val requestsBefore = server.requests.size

        FakeKarakeep().use { other ->
            assertEquals(Configured.Saved, configure(root = other.root))
            assertEquals(JobState.CANCELLED, job().state)
            now += 24 * 60 * 60_000
            drain()
            assertTrue(other.requests.isEmpty())
        }
        assertEquals(requestsBefore, server.requests.size)
    }

    @Test
    fun permanentRejectionNeedsTheUserAndCanBeDiscarded() {
        configure()
        server.fault = FakeKarakeep.Fault.BAD_REQUEST
        server.faultCount = 1
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.FAILED, job().state)
        assertEquals(SaveStatus.NeedsAttention("http_400"), status())

        runBlocking { integrations.discard("job0").join() }
        assertEquals(JobState.CANCELLED, job().state)
        assertEquals(SaveStatus.Unsaved, status())
    }

    @Test
    fun transientFailuresStopAfterTenAttempts() {
        configure()
        server.fault = FakeKarakeep.Fault.SERVER_ERROR
        server.faultCount = 100
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        repeat(10) {
            drain()
            now = maxOf(now, job().nextAttemptAt)
        }
        assertEquals(JobState.FAILED, job().state)
        assertEquals("http_503_after_10_attempts", job().error)
    }

    @Test
    fun attemptsThatCannotConnectNeverUseUpTheAttemptLimit() {
        configure(root = unreachableRoot())
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        repeat(15) {
            now = maxOf(now, job().nextAttemptAt)
            drain()
        }
        assertEquals(JobState.RETRY_WAIT, job().state)
        assertEquals(0, job().attempts)
        assertEquals(SaveStatus.Waiting, status())
        // The wait still backs off, up to the six-hour ceiling (minus jitter).
        assertTrue(job().nextAttemptAt - now in (SIX_HOURS_MS * 4 / 5)..SIX_HOURS_MS)
    }

    @Test
    fun aSaveThatCannotConnectForAWeekNeedsAttention() {
        configure(root = unreachableRoot())
        val created = now
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        while (now - created < WEEK_MS) {
            now = maxOf(now, job().nextAttemptAt)
            drain()
            if (job().state != JobState.RETRY_WAIT) break
        }
        now = maxOf(now, job().nextAttemptAt)
        drain()

        assertEquals(JobState.FAILED, job().state)
        assertEquals("unreachable_network_for_7_days", job().error)
        assertTrue(status() is SaveStatus.NeedsAttention)
    }

    @Test
    fun onlyAttemptsThatMayHaveReachedTheServerCountTowardTheLimit() {
        configure()
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        offline = true
        repeat(5) {
            drain()
            now = maxOf(now, job().nextAttemptAt)
        }
        assertEquals(0, job().attempts)

        // Back in reach, but no answer in time: each of these may have created the bookmark.
        offline = false
        server.fault = FakeKarakeep.Fault.STORE_THEN_HANG
        server.faultCount = 100
        repeat(9) {
            drain()
            now = maxOf(now, job().nextAttemptAt)
        }
        assertEquals(JobState.RETRY_WAIT, job().state)
        assertEquals(SaveStatus.Pending, status())
        drain()
        assertEquals(JobState.FAILED, job().state)
        assertEquals("timeout_after_10_attempts", job().error)
    }

    @Test
    fun internalAndCredentialBearingAddressesAreNotSent() {
        configure()
        assertEquals(SaveRequest.NotSaveable, save("about:blank"))
        assertEquals(SaveRequest.NotSaveable, save("data:text/html,x"))
        assertEquals(SaveRequest.Sensitive, save("https://kagi.com/search?q=x&token=secret"))
        assertNull(runBlocking { database.integrations().job("job0") })
    }

    @Test
    fun aWorkerThatLostItsLeaseCannotFinishTheJob() {
        configure()
        val outbox = integrations.outbox
        runBlocking {
            outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false)
            val stale = outbox.claim("worker-1", leaseMs = 1_000)!!
            now += 2_000
            val current = outbox.claim("worker-2", leaseMs = 60_000)!!
            assertEquals(stale.job.fence + 1, current.job.fence)

            assertFalse(outbox.finish(stale, DispatchResult.Rejected("http_400")))
            assertTrue(outbox.finish(current, DispatchResult.Done("bm9")))
        }
        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals("bm9", job().remoteId)
    }

    @Test
    fun newerContentSupersedesUnsentOlderContentAndOneJobPerKeyRunsAtATime() {
        val hister = ServiceEntity(ServiceKind.HISTER, "https://h.example", "fp", 1, captureEnabled = true, updatedAt = now)
        runBlocking { database.integrations().setService(hister) }
        val outbox = integrations.outbox
        runBlocking {
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "v1", supersede = true)
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "v2", supersede = true)
            assertEquals(JobState.SUPERSEDED, database.integrations().job("job0")!!.state)

            val running = outbox.claim("w", 60_000)!!
            assertEquals("v2", running.job.payload)
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "v3", supersede = true)
            // v2 is being sent: v3 waits for it instead of overtaking it.
            assertNull(outbox.claim("w2", 60_000))
            outbox.finish(running, DispatchResult.Done(null))
            assertEquals("v3", outbox.claim("w2", 60_000)!!.job.payload)
        }
    }

    @Test
    fun aStoredKeyIsOnlyReusedForTheServerItWasEnteredFor() {
        assertEquals(Configured.Saved, configure())
        FakeKarakeep().use { other ->
            assertEquals(Configured.MissingCredential, configure(root = other.root, key = null))
        }
        // The failed attempt changed nothing: the same server still reuses its stored key.
        assertEquals(Configured.Saved, configure(key = null))
    }

    @Test
    fun aKeyStoredForAnotherServerIsNeverSentToTheConfiguredOne() {
        configure()
        FakeKarakeep().use { other ->
            // A crash between storing the new server's key and switching the service row leaves
            // the secret bound to a fingerprint the configuration does not have.
            secrets.write(SecretName.KARAKEEP_API_KEY, ServiceUrls.fingerprint(other.root) + "\nother-key")

            assertEquals(SaveRequest.Queued, save())
            drain()
            assertEquals(JobState.AUTH_REQUIRED, job().state)
            assertEquals("credential_missing", job().error)
            assertTrue(server.requests.isEmpty())
            assertTrue(other.requests.isEmpty())
        }
    }

    @Test
    fun checkReportsCredentialMissingWhenTheStoredKeyIsNotUsable() {
        configure()
        secrets.delete(SecretName.KARAKEEP_API_KEY)
        assertEquals(ConnectionCheck.CredentialMissing, runBlocking { integrations.checkKarakeep() })

        // A key bound to another server is not usable for this one either.
        secrets.write(SecretName.KARAKEEP_API_KEY, "other-fingerprint\n${FakeKarakeep.KEY}")
        assertEquals(ConnectionCheck.CredentialMissing, runBlocking { integrations.checkKarakeep() })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun tappingSaveAgainWhileTheKeyIsRejectedReportsNeedsAttention() {
        configure()
        server.acceptedKey = "rotated"
        assertEquals(SaveRequest.Queued, save())
        drain()
        assertEquals(JobState.AUTH_REQUIRED, job().state)
        assertEquals(SaveRequest.NeedsAttention, save())
    }

    @Test
    fun aReconfiguredServerGetsAGenerationNewerThanAnyExistingJob() {
        configure()
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        val earlier = job().generation

        runBlocking { integrations.removeKarakeep() }
        assertEquals(Configured.Saved, configure())
        assertTrue(service().generation > earlier)

        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        assertEquals(service().generation, job("job1").generation)
    }

    @Test
    fun aFailedSendIsSupersededWhenNewerContentWaitsForTheSameKey() {
        val hister = ServiceEntity(ServiceKind.HISTER, "https://h.example", "fp", 1, captureEnabled = true, updatedAt = now)
        runBlocking { database.integrations().setService(hister) }
        val outbox = integrations.outbox
        runBlocking {
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "v1", supersede = true)
            val running = outbox.claim("w", 60_000)!!
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "v2", supersede = true)

            // The in-flight send fails transiently: v2 must go out, never the older v1 after it.
            assertTrue(outbox.finish(running, DispatchResult.Retry("timeout")))
            assertEquals(JobState.SUPERSEDED, database.integrations().job("job0")!!.state)
            assertEquals("superseded", database.integrations().job("job0")!!.error)
            assertEquals(JobState.PENDING, database.integrations().job("job1")!!.state)
        }
    }

    @Test
    fun tappingSaveAgainAfterAPermanentFailureRetriesThatSaveAndClearsTheWarning() {
        configure()
        server.fault = FakeKarakeep.Fault.BAD_REQUEST
        server.faultCount = 1
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.FAILED, job().state)

        assertEquals(SaveRequest.Queued, save())
        drain()
        assertEquals(SaveStatus.Saved, status())
        assertTrue(runBlocking { database.integrations().failedJobs(JobKind.KARAKEEP_SAVE) }.isEmpty())
        assertEquals(1, server.bookmarks.size)
    }

    @Test
    fun aJobQueuedWithAConfigurationReadBeforeAKeyChangeIsStillSent() {
        configure()
        val before = service()
        // Same server, new key: the generation moves on while a caller still holds the old row.
        configure(key = FakeKarakeep.KEY)
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, before, Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals(service().generation, job().generation)
    }

    @Test
    fun aKeyWithALineBreakIsRefusedBeforeItIsStored() {
        assertEquals(Configured.InvalidCredential, configure(key = "abc\ndef"))
        assertNull(runBlocking { database.integrations().service(ServiceKind.KARAKEEP) })
        assertNull(secrets.read(SecretName.KARAKEEP_API_KEY))
    }

    @Test
    fun aHeaderValueWithAControlCharacterFailsWithoutCarryingTheValue() {
        val request = HttpRequest("GET", server.root + "api/v1/bookmarks", mapOf("Authorization" to "Bearer abc\ndef"))
        assertEquals(HttpOutcome.Failure("bad_header"), UrlConnectionTransport(timeoutMs = 500).execute(request))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun savingUnchangedSettingsLeavesQueuedSavesGoing() {
        configure()
        server.fault = FakeKarakeep.Fault.SERVER_ERROR
        server.faultCount = 1
        runBlocking { integrations.outbox.enqueue(JobKind.KARAKEEP_SAVE, service(), Binding.CURRENT, URL, payload(), supersede = false) }
        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        val generation = service().generation

        // Settings saved again as shown: same address, key field left empty.
        assertEquals(Configured.Saved, configure(key = null))
        assertEquals(generation, service().generation)
        assertEquals(JobState.RETRY_WAIT, job().state)
    }

    @Test
    fun aKeyThatCannotBeStoredLeavesTheConfigurationUnchanged() {
        secrets.failWrites = true
        assertEquals(Configured.CredentialNotStored, configure())
        assertNull(runBlocking { database.integrations().service(ServiceKind.KARAKEEP) })
    }

    @Test
    fun aDiscardedCaptureDropsItsPageText() {
        val hister = ServiceEntity(ServiceKind.HISTER, "https://h.example", "fp", 1, captureEnabled = true, updatedAt = now)
        runBlocking {
            database.integrations().setService(hister)
            integrations.outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", "page text", supersede = true)
            integrations.discard("job0").join()
        }
        assertEquals(JobState.CANCELLED, job().state)
        assertEquals("", job().payload)
    }

    @Test
    fun anUnreachableAddressFallsBackToTheAlternateWhichIsThenTriedFirst() {
        val home = unreachableRoot()
        assertEquals(Configured.Saved, configure(root = home, alternate = alternateServer.root))
        save()
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals(1, alternateServer.bookmarks.size)
        assertTrue(sent.first().startsWith(home))

        // Remembered: the next save goes straight to the address that answered.
        sent.clear()
        save("https://news.example/second")
        drain()
        assertEquals(2, alternateServer.bookmarks.size)
        assertTrue(sent.single().startsWith(alternateServer.root))
    }

    @Test
    fun aRequestThatMayHaveArrivedIsNotSentToTheAlternate() {
        configure(alternate = alternateServer.root)
        server.fault = FakeKarakeep.Fault.STORE_THEN_HANG
        server.faultCount = 1
        save()
        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        assertTrue(alternateServer.requests.isEmpty())
    }

    @Test
    fun theConnectionCheckUsesTheAlternateToo() {
        configure(root = unreachableRoot(), alternate = server.root)
        assertEquals(ConnectionCheck.Connected(null), runBlocking { integrations.checkKarakeep() })
    }

    @Test
    fun aNewAlternateNeedsTheKeyAgainButKeepsQueuedSaves() {
        configure()
        server.fault = FakeKarakeep.Fault.SERVER_ERROR
        server.faultCount = 1
        save()
        drain()
        assertEquals(JobState.RETRY_WAIT, job().state)

        // The key must not reach an address it was not entered for.
        assertEquals(Configured.MissingCredential, configure(key = null, alternate = alternateServer.root))
        assertNull(service().alternateUrl)

        assertEquals(Configured.Saved, configure(alternate = alternateServer.root))
        assertEquals(alternateServer.root, service().alternateUrl)
        // Same server: the save is kept, paused like after any key change, until resumed.
        assertEquals(JobState.AUTH_REQUIRED, job().state)
        runBlocking { integrations.resume(ServiceKind.KARAKEEP).join() }
        now += 10 * 60_000
        drain()
        assertEquals(JobState.SUCCEEDED, job().state)
    }

    @Test
    fun droppingTheAlternateKeepsTheKeyAndQueuedSaves() {
        configure(alternate = alternateServer.root)
        val before = service()
        assertEquals(Configured.Saved, configure(key = null, alternate = " "))
        assertNull(service().alternateUrl)
        assertEquals(before.generation, service().generation)
        assertEquals(before.fingerprint, service().fingerprint)
    }

    @Test
    fun anInvalidAlternateIsReportedForThatField() {
        assertEquals(
            Configured.InvalidRoot(ServiceRootProblem.UNSUPPORTED_SCHEME, AddressField.ALTERNATE),
            configure(alternate = "ftp://keep.example"),
        )
        assertNull(runBlocking { database.integrations().service(ServiceKind.KARAKEEP) })
    }

    private fun chooseList(id: String) = runBlocking {
        val lists = integrations.karakeepLists()!!
        val list = (lists.read as KarakeepRead.Ok).value.single { it.id == id }
        assertTrue(integrations.setKarakeepList(lists.fingerprint, list))
    }

    @Test
    fun aSaveGoesIntoTheChosenList() {
        server.lists["reading"] = mutableSetOf()
        configure()
        chooseList("reading")
        save()
        drain()
        assertEquals(SaveStatus.Saved, status())
        assertEquals(setOf("bm1"), server.lists["reading"])
    }

    @Test
    fun aDeletedListNeedsTheUserAndARetryUsesTheListChosenThen() {
        server.lists["reading"] = mutableSetOf()
        configure()
        chooseList("reading")
        server.lists.remove("reading")
        save()
        drain()
        assertEquals(JobState.FAILED, job().state)
        assertEquals("list_not_found", job().error)
        assertEquals(SaveStatus.NeedsAttention("list_not_found"), status())

        server.lists["later"] = mutableSetOf()
        chooseList("later")
        runBlocking { integrations.outbox.retryNow("job0") }
        drain()
        assertEquals(SaveStatus.Saved, status())
        assertEquals(setOf("bm1"), server.lists["later"])
        assertEquals(1, server.bookmarks.size)
    }

    @Test
    fun aListStaysWithItsServer() {
        server.lists["reading"] = mutableSetOf()
        configure()
        chooseList("reading")
        configure(key = "rotated-key")
        assertEquals("reading", service().listId)
        assertFalse(runBlocking { integrations.setKarakeepList("another-server", null) })
        assertEquals("reading", service().listId)
        configure(root = alternateServer.root)
        assertNull(service().listId)
        assertNull(service().listName)
    }

    @Test
    fun savedBookmarksCanBeListedAndSearched() {
        configure()
        save()
        drain()
        val newest = runBlocking { integrations.karakeepBookmarks("") } as KarakeepRead.Ok
        assertEquals(listOf(URL), newest.value.bookmarks.map { it.url })
        val none = runBlocking { integrations.karakeepBookmarks("no such words") } as KarakeepRead.Ok
        assertTrue(none.value.bookmarks.isEmpty())
    }

    /** An address nothing listens on: connecting fails at once, before anything is sent. */
    private fun unreachableRoot(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port/keep"
    }

    private fun service() = runBlocking { database.integrations().service(ServiceKind.KARAKEEP) }!!

    private fun payload(url: String = URL) = """{"url":"$url","title":"Article"}"""

    private class MemorySecrets : SecretStore {
        private val values = HashMap<SecretName, String>()

        /** Like a full disk or a failing Keystore. */
        var failWrites = false

        override fun read(name: SecretName) = values[name]

        override fun write(name: SecretName, value: String) {
            check(!failWrites) { "could not store ${name.name}" }
            values[name] = value
        }

        override fun delete(name: SecretName) {
            values.remove(name)
        }
    }

    private companion object {
        const val URL = "https://news.example/article?id=7#comments"
        const val SIX_HOURS_MS = 6L * 60 * 60 * 1000
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
