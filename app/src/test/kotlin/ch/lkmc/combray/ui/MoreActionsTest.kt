package ch.lkmc.combray.ui

import ch.lkmc.combray.core.browser.ActiveTab
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreActionsTest {
    @Test
    fun aPageOffersEveryRowInOrder() {
        assertEquals(
            listOf(
                MoreAction.FORWARD,
                MoreAction.RELOAD,
                MoreAction.NEW_TAB,
                MoreAction.CLOSE_TAB,
                MoreAction.PAGE_INFO,
                MoreAction.READER,
                MoreAction.PAUSE_CAPTURE,
                MoreAction.SETTINGS,
            ),
            moreActions(PAGE.copy(canGoForward = true), captureEnabled = true),
        )
    }

    @Test
    fun aNewTabHomeCanBeClosedButHasNoPageRows() {
        assertEquals(
            listOf(MoreAction.NEW_TAB, MoreAction.CLOSE_TAB, MoreAction.SETTINGS),
            moreActions(PAGE.copy(url = null, title = null, hasView = false), captureEnabled = null),
        )
    }

    @Test
    fun withoutATabThereIsNothingToClose() {
        assertEquals(listOf(MoreAction.NEW_TAB, MoreAction.SETTINGS), moreActions(null, captureEnabled = null))
    }

    /** Paused capture offers its way back, through Settings, which explains what is sent. */
    @Test
    fun pausedCaptureOffersResume() {
        assertEquals(
            listOf(
                MoreAction.RELOAD,
                MoreAction.NEW_TAB,
                MoreAction.CLOSE_TAB,
                MoreAction.PAGE_INFO,
                MoreAction.READER,
                MoreAction.RESUME_CAPTURE,
                MoreAction.SETTINGS,
            ),
            moreActions(PAGE, captureEnabled = false),
        )
    }

    private companion object {
        val PAGE = ActiveTab(
            id = "tab",
            url = "https://example.org/article",
            title = "Article",
            loading = false,
            canGoBack = true,
            canGoForward = false,
            crashed = false,
            hasView = true,
        )
    }
}
