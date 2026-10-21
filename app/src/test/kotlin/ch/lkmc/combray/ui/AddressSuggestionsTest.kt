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

        // Newest first, even when the input arrives oldest first.
        val many = (1..10).map { RecentItem.Visit("v$it", "t", "https://page$it.test/", "Page $it", it.toLong()) }
        assertEquals(
            listOf("visit:v10", "visit:v9", "visit:v8", "visit:v7", "visit:v6"),
            addressSuggestions(many, "page", initial = "").map { it.key },
        )
        assertEquals(
            listOf("visit:v10", "visit:v9"),
            addressSuggestions(many, "page", initial = "", limit = 2).map { it.key },
        )
    }

    @Test
    fun rowsWithoutAnAddressOrWithoutAPassableReadingAreNotOffered() {
        // A title-only row would match the filter but render nothing in SuggestionRow.
        val titleOnly = RecentItem.OpenTab("tabT", url = null, title = "World News", time = 1, active = false)
        assertTrue(addressSuggestions(listOf(titleOnly), "world", initial = "").isEmpty())

        // A stored URL the address policy now rejects would be a dead, tappable row:
        // the typed path never submits a rejected reading either.
        val credentials = RecentItem.Visit("vc", "t", "https://user:pass@inner.test/", "inner", time = 1)
        assertTrue(addressSuggestions(listOf(credentials), "inner.test", initial = "").isEmpty())
    }
}
