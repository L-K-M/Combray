package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.HisterIntegration
import ch.lkmc.combray.core.integration.HttpOutcome
import ch.lkmc.combray.core.integration.HttpRequest
import ch.lkmc.combray.core.integration.HttpTransport
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.Outbox
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Hister and Karakeep scopes of Recent: the last answer stays on screen while the next
 * search is in flight, so typing does not clear the list the user is still reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SearchPanelTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val secrets = MemorySecrets()
    private val server = FakeSearchServer()
    private val clock = Clock { 0L }
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        integrations = Integrations(
            database = database,
            secrets = secrets,
            transport = server,
            clock = clock,
            newId = { "job" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            scheduler = {},
            reportDirectory = context.cacheDir,
            reportEnvironment = ReportEnvironment("1.0", 1, "debug", "0.6.0", "abc1234", "10", 30),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun histerKeepsTheLastAnswerWhileTheNextSearchIsInFlight() {
        runBlocking { assertEquals(Configured.Saved, integrations.hister.configure("https://hister.example", "token")) }
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "", {}, {}) } }
        awaitConfigured(HISTER_NOT_CONFIGURED)

        compose.onNode(hasSetTextAction()).performTextInput("one")
        awaitText("One")

        // The second search is slow: the first answer must stay on screen with the progress line.
        server.delayMillis = 2_000
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        compose.onNodeWithText("One").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        awaitText("Two")
        compose.onNodeWithText("Two").assertIsDisplayed()
    }

    @Test
    fun histerClearsTheRowsWhenTheQueryIsBlank() {
        runBlocking { assertEquals(Configured.Saved, integrations.hister.configure("https://hister.example", "token")) }
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "", {}, {}) } }
        awaitConfigured(HISTER_NOT_CONFIGURED)

        compose.onNode(hasSetTextAction()).performTextInput("one")
        awaitText("One")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("One").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun karakeepKeepsTheLastAnswerWhileTheNextSearchIsInFlight() {
        runBlocking { assertEquals(Configured.Saved, integrations.configureKarakeep("https://keep.example", "key")) }
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, "", {}, {}) } }
        awaitConfigured(KARAKEEP_NOT_CONFIGURED)

        compose.onNode(hasSetTextAction()).performTextInput("one")
        awaitText("One")

        server.delayMillis = 2_000
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        compose.onNodeWithText("One").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        awaitText("Two")
        compose.onNodeWithText("Two").assertIsDisplayed()
    }

    private fun awaitConfigured(message: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val HISTER_NOT_CONFIGURED = "Connect Hister in Settings to search all history."
        const val KARAKEEP_NOT_CONFIGURED = "Connect Karakeep in Settings to see your bookmarks."
    }
}

/** Answers Hister and Karakeep search reads from memory, with a configurable delay. */
private class FakeSearchServer : HttpTransport {
    @Volatile var delayMillis: Long = 0

    override fun execute(request: HttpRequest): HttpOutcome {
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val body = if (request.url.contains("/api/v1/bookmarks")) karakeep(request.url) else hister(request.url)
        return HttpOutcome.Response(200, emptyMap(), body.toByteArray(Charsets.UTF_8))
    }

    private fun hister(url: String): String {
        val hit = if (url.contains("two")) {
            """{"url":"https://example.org/two","title":"Two","text":"second"}"""
        } else {
            """{"url":"https://example.org/one","title":"One","text":"first"}"""
        }
        return """{"documents":[$hit],"history":null,"total":1,"page_key":""}"""
    }

    private fun karakeep(url: String): String {
        val bookmark = if (url.contains("two")) {
            """{"id":"b2","content":{"type":"link","url":"https://example.org/two"},"title":"Two"}"""
        } else {
            """{"id":"b1","content":{"type":"link","url":"https://example.org/one"},"title":"One"}"""
        }
        return """{"bookmarks":[$bookmark],"nextCursor":null}"""
    }
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
