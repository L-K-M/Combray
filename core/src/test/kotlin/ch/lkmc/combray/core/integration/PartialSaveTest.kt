package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Creation and optional membership are separate durable facts, including across process loss. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartialSaveTest {
    @get:Rule val files = TemporaryFolder()
    private val server = FakeKarakeep()
    private val other = FakeKarakeep()
    private lateinit var database: CombrayDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var integrations: Integrations
    private val secrets = object : SecretStore {
        private val values = mutableMapOf<SecretName, String>()
        override fun read(name: SecretName): String? = values[name]
        override fun write(name: SecretName, value: String) { values[name] = value }
        override fun delete(name: SecretName) { values.remove(name) }
    }
    private var now = 1_760_000_000_000L
    private var ids = 0
    private var afterCreate: (() -> Unit)? = null
    private var loseListResponse = false
    private val http = UrlConnectionTransport(timeoutMs = 2_000)
    private val transport = HttpTransport { request ->
        val response = http.execute(request)
        if (request.method == "POST" && response is HttpOutcome.Response && response.status in 200..299) {
            afterCreate?.also { afterCreate = null }?.invoke()
        }
        if (request.method == "PUT" && loseListResponse) {
            loseListResponse = false
            HttpOutcome.Timeout
        } else {
            response
        }
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        integrations = newIntegrations()
        assertEquals(Configured.Saved, integrations.configureKarakeep(server.root, FakeKarakeep.KEY))
        server.lists["reading"] = mutableSetOf()
        chooseList("reading")
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.close()
        other.close()
    }

    @Test
    fun discardingAFailedMembershipDoesNotEraseTheCreationReceipt() = runBlocking {
        failMembership()
        val fingerprint = service().fingerprint

        assertEquals("bm1", database.integrations().observeSaved(fingerprint, URL).first()?.remoteId)
        integrations.outbox.discard(job().id)

        assertEquals(SaveStatus.Saved, integrations.saveStatus(URL).first())
        assertEquals(1, creates(server))
    }

    @Test
    fun aRestartRetriesOnlyMembershipOnceTheBookmarkIdIsKnown() = runBlocking {
        failMembership()
        integrations = newIntegrations()
        server.lists["later"] = mutableSetOf()
        chooseList("later")
        integrations.outbox.retryNow(job().id)

        integrations.dispatcher.drain()

        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals(setOf("bm1"), server.lists["later"])
        assertEquals(1, creates(server))
    }

    @Test
    fun savingAgainAfterAListFailureKeepsTheKnownReceipt() = runBlocking {
        failMembership()
        server.lists["later"] = mutableSetOf()
        chooseList("later")
        assertEquals(SaveRequest.Queued, integrations.save(URL, "Updated title"))

        integrations.dispatcher.drain()

        assertEquals(1, creates(server))
        assertEquals("Updated title", database.integrations().observeSaved(service().fingerprint, URL).first()?.title)
        assertEquals(setOf("bm1"), server.lists["later"])
    }

    @Test
    fun aReceiptNeverFollowsAParkedSaveToAnotherServer() = runBlocking {
        failMembership()
        other.bookmarks["https://seed.example/"] = "unrelated"
        other.lists["later"] = mutableSetOf()
        assertEquals(Configured.Saved, integrations.configureKarakeep(other.root, FakeKarakeep.KEY))
        chooseList("later")
        integrations.outbox.sendParkedToCurrent(ServiceKind.KARAKEEP)

        integrations.dispatcher.drain()

        assertEquals("bm2", other.bookmarks[URL])
        assertEquals(setOf("bm2"), other.lists["later"])
        assertEquals(1, creates(server))
        assertEquals(1, creates(other))
    }

    @Test
    fun losingTheClaimAfterCreatePreventsAnOptionalListRequest() = runBlocking {
        afterCreate = { runBlocking { integrations.configureKarakeep(server.root, "replacement-key") } }
        integrations.save(URL, "Article")

        integrations.dispatcher.drain()

        assertEquals(JobState.AUTH_REQUIRED, job().state)
        assertTrue(server.lists.getValue("reading").isEmpty())
        assertTrue(server.requests.none { it.startsWith("PUT ") })
        assertNull(database.integrations().observeSaved(service().fingerprint, URL).first())
    }

    @Test
    fun aLostListAnswerRetriesTheIdempotentStepWithoutCreatingAgain() = runBlocking {
        loseListResponse = true
        integrations.save(URL, "Article")
        integrations.dispatcher.drain()
        assertEquals(JobState.RETRY_WAIT, job().state)
        assertEquals(setOf("bm1"), server.lists["reading"])
        now = job().nextAttemptAt
        integrations = newIntegrations()

        integrations.dispatcher.drain()

        assertEquals(JobState.SUCCEEDED, job().state)
        assertEquals(1, creates(server))
        assertEquals(2, server.requests.count { it.startsWith("PUT ") })
    }

    private suspend fun failMembership() {
        server.lists.remove("reading")
        integrations.save(URL, "Article")
        integrations.dispatcher.drain()
        assertEquals(JobState.FAILED, job().state)
        assertEquals("list_not_found", job().error)
    }

    private suspend fun chooseList(id: String) {
        val lists = integrations.karakeepLists()!!
        val choice = (lists.read as KarakeepRead.Ok).value.single { it.id == id }
        assertTrue(integrations.setKarakeepList(lists.fingerprint, choice))
    }

    private suspend fun service() = database.integrations().service(ServiceKind.KARAKEEP)!!
    private suspend fun job() = database.integrations().job("job0")!!
    private fun creates(fake: FakeKarakeep) = fake.requests.count { it.startsWith("POST ") }

    private fun newIntegrations() = Integrations(
        database = database,
        secrets = secrets,
        transport = transport,
        clock = { now },
        newId = { "job${ids++}" },
        scope = scope,
        scheduler = {},
        reportDirectory = files.root,
        reportEnvironment = FakeReports.ENVIRONMENT,
    )

    private companion object {
        const val URL = "https://article.example/read"
    }
}
