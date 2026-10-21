package ch.lkmc.combray.core.policy

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

/** What submitting the address field does. */
sealed interface AddressDecision {
    /** Load [url] in the browser. */
    data class Navigate(val url: String) : AddressDecision

    /** Open Kagi's ordinary results page for [query]. */
    data class Search(val query: String, val url: String) : AddressDecision

    /** Hand [url] to another Android app (`mailto:`, `tel:`) after the user confirms. */
    data class External(val url: String) : AddressDecision

    data class Rejected(val reason: RejectReason) : AddressDecision
}

enum class RejectReason {
    EMPTY,

    /** `javascript:`, `data:`, `file:`, `intent:` and other schemes the browser never loads. */
    UNSUPPORTED_SCHEME,

    /** `https://user:password@host`: credentials in an address are not passed on silently. */
    EMBEDDED_CREDENTIALS,

    /** Looks like a web address but does not parse as one. */
    MALFORMED_ADDRESS,
}

/** The decision for an input, plus the other reasonable reading the UI can offer. */
data class AddressInterpretation(val primary: AddressDecision, val alternative: AddressDecision?)

/**
 * Classifies address-field input. Nothing here contacts the network: typing stays local until
 * the user submits.
 *
 * ```
 * "https://example.org/a b"   -> Navigate (as typed, the engine parses it)
 * "example.org/path"          -> Navigate https://example.org/path, or Search
 * "localhost:8080", "nas/"    -> Navigate https://..., or Search
 * "router"                    -> Search, or Navigate https://router
 * "!w Zürich & co #1 😀"      -> Search, encoded once
 * "javascript:alert(1)"       -> Rejected
 * ```
 */
object AddressPolicy {
    private const val KAGI_HOST = "kagi.com"
    private const val KAGI_PATH = "/search"
    private const val KAGI_QUERY = "q"
    private const val KAGI_SEARCH = "https://$KAGI_HOST$KAGI_PATH?$KAGI_QUERY="

    private val WEB_SCHEMES = setOf("http", "https")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val PORT = Regex("^\\d{1,5}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LABEL = Regex("^(?!-)[\\p{L}\\p{N}-]{1,63}(?<!-)$")
    private val TOP_LEVEL = Regex("^(\\p{L}{2,63}|xn--[a-z0-9-]{1,59})$", RegexOption.IGNORE_CASE)

    fun interpret(input: String): AddressInterpretation {
        val text = input.trim()
        if (text.isEmpty()) return AddressInterpretation(AddressDecision.Rejected(RejectReason.EMPTY), null)

        val search = search(text)
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        // A known scheme is an address even with spaces in it (a pasted link): it is never sent
        // to Kagi as a query, so a credential or token in it does not leave as search text.
        if (scheme != null && scheme in KNOWN_SCHEMES) {
            return AddressInterpretation(withScheme(text.replace(" ", "%20"), scheme) ?: search, null)
        }
        if (text.any { it.isWhitespace() }) return AddressInterpretation(search, null)
        if (scheme != null && !looksLikeHostAndPort(text)) {
            return AddressInterpretation(withScheme(text, scheme) ?: return AddressInterpretation(search, null), null)
        }

        val hostLike = schemelessHost(text) ?: return AddressInterpretation(search, null)
        val navigate = navigateTo("https://$text") ?: return AddressInterpretation(search, null)
        // A bare word could be an intranet host, but is far more often a search.
        return if (hostLike == HostShape.SINGLE_LABEL) {
            AddressInterpretation(search, navigate)
        } else {
            AddressInterpretation(navigate, search)
        }
    }

    /** Kagi's normal web results for [query]: UTF-8, percent-encoded exactly once. */
    fun kagiSearchUrl(query: String): String =
        KAGI_SEARCH + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /**
     * The query of a Kagi results page, the inverse of [kagiSearchUrl], so editing a search starts
     * from its words instead of the encoded address. Only `https://kagi.com/search` qualifies
     * (exact host and path, no port or user); the first `q` parameter is read, others are
     * ignored. Null for any other address, or when there is no query.
     */
    fun kagiQuery(url: String): String? {
        val uri = try {
            URI(url)
        } catch (malformed: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(KAGI_HOST, ignoreCase = true)) return null
        if (uri.port != -1 || uri.rawUserInfo != null || uri.rawPath != KAGI_PATH) return null

        val parameter = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == KAGI_QUERY && '=' in it } ?: return null
        // The String overload: URLDecoder.decode(String, Charset) needs API 33. It also reads
        // "+" as a space, as in the form submissions of Kagi's own search field.
        val query = try {
            URLDecoder.decode(parameter.substringAfter('='), "UTF-8")
        } catch (malformed: IllegalArgumentException) {
            return null
        }
        return query.takeIf { it.isNotBlank() }
    }

