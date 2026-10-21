package ch.lkmc.combray.core.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local Recent filter lives on RecentItem now that one flow feeds every query. */
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
    }
}
