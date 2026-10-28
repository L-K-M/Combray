package ch.lkmc.combray.core.media

import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.HostNames
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The page a media load came from, as far as the player's network rules need it.
 *
 * [pageHost] is the page's host, or null for a page without one (`data:`, `about:`, `blob:`).
 * [secure]: the page or the referrer is https. [local]: the page is on a local host and the
 * referrer is present and local too. [referrer] is the origin of the referrer Servo computed for
 * the load (`https://example.org`), or null.
 */
data class MediaOrigin(val pageHost: String?, val secure: Boolean, val local: Boolean, val referrer: String?) {
    /** Hosts and referrers stay out of logs. */
    override fun toString() = "MediaOrigin(secure=$secure, local=$local)"
}

/** Why the player does not fetch an address. The names are logged; the address never is. */
enum class MediaRefusal {
    /** Looks like a web address but does not parse as one. */
    UNPARSEABLE,

    /** Not http(s): `file:`, `content:`, `data:`, `intent:` and the like, or a `Location` that does not resolve. */
    NOT_WEB,

    /** `user:password@` in the address. */
    CREDENTIALS,

    /** A host that ends in a number but is no dotted quad (`127.1`, `2130706433`, `0x7f.1`). */
    NUMERIC_HOST,

    /** A port the Fetch standard blocks (mail, FTP, IRC and other non-web services). */
    BAD_PORT,

    /** An http address from an https page or referrer. */
    MIXED_CONTENT,

    /** A local address or name from a public page. */
    PRIVATE_NETWORK,
}

/**
 * The network rules for the native media player (docs/VIDEO.md, "Network rules for the native
 * player"): a page may send the player only where Servo itself would go, and a public page never
 * to this device or the home network. Pure; addresses are parsed with OkHttp's [HttpUrl], the
 * parser that also issues the player's requests, so the check and the request cannot disagree.
 *
 * Where the rules apply: the coordinator offers only addresses [check] accepts; the player's
 * client checks the first request before connecting, then each hop, each redirect's target
 * before following it, and the address each hop connected to ([checkAddress]).
 */
object MediaPolicy {
    /** The Fetch standard's bad ports (https://fetch.spec.whatwg.org/#bad-port), as Servo blocks them. */
    private val BAD_PORTS = setOf(
        0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95,
        101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179,
        389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601,
        636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566,
        6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080,
    )

    private const val HTTPS = "https"
    private const val LOCALHOST_SUFFIX = ".localhost"
    private const val HEX_PREFIX = "0x"
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val BITS_PER_BYTE = 8
    private val DOTTED_QUAD = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val HEX_DIGITS = Regex("^[0-9A-Fa-f]*$")

    /** ::ffff:0:0/96, IPv4-mapped IPv6 addresses (RFC 4291). */
    private val IPV4_MAPPED_PREFIX = List(10) { 0 } + listOf(0xFF, 0xFF)

    /** 64:ff9b::/96, the NAT64 well-known prefix (RFC 6052): such an address reaches the IPv4 address it ends in. */
    private val NAT64_PREFIX = listOf(0x00, 0x64, 0xFF, 0x9B) + List(8) { 0 }

    /**
     * IPv4 networks that are local for media only, beyond [AddressPolicy.isLocalAddress], as
     * (bytes, prefix length): 0.0.0.0/8 reaches this device, and 100.64.0.0/10 is the shared
     * address space carrier NAT and Tailscale use.
     */
    private val MEDIA_LOCAL_IPV4 = listOf(listOf(0, 0, 0, 0) to 8, listOf(100, 64, 0, 0) to 10)

    /** The page context of a media load on [pageUrl], whose referrer Servo gave as [referrer]. */
    fun origin(pageUrl: String, referrer: String?): MediaOrigin {
        val page = pageUrl.toHttpUrlOrNull()
        val from = referrer?.toHttpUrlOrNull()
        // A null referrer counts as public: a late load of the previous page can be attributed
        // to a local one (docs/VIDEO.md).
        val local = page != null && from != null && isLocalHost(page.host) && isLocalHost(from.host)
        return MediaOrigin(
            pageHost = page?.host,
            secure = page?.isHttps == true || from?.isHttps == true,
            local = local,
            referrer = from?.let(::originOf),
        )
    }

    /** [check] for an address as the engine reported it; [MediaRefusal.NOT_WEB] or [MediaRefusal.UNPARSEABLE] when it is no web address. */
    fun check(origin: MediaOrigin, url: String): MediaRefusal? {
        val parsed = url.toHttpUrlOrNull() ?: return if (HostNames.isWeb(url)) MediaRefusal.UNPARSEABLE else MediaRefusal.NOT_WEB
        return check(origin, parsed)
    }

