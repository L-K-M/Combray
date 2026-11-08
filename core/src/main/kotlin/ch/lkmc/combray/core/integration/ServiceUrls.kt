package ch.lkmc.combray.core.integration

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** Outcome of checking a service root the user typed. */
sealed interface ServiceRoot {
    /** Normalized root without trailing slash or API suffix, e.g. `https://keep.example/sub`. */
    data class Valid(val root: String) : ServiceRoot

    data class Invalid(val reason: ServiceRootProblem) : ServiceRoot
}

enum class ServiceRootProblem { NOT_A_URL, UNSUPPORTED_SCHEME, HAS_CREDENTIALS_OR_QUERY }

/** Which of a service's two address fields a problem is in. */
enum class AddressField { MAIN, ALTERNATE }

/** Outcome of checking a service's address and its optional alternate. */
sealed interface ServiceAddresses {
    /**
     * [alternate] reaches the same server another way (a VPN such as Tailscale, outside home).
     * The server's identity is its main address: changing the alternate keeps queued work.
     */
    data class Valid(val root: String, val alternate: String?) : ServiceAddresses {
        val fingerprint get() = ServiceUrls.fingerprint(root)
    }

    data class Invalid(val field: AddressField, val problem: ServiceRootProblem) : ServiceAddresses
}

object ServiceUrls {
    /**
     * Normalizes a service root, HTTPS or plain HTTP (a home server often has no certificate;
     * Settings says what plain HTTP exposes). Keeps a path prefix (`/karakeep`) and drops a
     * pasted API suffix (`/api/v1`, `/api`), so endpoints are never doubled.
     */
    fun normalizeRoot(input: String, apiSuffixes: List<String>): ServiceRoot {
        val uri = try {
            URI(input.trim())
        } catch (malformed: URISyntaxException) {
            return ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL)
        }
        val scheme = uri.scheme?.lowercase() ?: return ServiceRoot.Invalid(ServiceRootProblem.UNSUPPORTED_SCHEME)
        if (scheme !in WEB_SCHEMES) return ServiceRoot.Invalid(ServiceRootProblem.UNSUPPORTED_SCHEME)
        val authority = uri.rawAuthority ?: return ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL)
        // java.net.URI does not read a non-ASCII (IDN) or trailing-dot authority as server-based:
        // its host, userinfo and port then come back null even when they are present. The '@' is
        // checked here for the same reason, so credentials cannot hide behind an IDN host.
        if (uri.rawUserInfo != null || '@' in authority || uri.rawQuery != null || uri.rawFragment != null) {
            return ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY)
        }
        val parsed = parseAuthority(authority) ?: return ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL)
        var path = (uri.rawPath ?: "").trimEnd('/')
        apiSuffixes.firstOrNull { path.endsWith(it) }?.let { path = path.removeSuffix(it).trimEnd('/') }
        // A default port and a trailing dot are the same server as without them, so they do not
        // change the destination identity or force queued work to be re-bound.
        val port = parsed.port?.takeUnless { it == defaultPort(scheme) }
        return ServiceRoot.Valid("$scheme://${parsed.host}${port?.let { ":$it" } ?: ""}$path")
    }

    /** The authority's ASCII host (IPv6 in brackets) and optional port, or null when unusable. */
    internal data class Authority(val host: String, val port: Int?)

    /**
     * Parses a URL authority strictly. An explicit port must be decimal digits in 1..65535;
     * a colon with no digits after it, or any other malformed port text, is refused rather than
     * read as the scheme default. The HTTP transport applies the same check before connecting.
     */
    internal fun parseAuthority(authority: String): Authority? {
        if ('@' in authority) return null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(0, end + 1).lowercase()
            // Only nothing or ":" + a valid port may follow the literal; anything else is not an
            // authority, not a silently dropped suffix.
            val rest = authority.substring(end + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> explicitPort(rest.substring(1)) ?: return null
                else -> return null
            }
            return Authority(host, port)
        }
        val written = authority.substringBefore(':').trimEnd('.')
        // A percent-encoded reg-name is not a host the app can resolve; the old URI parse refused it.
        if (written.isEmpty() || '%' in written) return null
        val host = try {
            IDN.toASCII(written, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (invalid: IllegalArgumentException) {
            return null
        }
        // A present but malformed or out-of-range port is refused, never read as the default port.
        val port = if (authority.contains(':')) explicitPort(authority.substringAfter(':', "")) ?: return null else null
        return Authority(host, port)
    }

    /** The port text after ":", or null when it is empty or malformed. */
    private fun explicitPort(text: String): Int? =
        text.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
            ?.toIntOrNull()
            ?.takeIf { port -> isValidPort(port) }

    /** -1 means the scheme's default; an explicit port must be a usable TCP destination. */
    internal fun isValidPort(port: Int): Boolean = port == -1 || port in 1..65535

    private fun defaultPort(scheme: String) = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> error("not a web scheme: $scheme")
    }

    /** Checks both fields; a blank alternate, or one equal to the main address, is none. */
    fun normalizeAddresses(rootInput: String, alternateInput: String?, apiSuffixes: List<String>): ServiceAddresses {
        val root = when (val checked = normalizeRoot(rootInput, apiSuffixes)) {
            is ServiceRoot.Invalid -> return ServiceAddresses.Invalid(AddressField.MAIN, checked.reason)
            is ServiceRoot.Valid -> checked.root
        }
        if (alternateInput.isNullOrBlank()) return ServiceAddresses.Valid(root, null)
        val alternate = when (val checked = normalizeRoot(alternateInput, apiSuffixes)) {
            is ServiceRoot.Invalid -> return ServiceAddresses.Invalid(AddressField.ALTERNATE, checked.reason)
            is ServiceRoot.Valid -> checked.root
        }
        return ServiceAddresses.Valid(root, alternate.takeIf { it != root })
    }

    /** `root` + `/path`, preserving the root's own path prefix. */
    fun endpoint(root: String, path: String): String = root.trimEnd('/') + "/" + path.trimStart('/')

    /** Non-secret destination identity (the normalized root), safe to store next to jobs. */
    fun fingerprint(root: String): String =
        MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Plain HTTP: the credential and what Combray sends can be read on the network. */
    fun isCleartext(root: String): Boolean = root.startsWith("http:", ignoreCase = true)

    private val WEB_SCHEMES = setOf("http", "https")
}
