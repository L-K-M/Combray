package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.browser.CapturedPage
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Service-root normalization strips default ports and trailing dots, but rows, credentials and
 * queued work written before that change carry the older spelling's fingerprint. Reconfiguring
 * the same destination through the new spelling must keep that opaque identity, key generation
 * and bindings; only a genuinely different destination starts a new one.
 *
 * The older rows cannot be produced by today's normalization, so they are built with the real
 * DAO and [ServiceCredentials]. The legacy spelling's host is a DNS name; the fixtures bind
 * loopback, so the transport maps it there (DNS resolution is not what these tests are about).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceLegacyIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var integrations: Integrations
    private val karakeep = FakeKarakeep()
    private val hister = FakeHister()
    private val reports = FakeReports()
    private val secrets = MemorySecrets()
    private val credentials = ServiceCredentials(secrets)
    private var now = 1_760_000_000_000L
    private var ids = 0

    @Before
    fun setUp() {
        karakeep.lists["reading"] = mutableSetOf()
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        integrations = Integrations(
            database = database,
            secrets = secrets,
            transport = loopbackTransport(),
            clock = { now },
            newId = { "job${ids++}" },
            scope = scope,
            scheduler = {},
            reportDirectory = File(folder.root, "reports"),
            reportEnvironment = FakeReports.ENVIRONMENT,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        karakeep.close()
        hister.close()
        reports.close()
    }

    @Test
    fun equivalentKarakeepRootsKeepStoredIdentityListReceiptAndQueuedSave() {
        val oldRoot = legacy(karakeep.root)
        val oldAlternate = "https://keep.tailnet.ts.net:443"
        val oldFingerprint = ServiceUrls.fingerprint(oldRoot)
        seed(ServiceKind.KARAKEEP, oldRoot, oldFingerprint, 3, oldAlternate, listId = "reading", listName = "Reading")
        bind(SecretName.KARAKEEP_API_KEY, oldFingerprint, FakeKarakeep.KEY)

        assertEquals(SaveRequest.Queued, runBlocking { integrations.save(URL, TITLE) })
        val queued = job("job0")
        assertEquals(oldFingerprint, queued.fingerprint)
        assertEquals(3, queued.generation)

        val canonicalRoot = canonical(karakeep.root)
        val canonicalAlternate = "https://keep.tailnet.ts.net"
        assertEquals(Configured.Saved, runBlocking { integrations.configureKarakeep(canonicalRoot, null, canonicalAlternate) })

        val stored = service(ServiceKind.KARAKEEP)
        assertEquals(oldFingerprint, stored.fingerprint)
        assertEquals(3, stored.generation)
        assertEquals(oldRoot, stored.baseUrl)
        assertEquals(canonicalAlternate, stored.alternateUrl)
        assertEquals("reading", stored.listId)
        assertEquals("Reading", stored.listName)
        assertTrue(runBlocking { integrations.hasCredential(SecretName.KARAKEEP_API_KEY, stored) })
        assertEquals(JobState.PENDING, job("job0").state)
        assertEquals("job0", nextDue()?.id)

        drain()
        assertEquals(JobState.SUCCEEDED, job("job0").state)
        assertTrue(karakeep.bookmarks.containsKey(URL))
        // The saved receipt stays bound to the stored fingerprint.
        assertTrue(runBlocking { database.integrations().observeSaved(oldFingerprint, URL).first() } != null)
    }

    @Test
    fun equivalentHisterRootsKeepStoredIdentityCapturePolicyAndQueuedCapture() {
        val oldRoot = legacy(hister.root)
        val oldAlternate = "https://hister.tailnet.ts.net:443"
        val oldFingerprint = ServiceUrls.fingerprint(oldRoot)
        seed(ServiceKind.HISTER, oldRoot, oldFingerprint, 5, oldAlternate, captureEnabled = true)
        bind(SecretName.HISTER_TOKEN, oldFingerprint, FakeHister.TOKEN)

        val generation = runBlocking { integrations.hister.wantsCapture(URL) }!!
        runBlocking { integrations.hister.captured(CapturedPage("tab", "visit", URL, TITLE, TEXT, "sha", false, generation)) }
        val queued = job("job0")
        assertEquals(oldFingerprint, queued.fingerprint)
        assertEquals(5, queued.generation)

        val canonicalRoot = canonical(hister.root)
        val canonicalAlternate = "https://hister.tailnet.ts.net"
        assertEquals(Configured.Saved, runBlocking { integrations.hister.configure(canonicalRoot, null, canonicalAlternate) })

        val stored = service(ServiceKind.HISTER)
        assertEquals(oldFingerprint, stored.fingerprint)
        assertEquals(5, stored.generation)
        assertEquals(oldRoot, stored.baseUrl)
        assertEquals(canonicalAlternate, stored.alternateUrl)
        assertTrue(stored.captureEnabled)
        assertTrue(runBlocking { integrations.hasCredential(SecretName.HISTER_TOKEN, stored) })
        assertEquals(JobState.PENDING, job("job0").state)
        assertEquals("job0", nextDue()?.id)

        drain()
        assertEquals(JobState.SUCCEEDED, job("job0").state)
        assertTrue(hister.added.single().contains(TEXT))
    }

    @Test
    fun equivalentReportsRootsKeepStoredIdentityAndQueuedReport() {
        val oldRoot = legacy(reports.root)
        val oldAlternate = "https://reports.tailnet.ts.net:443"
        val oldFingerprint = ServiceUrls.fingerprint(oldRoot)
        seed(ServiceKind.SITE_REPORTS, oldRoot, oldFingerprint, 7, oldAlternate)
        bind(SecretName.SITE_REPORTS_TOKEN, oldFingerprint, FakeReports.TOKEN)

        val draft = draft()
        assertEquals(ReportRequest.Queued, runBlocking { integrations.reports.submit(draft) })
        val queued = job("job0")
        assertEquals(oldFingerprint, queued.fingerprint)
        assertEquals(7, queued.generation)

        val canonicalRoot = canonical(reports.root)
        val canonicalAlternate = "https://reports.tailnet.ts.net"
        assertEquals(Configured.Saved, runBlocking { integrations.reports.configure(canonicalRoot, null, canonicalAlternate) })

        val stored = service(ServiceKind.SITE_REPORTS)
        assertEquals(oldFingerprint, stored.fingerprint)
        assertEquals(7, stored.generation)
        assertEquals(oldRoot, stored.baseUrl)
        assertEquals(canonicalAlternate, stored.alternateUrl)
        assertTrue(runBlocking { integrations.hasCredential(SecretName.SITE_REPORTS_TOKEN, stored) })
        assertEquals(JobState.PENDING, job("job0").state)
        assertEquals("job0", nextDue()?.id)

        drain()
        assertEquals(JobState.SUCCEEDED, job("job0").state)
        assertTrue(reports.reports.containsKey(draft.id))
    }

    @Test
    fun aDefaultPortLegacyRowKeepsItsFingerprint() {
        val oldRoot = "https://keep.example:443"
        val oldFingerprint = ServiceUrls.fingerprint(oldRoot)
        seed(ServiceKind.KARAKEEP, oldRoot, oldFingerprint, 3, null)
        bind(SecretName.KARAKEEP_API_KEY, oldFingerprint, FakeKarakeep.KEY)

        assertEquals(Configured.Saved, runBlocking { integrations.configureKarakeep("https://keep.example", null) })
        val stored = service(ServiceKind.KARAKEEP)
        assertEquals(oldFingerprint, stored.fingerprint)
        assertEquals(3, stored.generation)
        assertTrue(runBlocking { integrations.hasCredential(SecretName.KARAKEEP_API_KEY, stored) })
    }

    @Test
    fun suppliedKeyRotationKeepsTheStoredDestinationIdentity() {
        val karakeepOld = legacy(karakeep.root)
        val karakeepFingerprint = ServiceUrls.fingerprint(karakeepOld)
        seed(ServiceKind.KARAKEEP, karakeepOld, karakeepFingerprint, 3, null, listId = "reading", listName = "Reading")
        bind(SecretName.KARAKEEP_API_KEY, karakeepFingerprint, FakeKarakeep.KEY)
        assertEquals(SaveRequest.Queued, runBlocking { integrations.save(URL, TITLE) })

        val histerOld = legacy(hister.root)
        val histerFingerprint = ServiceUrls.fingerprint(histerOld)
        seed(ServiceKind.HISTER, histerOld, histerFingerprint, 5, null, captureEnabled = true)
        bind(SecretName.HISTER_TOKEN, histerFingerprint, FakeHister.TOKEN)
        val generation = runBlocking { integrations.hister.wantsCapture(URL) }!!
        runBlocking { integrations.hister.captured(CapturedPage("tab", "visit", URL, TITLE, TEXT, "sha", false, generation)) }

        val reportsOld = legacy(reports.root)
        val reportsFingerprint = ServiceUrls.fingerprint(reportsOld)
        seed(ServiceKind.SITE_REPORTS, reportsOld, reportsFingerprint, 7, null)
        bind(SecretName.SITE_REPORTS_TOKEN, reportsFingerprint, FakeReports.TOKEN)
        val draft = draft()
        assertEquals(ReportRequest.Queued, runBlocking { integrations.reports.submit(draft) })

        val rotatedKey = "rotated-key"
        val rotatedToken = "rotated-reporter-token"
        assertEquals(Configured.Saved, runBlocking { integrations.configureKarakeep(canonical(karakeep.root), rotatedKey) })
        assertEquals(Configured.Saved, runBlocking { integrations.hister.configure(canonical(hister.root), FakeHister.TOKEN) })
        assertEquals(Configured.Saved, runBlocking { integrations.reports.configure(canonical(reports.root), rotatedToken) })

        val karakeepStored = service(ServiceKind.KARAKEEP)
        assertEquals(karakeepFingerprint, karakeepStored.fingerprint)
        assertTrue(karakeepStored.generation > 3)
        assertEquals("reading", karakeepStored.listId)
        val histerStored = service(ServiceKind.HISTER)
        assertEquals(histerFingerprint, histerStored.fingerprint)
        assertTrue(histerStored.generation > 5)
        assertTrue(histerStored.captureEnabled)
        val reportsStored = service(ServiceKind.SITE_REPORTS)
        assertEquals(reportsFingerprint, reportsStored.fingerprint)
        assertTrue(reportsStored.generation > 7)

        // The new secrets are bound to the retained fingerprints.
        assertTrue(secrets.read(SecretName.KARAKEEP_API_KEY)!!.startsWith(karakeepFingerprint))
        assertTrue(secrets.read(SecretName.KARAKEEP_API_KEY)!!.endsWith(rotatedKey))
        assertTrue(secrets.read(SecretName.SITE_REPORTS_TOKEN)!!.startsWith(reportsFingerprint))
        assertTrue(secrets.read(SecretName.SITE_REPORTS_TOKEN)!!.endsWith(rotatedToken))
        assertTrue(runBlocking { integrations.hasCredential(SecretName.HISTER_TOKEN, histerStored) })

        // Same destination, new generation: work pauses for the new credential rather than
        // being parked or cancelled.
        assertEquals(JobState.AUTH_REQUIRED, job("job0").state)
        assertEquals(JobState.AUTH_REQUIRED, job("job1").state)
        assertEquals(JobState.AUTH_REQUIRED, job("job2").state)

        karakeep.acceptedKey = rotatedKey
        reports.acceptedToken = rotatedToken
        runBlocking {
            integrations.outbox.resume(ServiceKind.KARAKEEP)
            integrations.outbox.resume(ServiceKind.HISTER)
            integrations.outbox.resume(ServiceKind.SITE_REPORTS)
        }
        drain()
        assertEquals(JobState.SUCCEEDED, job("job0").state)
        assertEquals(JobState.SUCCEEDED, job("job1").state)
        assertEquals(JobState.SUCCEEDED, job("job2").state)
        assertTrue(karakeep.bookmarks.containsKey(URL))
        assertTrue(hister.added.single().contains(TEXT))
        assertTrue(reports.reports.containsKey(draft.id))
    }

    @Test
    fun aGenuinelyDifferentDestinationStillNeedsAKeyAndKeepsTheOldWorkIsolated() {
        val karakeepOld = legacy(karakeep.root)
        val karakeepFingerprint = ServiceUrls.fingerprint(karakeepOld)
        seed(ServiceKind.KARAKEEP, karakeepOld, karakeepFingerprint, 3, null, listId = "reading", listName = "Reading")
        bind(SecretName.KARAKEEP_API_KEY, karakeepFingerprint, FakeKarakeep.KEY)
        assertEquals(SaveRequest.Queued, runBlocking { integrations.save(URL, TITLE) })

        val histerOld = legacy(hister.root)
        val histerFingerprint = ServiceUrls.fingerprint(histerOld)
        seed(ServiceKind.HISTER, histerOld, histerFingerprint, 5, null, captureEnabled = true)
        bind(SecretName.HISTER_TOKEN, histerFingerprint, FakeHister.TOKEN)
        val generation = runBlocking { integrations.hister.wantsCapture(URL) }!!
        runBlocking { integrations.hister.captured(CapturedPage("tab", "visit", URL, TITLE, TEXT, "sha", false, generation)) }

        // No key: both destinations are refused and nothing changes.
        assertEquals(Configured.MissingCredential, runBlocking { integrations.configureKarakeep("https://other.example", null) })
        assertEquals(Configured.MissingCredential, runBlocking { integrations.hister.configure("https://other.example", null) })
        assertEquals(karakeepFingerprint, service(ServiceKind.KARAKEEP).fingerprint)
        assertEquals(histerFingerprint, service(ServiceKind.HISTER).fingerprint)
        assertEquals(JobState.PENDING, job("job0").state)
        assertEquals(JobState.PENDING, job("job1").state)
        assertEquals("job0", nextDue()?.id)

        // With a key: each new destination gets its own identity, and the old work is kept
        // out of it (parked for a save, cancelled for a capture).
        assertEquals(Configured.Saved, runBlocking { integrations.configureKarakeep("https://other.example", "new-key") })
        assertEquals(Configured.Saved, runBlocking { integrations.hister.configure("https://other.example", "new-token") })
        assertNotEquals(karakeepFingerprint, service(ServiceKind.KARAKEEP).fingerprint)
        assertEquals(ServiceUrls.fingerprint("https://other.example"), service(ServiceKind.KARAKEEP).fingerprint)
        assertNotEquals(histerFingerprint, service(ServiceKind.HISTER).fingerprint)
        assertEquals(ServiceUrls.fingerprint("https://other.example"), service(ServiceKind.HISTER).fingerprint)
        assertNull(service(ServiceKind.KARAKEEP).listId)
        assertEquals(JobState.DESTINATION_CHANGED, job("job0").state)
        assertEquals(JobState.CANCELLED, job("job1").state)
        assertTrue(secrets.read(SecretName.KARAKEEP_API_KEY)!!.endsWith("new-key"))
        assertTrue(secrets.read(SecretName.HISTER_TOKEN)!!.endsWith("new-token"))
        assertNull(nextDue())

        drain()
        assertTrue(karakeep.bookmarks.isEmpty())
        assertTrue(hister.added.isEmpty())
    }

    @Test
    fun anEquivalentAlternateIsAcceptedWithTheStoredKeyButANewOneNeedsIt() {
        val oldRoot = legacy(karakeep.root)
        val oldAlternate = "https://keep.tailnet.ts.net:443"
        val oldFingerprint = ServiceUrls.fingerprint(oldRoot)
        seed(ServiceKind.KARAKEEP, oldRoot, oldFingerprint, 3, oldAlternate)
        bind(SecretName.KARAKEEP_API_KEY, oldFingerprint, FakeKarakeep.KEY)

        // A canonically equivalent alternate keeps the stored key.
        assertEquals(
            Configured.Saved,
            runBlocking { integrations.configureKarakeep(canonical(karakeep.root), null, "https://keep.tailnet.ts.net") },
        )
        assertEquals("https://keep.tailnet.ts.net", service(ServiceKind.KARAKEEP).alternateUrl)

        // A genuinely different alternate must be entered with the key again.
        assertEquals(
            Configured.MissingCredential,
            runBlocking { integrations.configureKarakeep(canonical(karakeep.root), null, "https://other.example") },
        )
        assertEquals("https://keep.tailnet.ts.net", service(ServiceKind.KARAKEEP).alternateUrl)

        // Dropping the alternate stays allowed.
        assertEquals(Configured.Saved, runBlocking { integrations.configureKarakeep(canonical(karakeep.root), null, null) })
        assertNull(service(ServiceKind.KARAKEEP).alternateUrl)
    }

    /** The legacy spelling's host is a DNS name; the fixtures bind loopback, so map it there. */
    private fun loopbackTransport(): HttpTransport {
        val http = UrlConnectionTransport(timeoutMs = 2_000)
        return HttpTransport { request ->
            val url = request.url.replace("localhost.:", "127.0.0.1:")
            if (url == request.url) http.execute(request) else http.execute(request.copy(url = url))
        }
    }

    /** The older stored spelling: a trailing DNS dot the new normalization strips. */
    private fun legacy(root: String) = root.replace("//127.0.0.1:", "//localhost.:")

    /** The spelling the new normalization produces for the same server. */
    private fun canonical(root: String) = root.replace("//127.0.0.1:", "//localhost:")

    private fun seed(
        kind: ServiceKind,
        root: String,
        fingerprint: String,
        generation: Long,
        alternate: String?,
        captureEnabled: Boolean = false,
        listId: String? = null,
        listName: String? = null,
    ): ServiceEntity {
        val entity = ServiceEntity(
            kind = kind,
            baseUrl = root,
            fingerprint = fingerprint,
            generation = generation,
            captureEnabled = captureEnabled,
            updatedAt = now,
            alternateUrl = alternate,
            listId = listId,
            listName = listName,
        )
        runBlocking { database.integrations().setService(entity) }
        return entity
    }

    private fun bind(name: SecretName, fingerprint: String, secret: String) {
        runBlocking { assertTrue(credentials.write(name, fingerprint, secret)) }
    }

    private fun draft(url: String = URL) = ReportDraft(
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
        screenshot = EncodedScreenshot(FakeReports.PNG, 3, 2, scaled = false),
        omitted = null,
    )

    private fun service(kind: ServiceKind) = runBlocking { database.integrations().service(kind) }!!

    private fun job(id: String) = runBlocking { database.integrations().job(id) }!!

    private fun nextDue() = runBlocking { database.integrations().nextDue(now) }

    private fun drain() = runBlocking { integrations.dispatcher.drain() }

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
        const val URL = "https://news.example/article?id=7#comments"
        const val TITLE = "Distinctive article title"
        const val TEXT = "RENDERED-PHRASE only in the DOM"
        const val COMMENT = "The distinctive menu covers the text."
    }
}
