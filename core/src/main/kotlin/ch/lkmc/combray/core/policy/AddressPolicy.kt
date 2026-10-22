package ch.lkmc.combray.core.policy

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
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
 * "192.168.1.10", "localhost" -> Navigate http://..., or https://...
 * "keep.lan:3000", "nas:5000" -> Navigate https://..., or http://...
 * "8.8.8.8", "nas/"           -> Navigate https://..., or Search
 * "router"                    -> Search, or Navigate https://router
 * "!w Zürich & co #1 😀"      -> Search, encoded once
 * "javascript:alert(1)"       -> Rejected
 * "admin:pw@192.168.1.1"      -> Rejected
 * ```
 */
object AddressPolicy {
    private const val KAGI_SEARCH = "https://kagi.com/search?q="

    private val WEB_SCHEMES = setOf("http", "https")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val PORT = Regex("^\\d{1,5}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LABEL = Regex("^(?!-)[\\p{L}\\p{N}-]{1,63}(?<!-)$")
    private val TOP_LEVEL = Regex("^(\\p{L}{2,63}|xn--[a-z0-9-]{1,59})$", RegexOption.IGNORE_CASE)
    private val IPV6_GROUP = Regex("^[0-9A-Fa-f]{1,4}$")
    private const val IPV6_GROUPS = 8
    private const val BITS_PER_BYTE = 8
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    /** ::ffff:0:0/96, the prefix of IPv4-mapped IPv6 addresses (RFC 4291). */
    private val IPV4_MAPPED_PREFIX = List(10) { 0 } + listOf(0xFF, 0xFF)

    /** Names that only resolve on a home network (RFC 6762 .local, RFC 8375 .home.arpa, and common ones). */
    private val LOCAL_SUFFIXES = listOf(".lan", ".local", ".home.arpa", ".internal", ".localdomain")

    /** Private (RFC 1918, IPv6 unique local), loopback and link-local networks, as (bytes, prefix length). */
    private val LOCAL_NETWORKS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "::1/128", "fc00::/7", "fe80::/10",
    ).map { cidr ->
        val (network, prefixLength) = cidr.split('/')
        Pair(checkNotNull(ipv4Bytes(network) ?: ipv6Bytes(network)), prefixLength.toInt())
    }

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
        // "admin:hunter2@192.168.1.1" names no known scheme, but a search would send the password.
        if (hasSchemelessCredentials(text)) {
            return AddressInterpretation(AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS), null)
        }
        if (scheme != null && !looksLikeHostAndPort(text)) {
            return AddressInterpretation(withScheme(text, scheme) ?: return AddressInterpretation(search, null), null)
        }

        val hostLike = schemelessHost(text) ?: return AddressInterpretation(search, null)
        val navigate = navigateTo("https://$text") ?: return AddressInterpretation(search, null)
        val plain = navigateTo("http://$text") ?: return AddressInterpretation(search, null)
        return when (hostLike) {
            // A bare word could be an intranet host, but is far more often a search.
            HostShape.SINGLE_LABEL -> AddressInterpretation(search, navigate)
            // Home servers often have no certificate; Chrome does not upgrade these to HTTPS either.
            HostShape.LOCAL_ADDRESS -> AddressInterpretation(plain, navigate)
            // A home name may have a certificate or not; searching it would only leak it to Kagi.
            HostShape.LOCAL_NAME -> AddressInterpretation(navigate, plain)
            HostShape.DOMAIN, HostShape.ADDRESS -> AddressInterpretation(navigate, search)
        }
    }

    /** Kagi's normal web results for [query]: UTF-8, percent-encoded exactly once. */
    fun kagiSearchUrl(query: String): String =
        KAGI_SEARCH + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

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

    private enum class HostShape {
        /** A public name: "example.org". */
        DOMAIN,

        /** A public IP literal, or one word with a path ("router/"). */
        ADDRESS,

        /** A private, loopback or link-local IP literal, or localhost. */
        LOCAL_ADDRESS,

        /** A home network name ("keep.lan"), or one word with a port ("nas:5000"). */
        LOCAL_NAME,

        /** One word ("router"). */
        SINGLE_LABEL,
    }

    /**
     * "user:password@host/path": a colon before the authority's last "@". What follows the "@"
     * need not be a valid host: "admin:pw@nas!" would still send the password as a search.
     */
    private fun hasSchemelessCredentials(text: String): Boolean {
        val authority = text.takeWhile { it != '/' && it != '?' && it != '#' }
        if ('@' !in authority) return false

        return ':' in authority.substringBeforeLast('@')
    }

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
            host.startsWith("[") && host.endsWith("]") -> addressShape(ipv6Bytes(host.removeSurrounding("[", "]")))
            IPV4.matches(host) -> addressShape(ipv4Bytes(host) ?: return null)
            host.equals("localhost", ignoreCase = true) -> HostShape.LOCAL_ADDRESS
            '.' in host -> if (isDomain(host)) domainShape(host) else return null
            LABEL.matches(host) -> HostShape.SINGLE_LABEL
            else -> return null
        }
        // A single word is a host only with an explicit port or path ("nas:5000", "router/").
        if (shape == HostShape.SINGLE_LABEL && port != null) return HostShape.LOCAL_NAME
        if (shape == HostShape.SINGLE_LABEL && hasPath) return HostShape.ADDRESS
        return shape
    }

    /** [bytes] is null for a bracketed host that is not plain IPv6, which is treated as public. */
    private fun addressShape(bytes: List<Int>?): HostShape {
        // An IPv4-mapped IPv6 address ("::ffff:192.168.1.1") is that IPv4 address.
        val address = bytes?.takeIf { it.size == IPV6_BYTES && it.take(IPV4_MAPPED_PREFIX.size) == IPV4_MAPPED_PREFIX }
            ?.takeLast(IPV4_BYTES) ?: bytes
        val local = address != null && LOCAL_NETWORKS.any { (network, prefixLength) -> inNetwork(address, network, prefixLength) }
        return if (local) HostShape.LOCAL_ADDRESS else HostShape.ADDRESS
    }

    private fun domainShape(host: String): HostShape {
        val name = host.trimEnd('.')
        return if (LOCAL_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) HostShape.LOCAL_NAME else HostShape.DOMAIN
    }

    private fun inNetwork(address: List<Int>, network: List<Int>, prefixLength: Int): Boolean {
        if (address.size != network.size) return false
        return (0 until prefixLength).all { bit -> bitAt(address, bit) == bitAt(network, bit) }
    }

    private fun bitAt(bytes: List<Int>, index: Int): Int =
        (bytes[index / BITS_PER_BYTE] shr (BITS_PER_BYTE - 1 - index % BITS_PER_BYTE)) and 1

    /** The four bytes of a dotted-quad IPv4 address, or null. */
    private fun ipv4Bytes(host: String): List<Int>? {
        if (!IPV4.matches(host)) return null
        return host.split('.').map { it.toInt() }.takeIf { octets -> octets.all { it <= 255 } }
    }

    /**
     * The sixteen bytes of an IPv6 address such as "fe80::1", or null. A zone ("fe80::1%wlan0",
     * "%25" in a URL) names an interface and is ignored; a dotted IPv4 tail counts as two groups.
     */
    private fun ipv6Bytes(written: String): List<Int>? {
        val bare = written.substringBefore('%')
        val lastColon = bare.lastIndexOf(':')
        val dottedTail = bare.substring(lastColon + 1)
        val address = if ('.' in dottedTail) {
            val tail = ipv4Bytes(dottedTail) ?: return null
            bare.substring(0, lastColon + 1) + "%x:%x".format((tail[0] shl BITS_PER_BYTE) or tail[1], (tail[2] shl BITS_PER_BYTE) or tail[3])
        } else {
            bare
        }
        val halves = address.split("::")
        if (halves.size > 2) return null

        val head = ipv6GroupsOf(halves[0])
        val tail = if (halves.size == 2) ipv6GroupsOf(halves[1]) else emptyList()
        val missing = IPV6_GROUPS - head.size - tail.size
        val compressed = halves.size == 2
        if ((compressed && missing < 1) || (!compressed && missing != 0)) return null

        val groups = head + List(missing) { "0" } + tail
        if (groups.any { !IPV6_GROUP.matches(it) }) return null
        return groups.flatMap { group ->
            val value = group.toInt(16)
            listOf(value shr BITS_PER_BYTE, value and 0xFF)
        }
    }

    private fun ipv6GroupsOf(part: String): List<String> = if (part.isEmpty()) emptyList() else part.split(':')

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
    private const val BLOB_PREFIX = "blob:"
    private val AUTHORITY_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    /**
     * The only schemes whose authority is a web site. Any other scheme, page-chosen ones
     * included ("a.b://accounts.example.com"), writes whatever it likes after "//".
     */
    private val SITE_SCHEMES = setOf("http", "https")

    /** Lowercase ASCII (punycode) host of a URL without trailing dot, or null. */
    fun of(url: String): String? {
        val address = url.trim()
        // A blob: address belongs to the web page that made it, named by its inner address.
        if (address.startsWith(BLOB_PREFIX, ignoreCase = true)) {
            val inner = address.substring(BLOB_PREFIX.length)
            return if (isWeb(inner)) of(inner) else null
        }

        // Only "http(s)://" at the very start introduces a host. A data: or about: page chooses
        // its whole address, so a "://" further in must not make it look like another site.
        val prefix = AUTHORITY_PREFIX.find(address) ?: return null
        if (prefix.groupValues[1].lowercase() !in SITE_SCHEMES) return null

        val host = try {
            URI(address).host
        } catch (malformed: URISyntaxException) {
            null
        } ?: authorityHost(address.substring(prefix.value.length)) ?: return null
        return normalize(host)
    }

    /** java.net.URI has no host for Unicode (IDN) authorities: read it from the authority. */
    private fun authorityHost(afterPrefix: String): String? {
        val authority = afterPrefix
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
