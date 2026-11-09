package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import ch.lkmc.combray.ThirdPartyNotices
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.Retention
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.SecretStore
import ch.lkmc.combray.core.integration.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Settings preference controls fence their failure message to the click-time request
 * generation. A delayed failure from an older request must not appear over a newer selection,
 * and the latest request's success must clear an old failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SettingsPreferenceFeedbackTest {
    private val compose = createComposeRule()
    private val folder = TemporaryFolder()
    private val resources = FixtureResources()

    /**
     * TemporaryFolder outermost, fixture resources next, Compose innermost: the Compose rule
     * disposes its composition and lifecycle collectors before the fixture cancels its scope
     * and closes Room. The old `@After` order closed Room while `collectAsStateWithLifecycle`
     * was still querying it.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(folder).around(resources).around(compose)

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val compositionActive = AtomicBoolean(false)
    private val scripted = ScriptedPreferences(
        context.getSharedPreferences("settings-feedback-test", Context.MODE_PRIVATE),
    )
    private lateinit var scope: CoroutineScope
    private lateinit var settings: BrowserSettings
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations
    private var now = 1_760_000_000_000L
    private var ids = 0

    @Before
    fun setUp() {
        scripted.delegate.edit().clear().commit()
        scripted.modes.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = BrowserSettings(scripted, scope)
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
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

    /** Cancels and joins the fixture scope and closes Room, only after Compose has disposed. */
    private inner class FixtureResources : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    try {
                        base.evaluate()
                    } finally {
                        // Check the ordering without preventing cleanup if setup or the test failed.
                        val disposed = !compositionActive.get()
                        // A failed test can leave an admitted write waiting on the scripted latch;
                        // release it so the bounded join cannot hang on a NonCancellable commit.
                        scripted.latch?.countDown()
                        try {
                            if (::scope.isInitialized) {
                                runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
                            }
                        } finally {
                            if (::database.isInitialized) database.close()
                        }
                        check(disposed) { "Compose was not disposed before fixture cleanup" }
                    }
                }
            }
    }

    private fun show(onChanged: () -> Unit = {}) {
        compose.setContent {
            DisposableEffect(Unit) {
                compositionActive.set(true)
                onDispose { compositionActive.set(false) }
            }
            SettingsSheet(
                integrations = integrations,
                settings = settings,
                retention = Retention(),
                onUnusedTabsChanged = onChanged,
                notices = ThirdPartyNotices(context.assets),
                onDismiss = {},
            )
        }
    }

    private fun toggleUnusedTabs() {
        compose.onNodeWithText(UNUSED_TABS).performScrollTo().performClick()
    }

    private fun failureShown(): Boolean =
        compose.onAllNodesWithText(FAILURE).fetchSemanticsNodes().isNotEmpty()

    /** A fast second toggle must not leave the first write's failure on screen. */
    @Test
    fun aDelayedOlderFailureDoesNotShowOverANewerSelection() {
        // The first commit stays in flight across the second click, so its failure lands after
        // the newer selection has already cleared the message.
        val latch = CountDownLatch(1)
        scripted.latch = latch
        scripted.modes += ScriptedPreferences.Mode.Fail
        scripted.modes += ScriptedPreferences.Mode.Acknowledge
        val maintenance = AtomicInteger()
        show { maintenance.incrementAndGet() }

        toggleUnusedTabs()
        toggleUnusedTabs()
        latch.countDown()

        compose.waitUntil(5_000) { maintenance.get() == 1 }
        compose.onNodeWithText(FAILURE).assertDoesNotExist()
    }

    /** The latest request's success clears a failure that was already on screen. */
    @Test
    fun aNewerSuccessClearsAnOlderFailure() {
        scripted.modes += ScriptedPreferences.Mode.Fail
        scripted.modes += ScriptedPreferences.Mode.Acknowledge
        val maintenance = AtomicInteger()
        show { maintenance.incrementAndGet() }

        toggleUnusedTabs()
        compose.waitUntil(5_000) { failureShown() }

        toggleUnusedTabs()
        compose.waitUntil(5_000) { maintenance.get() == 1 }
        compose.onNodeWithText(FAILURE).assertDoesNotExist()
    }

    /** The latest request's failure is the one that shows. */
    @Test
    fun theLatestFailureShows() {
        scripted.modes += ScriptedPreferences.Mode.Acknowledge
        scripted.modes += ScriptedPreferences.Mode.Fail
        show()

        toggleUnusedTabs()
        toggleUnusedTabs()

        compose.waitUntil(5_000) { failureShown() }
        compose.onNodeWithText(FAILURE).assertExists()
    }

    /**
     * The write is admitted at the click, before the UI observer runs, and it finishes even when
     * the sheet is disposed while the disk write is still in flight.
     */
    @Test
    fun anAdmittedWriteOutlivesTheDismissedSheet() {
        val latch = CountDownLatch(1)
        scripted.latch = latch
        var shown by mutableStateOf(true)
        compose.setContent {
            DisposableEffect(Unit) {
                compositionActive.set(true)
                onDispose { compositionActive.set(false) }
            }
            if (shown) {
                SettingsSheet(
                    integrations = integrations,
                    settings = settings,
                    retention = Retention(),
                    onUnusedTabsChanged = {},
                    notices = ThirdPartyNotices(context.assets),
                    onDismiss = {},
                )
            }
        }

        toggleUnusedTabs()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()

        // The click admitted the write: it is in flight even though the sheet is gone.
        compose.waitUntil(5_000) { scripted.commits.get() == 1 }
        latch.countDown()
        compose.waitUntil(5_000) { settings.unusedTabs.value == ch.lkmc.combray.core.browser.UnusedTabs.CLOSE }
    }

    private companion object {
        const val UNUSED_TABS = "Close tabs unused for 7 days"
        const val FAILURE = "Could not save this change. Try again."
    }
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
