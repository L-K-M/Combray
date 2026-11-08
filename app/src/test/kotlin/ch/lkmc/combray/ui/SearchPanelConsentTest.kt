package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.HttpOutcome
import ch.lkmc.combray.core.integration.HttpRequest
import ch.lkmc.combray.core.integration.HttpTransport
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The service scopes' consent promise: a filter seeded from local Recent is never sent to
 * Hister or Karakeep until the user types in the panel or submits it. A blank Karakeep field
 * still loads the newest bookmarks, and an unchanged query is resent by an explicit submit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SearchPanelConsentTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = CopyOnWriteArrayList<HttpRequest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { 0L }
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        val transport = HttpTransport { request ->
            requests += request
            val body = if (request.url.contains("/api/v1/bookmarks")) KARAKEEP_PAGE else HISTER_PAGE
            HttpOutcome.Response(200, emptyMap(), body.toByteArray(Charsets.UTF_8))
        }
        integrations = Integrations(
            database = database,
            secrets = MemorySecrets(),
            transport = transport,
            clock = clock,
            newId = { "job" },
            scope = scope,
            scheduler = {},
            reportDirectory = context.cacheDir,
            reportEnvironment = ReportEnvironment("0", 0, "debug", "0", "rev", "1", 30),
        )
        runBlocking {
            integrations.configureKarakeep("http://127.0.0.1:1/keep", "key")
            integrations.hister.configure("http://127.0.0.1:1/h", "token")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun sent(): List<HttpRequest> = requests.toList()

    private fun awaitFieldEnabled() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasSetTextAction()).assertIsEnabled() }.isSuccess
        }
    }

    @Test
    fun aSeededHisterQueryIsNotSentUntilTheUserTypes() {
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("a seeded query must not be sent", sent().none { it.url.contains("/search") })

        compose.onNodeWithText("recipe").performTextInput("x")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { it.url.contains("q=xrecipe") } }
        assertEquals(1, sent().count { it.url.contains("/search") })
    }

    @Test
    fun aSeededHisterQueryIsSentByAnExplicitSubmit() {
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().none { it.url.contains("/search") })

        compose.onNodeWithText("recipe").performImeAction()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { it.url.contains("q=recipe") } }
        assertEquals(1, sent().count { it.url.contains("/search") })
    }

    @Test
    fun anUnchangedHisterQueryIsResubmittedByAnExplicitSubmit() {
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().none { it.url.contains("/search") })

        compose.onNodeWithText("recipe").performImeAction()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { it.url.contains("q=recipe") } }
        compose.waitForIdle()

        compose.onNodeWithText("recipe").performImeAction()
        compose.waitUntil(5_000) { sent().count { it.url.contains("q=recipe") } >= 2 }
        assertEquals(2, sent().count { it.url.contains("/search") })
    }

    @Test
    fun aBlankHisterQueryStaysIdle() {
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().none { it.url.contains("/search") })
    }

    @Test
    fun aSeededKarakeepQueryIsNotSentUntilTheUserTypes() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("a seeded query must not be sent", sent().none { it.url.contains("/bookmarks") })

        compose.onNodeWithText("recipe").performTextInput("x")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { it.url.contains("q=xrecipe") } }
        assertEquals(1, sent().count { it.url.contains("/search?") })
    }

    @Test
    fun aSeededKarakeepQueryIsSentByAnExplicitSubmit() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().none { it.url.contains("/bookmarks") })

        compose.onNodeWithText("recipe").performImeAction()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { it.url.contains("q=recipe") } }
        assertEquals(1, sent().count { it.url.contains("/search?") })
    }

    @Test
    fun aBlankKarakeepQueryLoadsTheNewestBookmarks() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, "", {}, {}) } }
        awaitFieldEnabled()
        compose.waitUntil(5_000) { sent().any { it.url.contains("/api/v1/bookmarks?") } }
        assertTrue(sent().none { it.url.contains("/search?") })
    }

    @Test
    fun switchingScopesDoesNotSendASeededFilter() {
        compose.setContent {
            var showHister by remember { mutableStateOf(true) }
            CombrayTheme {
                Column {
                    if (showHister) {
                        HisterSearchPanel(integrations.hister, "recipe", {}, {})
                    } else {
                        KarakeepPanel(integrations, "recipe", {}, {})
                    }
                    TextButton(onClick = { showHister = !showHister }) { Text("switch") }
                }
            }
        }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().isEmpty())

        compose.onNodeWithText("switch").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aSeededKarakeepQueryLeavesNoLoadMoreBehind() {
        // The fake answers carry a cursor. A seeded query that is never sent must not produce a
        // page, so there is no Load more to bypass the gate.
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("More results").assertDoesNotExist()
        assertTrue(sent().none { it.url.contains("/bookmarks") })
    }

    @Test
    fun aSeededHisterQueryLeavesNoLoadMoreBehind() {
        compose.setContent { CombrayTheme { HisterSearchPanel(integrations.hister, "recipe", {}, {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("More results").assertDoesNotExist()
        assertTrue(sent().none { it.url.contains("/search") })
    }

    private companion object {
        val HISTER_PAGE = """
            {"documents":[{"url":"https://example.org/one","title":"One","text":"first"}],
             "history":null,"total":1,"page_key":"page-2"}
        """.trimIndent()
        val KARAKEEP_PAGE = """
            {"bookmarks":[{"id":"b1","content":{"type":"link","url":"https://example.org/one"},
             "title":"One"}],"nextCursor":"cursor-2"}
        """.trimIndent()
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
