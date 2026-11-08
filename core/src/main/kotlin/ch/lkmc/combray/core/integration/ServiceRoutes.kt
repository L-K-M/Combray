package ch.lkmc.combray.core.integration

import ch.lkmc.combray.core.data.ServiceEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
     * result. [request] must make exactly one HTTP request: a failure to connect anywhere in it
     * runs all of it again on the next root. A handler that sends twice, or returns without
     * sending, is a programming bug: the attempt fails instead of returning a retryable result.
     */
    fun <T> call(service: ServiceEntity, request: (HttpTransport, String) -> T): T {
        val roots = ordered(service)
        for ((index, root) in roots.withIndex()) {
            val attempt = AttemptTransport(transport)
            val result = request(attempt, root)
            // A callback that returns without sending would otherwise look like an answered
            // attempt and would remember this root as the one that works.
            check(attempt.consumed) { "an attempt makes exactly one request" }
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
        private val used = AtomicBoolean(false)

        /** Whether the attempt's single request has run. */
        val consumed: Boolean get() = used.get()

        override fun execute(request: HttpRequest): HttpOutcome {
            // Claim the attempt before sending: every outcome, including a thrown failure,
            // leaves it used, so a second request cannot slip through a route fallback.
            check(used.compareAndSet(false, true)) { "an attempt makes exactly one request" }
            return transport.execute(request).also { if (it is HttpOutcome.NotConnected) notConnected = true }
        }
    }
}
