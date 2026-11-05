package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.integration.SiteReports

/** Why the address in a report's preview cannot be sent as it is. */
internal enum class ReportAddressProblem { BLANK, TOO_LONG, CONTROL_CHARACTERS }

/** The address a report sends, as the user edits it in the preview. */
internal object ReportAddress {
    /** What the report service refuses in an address: C0, DEL and C1 controls, line breaks included. */
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

    /**
     * [url] without its query and fragment: everything from the first `?` or `#` on. A
     * serialized URL percent-encodes both characters before its query, so scheme, user
     * information, host, port and path are never cut. Text without either is returned as is.
     */
    fun trimQueryAndFragment(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        return if (cut < 0) url else url.substring(0, cut)
    }

    /**
     * The address a new report starts with. Proposed default (open question 1, not an owner
     * decision): the full address, unless it looks like it carries a sign-in or session code;
     * then without query and fragment, and one tap restores it.
     */
    fun starting(url: String): String = if (SensitiveUrls.looksSensitive(url)) trimQueryAndFragment(url) else url

    /** Null when the service would accept [text] as the report's address. */
    fun problem(text: String): ReportAddressProblem? = when {
        text.isBlank() -> ReportAddressProblem.BLANK
        text.toByteArray(Charsets.UTF_8).size > SiteReports.MAX_URL_BYTES -> ReportAddressProblem.TOO_LONG
        CONTROL.containsMatchIn(text) -> ReportAddressProblem.CONTROL_CHARACTERS
        else -> null
    }
}
