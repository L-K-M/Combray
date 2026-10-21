package ch.lkmc.combray.core.integration

import android.util.Log
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.OutboxJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        var sent = 0
        try {
            // Expired captures must never go out: if pruning cannot run, this round claims
            // nothing. The wakeup below brings the queue back when storage recovers.
            if (step { outbox.prune() } != null) {
                while (sent < maxJobs) {
                    val claim = step { outbox.claim(owner, leaseMs) } ?: break
                    val result = dispatch(claim)
                    val finished = step { outbox.finish(claim, result, onDone) }
                    // A cycle that broke after the claim (a storage error, a recorder bug) must
                    // not leave the job RUNNING under a live lease: it waits for its backoff
                    // instead, and the attempt limit hands it to the user.
                    if (finished == null) step { outbox.finish(claim, DispatchResult.Retry("cycle_failed")) }
                    if (finished == true) sent++
                }
                step { outbox.prune() }
            }
        } finally {
            // Booked even when this coroutine is cancelled: a broken step or a cancelled drain
            // must not strand the jobs behind it until the next foreground kick.
            withContext(NonCancellable) { step { scheduler.schedule(outbox.nextWakeup()) } }
        }
        sent
    }

    /**
     * Runs one queue step. A non-cancellation failure is logged and swallowed (the class name
     * only: an exception message can quote a request header or page content), so a broken step
     * never stops the queue or the wakeup behind it. Cancellation still propagates.
     */
    private suspend fun <T> step(action: suspend () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.e(LOG_TAG, "outbox step failed: ${failure.javaClass.name}")
        null
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

        /** Longer than one request can take (two 15 s timeouts), so a live claim never expires. */
        const val LEASE_MS = 60_000L
        const val MAX_JOBS_PER_DRAIN = 50
    }
}
