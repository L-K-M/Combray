package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.HttpOutcome
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Hister and Karakeep scopes of Recent. The local filter prefills their field; it must not
 * leave the device until the user submits it there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RecentScopeTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = CopyOnWriteArrayList<String>()
    private val database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = AtomicInteger()
    private val now = 1_760_000_000_000L
    private lateinit var integrations: Integrations

    @Before
    fun setUp() {
        val transport = HttpTransport { request ->
            requests += request.url
            HttpOutcome.Response(200, emptyMap(), bodyFor(request.url))
        }
        integrations = Integrations(
            database = database,
            secrets = MemorySecrets(),
            transport = transport,
            clock = { now },
            newId = { "job${ids.incrementAndGet()}" },
            scope = scope,
            scheduler = {},
            reportDirectory = File(context.cacheDir, "reports"),
            reportEnvironment = ReportEnvironment("0.1.0", 1, "debug", "0.7.0", "abc1234", "15", 35),
        )
        runBlocking {
            integrations.hister.configure("https://hister.example", "hister-token")
            integrations.configureKarakeep("https://keep.example", "karakeep-key")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun aPrefilledHisterQueryIsNotSentUntilTheUserSubmitsIt() {
        compose.setContent {
            CombrayTheme {
                HisterSearchPanel(integrations.hister, initialQuery = "bank", onOpen = {}, onSettings = {})
            }
        }
        awaitService()
        // Give the debounce time to fire: the prefilled text is not a request.
        val autoSent = runCatching {
            compose.waitUntil(2_000) { requests.any { it.contains("/search?q=bank") } }
        }.isSuccess
        assertFalse(autoSent)

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { requests.any { it.contains("/search?q=bank") } }
    }

    @Test
    fun aPrefilledKarakeepQueryIsNotSentUntilTheUserSubmitsIt() {
        compose.setContent {
            CombrayTheme {
                KarakeepPanel(integrations, initialQuery = "bank", onOpen = {}, onSettings = {})
            }
        }
        awaitService()
        val autoSent = runCatching {
            compose.waitUntil(2_000) { requests.any { it.contains("q=bank") } }
        }.isSuccess
        assertFalse(autoSent)

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { requests.any { it.contains("q=bank") } }
    }

    /** The field is enabled only once the configured service reaches the panel. */
    private fun awaitService() {
        compose.waitUntil(5_000) { runCatching { compose.onNode(hasSetTextAction()).assertIsEnabled() }.isSuccess }
        compose.waitForIdle()
    }

    private fun bodyFor(url: String): ByteArray = when {
        url.contains("/search") -> """{"documents":[],"history":null,"total":0,"page_key":""}""".toByteArray()
        url.contains("/bookmarks") -> """{"bookmarks":[]}""".toByteArray()
        else -> """{}""".toByteArray()
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
