package ch.lkmc.combray.ui

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import ch.lkmc.combray.core.policy.HostNames
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

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

/**
 * How a page's content reached the engine, by its address's scheme. A blob: page holds what the
 * page that made it fetched, so "blob:http://..." is cleartext like that page, as [displayHost]
 * names it by that page's host. HTTPS is not treated as verified: the engine does not report
 * the certificate.
 */
internal enum class PageConnection {
    HTTPS,
    CLEARTEXT,

    /** data:, about: and file: pages came over no connection: there is nothing to describe. */
    NONE,
    ;

    companion object {
        private const val BLOB_PREFIX = "blob:"

        fun of(url: String): PageConnection {
            val trimmed = url.trim()
            val address = if (trimmed.startsWith(BLOB_PREFIX, ignoreCase = true)) trimmed.substring(BLOB_PREFIX.length) else trimmed
            return when {
                address.startsWith("https:", ignoreCase = true) -> HTTPS
                address.startsWith("http:", ignoreCase = true) -> CLEARTEXT
                else -> NONE
            }
        }
    }
}

fun relativeTime(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * The share's subject: the page's title with its whitespace collapsed (a title's line break
 * must not become a second line of the share) and capped, or null when it has nothing usable.
 * The page chooses its title, so it is bounded before it goes into the share twice. Bidi
 * controls are dropped: the sender of a share chooses its subject too, and U+202E would show
 * "moc.elgoog.stnuocca" as "accounts.google.com" next to the host in Save to Karakeep.
 */
fun shareTitle(title: String?): String? =
    title?.replace(BIDI_CONTROLS, "")?.replace(TITLE_WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }?.let(::capped)

/** Java's `\s` is ASCII-only; NEL and the Unicode line and paragraph separators belong with it. */
private val TITLE_WHITESPACE = Regex("[\\s\\u0085\\u2028\\u2029]+")

/** Invisible marks, embeddings, overrides and isolates that reorder the text around them. */
private val BIDI_CONTROLS = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

/** Long enough for any real headline, short enough to stay one line in a message. */
private const val MAX_SHARE_TITLE = 200

/** At most [MAX_SHARE_TITLE] characters plus an ellipsis, never splitting a surrogate pair. */
private fun capped(title: String): String {
    if (title.length <= MAX_SHARE_TITLE) return title
    return title.substring(0, cutAt(title, MAX_SHARE_TITLE)).trimEnd() + "\u2026"
}

/** Where to cut [text] to at most [max] chars without splitting a surrogate pair. */
private fun cutAt(text: String, max: Int): Int = when {
    text.length <= max -> text.length
    text[max - 1].isHighSurrogate() -> max - 1
    else -> max
}

/** What a share carries: the title as the first line (when there is one), then the address. */
fun shareText(url: String, title: String?): String =
    listOfNotNull(shareTitle(title), url).joinToString("\n")
/** The letter on a Recent row's site tile: the host's first letter or digit, upper case. */
internal fun monogram(url: String?): String {
    if (url == null) return NEW_TAB_MONOGRAM

    val host = displayHost(url)
    var index = 0
    while (index < host.length) {
        val codePoint = host.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) return String(Character.toChars(Character.toUpperCase(codePoint)))
        index += Character.charCount(codePoint)
    }
    return NO_LETTER_MONOGRAM
}

/** How a Recent day header names its day. */
internal enum class DayName { TODAY, YESTERDAY, WEEKDAY, DATE }

/** The calendar day of [time] in [zone], as days since 1970-01-01. */
internal fun calendarDay(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay()

/** Today and yesterday by name, the rest of the past week by weekday, anything else by date. */
internal fun dayName(day: Long, today: Long): DayName = when (today - day) {
    0L -> DayName.TODAY
    1L -> DayName.YESTERDAY
    in 2L..LAST_WEEKDAY_NAMED -> DayName.WEEKDAY
    else -> DayName.DATE
}

/**
 * [text] with each occurrence of the query's words in [style], ignoring case and accents, so
 * "cafe" marks "Café". Words shorter than [MIN_HIGHLIGHT_TERM] are left out: they would mark
 * almost every word. The text stays plain: nothing in it is read as markup. Only its first
 * [MAX_HIGHLIGHTED_LENGTH] characters are marked: a page chooses its title, and one span per
 * match in a huge one makes laying out the row quadratic in the matches, enough to freeze typing.
 */
internal fun highlightMatches(text: String, query: String, style: SpanStyle): AnnotatedString {
    val terms = query.split(WHITESPACE).map { FoldedText(it).value }.filter(::worthMarking).distinct()
    if (terms.isEmpty()) return AnnotatedString(text)

    val folded = FoldedText(text.substring(0, cutAt(text, MAX_HIGHLIGHTED_LENGTH)))
    val ranges = mergeRanges(terms.flatMap(folded::occurrences))
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/**
 * A term long enough to mean something. One Han, kana or Hangul character is a whole word in
 * Chinese, Japanese and Korean; one Latin letter would mark almost every word.
 */
private fun worthMarking(term: String): Boolean =
    term.length >= MIN_HIGHLIGHT_TERM || term.any { Character.UnicodeScript.of(it.code) in WORD_SCRIPTS }

private val WORD_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
)

/** How a remembered word stands out in a result: bolder, on a marker-pen background. */
@Composable
@ReadOnlyComposable
internal fun matchStyle(): SpanStyle = SpanStyle(
    color = MaterialTheme.colorScheme.onTertiaryContainer,
    background = MaterialTheme.colorScheme.tertiaryContainer,
    fontWeight = FontWeight.SemiBold,
)

/**
 * [source] in lower case without accents, for matching. Each folded char remembers the range of
 * [source] it came from, so a match found here can be marked in the original text.
 */
private class FoldedText(source: String) {
    val value: String
    private val starts = mutableListOf<Int>()
    private val ends = mutableListOf<Int>()

    init {
        val folded = StringBuilder()
        var index = 0
        var lastPiece = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val next = index + Character.charCount(codePoint)
            val piece = fold(codePoint)
            if (piece.isEmpty()) {
                // A combining accent belongs to the letter before it: marking the letter covers it.
                for (k in lastPiece until folded.length) ends[k] = next
            } else {
                lastPiece = folded.length
                for (char in piece) {
                    folded.append(char)
                    starts += index
                    ends += next
                }
            }
            index = next
        }
        value = folded.toString()
    }

    /** The ranges of the source text where [term] (already folded) occurs. */
    fun occurrences(term: String): List<IntRange> = buildList {
        var at = value.indexOf(term)
        while (at >= 0) {
            add(starts[at] until ends[at + term.length - 1])
            at = value.indexOf(term, at + 1)
        }
    }

    private fun fold(codePoint: Int): String =
        Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
            .lowercase()
            .filterNot { it.category == CharCategory.NON_SPACING_MARK }
}

/** [ranges] sorted, with overlapping or touching ranges joined into one. */
private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    val merged = mutableListOf<IntRange>()
    for (range in ranges.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

private val WHITESPACE = Regex("\\s+")
private const val MIN_HIGHLIGHT_TERM = 2

/** More than a result shows: a title is one line and a Hister excerpt two, on the widest sheet. */
private const val MAX_HIGHLIGHTED_LENGTH = 300

/** A week ago would repeat today's weekday, so from then on headers show the date. */
private const val LAST_WEEKDAY_NAMED = 6L

/** A new tab has no address yet. */
private const val NEW_TAB_MONOGRAM = "+"
private const val NO_LETTER_MONOGRAM = "•"
