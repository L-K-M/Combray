package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.KarakeepBookmark
import ch.lkmc.combray.core.integration.KarakeepPage
import ch.lkmc.combray.core.integration.KarakeepRead
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

/** Delayed application-service reads, not HTTP or synthetic engine events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class SearchPanelPaginationTest {
    @get:Rule val compose = createComposeRule()
    private val service = mutableStateOf<ServiceEntity?>(SERVICE)
    private val requests = CopyOnWriteArrayList<Read>()

    @After
    fun releaseOutstandingReads() {
        requests.forEach { it.answer.complete(KarakeepRead.Unreachable("test_ended")) }
    }

    @Test
    fun twoQueuedTapsStartOnlyOnePageAndTheButtonWaits() {
        showFirstPage()
        val click = compose.onNodeWithText(MORE).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Two already queued taps can both carry the enabled button's action from the same frame.
        compose.runOnIdle { click(); click() }
        request(1)
        compose.onNodeWithText(MORE).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, requests.size) }

        answer(1, page("Beta", "page-3"))
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText(MORE).assertIsEnabled()
        compose.onNodeWithText(MORE).performClick()
        assertEquals("page-3", request(2).page.cursor)
        answer(2, page("Gamma", null))
        compose.onNodeWithText(MORE).assertDoesNotExist()
    }

    @Test
    fun aFailedPageKeepsRowsAndCanBeRetried() {
        showFirstPage()
        compose.onNodeWithText(MORE).performClick()
        answer(1, KarakeepRead.Unreachable("network"))
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Could not reach Karakeep (network).").assertExists()
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("page-2", request(2).page.cursor)
        answer(2, page("Beta", null))
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("Could not reach Karakeep (network).").assertDoesNotExist()
    }

    @Test
    fun anOldQuerysPageCannotAppendOrReleaseANewerPagesGate() {
        verifyLatePage {
            compose.onNode(hasSetTextAction()).performTextInput("new")
        }
        assertEquals("new", requests[2].page.query)
        assertEquals("new", requests[3].page.query)
    }

    @Test
    fun anOldDestinationsPageCannotAppendOrReleaseANewerPagesGate() {
        verifyLatePage {
            compose.runOnIdle {
                service.value = SERVICE.copy(baseUrl = "https://other.example", fingerprint = "other", generation = 2)
            }
        }
        assertEquals("", requests[2].page.query)
        // The old page request still names the destination that produced it.
        assertEquals("keep", requests[1].page.service?.fingerprint)
        assertEquals("other", requests[2].page.service?.fingerprint)
    }

    @Test
    fun aSubmittedSeedPageIsExtendedWithItsOwnQuery() {
        compose.setContent {
            CombrayTheme {
                Column(Modifier.fillMaxSize()) {
                    KarakeepPanelContent(service.value, "recipe", ::read, {}, {})
                }
            }
        }
        compose.runOnIdle { assertEquals(0, requests.size) }
        compose.onNode(hasSetTextAction()).performImeAction()
        request(0)
        answer(0, page("Alpha", "page-2"))
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("recipe", request(1).page.query)
        assertEquals("page-2", request(1).page.cursor)
        answer(1, page("Beta", null))
        compose.onNodeWithText("Beta").assertExists()
    }

    @Test
    fun aRetainedPageIsNotExtendedWhileANewSearchRuns() {
        showFirstPage()
        compose.onNode(hasSetTextAction()).performTextInput("new")
        request(1)
        // The first answer is retained, but its page belongs to the previous generation: the
        // button waits for the new search instead of sending the old cursor with the new query.
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText(MORE).assertIsNotEnabled()

        answer(1, page("New answer", "new-page-2"))
        compose.onNodeWithText(MORE).assertIsEnabled().performClick()
        assertEquals("new-page-2", request(2).page.cursor)
        assertEquals("new", request(2).page.query)
        answer(2, page("New second page", null))
        compose.onNodeWithText("New second page").assertExists()
    }

    private fun verifyLatePage(change: () -> Unit) {
        showFirstPage()
        compose.onNodeWithText(MORE).performClick()
        request(1)
        change()
        compose.waitForIdle()
        answer(2, page("New answer", "new-page-2"))
        compose.onNodeWithText(MORE).performClick()
        assertEquals("new-page-2", request(3).page.cursor)
        answer(1, page("Stale answer", "stale-page-3"))
        compose.onNodeWithText("Stale answer").assertDoesNotExist()
        compose.onNodeWithText("New answer").assertExists()
        compose.onNodeWithText(MORE).assertIsNotEnabled()
        answer(3, page("New second page", null))
        compose.onNodeWithText("New second page").assertExists()
        compose.onNodeWithText(MORE).assertDoesNotExist()
    }

    private fun showFirstPage() {
        compose.setContent {
            CombrayTheme {
                Column(Modifier.fillMaxSize()) {
                    KarakeepPanelContent(service.value, "", ::read, {}, {})
                }
            }
        }
        assertEquals(null, request(0).page.cursor)
        answer(0, page("Alpha", "page-2"))
        compose.onNodeWithText(MORE).assertIsEnabled()
    }

    private suspend fun read(page: SearchPage): KarakeepRead<KarakeepPage>? {
        val request = Read(page)
        requests += request
        // A blocking native HTTP request can finish after cancellation: model that late answer.
        return withContext(NonCancellable) { request.answer.await() }
    }

    private fun request(index: Int): Read {
        compose.waitUntil(5_000) { requests.size > index }
        return requests[index]
    }

    private fun answer(index: Int, result: KarakeepRead<KarakeepPage>) {
        val request = request(index)
        compose.runOnIdle { request.answer.complete(result) }
        compose.waitForIdle()
    }

    private fun page(title: String, next: String?) = KarakeepRead.Ok(
        KarakeepPage(listOf(KarakeepBookmark("id-$title", "https://example.org/${title.replace(' ', '-')}", title)), next),
    )

    private data class Read(
        val page: SearchPage,
        val answer: CompletableDeferred<KarakeepRead<KarakeepPage>?> = CompletableDeferred(),
    )

    private companion object {
        const val MORE = "More results"
        val SERVICE = ServiceEntity(ServiceKind.KARAKEEP, "https://keep.example", "keep", 1, false, 0)
    }
}
