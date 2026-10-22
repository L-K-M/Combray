package ch.lkmc.combray.ui

import android.text.format.DateUtils
import ch.lkmc.combray.core.policy.HostNames

/**
 * The host as people read it: Unicode where safe, without "www.". A page without a host is named
 * by its scheme alone, never by text from its address, which the page itself can choose.
 */
fun displayHost(url: String): String {
    val address = url.trim()
    val host = HostNames.of(address) ?: return HostlessPage.of(address).label(address)
    return HostNames.forDisplay(host).removePrefix("www.")
}

/** What a page without a host shows instead. Only the scheme picks it; the rest is never shown. */
internal enum class HostlessPage {
    DATA,
    BLANK,
    LOCAL_FILE,
    OTHER,
    ;

    /** Scheme names are the same in every language, as in other browsers' address bars. */
    fun label(url: String): String = when (this) {
        DATA -> "data:"
        BLANK -> ABOUT_BLANK
        LOCAL_FILE -> "file:"
        // A dot-free scheme cannot pass for a site's name ("accounts.example.com:x" is a valid
        // scheme); anything else shows no text of its own.
        OTHER -> scheme(url).takeIf { PLAIN_SCHEME.matches(it) }?.let { "$it:" } ?: UNNAMED
    }

    companion object {
        private const val ABOUT_BLANK = "about:blank"
        private const val UNNAMED = "…"
        private val PLAIN_SCHEME = Regex("[a-z][a-z0-9+-]{0,15}")

        fun of(url: String): HostlessPage = when {
            scheme(url) == "data" -> DATA
            scheme(url) == "file" -> LOCAL_FILE
            // about:blank keeps its name with a query or fragment; other about: pages do not.
            url.substringBefore('?').substringBefore('#').equals(ABOUT_BLANK, ignoreCase = true) -> BLANK
            else -> OTHER
        }

        private fun scheme(url: String) = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    }
}

fun relativeTime(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * The share's subject: the page's title with its whitespace collapsed (a title's line break
 * must not become a second line of the share) and capped, or null when it has nothing usable.
 * The page chooses its title, so it is bounded before it goes into the share twice.
 */
fun shareTitle(title: String?): String? =
    title?.replace(TITLE_WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }?.let(::capped)

/** Java's `\s` is ASCII-only; NEL and the Unicode line and paragraph separators belong with it. */
private val TITLE_WHITESPACE = Regex("[\\s\\u0085\\u2028\\u2029]+")

/** Long enough for any real headline, short enough to stay one line in a message. */
private const val MAX_SHARE_TITLE = 200

/** At most [MAX_SHARE_TITLE] characters plus an ellipsis, never splitting a surrogate pair. */
private fun capped(title: String): String {
    if (title.length <= MAX_SHARE_TITLE) return title
    val end = if (title[MAX_SHARE_TITLE - 1].isHighSurrogate()) MAX_SHARE_TITLE - 1 else MAX_SHARE_TITLE
    return title.substring(0, end).trimEnd() + "\u2026"
}

/** What a share carries: the title as the first line (when there is one), then the address. */
fun shareText(url: String, title: String?): String =
    listOfNotNull(shareTitle(title), url).joinToString("\n")
