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
import ch.lkmc.combray.core.integration.HttpOutcome
import ch.lkmc.combray.core.integration.HttpRequest
import ch.lkmc.combray.core.integration.HttpTransport
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import ch.lkmc.combray.core.integration.WakeupScheduler
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
import java.io.File

/** A seeded query is not sent until the user types or submits; a blank query loads bookmarks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class KarakeepPanelTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations

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
            HttpOutcome.Response(200, emptyMap(), """{"bookmarks":[],"nextCursor":null}""".toByteArray())
        }
        val clock = Clock { 0L }
        integrations = Integrations(
            database = database,
            secrets = secrets,
            transport = transport,
            clock = clock,
            newId = { "job" },
            scope = scope,
            scheduler = WakeupScheduler { },
            reportDirectory = File(context.cacheDir, "reports"),
            reportEnvironment = ReportEnvironment("0", 0, "debug", "0", "rev", "1", 30),
        )
        runBlocking { integrations.configureKarakeep("http://127.0.0.1:1/keep", "token") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun sent(): List<String> = synchronized(requests) { requests.toList() }

    private fun awaitService() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasSetTextAction()).assertIsEnabled() }.isSuccess
        }
    }

    @Test
    fun aSeededQueryIsSentOnlyAfterTheUserTypes() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, initialQuery = "recipe", onOpen = {}, onSettings = {}) } }
        awaitService()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("a seeded query must not be sent", sent().isEmpty())

        compose.onNodeWithText("recipe").performTextInput("x")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, sent().count { "/search?" in it })
        assertTrue(sent().any { "q=xrecipe" in it })
    }

    @Test
    fun aSeededQueryIsSentWhenTheUserSubmitsIt() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, initialQuery = "recipe", onOpen = {}, onSettings = {}) } }
        awaitService()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().isEmpty())

        compose.onNodeWithText("recipe").performImeAction()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(sent().any { "q=recipe" in it })
    }

    @Test
    fun aBlankQueryLoadsTheNewestBookmarksAtOnce() {
        compose.setContent { CombrayTheme { KarakeepPanel(integrations, initialQuery = "", onOpen = {}, onSettings = {}) } }
        awaitService()
        compose.waitUntil(5_000) { sent().any { "/bookmarks?" in it } }
        assertTrue(sent().none { "/search?" in it })
    }
}
