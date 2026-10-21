package ch.lkmc.combray.core.integration

import android.util.Log
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.OutboxJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Sends one job. Rechecks policy and destination first; never retargets the payload. */
fun interface JobHandler {
    suspend fun dispatch(job: OutboxJobEntity): DispatchResult
}

/** Asks the platform to run [OutboxDispatcher.drain] at [atMillis] (null: nothing scheduled). */
fun interface WakeupScheduler {
    fun schedule(atMillis: Long?)
}

/**
 * Drains due jobs. Foreground triggers and the background worker share this and the claim
 * mechanism; the mutex only avoids two loops in one process, the fenced claims make concurrent
 * dispatchers safe.
 */
class OutboxDispatcher(
    private val outbox: Outbox,
    private val handlers: Map<JobKind, JobHandler>,
    private val onDone: suspend (OutboxJobEntity, DispatchResult.Done) -> Unit,
    private val scheduler: WakeupScheduler,
    private val leaseMs: Long = LEASE_MS,
) {
    private val running = Mutex()

    /** Sends due jobs until none is left (or [maxJobs]), then schedules the next wakeup. */
    suspend fun drain(maxJobs: Int = MAX_JOBS_PER_DRAIN): Int = running.withLock {
        val owner = UUID.randomUUID().toString()
        // Expire old captures before anything is claimed: they must never go out.
        outbox.prune()
        var sent = 0
        while (sent < maxJobs) {
            val claim = outbox.claim(owner, leaseMs) ?: break
            val result = dispatch(claim)
            outbox.finish(claim, result, onDone)
            sent++
        }
        outbox.prune()
        scheduler.schedule(outbox.nextWakeup())
        sent
    }

    /** A handler bug stops the job (FAILED, visible in Settings), never the app. */
    private suspend fun dispatch(claim: Claim): DispatchResult {
        val handler = handlers[claim.job.kind] ?: return DispatchResult.Rejected("no_handler")
        return try {
            withContext(Dispatchers.IO) { handler.dispatch(claim.job) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The class only: an exception message can quote a request header or page content.
            Log.e(LOG_TAG, "dispatching job ${claim.job.id} failed: ${failure.javaClass.name}")
            DispatchResult.Rejected("internal_error")
        }
    }

    companion object {
        private const val LOG_TAG = "CombrayOutbox"

        /**
         * Longer than the slowest live dispatch: a Karakeep save-with-list is two requests, and
         * each retries the second root only when the first never connected. That bounds a
         * request at ~8 s failed connect + ~23 s successful one (8 s connect + 15 s read), so
         * ~62 s per dispatch before DNS, which the timeouts do not bound. The lease stays above
         * it so a live claim never expires under a second dispatcher.
         */
        const val LEASE_MS = 150_000L
        const val MAX_JOBS_PER_DRAIN = 50
    }
}
