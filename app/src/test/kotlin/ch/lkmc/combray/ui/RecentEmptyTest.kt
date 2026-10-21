package ch.lkmc.combray.ui

import ch.lkmc.combray.core.browser.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentEmptyTest {
    private val tab = RecentItem.OpenTab(tabId = "t1", url = "https://example.org/", title = "Example", time = 2, active = true)
    private val visit = RecentItem.Visit(visitId = "v1", tabId = "t0", url = "https://example.org/old", title = "Old", time = 1)

    @Test
    fun rowsShownMeanNoEmptyMessage() {
        assertNull(recentEmpty(query = "", all = listOf(tab, visit), shown = listOf(tab)))
    }

    @Test
    fun anEmptyRecentSaysSo() {
        assertEquals(RecentEmpty.NOTHING, recentEmpty(query = "", all = emptyList(), shown = emptyList()))
        assertEquals(RecentEmpty.NO_MATCHES, recentEmpty(query = "kagi", all = emptyList(), shown = emptyList()))
    }

    @Test
    fun openTabsWithoutTabsPointToTheRecentPages() {
        // The last tab was closed: its page is under All, so Recent is not empty.
        val empty = recentEmpty(query = "", all = listOf(visit), shown = emptyList())
        assertEquals(RecentEmpty.NO_OPEN_TABS, empty)
        assertEquals(true, empty?.offersAll)
    }

    @Test
    fun openTabsWithoutMatchesPointToMatchingRecentPages() {
        val empty = recentEmpty(query = "old", all = listOf(visit), shown = emptyList())
        assertEquals(RecentEmpty.NO_OPEN_TAB_MATCHES, empty)
        assertEquals(true, empty?.offersAll)
        assertEquals(false, RecentEmpty.NOTHING.offersAll)
        assertEquals(false, RecentEmpty.NO_MATCHES.offersAll)
    }
}
