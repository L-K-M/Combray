package ch.lkmc.combray.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSuggestionsTest {
    private fun tab(id: String, url: String?, time: Long, active: Boolean = false, title: String? = null) =
        RecentItem.OpenTab(id, url, title, time, active)

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
