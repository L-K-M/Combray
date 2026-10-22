package ch.lkmc.combray.core.browser

/**
 * Where captured page text goes (Hister, through the outbox). The coordinator decides *when* a
 * document can be captured and guarantees *which* document the text came from; the sink decides
 * *whether* (opt-in, exclusions, budget) and does the queueing.
 *
 * Forgetting runs inside the browser database transaction: durable cancellation must join that
 * same database instance and transaction, and finish before returning. No network work belongs
 * in that path; a separate Room instance cannot join the transaction.
 */
interface CaptureSink {
    /**
     * Policy at decision time: the policy generation to tag the capture with, or null when
     * [url] must not be captured (capture off, excluded host, credential-bearing address...).
     */
    suspend fun wantsCapture(url: String): Long?

    /** Text from the document that is still current in its tab. Never relabeled with another URL. */
    suspend fun captured(page: CapturedPage)

    /** Local forgetting also drops unsent captures for the site (or the one address). */
    suspend fun forgetHost(host: String, includeSubdomains: Boolean)

    suspend fun forgetUrl(url: String)
}

data class CapturedPage(
    val tabId: String,
    val visitId: String?,
    /** The engine's committed address for the captured document, not the page-reported one. */
    val url: String,
    val title: String?,
    val text: String,
    val sha256: String,
    val truncated: Boolean,
    val policyGeneration: Long,
)

/** Capture timing and bounds (docs/IMPLEMENTATION_PLAN.md section 5, proposed values). */
data class CaptureTiming(
    /** After load completes: let late scripts render. */
    val settleMs: Long = 2_000,
    /** After commit, for pages that never finish loading. */
    val fallbackMs: Long = 10_000,
    /** After a same-document URL change (single-page apps). */
    val debounceMs: Long = 2_000,
    /** The engine abandons a result after this; the page script itself cannot be interrupted. */
    val timeoutMs: Int = 3_000,
    val maxTextBytes: Int = 256 * 1024,
)
