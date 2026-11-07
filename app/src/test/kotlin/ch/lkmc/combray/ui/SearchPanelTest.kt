package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import ch.lkmc.combray.core.integration.UrlConnectionTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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

/** The service scopes' promise: only text typed in the panel is sent to the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchPanelTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeHisterServer()
    private val secrets = MemorySecrets()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        integrations = Integrations(
            database = database,
            secrets = secrets,
            transport = UrlConnectionTransport(timeoutMs = 2_000),
            clock = { 0L },
            newId = { "job" },
            scope = scope,
            scheduler = {},
            reportDirectory = File(context.cacheDir, "reports-test"),
            reportEnvironment = ReportEnvironment("0", 0, "debug", "0", "0", "1", 30),
        )
        runBlocking { assertEquals(Configured.Saved, integrations.hister.configure(server.root, "token")) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        server.close()
    }

    @Test
    fun typingInTheHisterPanelSendsTheTypedText() {
        compose.setContent {
            HisterSearchPanel(integrations.hister, initialQuery = "prefilled", onOpen = {}, onSettings = {})
        }

        compose.onNodeWithText("prefilled").performTextReplacement("prefilledx")
        compose.waitUntil(timeoutMillis = 3_000) { server.queries.isNotEmpty() }
        assertTrue(server.queries.single().contains("prefilledx"))
    }
}

/** A minimal Hister: every search is recorded; the answer is empty. */
private class FakeHisterServer : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val queries = CopyOnWriteArrayList<String>()
    val root get() = "http://127.0.0.1:${http.address.port}/h"

    init {
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/h/search") { exchange ->
            queries += exchange.requestURI.rawQuery.orEmpty()
            val body = """{"documents":[],"history":null,"total":0,"page_key":""}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        http.start()
    }

    override fun close() = http.stop(0)
}

private class MemorySecrets : SecretStore {
    private val values = HashMap<SecretName, String>()

    override fun read(name: SecretName): String? = values[name]

    override fun write(name: SecretName, value: String) {
        values[name] = value
    }

    override fun delete(name: SecretName) {
        values.remove(name)
    }
}