    /** The rules that need no DNS: null when [origin]'s page may send the player to [url]. */
    fun check(origin: MediaOrigin, url: HttpUrl): MediaRefusal? = when {
        url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() -> MediaRefusal.CREDENTIALS
        // OkHttp hands such hosts to InetAddress without asking its Dns, and InetAddress reads
        // them as addresses ("127.1" is 127.0.0.1).
        endsInNumber(url.host) && !DOTTED_QUAD.matches(url.host) -> MediaRefusal.NUMERIC_HOST
        url.port in BAD_PORTS -> MediaRefusal.BAD_PORT
        origin.secure && !url.isHttps -> MediaRefusal.MIXED_CONTENT
        !origin.local && isLocalHost(url.host) -> MediaRefusal.PRIVATE_NETWORK
        else -> null
    }

    /**
     * The private network rule for [address], which a hop to [host] connected to. A local page
     * may reach local addresses, and any page its own host: Servo already connected there, and
     * a tailnet or home name resolves to a local address.
     */
    fun checkAddress(
        origin: MediaOrigin,
        host: String,
        address: InetAddress,
        isLocal: (InetAddress) -> Boolean = ::isLocalAddress,
    ): MediaRefusal? {
        if (origin.local || host.equals(origin.pageHost, ignoreCase = true)) return null
        return if (isLocal(address)) MediaRefusal.PRIVATE_NETWORK else null
    }

    /**
     * The `Referer` for a request to [hop]: the referrer's origin with a slash, or null. An http
     * hop from an https referrer gets none, as `strict-origin-when-cross-origin` does.
     */
    fun referer(origin: MediaOrigin, hop: HttpUrl): String? {
        val referrer = origin.referrer ?: return null
        if (referrer.startsWith("$HTTPS:") && !hop.isHttps) return null
        return "$referrer/"
    }

    /**
     * Whether the player treats [address] as this device or the home network: what typed
     * addresses count as local, plus 0.0.0.0/8 and `::`, 100.64.0.0/10, and NAT64 addresses by
     * the IPv4 address they reach.
     */
    fun isLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 0xFF }
        val ipv4 = when {
            bytes.size == IPV4_BYTES -> bytes
            bytes.size == IPV6_BYTES && (bytes.startsWith(IPV4_MAPPED_PREFIX) || bytes.startsWith(NAT64_PREFIX)) ->
                bytes.takeLast(IPV4_BYTES)
            else -> null
        }
        if (ipv4 == null) return bytes.all { it == 0 } || AddressPolicy.isLocalAddress(bytes)
        return AddressPolicy.isLocalAddress(ipv4) || MEDIA_LOCAL_IPV4.any { (network, length) -> inNetwork(ipv4, network, length) }
    }

    /**
     * A local name or literal: localhost and its subdomains, home network names, single-label
     * names ("nas"), and local addresses ([isLocalAddress]).
     */
    private fun isLocalHost(host: String): Boolean {
        literal(host)?.let { return isLocalAddress(it) }
        val name = host.trimEnd('.')
        return HostNames.isLocalHost(name) || name.endsWith(LOCALHOST_SUFFIX, ignoreCase = true) || '.' !in name
    }

    /** [host] as an address when it is an IPv6 literal or a dotted quad; never a DNS lookup. */
    private fun literal(host: String): InetAddress? {
        if (DOTTED_QUAD.matches(host)) return InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray())
        if (':' !in host) return null
        // A literal: InetAddress parses it without resolving anything.
        return try {
            InetAddress.getByName(host)
        } catch (invalid: UnknownHostException) {
            null
        }
    }

    /** WHATWG URL's "ends in a number": the last label (a trailing dot aside) is decimal or 0x hex. */
    private fun endsInNumber(host: String): Boolean {
        if (':' in host) return false
        val labels = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        val last = labels.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return last.startsWith(HEX_PREFIX, ignoreCase = true) && HEX_DIGITS.matches(last.substring(HEX_PREFIX.length))
    }

    /** The origin of [url] (`https://example.org`, with a port only when it is not the default). */
    private fun originOf(url: HttpUrl): String =
        HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build().toString().removeSuffix("/")

    private fun List<Int>.startsWith(prefix: List<Int>) = size >= prefix.size && subList(0, prefix.size) == prefix

    private fun inNetwork(address: List<Int>, network: List<Int>, prefixLength: Int): Boolean =
        (0 until prefixLength).all { bit -> bitAt(address, bit) == bitAt(network, bit) }

    private fun bitAt(bytes: List<Int>, index: Int): Int =
        (bytes[index / BITS_PER_BYTE] shr (BITS_PER_BYTE - 1 - index % BITS_PER_BYTE)) and 1
}
