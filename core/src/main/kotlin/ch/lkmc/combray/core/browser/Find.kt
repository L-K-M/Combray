package ch.lkmc.combray.core.browser

/** Which match a find request selects. */
enum class FindStep {
    /** The first match of a new query. */
    FIRST,
    NEXT,
    PREVIOUS,
}

/** Find in page over the focused tab, while the find bar is open. */
data class FindState(
    /** The query the page was last asked about; blank before the first search. */
    val query: String,
    /** The page's answer for [query]; null until it arrives, or while [query] is blank. */
    val outcome: FindOutcome?,
)

sealed interface FindOutcome {
    /** [count] matches (a lower bound when [capped]); match [index] (0-based) is selected. */
    data class Found(val count: Int, val index: Int, val capped: Boolean) : FindOutcome

    /** Nothing matched; when [capped], only part of the page was searched. */
    data class NotFound(val capped: Boolean) : FindOutcome

    /** The page could not be searched (its script failed or answered something unusable). */
    data object Failed : FindOutcome
}

/**
 * The match index to ask the page for. Stepping past either end wraps around; a negative index
 * asks the page for its last match, since the coordinator may not know the full count. When the
 * count is only a lower bound, Next keeps going and the page wraps once it runs out.
 */
internal fun findIndex(step: FindStep, current: FindOutcome.Found?): Int {
    if (current == null) {
        return if (step == FindStep.PREVIOUS) LAST_MATCH else 0
    }
    return when (step) {
        FindStep.FIRST -> 0
        FindStep.NEXT -> if (!current.capped && current.index >= current.count - 1) 0 else current.index + 1
        FindStep.PREVIOUS -> if (current.index <= 0) LAST_MATCH else current.index - 1
    }
}

/**
 * Longest find query, in UTF-16 units: well under the engine's 1 KiB bound on the script's query
 * (native/src/find.rs), so the page is never asked about a cut query the user did not type.
 */
const val MAX_FIND_QUERY_LENGTH = 256

/** [query] cut to [MAX_FIND_QUERY_LENGTH] without splitting a surrogate pair. */
internal fun boundedFindQuery(query: String): String {
    if (query.length <= MAX_FIND_QUERY_LENGTH) return query
    val cut = query.take(MAX_FIND_QUERY_LENGTH)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Asks the page for its last match (native/src/find.js counts negative indexes from the end). */
private const val LAST_MATCH = -1
