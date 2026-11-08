package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.HisterHit
import ch.lkmc.combray.core.integration.HisterSearch
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Hister scope keeps the last answer while a search runs and binds pages to their search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SearchPanelResultsTest {
    @get:Rule val compose = createComposeRule()
    private val service = mutableStateOf<ServiceEntity?>(SERVICE)
    private val requests = CopyOnWriteArrayList<Read>()

    @After
    fun releaseOutstandingSearches() {
        requests.forEach { it.answer.complete(HisterSearch.Unreachable("test_ended")) }
    }

    @Test
    fun oldHitsStayWhileANewSearchRuns() {
        showFirstSearch()
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        request(1)
        compose.onNodeWithText("One").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        answer(1, results("Two", null))
        compose.onNodeWithText("Two").assertIsDisplayed()
    }

    @Test
    fun aCapturePolicyChangeDoesNotRestartTheSearchOrDiscardItsCursor() {
        showFirstSearch(next = "page-2")
        compose.runOnIdle {
            service.value = SERVICE.copy(captureEnabled = !SERVICE.captureEnabled, updatedAt = SERVICE.updatedAt + 1)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("Capture policy does not change the remote search", 1, requests.size)
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("page-2", request(1).page.cursor)
        answer(1, results("Two", null))
        compose.onNodeWithText("One").assertExists()
        compose.onNodeWithText("Two").assertExists()
    }

    @Test
    fun aFailedSearchShowsTheError() {
        showFirstSearch()
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        request(1)
        answer(1, HisterSearch.Unreachable("network"))
        compose.onNodeWithText("Could not reach Hister (network).", substring = true).assertExists()
        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun aStaleSearchDoesNotReplaceNewerState() {
        showFirstSearch()
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        request(1)
        answer(1, results("Two", null))
        answer(0, results("One", null))
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithText("Two").assertIsDisplayed()
    }

    @Test
    fun aPageFailureKeepsRowsAndCursor() {
        showFirstSearch(next = "page-2")
        compose.onNodeWithText(MORE).performClick()
        answer(1, HisterSearch.Unreachable("network"))
        compose.onNodeWithText("One").assertExists()
        compose.onNodeWithText("Could not reach Hister (network).", substring = true).assertExists()
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("page-2", request(2).page.cursor)
        answer(2, results("Two", null))
        compose.onNodeWithText("One").assertExists()
        compose.onNodeWithText("Two").assertExists()
        compose.onNodeWithText("Could not reach Hister (network).", substring = true).assertDoesNotExist()
    }

    @Test
    fun twoQueuedTapsStartOnlyOnePage() {
        showFirstSearch(next = "page-2")
        val click = compose.onNodeWithText(MORE).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        request(1)
        compose.onNodeWithText(MORE).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, requests.size) }

        answer(1, results("Two", null))
        compose.onNodeWithText("Two").assertExists()
        compose.onNodeWithText(MORE).assertDoesNotExist()
    }

    @Test
    fun aSubmittedSeedPageIsExtendedWithItsOwnQuery() {
        compose.setContent {
            CombrayTheme {
                Column(Modifier.fillMaxSize()) {
                    HisterSearchPanelContent(service.value, "recipe", ::search, {}, {})
                }
            }
        }
        compose.runOnIdle { assertEquals(0, requests.size) }
        compose.onNode(hasSetTextAction()).performImeAction()
        request(0)
        answer(0, results("One", "page-2"))
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("recipe", request(1).page.query)
        assertEquals("page-2", request(1).page.cursor)
        answer(1, results("Two", null))
        compose.onNodeWithText("Two").assertExists()
    }

    @Test
    fun aRetainedPageIsNotExtendedWhileANewSearchRuns() {
        showFirstSearch(next = "page-2")
        compose.onNode(hasSetTextAction()).performTextInput(" two")
        request(1)
        compose.onNodeWithText("One").assertExists()
        compose.onNodeWithText(MORE).assertIsNotEnabled()

        answer(1, results("Two", "new-page-2"))
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("new-page-2", request(2).page.cursor)
        assertEquals("one two", request(2).page.query)
        answer(2, results("Three", null))
        compose.onNodeWithText("Three").assertExists()
    }

    private fun showFirstSearch(next: String? = null) {
        compose.setContent {
            CombrayTheme {
                Column(Modifier.fillMaxSize()) {
                    HisterSearchPanelContent(service.value, "", ::search, {}, {})
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("one")
        request(0)
        answer(0, results("One", next))
        compose.onNodeWithText("One").assertExists()
    }

    private suspend fun search(page: SearchPage): HisterSearch? {
        val request = Read(page)
        requests += request
        return withContext(NonCancellable) { request.answer.await() }
    }

    private fun request(index: Int): Read {
        compose.waitUntil(5_000) { requests.size > index }
        return requests[index]
    }

    private fun answer(index: Int, result: HisterSearch) {
        val request = request(index)
        compose.runOnIdle { request.answer.complete(result) }
        compose.waitForIdle()
    }

    private fun results(title: String, next: String?): HisterSearch.Results {
        val hit = HisterHit("https://example.org/${title.replace(' ', '-')}", title, null, false)
        return HisterSearch.Results(listOf(hit), 1, next)
    }

    private data class Read(
        val page: SearchPage,
        val answer: CompletableDeferred<HisterSearch?> = CompletableDeferred(),
    )

    private companion object {
        const val MORE = "More results"
        val SERVICE = ServiceEntity(ServiceKind.HISTER, "https://hister.example", "hister", 1, false, 0)
    }
}
