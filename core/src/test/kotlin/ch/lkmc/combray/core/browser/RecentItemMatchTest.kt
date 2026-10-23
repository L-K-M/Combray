package ch.lkmc.combray.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local Recent filter lives on RecentItem now that one flow per depth feeds every query. */
class RecentItemMatchTest {
    private val tab = RecentItem.OpenTab("tab1", "https://news.example.org/world", "World News", 10, active = false)
    private val visit = RecentItem.Visit("visit1", "tab1", "https://example.org/", "Example Home", 5)

    @Test
    fun blankQueryKeepsEverything() {
        assertTrue(tab.matches(null))
        assertTrue(tab.matches(""))
        assertTrue(tab.matches("   "))
        assertTrue(visit.matches(null))
    }

    @Test
    fun matchingIgnoresCaseAndTrimsTheQuery() {
        assertTrue(tab.matches("world news"))
        assertTrue(tab.matches("  WORLD "))
        assertTrue(visit.matches("example home"))
    }

    @Test
    fun urlCountsWhenTheTitleDoesNotMatch() {
        assertTrue(tab.matches("news.example"))
        assertTrue(visit.matches("example.org"))
        assertFalse(tab.matches("missing"))
        assertFalse(visit.matches("news.example"))
    }

    @Test
    fun missingFieldsFallBackToFalsePerSide() {
        val noTitle = RecentItem.Visit("visit2", "tab1", "https://only-url.test/", title = null, time = 0)
        assertTrue(noTitle.matches("only-url"))
        assertFalse(noTitle.matches("nothing"))

        val noUrl = RecentItem.OpenTab("tab2", url = null, title = null, time = 0, active = false)
        assertFalse(noUrl.matches("anything"))
        assertTrue(noUrl.matches(null))

        val titleOnly = RecentItem.OpenTab("tab3", url = null, title = "Kotlin Docs", time = 0, active = false)
        assertTrue(titleOnly.matches("docs"))
        assertFalse(titleOnly.matches("example.com"))
    }

    @Test
    fun theHistoryCapAppliesAfterFilteringAndNeverCutsOpenTabs() {
        val rows = listOf(
            RecentItem.OpenTab("t1", "https://a.test/", "A", 9, active = true),
            RecentItem.Visit("v1", "t1", "https://a.test/1", "Soup one", 8),
            RecentItem.Visit("v2", "t1", "https://b.test/2", "Other", 7),
            RecentItem.Visit("v3", "t1", "https://a.test/3", "Soup three", 6),
            RecentItem.OpenTab("t2", "https://a.test/tab", "Soup tab", 5, active = false),
            RecentItem.Visit("v4", "t1", "https://a.test/4", "Soup four", 4),
        )

        assertEquals(listOf("t1", "v1", "v2", "t2"), rows.matching(null, historyLimit = 2).map(::id))
        assertEquals(listOf("v1", "v3", "t2"), rows.matching("soup", historyLimit = 2).map(::id))
    }

    private fun id(item: RecentItem) = when (item) {
        is RecentItem.OpenTab -> item.tabId
        is RecentItem.Visit -> item.visitId
    }
}
