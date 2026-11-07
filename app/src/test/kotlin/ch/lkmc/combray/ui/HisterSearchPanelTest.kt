package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.HisterIntegration
import ch.lkmc.combray.core.integration.HttpOutcome
import ch.lkmc.combray.core.integration.HttpRequest
import ch.lkmc.combray.core.integration.HttpTransport
import ch.lkmc.combray.core.integration.Outbox
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
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

/**
 * A query seeded from Recent's local filter must not leave the device until the user types
 * in the panel or submits it. The panel's own typed text is searched after the pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class HisterSearchPanelTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = mutableListOf<String>()
    private lateinit var database: CombrayDatabase
    private lateinit var hister: HisterIntegration

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        val secrets = object : SecretStore {
            private val stored = mutableMapOf<SecretName, String>()
            override fun read(name: SecretName): String? = stored[name]
            override fun write(name: SecretName, value: String) {
                stored[name] = value
            }

            override fun delete(name: SecretName) {
                stored.remove(name)
            }
        }
        val transport = HttpTransport { request: HttpRequest ->
            synchronized(requests) { requests += request.url }
            HttpOutcome.Response(200, emptyMap(), """{"documents":[],"history":null}""".toByteArray())
        }
        val clock = Clock { 0L }
        hister = HisterIntegration(
            database,
            secrets,
            transport,
            clock,
            Outbox(database, clock, { "job" }),
            kick = {},
        )
        runBlocking { hister.configure("http://127.0.0.1:1/keep", "token") }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sent(): List<String> = synchronized(requests) { requests.toList() }

    /** The panel collects the service flow itself; wait until the field is enabled (configured). */
    private fun awaitService() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("recipe").assertIsEnabled() }.isSuccess
        }
    }

    private fun awaitFieldEnabled() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasSetTextAction()).assertIsEnabled() }.isSuccess
        }
    }

    @Test
    fun aSeededQueryIsSentOnlyAfterTheUserTypes() {
        compose.setContent { CombrayTheme { HisterSearchPanel(hister, initialQuery = "recipe", onOpen = {}, onSettings = {}) } }
        awaitService()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("a seeded query must not be sent", sent().isEmpty())

        compose.onNodeWithText("recipe").performTextInput("x")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { "q=xrecipe" in it } }
        assertEquals(listOf("http://127.0.0.1:1/keep/search?q=xrecipe&format=json"), sent())
    }

    @Test
    fun aBlankQueryStaysIdleAndSendsNothing() {
        compose.setContent { CombrayTheme { HisterSearchPanel(hister, initialQuery = "", onOpen = {}, onSettings = {}) } }
        awaitFieldEnabled()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aSeededQueryIsSentWhenTheUserSubmitsIt() {
        compose.setContent { CombrayTheme { HisterSearchPanel(hister, initialQuery = "recipe", onOpen = {}, onSettings = {}) } }
        awaitService()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().isEmpty())

        compose.onNodeWithText("recipe").performImeAction()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sent().any { "q=recipe" in it } }
        assertEquals(listOf("http://127.0.0.1:1/keep/search?q=recipe&format=json"), sent())
    }
}
