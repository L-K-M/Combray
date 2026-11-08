package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Settings service form's configuration actions: a tap admits one operation, the address and
 * key tapped stay bound while it runs, and a delayed result cannot clear a newer edit. The fake
 * key store and transport block on latches so the tests control when an operation is in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SettingsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var integrations: Integrations

    @Volatile private var blockWrites = false
    @Volatile private var blockChecks = false
    private val writeEntered = CountDownLatch(1)
    private val writeRelease = CountDownLatch(1)
    private val writeReturned = CountDownLatch(1)
    private val writeCount = AtomicInteger(0)
    private val checkEntered = CountDownLatch(1)
    private val checkRelease = CountDownLatch(1)
    private var initialGeneration = 0L
    private var savedGeneration: CompletableDeferred<Long>? = null

    private val secrets = object : SecretStore {
        private val stored = mutableMapOf<SecretName, String>()
        override fun read(name: SecretName): String? = stored[name]
        override fun write(name: SecretName, value: String) {
            if (blockWrites) {
                writeCount.incrementAndGet()
                writeEntered.countDown()
                writeRelease.await(5, TimeUnit.SECONDS)
            }
            stored[name] = value
            if (blockWrites) writeReturned.countDown()
        }

        override fun delete(name: SecretName) {
            stored.remove(name)
        }
    }

    private val transport = HttpTransport { _: HttpRequest ->
        if (blockChecks) {
            checkEntered.countDown()
            checkRelease.await(5, TimeUnit.SECONDS)
        }
        HttpOutcome.Response(200, emptyMap(), """{"bookmarks":[],"nextCursor":null}""".toByteArray())
    }

    @Before
    fun setUp() {
        File(context.filesDir, CombrayDatabase.FILE_NAME).delete()
        integrations = Integrations(
            database = CombrayDatabase.open(context),
            secrets = secrets,
            transport = transport,
            clock = Clock { 0L },
            newId = { "job" },
            scope = scope,
            scheduler = WakeupScheduler { },
            reportDirectory = File(context.cacheDir, "reports"),
            reportEnvironment = ReportEnvironment("0", 0, "debug", "0", "rev", "1", 30),
        )
        runBlocking { integrations.configureKarakeep("http://127.0.0.1:1/keep", "token") }
        initialGeneration = runBlocking { integrations.karakeepService.first() }!!.generation
        savedGeneration = CompletableDeferred()
        scope.launch {
            integrations.karakeepService.collect { service ->
                val target = savedGeneration
                if (target != null && !target.isCompleted && service != null && service.generation > initialGeneration) {
                    target.complete(service.generation)
                }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun twoSaveTapsBeforeTheCoroutineStartsAdmitOneOperation() {
        show()
        field("API key").performTextInput("new-key")
        blockWrites = true
        compose.mainClock.autoAdvance = false
        try {
            // Invoke the click action directly, without waiting for idle: the coroutine must not
            // start between the two taps.
            saveButton().performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
            assertEquals("the first save started before the second tap", 1L, writeEntered.count)
            saveButton().performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue("the save never reached the key store", writeEntered.await(5, TimeUnit.SECONDS))
        assertEquals("a second tap launched a second save", 1, writeCount.get())
        writeRelease.countDown()
        assertTrue(writeReturned.await(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { savedGeneration!!.isCompleted }
        compose.waitForIdle()
        assertEquals(1, writeCount.get())
        compose.onAllNodesWithText("Settings saved.").onFirst().assertExists()
    }

    @Test
    fun anEditAfterTheTapBeforeTheCoroutineStartsKeepsTheDraftAndTheTappedAddress() {
        show()
        field("API key").performTextInput("new-key")
        blockWrites = true
        compose.mainClock.autoAdvance = false
        try {
            saveButton().performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
            // The user keeps editing before the coroutine runs. SetText invokes the field's
            // onValueChange directly, without waiting for idle and starting the coroutine.
            field("Server address").performSemanticsAction(SemanticsActions.SetText) {
                it.invoke(AnnotatedString("http://127.0.0.1:1/keep2"))
            }
            assertEquals("the save started before the edit", 1L, writeEntered.count)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
        writeRelease.countDown()
        assertTrue(writeReturned.await(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { savedGeneration!!.isCompleted }
        compose.waitForIdle()
        // The tapped address was saved, not the edited draft.
        assertEquals("http://127.0.0.1:1/keep", storedAddress())
        // The edit survived the delayed result, and no success message describes the newer form.
        field("Server address").assert(hasText("http://127.0.0.1:1/keep2"))
        assertEquals(0, compose.onAllNodesWithText("Settings saved.").fetchSemanticsNodes().size)
    }

    @Test
    fun aCheckAndARemoveWhileASaveIsPendingAdmitOnlyTheSave() {
        show()
        field("API key").performTextInput("new-key")
        blockWrites = true
        saveButton().performClick()
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
        checkButton().assertIsNotEnabled()
        removeButton().assertIsNotEnabled()

        writeRelease.countDown()
        compose.waitUntil(5_000) { savedGeneration!!.isCompleted }
        compose.waitForIdle()
        saveButton().assertIsEnabled()
        checkButton().assertIsEnabled()
        removeButton().assertIsEnabled()

        checkButton().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connected.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aSaveAndARemoveWhileACheckIsPendingAdmitOnlyTheCheck() {
        show()
        blockChecks = true
        checkButton().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Checking…").onFirst().assertExists()
        assertTrue(checkEntered.await(5, TimeUnit.SECONDS))
        saveButton().assertIsNotEnabled()
        removeButton().assertIsNotEnabled()

        checkRelease.countDown()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connected.").fetchSemanticsNodes().isNotEmpty()
        }
        saveButton().assertIsEnabled()
        removeButton().assertIsEnabled()

        removeButton().performClick()
        compose.onNodeWithText("Remove Karakeep?").assertExists()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun show() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            CombrayTheme {
                SettingsSheet(
                    integrations = integrations,
                    settings = BrowserSettings(context.getSharedPreferences("settings-form-test", Context.MODE_PRIVATE)),
                    retention = Retention(),
                    onUnusedTabsChanged = {},
                    notices = ThirdPartyNotices(application.assets),
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun storedAddress(): String = runBlocking { integrations.karakeepService.first() }!!.baseUrl

    private fun saveButton() = compose.onAllNodesWithText("Save settings").onFirst()

    private fun checkButton() = compose.onAllNodesWithText("Check connection").onFirst()

    private fun removeButton() = compose.onAllNodesWithText("Remove").onFirst()

    private fun field(label: String) = compose.onAllNodes(hasSetTextAction() and hasText(label)).onFirst()
}