    private fun search(query: String) = AddressDecision.Search(query, kagiSearchUrl(query))

    private fun withScheme(text: String, scheme: String): AddressDecision? = when {
        scheme in WEB_SCHEMES -> navigateTo(text) ?: AddressDecision.Rejected(RejectReason.MALFORMED_ADDRESS)
        scheme in EXTERNAL_SCHEMES -> AddressDecision.External(text)
        // "define:word" or "c:" style text without "//" reads as a query, not an address.
        !text.substring(scheme.length + 1).startsWith("//") && scheme !in BLOCKED_SCHEMES -> null
        else -> AddressDecision.Rejected(RejectReason.UNSUPPORTED_SCHEME)
    }

    private val BLOCKED_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent", "blob", "about", "chrome", "view-source",
        "filesystem", "ftp", "ws", "wss", "jar", "resource", "android-app",
    )

    private val KNOWN_SCHEMES = WEB_SCHEMES + EXTERNAL_SCHEMES + BLOCKED_SCHEMES

    private fun navigateTo(address: String): AddressDecision? {
        val uri = try {
            URI(address)
        } catch (malformed: URISyntaxException) {
            // Characters java.net.URI rejects (spaces, some Unicode) are fine for Servo's URL
            // parser; only the authority has to be checked here.
            return authorityOf(address)?.let { checkAuthority(it, address) }
        }
        if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
        val authority = uri.rawAuthority ?: return AddressDecision.Rejected(RejectReason.MALFORMED_ADDRESS)
        return checkAuthority(authority, address)
    }

    private fun checkAuthority(authority: String, address: String): AddressDecision {
        if ('@' in authority) return AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS)
        if (authority.isEmpty()) return AddressDecision.Rejected(RejectReason.MALFORMED_ADDRESS)
        return AddressDecision.Navigate(address)
    }

    private fun authorityOf(address: String): String? {
        val afterScheme = address.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    }

    private enum class HostShape { DOMAIN, ADDRESS, SINGLE_LABEL }

    /** "host:1234" is a host with a port, not a URL with scheme "host". */
    private fun looksLikeHostAndPort(text: String): Boolean {
        val authority = text.takeWhile { it != '/' && it != '?' && it != '#' }
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        return PORT.matches(port) && schemelessHost(text) != null
    }

    /** The host shape of scheme-less input, or null when it cannot be a web address. */
    private fun schemelessHost(text: String): HostShape? {
        val authority = text.takeWhile { it != '/' && it != '?' && it != '#' }
        if ('@' in authority || authority.isEmpty()) return null
        val hasPath = authority.length < text.length

        val (host, port) = splitPort(authority) ?: return null
        if (port != null && !PORT.matches(port)) return null
        val shape = when {
            host.startsWith("[") && host.endsWith("]") -> HostShape.ADDRESS
            IPV4.matches(host) -> if (host.split('.').all { it.toInt() <= 255 }) HostShape.ADDRESS else return null
            host.equals("localhost", ignoreCase = true) -> HostShape.ADDRESS
            '.' in host -> if (isDomain(host)) HostShape.DOMAIN else return null
            LABEL.matches(host) -> HostShape.SINGLE_LABEL
            else -> return null
        }
        // A single word is a host only with an explicit port or path ("nas:5000", "router/").
        if (shape == HostShape.SINGLE_LABEL && (port != null || hasPath)) return HostShape.ADDRESS
        return shape
    }

    private fun splitPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val rest = authority.substring(end + 1)
            return when {
                rest.isEmpty() -> Pair(authority, null)
                rest.startsWith(":") -> Pair(authority.substring(0, end + 1), rest.substring(1))
                else -> null
            }
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return Pair(authority, null)
        return Pair(authority.substring(0, colon), authority.substring(colon + 1))
    }

    private fun isDomain(host: String): Boolean {
        val labels = host.trimEnd('.').split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return false
        return TOP_LEVEL.matches(labels.last())
    }
}

