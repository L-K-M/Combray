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
                MoreAction.CLOSE_TAB,
                MoreAction.PAGE_INFO,
                MoreAction.READER,
                MoreAction.FIND,
                MoreAction.TEXT_SIZE,
                MoreAction.SETTINGS,
            ),
            moreActions(PAGE.copy(canGoForward = true), readerOpen = false),
        )
    }

    /** Find searches the page, which reader view covers. */
    @Test
    fun readerViewHasNoFind() {
        val rows = moreActions(PAGE, readerOpen = true)
        assertEquals(false, MoreAction.FIND in rows)
        assertEquals(true, MoreAction.READER in rows)
    }

    @Test
    fun aNewTabHomeCanBeClosedButHasNoPageRows() {
        assertEquals(
            listOf(MoreAction.CLOSE_TAB, MoreAction.TEXT_SIZE, MoreAction.SETTINGS),
            moreActions(PAGE.copy(url = null, title = null, hasView = false), readerOpen = false),
        )
    }

    @Test
    fun withoutATabThereIsNothingToClose() {
        assertEquals(listOf(MoreAction.TEXT_SIZE, MoreAction.SETTINGS), moreActions(null, readerOpen = false))
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
