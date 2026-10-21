package ch.lkmc.combray.ui

import android.text.format.DateUtils
import ch.lkmc.combray.core.policy.HostNames

/**
 * The host as people read it: Unicode where safe, without "www.". A page without a host is named
 * by its scheme alone, never by text from its address, which the page itself can choose.
 */
fun displayHost(url: String): String {
    val host = HostNames.of(url) ?: return HostlessPage.of(url).label(url)
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
