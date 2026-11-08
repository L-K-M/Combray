package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chooses which of a service's addresses a request goes to. The address that last answered
 * goes first; the other is tried only when no connection could be made, so a request the
 * server may have received is never sent again elsewhere.
 *
 * ```
 *  at home:  http://keep.lan  answers                            -> remembered
 *  outside:  http://keep.lan  no connection -> https://keep.tailnet.ts.net answers -> remembered
 *  next:     https://keep.tailnet.ts.net first
 * ```
 */
internal class ServiceRoutes(private val transport: HttpTransport) {
    /** Destination fingerprint -> the root that last answered. Process memory only. */
    private val lastAnswered = ConcurrentHashMap<String, String>()

    /**
     * Runs [request] with a root of [service] and a transport for it; returns the last attempt's
     * result. [request] must make at most one HTTP request: a failure to connect anywhere in it
     * runs all of it again on the next root. Sending twice is a programming bug, not a retryable
     * result. Preflight validation may return without sending; that is not an answered root.
     */
    fun <T> call(service: ServiceEntity, request: (HttpTransport, String) -> T): T {
        val roots = ordered(service)
        for ((index, root) in roots.withIndex()) {
            val attempt = AttemptTransport(transport)
            val result = request(attempt, root)
            val calls = attempt.requestCount
            check(calls <= 1) { "an attempt makes exactly one request" }
            if (calls == 0) return result
            if (!attempt.notConnected) {
                lastAnswered[service.fingerprint] = root
                return result
            }
            if (index == roots.lastIndex) return result
        }
        error("a service always has a root")
    }

    private fun ordered(service: ServiceEntity): List<String> {
        val preferred = lastAnswered[service.fingerprint]?.takeIf { it in service.roots } ?: return service.roots
        return listOf(preferred) + (service.roots - preferred)
    }

    /**
     * Remembers whether a request of this attempt could not connect. One attempt is one request:
     * a handler that sends twice cannot re-send an ambiguous request on the next root.
     */
    private class AttemptTransport(private val transport: HttpTransport) : HttpTransport {
        @Volatile var notConnected = false
        private val calls = AtomicInteger()

        /** A caught second-call violation must still invalidate the handler's final result. */
        val requestCount: Int get() = calls.get()

        override fun execute(request: HttpRequest): HttpOutcome {
            // Claim the attempt before sending: every outcome, including a thrown failure,
            // leaves it used, so a second request cannot slip through a route fallback.
            check(calls.incrementAndGet() == 1) { "an attempt makes exactly one request" }
            return transport.execute(request).also { if (it is HttpOutcome.NotConnected) notConnected = true }
        }
    }
}
