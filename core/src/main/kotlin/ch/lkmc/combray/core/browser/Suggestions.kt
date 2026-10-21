package ch.lkmc.combray.core.browser

import ch.lkmc.combray.core.policy.HostNames

/**
 * What the address sheet offers while you type: matches from local Recent only, so typing
 * contacts no server. Rows rank by kind, then by how the text matches, then by recency:
 *
 * ```
 * 1. open tabs                     switching to one avoids a second tab on the same page
 * 2. host starts with the text     "git" -> github.com
 * 3. title or address contains it  what Recent's filter matched
 * ```
 */
object AddressSuggestions {
    private const val MAX_SUGGESTIONS = 5

    private const val WWW = "www."

    /**
     * Ranks [matches], the output of [BrowserCoordinator.recent] for [query]. The tab the editor
     * was opened for ([editedTab]) and the focused tab are left out: switching to them changes
     * nothing. A visit at an address an open tab shows is represented by that tab (even a left
     * out one), and repeated visits to one address by the first of them.
     */
    fun rank(matches: List<RecentItem>, query: String, editedTab: String?): List<RecentItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val openAddresses = matches.filterIsInstance<RecentItem.OpenTab>().mapNotNullTo(HashSet()) { it.url }
        val candidates = matches.filterNot { it is RecentItem.OpenTab && (it.tabId == editedTab || it.active) }
        val ranked = candidates
            .map { it to hostStartsWith(it.url, needle) }
            .sortedWith(
                compareBy<Pair<RecentItem, Boolean>> { (item, _) -> item !is RecentItem.OpenTab }
                    .thenBy { (_, hostMatch) -> !hostMatch }
                    .thenByDescending { (item, _) -> item.time },
            )
            .map { it.first }

        val listedVisits = HashSet<String>()
        return ranked
            .filter { it !is RecentItem.Visit || (it.url !in openAddresses && listedVisits.add(it.url)) }
            .take(MAX_SUGGESTIONS)
    }

    /** Whether the host, as stored or as shown, starts with [needle], with or without "www.". */
    private fun hostStartsWith(url: String?, needle: String): Boolean {
        val host = url?.let(HostNames::of) ?: return false
        val shown = HostNames.forDisplay(host).lowercase()
        return listOf(host, shown).any { it.startsWith(needle) || it.removePrefix(WWW).startsWith(needle) }
    }
}
