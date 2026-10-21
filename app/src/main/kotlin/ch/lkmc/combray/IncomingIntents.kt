package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy

/** What an intent from another app or a launcher shortcut asks Combray to do. */
sealed interface Incoming {
    /** Open [url] in a new tab; Back on its first page returns to the other app. */
    data class Open(val url: String) : Incoming
}

/** A sheet the browser screen opens once, because an intent asked for it. */
sealed interface LaunchRequest : Incoming {
    /** The address editor for a new tab, starting with [text] selected. Nothing is sent before the user submits. */
    data class EditNewTab(val text: String) : LaunchRequest

    /** Recent, on its Hister scope. */
    data object SearchHistory : LaunchRequest
}

/**
 * Reads the intents other apps and launcher shortcuts send. Addresses go through
 * [AddressPolicy] like typed input. Shared text reaches Kagi only after an explicit submit,
 * except from "Search with Kagi", which the user chose for it in the other app.
 *
 * ```
 * SEND "see https://example.org/a."   -> Open https://example.org/a
 * SEND "madeleine"                    -> EditNewTab "madeleine"
 * WEB_SEARCH "madeleine"              -> EditNewTab "madeleine"
 * PROCESS_TEXT "madeleine"            -> Open https://kagi.com/search?q=madeleine
 * ```
 */
object IncomingIntents {
    /** Launcher shortcut: the address editor for a new tab. */
    const val ACTION_NEW_TAB = "ch.lkmc.combray.action.NEW_TAB"

    /** Launcher shortcut: Recent on its Hister scope. */
    const val ACTION_SEARCH_HISTORY = "ch.lkmc.combray.action.SEARCH_HISTORY"

    // The address field holds one line; longer text is cut before it is shown or searched.
    private const val MAX_TEXT_LENGTH = 2_000

    private val WEB_ADDRESS = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    // After a link in prose these end the sentence or close a quote: not taken as part of it.
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"”’>"

    // Closing brackets, trimmed only when the link does not open them itself.
    private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[')

    /** [extra] reads one of the intent's text extras by name. */
    fun interpret(action: String?, data: String?, extra: (String) -> String?): Incoming? = when (action) {
        Intent.ACTION_VIEW -> data?.let(::open)
        Intent.ACTION_SEND -> sharedText(extra(Intent.EXTRA_TEXT), extra(Intent.EXTRA_SUBJECT))?.let(::shared)
        Intent.ACTION_WEB_SEARCH -> extra(SearchManager.QUERY)?.let(::editNewTab)
        Intent.ACTION_PROCESS_TEXT -> extra(Intent.EXTRA_PROCESS_TEXT)?.let(::searchNow)
        ACTION_NEW_TAB -> LaunchRequest.EditNewTab("")
        ACTION_SEARCH_HISTORY -> LaunchRequest.SearchHistory
        else -> null
    }

    /**
     * The first http(s) link in [text], without the punctuation or bracket that closes the
     * sentence around it: "(see https://example.org/Foo_(bar))." -> "https://example.org/Foo_(bar)".
     */
    fun firstWebAddress(text: String): String? {
        var address = WEB_ADDRESS.find(text)?.value ?: return null
        while (endsOutsideTheLink(address)) address = address.dropLast(1)
        return address.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    private fun endsOutsideTheLink(address: String): Boolean {
        val last = address.lastOrNull() ?: return false
        if (last in TRAILING_PUNCTUATION) return true
        val opening = BRACKET_PAIRS[last] ?: return false
        return address.count { it == last } > address.count { it == opening }
    }

    private fun open(address: String): Incoming.Open? =
        (AddressPolicy.interpret(address).primary as? AddressDecision.Navigate)?.let { Incoming.Open(it.url) }

    /** A shared link opens at once; other shared text waits in the editor. */
    /**
     * What a share is about: the text, unless only the subject holds a link (some apps share a
     * link as the subject, with no text or an empty one).
     */
    private fun sharedText(text: String?, subject: String?): String? = when {
        text != null && firstWebAddress(text) != null -> text
        subject != null && firstWebAddress(subject) != null -> subject
        else -> text?.takeIf { it.isNotBlank() } ?: subject
    }

    private fun shared(text: String): Incoming? {
        val address = firstWebAddress(text) ?: return editNewTab(text)
        // A refused link (credentials in it) goes to the editor alone, which says why. With the
        // text around it, it would read as a query one tap away from Kagi.
        return open(address) ?: editNewTab(address)
    }

    /** The user chose "Search with Kagi" for this text: search, or open it if it is an address. */
    private fun searchNow(text: String): Incoming? {
        val query = oneLine(text) ?: return null
        return when (val decision = AddressPolicy.interpret(query).primary) {
            is AddressDecision.Navigate -> Incoming.Open(decision.url)
            is AddressDecision.Search -> Incoming.Open(decision.url)
            // mailto:, tel: and refused addresses: the editor says what they are.
            is AddressDecision.External, is AddressDecision.Rejected -> LaunchRequest.EditNewTab(query)
        }
    }

    private fun editNewTab(text: String): LaunchRequest.EditNewTab? = oneLine(text)?.let { LaunchRequest.EditNewTab(it) }

    /** [text] as one bounded line, or null when it is blank. */
    private fun oneLine(text: String): String? {
        val line = text.trim().replace(WHITESPACE, " ")
        if (line.isEmpty()) return null
        if (line.length <= MAX_TEXT_LENGTH) return line
        // Not between the two halves of a surrogate pair.
        val end = if (line[MAX_TEXT_LENGTH - 1].isHighSurrogate()) MAX_TEXT_LENGTH - 1 else MAX_TEXT_LENGTH
        return line.substring(0, end)
    }
}
