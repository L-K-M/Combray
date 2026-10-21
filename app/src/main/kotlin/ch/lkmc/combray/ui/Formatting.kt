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

/** What a share carries: the title as the first line (when there is one), then the address. */
fun shareText(url: String, title: String?): String =
    listOfNotNull(title?.takeIf { it.isNotBlank() }, url).joinToString("\n")
