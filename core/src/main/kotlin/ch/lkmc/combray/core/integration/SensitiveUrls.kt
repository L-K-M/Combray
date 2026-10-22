package ch.lkmc.combray.core.integration

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Addresses that look like they carry a login or session credential (a Kagi private-session
 * link, an emailed sign-in link, user:password@). They are not sent to Karakeep or Hister, and
 * the user is told why. This is a narrow guard, not a secret detector.
 */
object SensitiveUrls {
    /**
     * Parameter names compared after lowercasing and dropping `-`, `_` and `.`, so `accessToken`,
     * `access-token` and `ACCESS_TOKEN` read the same.
     */
    private val CREDENTIAL_NAMES = setOf(
        "auth", "authorization", "password", "passwd", "pwd", "pass", "session", "sid", "sig",
        "key", "ticket", "jwt", "otp", "authcode",
    )

    /** Fragments that make any name a credential: `client_secret`, `X-Amz-Signature`, `oauth_verifier`. */
    private val CREDENTIAL_FRAGMENTS = listOf(
        "token", "secret", "password", "signature", "credential", "apikey", "accesskey", "sessionid",
        "sessid", "oauth",
    )

    /** Pagination cursors that only happen to contain "token" (Google, AWS, Microsoft Graph). */
    private val HARMLESS_NAMES = setOf(
        "pagetoken", "nextpagetoken", "prevpagetoken", "continuationtoken", "nexttoken", "\$skiptoken",
    )

    /** Separators of parameters: `&`, and `;` in older query strings and in path matrix parameters. */
    private val PARAMETER_SEPARATORS = Regex("[&;]")

    /** An OAuth authorization code (`code`) is a credential when its callback also carries `state`. */
    private const val AUTHORIZATION_CODE = "code"
    private const val AUTHORIZATION_STATE = "state"

    fun looksSensitive(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (malformed: URISyntaxException) {
            return true
        }
        if (uri.rawUserInfo != null) return true
        // OAuth implicit-flow callbacks carry tokens in the fragment (#access_token=...), after
        // the route in hash-routed apps (#/callback?access_token=...); Java servlets rewrite
        // session IDs into the path (/cart;jsessionid=...).
        val matrix = uri.rawPath.orEmpty().split('/').flatMap { it.split(';').drop(1) }.joinToString(";")
        // In a fragment only name=value pairs are parameters: "#token-usage" or a route such as
        // "#/settings/api-keys" is an anchor, not a credential.
        val fragment = uri.rawFragment?.substringAfterLast('?')?.split(PARAMETER_SEPARATORS)?.filter { '=' in it }
        val parts = listOfNotNull(
            uri.rawQuery?.split(PARAMETER_SEPARATORS),
            fragment,
            matrix.ifEmpty { null }?.split(PARAMETER_SEPARATORS),
        )
        return parts.any { segments ->
            val names = segments.map { decodedName(it.substringBefore('=')) ?: return true }.map(::normalized)
            names.any(::isCredential) || (AUTHORIZATION_CODE in names && AUTHORIZATION_STATE in names)
        }
    }

    /**
     * Names are percent-decoded once (#95) so `%74oken` cannot hide a credential. Decoding again
     * is not done: `%2574oken` becomes the literal name "%74oken", not a credential. An escape
     * the decoder rejects gives null, treated conservatively like a malformed URI; [URI] already
     * refuses such escapes before this runs, so that is defense in depth.
     */
    private fun decodedName(name: String): String? = try {
        URLDecoder.decode(name, Charsets.UTF_8.name())
    } catch (malformed: IllegalArgumentException) {
        null
    }

    private fun normalized(name: String): String = name.lowercase().filterNot { it == '-' || it == '_' || it == '.' }

    private fun isCredential(name: String): Boolean {
        if (name in HARMLESS_NAMES) return false
        return name in CREDENTIAL_NAMES || CREDENTIAL_FRAGMENTS.any { it in name }
    }
}