/** Host handling for history, exclusions and site actions. */
object HostNames {
    /** Lowercase ASCII (punycode) host of a URL without trailing dot, or null. */
    fun of(url: String): String? {
        val host = try {
            URI(url).host
        } catch (malformed: URISyntaxException) {
            null
        } ?: authorityHost(url) ?: return null
        return normalize(host)
    }

    /** java.net.URI has no host for Unicode (IDN) authorities: read it from the authority. */
    private fun authorityHost(url: String): String? {
        val authority = url.substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
        if (authority.isEmpty() || authority.startsWith("[")) return null
        return authority.substringBefore(':').takeIf { it.isNotEmpty() }
    }

    fun normalize(host: String): String? {
        val trimmed = host.trim().trimEnd('.')
        if (trimmed.isEmpty()) return null
        return try {
            IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    /**
     * The host as people should read it: Unicode only where that cannot imitate another site.
     * A label shows in Unicode when it uses one script that does not resemble Latin, or Latin
     * letters that are ASCII letters with accents (é, ü, ñ); anything else stays punycode
     * ("xn--80ak6aa92e.com", not a Cyrillic "аррӏе.com"; "xn--gthub-n4a.com", not "gıthub.com").
     */
    fun forDisplay(host: String): String {
        val labels = host.split('.')
        val unicode = labels.map { label ->
            if (!label.startsWith(ACE_PREFIX)) return@map label
            val decoded = try {
                IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED)
            } catch (invalid: IllegalArgumentException) {
                return host
            }
            if (!readableLabel(decoded)) return host
            decoded
        }
        return unicode.joinToString(".")
    }

    private fun readableLabel(label: String): Boolean {
        val scripts = HashSet<Character.UnicodeScript>()
        var previous: Character.UnicodeScript? = null
        for (point in label.codePoints()) {
            if (point == '-'.code || point in '0'.code..'9'.code) {
                previous = null
                continue
            }
            val script = when {
                // The kana length mark ("ソニー") is script COMMON; it is only valid after kana.
                point == KATAKANA_LONG_VOWEL -> previous?.takeIf { it in KANA } ?: return false
                Character.isLetter(point) -> Character.UnicodeScript.of(point)
                // Vowel signs and other marks carry their script ("हिंदी"); generic combining marks do not.
                Character.getType(point) in SCRIPT_MARKS -> Character.UnicodeScript.of(point).takeIf { it in READABLE_MARK_SCRIPTS } ?: return false
                else -> return false
            }
            if (script == Character.UnicodeScript.LATIN && point > ASCII_END && !isAccentedAscii(point)) return false
            scripts += script
            previous = script
        }
        return READABLE_SCRIPT_SETS.any { it.containsAll(scripts) }
    }

    /** "é" is "e" plus an accent; "ı", "ĸ", "ł", "ø" and "þ" decompose to no ASCII letter. */
    private fun isAccentedAscii(point: Int): Boolean {
        val parts = Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFD)
        return parts.length > 1 && parts[0] in 'a'..'z' && parts.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }

    private const val ACE_PREFIX = "xn--"
    private const val ASCII_END = 0x7F
    private const val KATAKANA_LONG_VOWEL = 0x30FC
    private val KANA = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    private val SCRIPT_MARKS = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())
    private val READABLE_MARK_SCRIPTS = setOf(Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.THAI)

    /** Scripts a label may combine. Cyrillic, Greek and others with Latin look-alikes are absent. */
    private val READABLE_SCRIPT_SETS = listOf(
        setOf(Character.UnicodeScript.LATIN),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA),
        setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL),
        setOf(Character.UnicodeScript.ARABIC),
        setOf(Character.UnicodeScript.HEBREW),
        setOf(Character.UnicodeScript.THAI),
        setOf(Character.UnicodeScript.DEVANAGARI),
    )

    /** Exact host match, or a subdomain of [site] when [includeSubdomains]; never a suffix string test. */
    fun matches(host: String, site: String, includeSubdomains: Boolean): Boolean =
        host == site || (includeSubdomains && host.endsWith(".$site"))

    fun isWeb(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "http" || scheme == "https"
    }
}
