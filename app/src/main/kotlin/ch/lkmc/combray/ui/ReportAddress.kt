package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.integration.SiteReports

/** Why the address in a report's preview cannot be sent as it is. */
internal enum class ReportAddressProblem { BLANK, TOO_LONG, CONTROL_CHARACTERS }

/** The address a report sends, as the user edits it in the preview. */
internal object ReportAddress {
    /** What the report service refuses in an address: C0, DEL and C1 controls, line breaks included. */
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

    private const val AUTHORITY_START = "://"

    /**
     * [url] with only its scheme, host, port and path: user information, path parameters
     * (`/cart;jsessionid=…`), query and fragment go, as any of them can carry a sign-in or
     * session code. A serialized URL percent-encodes `?` and `#` before its query and `@` in its
     * user information, so no cut splits a host or a path segment's name. Text without `://`
     * has no authority or path to read: only its query and fragment go.
     */
    fun siteAndPath(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val address = if (cut < 0) url else url.substring(0, cut)
        val separator = address.indexOf(AUTHORITY_START)
        if (separator < 0) return address

        val authorityStart = separator + AUTHORITY_START.length
        val pathStart = address.indexOf('/', authorityStart).takeIf { it >= 0 } ?: address.length
        val hostAndPort = address.substring(authorityStart, pathStart).substringAfterLast('@')
        val path = address.substring(pathStart).split('/').joinToString("/") { it.substringBefore(';') }
        return address.substring(0, authorityStart) + hostAndPort + path
    }

    /**
     * The address a new report starts with. Proposed default (open question 1, not an owner
     * decision): the full address, unless it looks like it carries a sign-in or session code;
     * then only its [siteAndPath], and one tap restores it.
     */
    fun starting(url: String): String = if (SensitiveUrls.looksSensitive(url)) siteAndPath(url) else url

    /** Null when the service would accept [text] as the report's address. */
    fun problem(text: String): ReportAddressProblem? = when {
        text.isBlank() -> ReportAddressProblem.BLANK
        text.toByteArray(Charsets.UTF_8).size > SiteReports.MAX_URL_BYTES -> ReportAddressProblem.TOO_LONG
        CONTROL.containsMatchIn(text) -> ReportAddressProblem.CONTROL_CHARACTERS
        else -> null
    }
}
