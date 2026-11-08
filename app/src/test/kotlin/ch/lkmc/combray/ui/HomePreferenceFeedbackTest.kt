package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Home editor's visibility switches fence their failure message to the click-time request
 * generation. A delayed failure from an older request must not appear over a newer selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class HomePreferenceFeedbackTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scripted = ScriptedPreferences(
        context.getSharedPreferences("home-feedback-test", Context.MODE_PRIVATE),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: BrowserSettings
    private lateinit var database: CombrayDatabase
    private val states = MutableStateFlow(HomeLinksState(emptyList(), emptyList(), emptyList()))

    @Before
    fun setUp() {
        scripted.delegate.edit().clear().commit()
        scripted.modes.clear()
        settings = BrowserSettings(scripted, scope)
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun show() {
        compose.setContent {
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
