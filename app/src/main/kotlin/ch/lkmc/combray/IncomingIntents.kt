package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import ch.lkmc.combray.core.integration.SensitiveUrls
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
 * PROCESS_TEXT "see https://a/?token=1" -> EditNewTab "https://a/?token=1"
 * ```
 */
object IncomingIntents {
    /** Launcher shortcut: the address editor for a new tab. */
    const val ACTION_NEW_TAB = "ch.lkmc.combray.action.NEW_TAB"

    /** Launcher shortcut: Recent on its Hister scope. */
    const val ACTION_SEARCH_HISTORY = "ch.lkmc.combray.action.SEARCH_HISTORY"

    // The address field holds one line; longer text is cut before it is shown or searched.
    private const val MAX_TEXT_LENGTH = 2_000

    // Any app can share without a tap, and this runs on the main thread: a huge share is searched
    // for a link only this far.
    private const val MAX_SCANNED_LENGTH = 65_536

    // The longest link taken from shared text, as in the engine (native MAX_URL_CHARS).
    private const val MAX_LINK_LENGTH = 32_768

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
     * The text extra [name] of [intent], or null. Reading one extra unparcels them all, and on
     * API 30 to 32 another app's extra of a class Combray does not have throws: that is no text,
     * not a crash.
     */
    fun textExtra(intent: Intent, name: String): String? = try {
        intent.getCharSequenceExtra(name)?.toString()
    } catch (unreadable: RuntimeException) {
        null
    }

    /**
     * The first http(s) link in [text], without the punctuation or bracket that closes the
     * sentence around it: "(see https://example.org/Foo_(bar))." -> "https://example.org/Foo_(bar)".
     * A link longer than the engine takes, or running past the part of [text] searched, is
     * refused rather than cut.
     */
    fun firstWebAddress(text: String): String? {
        val scanned = text.take(MAX_SCANNED_LENGTH)
        val match = WEB_ADDRESS.find(scanned) ?: return null
        val cut = match.range.last == scanned.lastIndex && text.length > scanned.length
        if (cut || match.value.length > MAX_LINK_LENGTH) return null

        val address = match.value.substring(0, linkEnd(match.value))
        return address.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    /**
     * Where the link in [address] ends: before the punctuation, and the closing brackets it does
     * not open itself, that end the sentence. One pass, with each bracket's count kept as the end
     * moves back.
     */
    private fun linkEnd(address: String): Int {
        val counts = address.groupingBy { it }.eachCount().toMutableMap()
        var end = address.length
        while (end > 0) {
            val last = address[end - 1]
            val opening = BRACKET_PAIRS[last]
            if (opening != null && counts.getValue(last) > (counts[opening] ?: 0)) {
                counts[last] = counts.getValue(last) - 1
            } else if (last !in TRAILING_PUNCTUATION) {
                break
            }
            end--
        }
        return end
    }

    private fun open(address: String): Incoming.Open? =
        (AddressPolicy.interpret(address).primary as? AddressDecision.Navigate)?.let { Incoming.Open(it.url) }

    /**
     * What a share is about: the text, unless only the subject holds a link (some apps share a
     * link as the subject, with no text or an empty one).
     */
    private fun sharedText(text: String?, subject: String?): String? = when {
        text != null && firstWebAddress(text) != null -> text
        subject != null && firstWebAddress(subject) != null -> subject
        else -> text?.takeIf { it.isNotBlank() } ?: subject
    }

    /** A shared link opens at once; other shared text waits in the editor. */
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
            // A link with a password or token among other words goes to the editor alone, as from
            // a share: searched with them, it would reach Kagi unseen.
            is AddressDecision.Search -> secretLink(query)?.let { LaunchRequest.EditNewTab(it) } ?: Incoming.Open(decision.url)
            // mailto:, tel: and refused addresses: the editor says what they are.
            is AddressDecision.External, is AddressDecision.Rejected -> LaunchRequest.EditNewTab(query)
        }
    }

    /** The first link in [text] if it must not leave as search text: refused, or carrying a token. */
    private fun secretLink(text: String): String? =
        firstWebAddress(text)?.takeIf { open(it) == null || SensitiveUrls.looksSensitive(it) }

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
