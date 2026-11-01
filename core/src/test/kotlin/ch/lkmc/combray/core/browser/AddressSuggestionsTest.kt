package ch.lkmc.combray.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSuggestionsTest {
    private fun tab(id: String, url: String?, time: Long, active: Boolean = false, title: String? = null) =
        RecentItem.OpenTab(id, url, title, time, active, createdAt = time)

    private fun visit(id: String, url: String, time: Long, title: String? = null) =
        RecentItem.Visit(id, "closed-tab", url, title, time)

    private fun keys(items: List<RecentItem>) = items.map { it.key }

    @Test
    fun openTabsComeFirstThenHostMatchesThenRecency() {
        val matches = listOf(
            visit("new-mention", "https://news.example/about-github", time = 50),
            visit("old-host", "https://github.com/servo", time = 10),
            tab("mention-tab", "https://blog.example/github-tips", time = 5),
            visit("new-host", "https://www.github.com/rust", time = 40),
            tab("host-tab", "https://github.com/", time = 1),
            visit("old-mention", "https://docs.example/?ref=github", time = 30),
        )

        val ranked = AddressSuggestions.rank(matches, "Git", editedTab = null)

        assertEquals(
            listOf("tab:host-tab", "tab:mention-tab", "visit:new-host", "visit:old-host", "visit:new-mention"),
            keys(ranked),
        )
    }

    @Test
    fun theEditedAndFocusedTabsAreNotOffered() {
        val matches = listOf(
            tab("edited", "https://github.com/a", time = 3),
            tab("focused", "https://github.com/b", time = 2, active = true),
            tab("other", "https://github.com/c", time = 1),
        )

        assertEquals(listOf("tab:other"), keys(AddressSuggestions.rank(matches, "git", editedTab = "edited")))
    }

    @Test
    fun aVisitAtAnOpenTabsAddressShowsAsThatTabAndRepeatedVisitsOnce() {
        val matches = listOf(
            tab("open", "https://github.com/servo", time = 1),
            tab("edited", "https://github.com/edited", time = 2),
            visit("same-as-tab", "https://github.com/servo", time = 9),
            visit("same-as-edited", "https://github.com/edited", time = 8),
            visit("repeat-new", "https://github.com/rust", time = 7),
            visit("repeat-old", "https://github.com/rust", time = 6),
        )

        val ranked = AddressSuggestions.rank(matches, "github", editedTab = "edited")

        assertEquals(listOf("tab:open", "visit:repeat-new"), keys(ranked))
    }

    @Test
    fun blankOrUntouchedTextOffersNothing() {
        assertFalse(AddressSuggestions.shouldSuggest("", prefilled = ""))
        assertFalse(AddressSuggestions.shouldSuggest("   ", prefilled = ""))
        // The editor opens holding the current address; suggesting it back is noise.
        assertFalse(AddressSuggestions.shouldSuggest("https://news.example/", prefilled = "https://news.example/"))
        assertTrue(AddressSuggestions.shouldSuggest("https://news.example/w", prefilled = "https://news.example/"))
    }

    @Test
    fun rowsWithoutAnAddressOrWithoutAPassableReadingAreNotOffered() {
        // A title-only row would match the filter but leave nothing to open.
        val titleOnly = tab("title-only", url = null, time = 2, title = "Inner news")
        // A stored address the address policy now rejects would be a dead, tappable row.
        val credentials = visit("credentials", "https://user:pass@inner.test/", time = 1, title = "Inner news")

        assertTrue(AddressSuggestions.rank(listOf(titleOnly, credentials), "inner", null).isEmpty())
    }

    @Test
    fun theNewestOfRepeatedVisitsSurvives() {
        val matches = listOf(visit("old", "https://a.example/", time = 3), visit("new", "https://a.example/", time = 30))

        assertEquals(listOf("visit:new"), keys(AddressSuggestions.rank(matches, "a.example", null)))
    }

    @Test
    fun aHistoryRowOpensThroughTheAddressPolicy() {
        assertEquals("https://a.example/", AddressSuggestions.navigation(visit("v", "https://a.example/", time = 1))?.url)
        assertEquals(null, AddressSuggestions.navigation(visit("c", "https://user:pass@inner.test/", time = 1)))
    }

    @Test
    fun atMostFiveAndNothingForBlankText() {
        val matches = (1..8).map { visit("v$it", "https://example.org/$it", time = it.toLong()) }

        assertEquals(listOf("visit:v8", "visit:v7", "visit:v6", "visit:v5", "visit:v4"), keys(AddressSuggestions.rank(matches, "example", null)))
        assertTrue(AddressSuggestions.rank(matches, "  ", null).isEmpty())
    }

    @Test
    fun aUnicodeHostMatchesAsShown() {
        val matches = listOf(
            visit("mention", "https://example.org/bücher", time = 9, title = "Bücher"),
            visit("host", "https://xn--bcher-kva.de/", time = 1),
        )

        assertEquals(listOf("visit:host", "visit:mention"), keys(AddressSuggestions.rank(matches, "bü", null)))
    }
}
