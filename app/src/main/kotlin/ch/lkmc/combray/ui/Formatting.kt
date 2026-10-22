package ch.lkmc.combray.ui

import android.text.format.DateUtils
import ch.lkmc.combray.core.policy.HostNames

/** The host as people read it: Unicode where safe, without "www.". Falls back to the address. */
fun displayHost(url: String): String {
    val host = HostNames.of(url) ?: return url
    return HostNames.forDisplay(host).removePrefix("www.")
}

fun relativeTime(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * The share's subject: the page's title with its whitespace collapsed (a title's line break
 * must not become a second line of the share), or null when it has nothing usable.
 */
fun shareTitle(title: String?): String? = title?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")

/** What a share carries: the title as the first line (when there is one), then the address. */
fun shareText(url: String, title: String?): String =
    listOfNotNull(shareTitle(title), url).joinToString("\n")
