package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import ch.lkmc.combray.ThirdPartyNotices
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.Retention
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import ch.lkmc.combray.core.integration.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Settings queue rows: a job's address comes from the row or, for one written before schema 6,
 * from its payload decoded off the UI thread. A row with no usable address says "Unknown page",
 * never its raw dedupe key or report id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SettingsQueueTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations
    private lateinit var scope: CoroutineScope
    private var now = 1_760_000_000_000L
    private var ids = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        integrations = Integrations(
            database = database,
            secrets = MemorySecrets(),
            transport = UrlConnectionTransport(timeoutMs = 1_000),
            clock = { now },
            newId = { "job${ids++}" },
            scope = scope,
            scheduler = { _ -> },
            reportDirectory = folder.root,
            reportEnvironment = ReportEnvironment("0.1.0", 1, "debug", "0.7.0", "rev", "16", 36),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun insertJob(dedupeKey: String, payload: String, url: String?) {
        runBlocking {
            database.integrations().insertJob(
                OutboxJobEntity(
                    id = "job${ids++}",
                    kind = JobKind.KARAKEEP_SAVE,
                    service = ServiceKind.KARAKEEP,
                    fingerprint = "fp",
                    generation = 1,
                    dedupeKey = dedupeKey,
                    url = url,
                    sequence = ids.toLong(),
                    payload = payload,
                    payloadBytes = payload.toByteArray(Charsets.UTF_8).size,
                    state = JobState.PENDING,
                    attempts = 0,
                    nextAttemptAt = now,
                    leaseOwner = null,
                    leaseUntil = null,
                    fence = 0,
                    error = null,
                    remoteId = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun show() {
        compose.setContent {
            SettingsSheet(
                integrations = integrations,
                settings = BrowserSettings(context.getSharedPreferences(BrowserSettings.FILE_NAME, Context.MODE_PRIVATE), scope),
                retention = Retention(),
                onUnusedTabsChanged = {},
                notices = ThirdPartyNotices(context.assets),
                onDismiss = {},
            )
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aQueuedRowShowsItsRecordedAddressHost() {
        insertJob(dedupeKey = "https://a.example/", payload = """{"url":"https://a.example/"}""", url = "https://a.example/article")
        show()
        awaitText("a.example")
        compose.onNodeWithText("a.example").assertExists()
    }

    @Test
    fun aLegacyRowShowsTheAddressDecodedFromItsPayload() {
        insertJob(dedupeKey = "https://a.example/", payload = """{"url":"https://a.example/article","title":"A"}""", url = null)
        show()
        awaitText("a.example")
        compose.onNodeWithText("a.example").assertExists()
    }

    @Test
    fun aQueueRowWithoutAUsableUrlSaysUnknownPageNotItsRawKey() {
        insertJob(dedupeKey = "raw-dedupe-key", payload = "not json", url = null)
        show()
        awaitText("Unknown page")
        compose.onNodeWithText("Unknown page").assertExists()
        compose.onNodeWithText("raw-dedupe-key").assertDoesNotExist()
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<SecretName, String>()

        override fun read(name: SecretName): String? = values[name]

        override fun write(name: SecretName, value: String) {
            values[name] = value
        }

        override fun delete(name: SecretName) {
            values.remove(name)
        }
    }
}
