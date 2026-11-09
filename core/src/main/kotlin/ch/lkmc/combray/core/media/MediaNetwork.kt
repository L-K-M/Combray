package ch.lkmc.combray.core.media

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.ProtocolException
import java.net.Proxy

/**
 * The media player's HTTP client: one base client (one connection pool) for the process, and a
 * call factory per playback that applies [MediaPolicy] to every hop.
 *
 * ```
 *  Media3 ─> call ─> first-request check ─> OkHttp: resolve ─> connect ─> HopPolicy ─> send
 *                   (URL rules, no connect)  (open addresses             (URL rules, connected address,
 *                                             only: OpenAddresses)        headers; a redirect's target
 *                                                                         before OkHttp follows it)
 * ```
 *
 * Nothing of Servo's is shared: no cookies, no cache, no authenticator (a 401 is an error), no
 * proxy. OkHttp follows redirects itself, at most 20, and returns one to a non-web scheme
 * unfollowed; [HopPolicy] refuses that one first.
 */
class MediaNetwork(
    private val base: OkHttpClient = baseClient(),
    private val zoneOf: (InetAddress) -> AddressZone = MediaPolicy::zoneOf,
) {
    /** The [Call.Factory] Media3 fetches [request] with. */
    fun callFactory(request: PlaybackRequest): Call.Factory = base.newBuilder()
        .dns(OpenAddresses(base.dns, request.origin, zoneOf))
        .addInterceptor(FirstRequestCheck(request.origin))
        .addNetworkInterceptor(HopPolicy(request, zoneOf))
        .build()

    companion object {
        /** The shared client; tests pass their own [dns] (production: the system's). */
        fun baseClient(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .dns(dns)
            .build()
    }
}

/**
 * The player refused a request under [reason]. A [ProtocolException], so OkHttp does not retry it
 * on another route. The message is the reason's name only: never the address.
 */
class MediaRefusedException(val reason: MediaRefusal) : ProtocolException(reason.name)

/** The refusal behind [error], looking through its causes (Media3 and OkHttp wrap it), or null. */
fun refusalIn(error: Throwable): MediaRefusal? {
    val seen = HashSet<Throwable>()
    var cause: Throwable? = error
    while (cause != null && seen.add(cause)) {
        if (cause is MediaRefusedException) return cause.reason
        cause = cause.cause
    }
    return null
}

/**
 * The addresses [system] resolves a host to, minus those the device rule closes to [origin]'s page:
 * OkHttp then never connects to one and tries the next address instead. No address left is a
 * refusal, before any connection.
 */
private class OpenAddresses(
    private val system: Dns,
    private val origin: MediaOrigin,
    private val zoneOf: (InetAddress) -> AddressZone,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = system.lookup(hostname)
        .filter { MediaPolicy.checkAddress(origin, hostname, it, zoneOf) == null }
        .ifEmpty { throw MediaRefusedException(MediaRefusal.DEVICE_OR_LINK) }
}

/** Refuses the first request before OkHttp connects, when its address alone breaks a rule. */
private class FirstRequestCheck(private val origin: MediaOrigin) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        MediaPolicy.check(origin, chain.request().url)?.let { throw MediaRefusedException(it) }
        return chain.proceed(chain.request())
    }
}

/**
 * A network interceptor: OkHttp calls it once per hop, after connecting and before sending. It
 * applies the URL rules again, the device rule to the address actually connected to (a backstop
 * behind [OpenAddresses]),
 * sets the headers, and checks a redirect's target before OkHttp follows it, so a target the URL
 * rules refuse is never connected to.
 */
internal class HopPolicy(
    private val request: PlaybackRequest,
    private val zoneOf: (InetAddress) -> AddressZone = MediaPolicy::zoneOf,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val hop = chain.request()
        val origin = request.origin
        MediaPolicy.check(origin, hop.url)?.let { throw MediaRefusedException(it) }
        // Whatever name or literal led there: the address OkHttp connected to.
        val connection = checkNotNull(chain.connection()) { "a network interceptor runs on a connection" }
        MediaPolicy.checkAddress(origin, hop.url.host, connection.route().socketAddress.address, zoneOf)?.let { throw MediaRefusedException(it) }

        val referer = MediaPolicy.referer(origin, hop.url)
        val sent = hop.newBuilder()
            .header(USER_AGENT, request.userAgent)
            .apply { if (referer != null) header(REFERER, referer) else removeHeader(REFERER) }
            .build()
        val response = chain.proceed(sent)
        if (!response.isRedirect) return response

        val location = response.header(LOCATION) ?: return response
        // OkHttp resolves the target the same way, against this hop.
        val target = hop.url.resolve(location)
        val refusal = if (target == null) MediaRefusal.NOT_WEB else MediaPolicy.check(origin, target)
        if (refusal != null) {
            // Closed, so the connection is reused or released without OkHttp's leak warning.
            response.close()
            throw MediaRefusedException(refusal)
        }
        return response
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
        const val REFERER = "Referer"
        const val LOCATION = "Location"
    }
}
