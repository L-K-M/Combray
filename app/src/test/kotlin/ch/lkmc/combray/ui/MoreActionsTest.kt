package ch.lkmc.combray.ui

import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.MediaPageRef
import ch.lkmc.combray.core.browser.PlayableMedia
import ch.lkmc.combray.engine.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreActionsTest {
    @Test
    fun aPageOffersEveryRowInOrder() {
        assertEquals(
            listOf(
                MoreAction.FORWARD,
                MoreAction.RELOAD,
                MoreAction.SHARE,
                MoreAction.PAGE_INFO,
                MoreAction.FIND,
                MoreAction.REPORT_SITE,
                MoreAction.TEXT_SIZE,
                MoreAction.SETTINGS,
            ),
            moreActions(PAGE.copy(canGoForward = true), readerDisplay = ReaderDisplay.PAGE),
        )
    }

    @Test
    fun playAppearsOnlyWithMediaBetweenFindAndReport() {
        assertEquals(false, MoreAction.PLAY_MEDIA in moreActions(PAGE, readerDisplay = ReaderDisplay.PAGE))
        val withMedia = PAGE.copy(
            media = listOf(PlayableMedia(1, MediaKind.VIDEO, "clip.mp4", "example.org")),
            mediaPage = MediaPageRef("tab", 1),
        )
        assertEquals(
            listOf(MoreAction.FIND, MoreAction.PLAY_MEDIA, MoreAction.REPORT_SITE, MoreAction.TEXT_SIZE),
            moreActions(withMedia, readerDisplay = ReaderDisplay.PAGE).dropWhile { it != MoreAction.FIND }.take(4),
        )
        // Reader view covers the page, not its media.
        assertEquals(true, MoreAction.PLAY_MEDIA in moreActions(withMedia, readerDisplay = ReaderDisplay.ARTICLE))
    }

    /** Find searches the page, which reader view covers. */
    @Test
    fun readerViewHasNoFind() {
        val rows = moreActions(PAGE, readerDisplay = ReaderDisplay.ARTICLE)
        assertEquals(false, MoreAction.FIND in rows)
        assertEquals(true, MoreAction.SHARE in rows)
    }

    /** Reader view, an error page and a crashed tab are all worth reporting; Home is not a site. */
    @Test
    fun reportIsOfferedWheneverTheTabHasAnAddress() {
        assertEquals(
            listOf(MoreAction.PAGE_INFO, MoreAction.REPORT_SITE, MoreAction.TEXT_SIZE),
            moreActions(PAGE, readerDisplay = ReaderDisplay.ARTICLE).dropWhile { it != MoreAction.PAGE_INFO }.take(3),
        )
        assertEquals(true, MoreAction.REPORT_SITE in moreActions(PAGE.copy(loadFailed = true), readerDisplay = ReaderDisplay.PAGE))
        assertEquals(true, MoreAction.REPORT_SITE in moreActions(PAGE.copy(crashed = true, hasView = false), readerDisplay = ReaderDisplay.PAGE))
        assertEquals(false, MoreAction.REPORT_SITE in moreActions(PAGE.copy(url = null, title = null, hasView = false), readerDisplay = ReaderDisplay.PAGE))
    }

    @Test
    fun aNewTabHomeHasNoPageRows() {
        assertEquals(
            listOf(MoreAction.TEXT_SIZE, MoreAction.SETTINGS),
            moreActions(PAGE.copy(url = null, title = null, hasView = false), readerDisplay = ReaderDisplay.PAGE),
        )
    }

    @Test
    fun withoutATabThereIsNothingToClose() {
        assertEquals(listOf(MoreAction.TEXT_SIZE, MoreAction.SETTINGS), moreActions(null, readerDisplay = ReaderDisplay.PAGE))
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
