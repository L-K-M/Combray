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
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.HomeLinks
import ch.lkmc.combray.core.browser.HomeLinksState
import ch.lkmc.combray.core.browser.HomeSectionVisibility
import ch.lkmc.combray.core.data.CombrayDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Home editor's visibility switches fence their failure message to the click-time request
 * generation. A delayed failure from an older request must not appear over a newer selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class HomePreferenceFeedbackTest {
    private val compose = createComposeRule()
    private val resources = FixtureResources()

    /**
     * Fixture resources outer, Compose inner: the Compose rule disposes its composition and
     * lifecycle collectors before the fixture cancels its scope and closes Room. The old
     * `@After` order closed Room while `collectAsStateWithLifecycle` was still querying it.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val compositionActive = AtomicBoolean(false)
    private val scripted = ScriptedPreferences(
        context.getSharedPreferences("home-feedback-test", Context.MODE_PRIVATE),
    )
    private lateinit var scope: CoroutineScope
    private lateinit var settings: BrowserSettings
    private lateinit var database: CombrayDatabase
    private val states = MutableStateFlow(HomeLinksState(emptyList(), emptyList(), emptyList()))

    @Before
    fun setUp() {
        scripted.delegate.edit().clear().commit()
        scripted.modes.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = BrowserSettings(scripted, scope)
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
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

    private fun show() {
        compose.setContent {
            DisposableEffect(Unit) {
                compositionActive.set(true)
                onDispose { compositionActive.set(false) }
            }
            HomeEditorSheet(
                home = HomeLinks(database),
                states = states,
                settings = settings,
                initialLink = null,
                initialPage = HomeEditPage.OVERVIEW,
                onDismiss = {},
            )
        }
    }

    private fun toggleContinue() {
        compose.onNodeWithText(CONTINUE).performScrollTo().performClick()
    }

    private fun toggleFrequent() {
        compose.onNodeWithText(FREQUENT).performScrollTo().performClick()
    }

    /** A fast second visibility change must not leave the first write's failure on screen. */
    @Test
    fun aDelayedOlderFailureDoesNotShowOverANewerSelection() {
        // The first commit stays in flight across the second click, so its failure lands after
        // the newer selection has already cleared the message.
        val latch = CountDownLatch(1)
        scripted.latch = latch
        scripted.modes += ScriptedPreferences.Mode.Fail
        scripted.modes += ScriptedPreferences.Mode.Acknowledge
        show()

        toggleContinue()
        toggleFrequent()
        latch.countDown()

        compose.waitUntil(5_000) { settings.homeFrequentSites.value == HomeSectionVisibility.HIDDEN }
        compose.onNodeWithText(FAILURE).assertDoesNotExist()
    }

    /** The latest visibility request's success clears a failure that was already on screen. */
    @Test
    fun aNewerSuccessClearsAnOlderFailure() {
        scripted.modes += ScriptedPreferences.Mode.Fail
        scripted.modes += ScriptedPreferences.Mode.Acknowledge
        show()

        toggleContinue()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(FAILURE).fetchSemanticsNodes().isNotEmpty()
        }

        toggleFrequent()
        compose.waitUntil(5_000) { settings.homeFrequentSites.value == HomeSectionVisibility.HIDDEN }
        compose.onNodeWithText(FAILURE).assertDoesNotExist()
    }

    /**
     * The visibility write is admitted at the click, before the UI observer runs, and it finishes
     * even when the sheet is disposed while the disk write is still in flight.
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
                HomeEditorSheet(
                    home = HomeLinks(database),
                    states = states,
                    settings = settings,
                    initialLink = null,
                    initialPage = HomeEditPage.OVERVIEW,
                    onDismiss = {},
                )
            }
        }

        toggleContinue()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()

        // The click admitted the write: it is in flight even though the sheet is gone.
        compose.waitUntil(5_000) { scripted.commits.get() == 1 }
        latch.countDown()
        compose.waitUntil(5_000) { settings.homeContinue.value == HomeSectionVisibility.HIDDEN }
    }

    private companion object {
        const val CONTINUE = "Show Continue"
        const val FREQUENT = "Show frequent sites"
        const val FAILURE = "Could not save this change. Try again."
    }
}
