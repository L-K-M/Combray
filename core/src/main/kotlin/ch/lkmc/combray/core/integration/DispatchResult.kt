package ch.lkmc.combray.core.integration

/** What one delivery attempt established. Categories are safe to store and log. */
sealed interface DispatchResult {
    /** The service acknowledged it. [remoteId] may be null if the answer could not be parsed. */
    data class Done(val remoteId: String?, val note: String? = null) : DispatchResult

    /** Transient: network, timeout, 408, 429, 5xx. [retryAfterMs] from the server, if any. */
    data class Retry(val category: String, val retryAfterMs: Long? = null) : DispatchResult

    /** 401/403: pause until the user fixes the credential (or the CSRF convention). */
    data class AuthRequired(val category: String) : DispatchResult

    /** Permanent: invalid request, unexpected redirect, oversized payload. Needs the user. */
    data class Rejected(val category: String) : DispatchResult

    /** Not sent: policy changed since the job was made (exclusion, forgetting). */
    data class Cancelled(val category: String) : DispatchResult
}

sealed interface ConnectionCheck {
    data class Connected(val detail: String?) : ConnectionCheck

    data object Unauthorized : ConnectionCheck

    /** No credential stored on this device (never entered, or unreadable after a key reset). */
    data object CredentialMissing : ConnectionCheck

    /** Reachable, but the root does not look like this service (404, redirect, other answer). */
    data class WrongAddress(val status: Int) : ConnectionCheck

    data class Unreachable(val category: String) : ConnectionCheck
}

/** Shared HTTP status handling for the service clients. */
internal object HttpStatus {
    private const val MAX_RETRY_AFTER_MS = 6L * 60 * 60 * 1000
    private const val MILLIS_PER_SECOND = 1000L

    fun classifyFailure(outcome: HttpOutcome.Response): DispatchResult = when (val status = outcome.status) {
        401, 403 -> DispatchResult.AuthRequired("http_$status")
        408, 429 -> DispatchResult.Retry("http_$status", retryAfterMs(outcome.headers))
        in 500..599 -> DispatchResult.Retry("http_$status", retryAfterMs(outcome.headers))
        in 300..399 -> DispatchResult.Rejected("redirect")
        else -> DispatchResult.Rejected("http_$status")
    }

    /**
     * `Retry-After` in seconds, bounded; HTTP-date values are ignored (the backoff applies).
     * The seconds are clamped before multiplying: a huge value would overflow `Long`, and
     * the wrapped result would replace the server's "wait" with the ordinary backoff or an
     * arbitrary delay.
     */
    fun retryAfterMs(headers: Map<String, String>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value ?: return null
        val seconds = value.trim().toLongOrNull() ?: return null
        return seconds.coerceIn(0, MAX_RETRY_AFTER_MS / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
    }
}
