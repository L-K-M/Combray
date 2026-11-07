package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.ThirdPartyNotices
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.browser.Retention
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A save and a connection check that run together keep their own results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SettingsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var integrations: Integrations

    @Before
    fun setUp() {
        // The app module's unit tests do not carry Room on their compile classpath; the
        // database file is deleted so each test starts from an empty store.
        File(context.filesDir, CombrayDatabase.FILE_NAME).delete()
        val database = CombrayDatabase.open(context)
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
        // The check is slower than the save, as a real network check is.
        val transport = HttpTransport { request: HttpRequest ->
            Thread.sleep(500)
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
    }

    @Test
    fun aSlowCheckDoesNotReplaceTheSaveResult() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            CombrayTheme {
                SettingsSheet(
                    integrations = integrations,
                    settings = BrowserSettings(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
                    retention = Retention(),
                    onUnusedTabsChanged = {},
                    notices = ThirdPartyNotices(application.assets),
                    onDismiss = {},
                )
            }
        }

        compose.onAllNodesWithText("Save settings").onFirst().performClick()
        compose.onAllNodesWithText("Check connection").onFirst().performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connected.").fetchSemanticsNodes().isNotEmpty()
        }
        // The save is fast but involves a real database write; wait for its result too.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Settings saved.").fetchSemanticsNodes().isNotEmpty()
        }
        // The save's own result stays visible beside the check's.
        assertEquals(1, compose.onAllNodesWithText("Settings saved.").fetchSemanticsNodes().size)
    }
}
