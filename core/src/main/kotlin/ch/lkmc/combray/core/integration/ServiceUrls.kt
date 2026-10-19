package ch.lkmc.combray.core.integration

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** Outcome of checking a service root the user typed. */
sealed interface ServiceRoot {
    /** Normalized root without trailing slash or API suffix, e.g. `https://keep.example/sub`. */
    data class Valid(val root: String) : ServiceRoot

    data class Invalid(val reason: ServiceRootProblem) : ServiceRoot
}

enum class ServiceRootProblem { NOT_A_URL, UNSUPPORTED_SCHEME, CLEARTEXT_NOT_ALLOWED, HAS_CREDENTIALS_OR_QUERY }

object ServiceUrls {
    /**
     * Normalizes a service root: HTTPS, or plain HTTP only to [cleartextHosts] (debug builds allow
     * the emulator's own loopback for fixtures). Keeps a path prefix (`/karakeep`) and drops a
     * pasted API suffix (`/api/v1`, `/api`), so endpoints are never doubled.
     */
    fun normalizeRoot(input: String, apiSuffixes: List<String>, cleartextHosts: Set<String>): ServiceRoot {
        val uri = try {
            URI(input.trim())
        } catch (malformed: URISyntaxException) {
            return ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL)
        }
        val host = uri.host?.lowercase() ?: return ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL)
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (host !in cleartextHosts) return ServiceRoot.Invalid(ServiceRootProblem.CLEARTEXT_NOT_ALLOWED)
            else -> return ServiceRoot.Invalid(ServiceRootProblem.UNSUPPORTED_SCHEME)
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY)
        }
        var path = (uri.rawPath ?: "").trimEnd('/')
        apiSuffixes.firstOrNull { path.endsWith(it) }?.let { path = path.removeSuffix(it).trimEnd('/') }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        // java.net.URI already returns IPv6 literals in brackets.
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return ServiceRoot.Valid("${uri.scheme.lowercase()}://$hostPart$port$path")
    }

    /** `root` + `/path`, preserving the root's own path prefix. */
    fun endpoint(root: String, path: String): String = root.trimEnd('/') + "/" + path.trimStart('/')

    /** Non-secret destination identity (the normalized root), safe to store next to jobs. */
    fun fingerprint(root: String): String =
        MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
