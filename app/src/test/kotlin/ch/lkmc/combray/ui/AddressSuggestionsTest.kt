package ch.lkmc.combray.ui

import ch.lkmc.combray.core.browser.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggestions appear only while editing, newest first, one row per address. */
class AddressSuggestionsTest {
    private val items = listOf(
        RecentItem.OpenTab("tab1", "https://news.example.org/world", "World News", 30, active = true),
        RecentItem.Visit("v1", "tab1", "https://news.example.org/world", null, 20),
        RecentItem.Visit("v2", "tab1", "https://example.org/", "Example Home", 10),
        RecentItem.Visit("v3", "tab1", "https://other.test/", "Nothing Here", 5),
    )

    @Test
    fun blankOrUntouchedTextOffersNothing() {
        assertTrue(addressSuggestions(items, "", "").isEmpty())
        assertTrue(addressSuggestions(items, "   ", "   ").isEmpty())
        // The editor opens holding the current address; suggesting it back is noise.
        assertTrue(addressSuggestions(items, "https://news.example.org/world", "https://news.example.org/world").isEmpty())
    }

    @Test
    fun editingMatchesTitleAndUrlCaseInsensitively() {
        val byTitle = addressSuggestions(items, "world", initial = "https://open.test/")
        assertEquals(listOf("tab:tab1"), byTitle.map { it.key })

        val byUrl = addressSuggestions(items, "OTHER.test", initial = "")
        assertEquals(listOf("visit:v3"), byUrl.map { it.key })
    }

    @Test
    fun oneRowPerAddressAndALimit() {
        // The open tab and its committed visit share an address: one row survives.
        val deduped = addressSuggestions(items, "news.example", initial = "")
        assertEquals(1, deduped.size)

        val many = (1..10).map { RecentItem.Visit("v$it", "t", "https://page$it.test/", "Page $it", it.toLong()) }
        assertEquals(5, addressSuggestions(many, "page", initial = "").size)
        assertEquals(2, addressSuggestions(many, "page", initial = "", limit = 2).size)
    }

    @Test
    fun blankTabsWithoutAnAddressNeverMatch() {
        val blank = RecentItem.OpenTab("tab9", url = null, title = null, time = 1, active = false)
        assertTrue(addressSuggestions(listOf(blank), "anything", initial = "").isEmpty())
    }
}
