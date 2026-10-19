package ch.lkmc.combray.core.integration

import kotlin.random.Random

/**
 * Combray's retry policy (docs/IMPLEMENTATION_PLAN.md section 6): exponential backoff with
 * jitter from 30 s up to 6 h, a server's Retry-After when longer, and the user's attention after
 * ten attempts. Policy values, not API limits.
 */
class RetryPolicy(
    private val initialMs: Long = 30_000,
    private val maxMs: Long = 6L * 60 * 60 * 1000,
    val maxAttempts: Int = 10,
    private val random: Random = Random.Default,
) {
    /** Delay before the next attempt, after [attempts] failed attempts (1 or more). */
    fun delayMs(attempts: Int, retryAfterMs: Long?): Long {
        val exponent = (attempts - 1).coerceIn(0, MAX_DOUBLINGS)
        val backoff = (initialMs shl exponent).coerceAtMost(maxMs)
        val jittered = (backoff * (1.0 - JITTER + random.nextDouble() * 2 * JITTER)).toLong()
        return maxOf(jittered, retryAfterMs ?: 0).coerceAtMost(maxMs)
    }

    private companion object {
        const val JITTER = 0.2
        const val MAX_DOUBLINGS = 20
    }
}
