package ch.lkmc.combray.core.integration

import java.net.URI
import java.net.URISyntaxException

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
        "key", "ticket", "jwt", "otp",
    )

    /** Fragments that make any name a credential: `client_secret`, `X-Amz-Signature`, `oauth_verifier`. */
    private val CREDENTIAL_FRAGMENTS = listOf(
        "token", "secret", "password", "signature", "credential", "apikey", "accesskey", "sessionid",
        "sessid", "oauth",
    )

    /** Pagination cursors that only happen to contain "token". */
    private val HARMLESS_NAMES = setOf("pagetoken", "nextpagetoken", "prevpagetoken", "continuationtoken")

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
        // OAuth implicit-flow callbacks carry tokens in the fragment (#access_token=...).
        return listOfNotNull(uri.rawQuery, uri.rawFragment).any { part ->
            val names = part.split('&').map { normalized(it.substringBefore('=')) }
            names.any(::isCredential) || (AUTHORIZATION_CODE in names && AUTHORIZATION_STATE in names)
        }
    }

    private fun normalized(name: String): String = name.lowercase().filterNot { it == '-' || it == '_' || it == '.' }

    private fun isCredential(name: String): Boolean {
        if (name in HARMLESS_NAMES) return false
        return name in CREDENTIAL_NAMES || CREDENTIAL_FRAGMENTS.any { it in name }
    }
}
