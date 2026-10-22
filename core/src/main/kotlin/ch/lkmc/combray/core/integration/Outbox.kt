package ch.lkmc.combray.core.integration

import android.util.Log
import androidx.room.withTransaction
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind

sealed interface Enqueued {
    data class Queued(val job: OutboxJobEntity) : Enqueued

    /** An unfinished job for the same destination and key exists; no second job was made. */
    data class AlreadyQueued(val job: OutboxJobEntity) : Enqueued

    /** The service was removed, or (for [Binding.SAME_DESTINATION]) points elsewhere now. */
    data object DestinationChanged : Enqueued
}

/**
 * Which configuration a new job is bound to. The caller's copy of the service row may be stale
 * (settings can change while a page is captured), so the row is read again when queuing.
 */
enum class Binding {
    /** Whatever is configured when the job is stored: a save the user just tapped. */
    CURRENT,

    /** Only the destination the caller read: a capture made under that destination's policy. */
    SAME_DESTINATION,
}

/** A job claimed by one dispatcher run. Only this [owner] with this [fence] may finish it. */
data class Claim(val job: OutboxJobEntity, val owner: String)

/**
 * The durable queue. The database is the source of truth: WorkManager only wakes the
 * dispatcher, and every state change after a claim is checked against the claim's fence, so a
 * worker whose lease expired (and whose job was taken over) cannot overwrite newer state.
 *
 * ```
 * PENDING ─claim─> RUNNING ─Done──────> SUCCEEDED
 *    ^                │──Retry────────> RETRY_WAIT ─due─> (claimable again)
 *    │                │──AuthRequired─> AUTH_REQUIRED ─resume─┐
 *    │                └──Rejected/too many attempts─> FAILED ─retry─┤
 *    └──────────────────────────────────────────────────────────────┘
 * newer capture of the same key: unsent older jobs -> SUPERSEDED
 * destination changed: unfinished saves -> DESTINATION_CHANGED (sent only after the user
 *   rebinds them), unfinished captures -> CANCELLED; never sent elsewhere silently
 * refused on purpose by the service (Declined): -> CANCELLED, captured text dropped
 * Retry that could not connect: RETRY_WAIT, not counted toward the attempt limit
 * ```
 */
class Outbox(
    private val database: CombrayDatabase,
    private val clock: Clock,
    private val newId: () -> String,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private val dao = database.integrations()

    /**
     * Records an action and its job in one transaction (invariant 7). With [supersede] (captures),
     * unsent older jobs of the same key are replaced; otherwise (saves) an unfinished job for the
     * same key means this one is already queued.
     */
    suspend fun enqueue(
        kind: JobKind,
        read: ServiceEntity,
        binding: Binding,
        dedupeKey: String,
        payload: String,
        supersede: Boolean,
    ): Enqueued = database.withTransaction {
        val service = dao.service(read.kind) ?: return@withTransaction Enqueued.DestinationChanged
        if (binding == Binding.SAME_DESTINATION && service.fingerprint != read.fingerprint) {
            return@withTransaction Enqueued.DestinationChanged
        }

        val now = clock.now()
        if (!supersede) {
            // Saving again for the current destination replaces a copy parked for an earlier one.
            dao.parkedJobs(service.kind).filter { it.kind == kind && it.dedupeKey == dedupeKey }.forEach {
                dao.updateJob(it.copy(state = JobState.SUPERSEDED, error = "superseded", updatedAt = now))
            }
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        val active = dao.activeJobs(kind, service.fingerprint, dedupeKey)
        if (!supersede && active.isNotEmpty()) return@withTransaction Enqueued.AlreadyQueued(active.first())
        val failed = dao.failedJobs(kind, service.fingerprint, dedupeKey)
        if (!supersede && failed.isNotEmpty()) {
            // Asking again after a failure retries that job, so no stale failure outlives a save.
            val retried = failed.maxBy { it.sequence }.copy(
                state = JobState.PENDING, generation = service.generation, attempts = 0, nextAttemptAt = now, error = null, updatedAt = now,
                // The user saved this page again: its current title travels, not the stale one.
                payload = payload, payloadBytes = payloadBytes,
                leaseOwner = null, leaseUntil = null,
            ).restarted(now)
            dao.updateJob(retried)
            failed.filter { it.id != retried.id }.forEach { dao.updateJob(it.copy(state = JobState.SUPERSEDED, error = "superseded", updatedAt = now)) }
            return@withTransaction Enqueued.Queued(retried)
        }
        if (supersede) {
            // A job being sent stays; the new one waits for it, so order is kept per key.
            (active.filter { it.state != JobState.RUNNING } + failed).forEach {
                dao.updateJob(it.copy(state = JobState.SUPERSEDED, updatedAt = now, leaseOwner = null, leaseUntil = null))
            }
        }
        val job = OutboxJobEntity(
            id = newId(),
            kind = kind,
            service = service.kind,
            fingerprint = service.fingerprint,
            generation = service.generation,
            dedupeKey = dedupeKey,
            sequence = dao.lastSequence() + 1,
            payload = payload,
            payloadBytes = payloadBytes,
            state = JobState.PENDING,
            attempts = 0,
            nextAttemptAt = now,
            leaseOwner = null,
            leaseUntil = null,
            fence = 0,
            error = null,
            remoteId = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertJob(job)
        dao.dropFinishedCapturePayloads()
        Enqueued.Queued(job)
    }

    /** Claims the next due job under a lease of [leaseMs], or returns null. */
    suspend fun claim(owner: String, leaseMs: Long): Claim? = database.withTransaction {
        val now = clock.now()
        // Earlier requests in this drain may have taken another body past its deadline.
        expireCaptures(now)
        var due = dao.nextDue(now)
        while (due != null && due.attempts >= retry.maxAttempts) {
            // Crashed workers reclaimed this job until its attempts ran out: it needs the user
            // now, and must not be re-run at every wakeup forever.
            Log.w(LOG_TAG, "giving up on job ${due.id} after ${due.attempts} attempts")
            dao.updateJob(due.copy(state = JobState.FAILED, error = "abandoned_after_${due.attempts}_attempts", updatedAt = now, leaseOwner = null, leaseUntil = null))
            due = dao.nextDue(now)
        }
        if (due == null) return@withTransaction null
        if (due.state == JobState.RUNNING) {
            // The previous claimant's request may still have reached the server: ambiguous.
            Log.w(LOG_TAG, "reclaiming job ${due.id} after an expired lease")
        }
        val claimed = due.copy(
            state = JobState.RUNNING,
            leaseOwner = owner,
            leaseUntil = now + leaseMs,
            fence = due.fence + 1,
            attempts = due.attempts + 1,
            updatedAt = now,
        )
        dao.updateJob(claimed)
        Claim(claimed, owner)
    }

    /**
     * Applies a dispatch result if [claim] still owns the job; returns false otherwise (the result
     * is then not applied: a newer claim owns the job). [onDone] runs in the same transaction.
     */
    suspend fun finish(
        claim: Claim,
        result: DispatchResult,
        onDone: suspend (OutboxJobEntity, DispatchResult.Done) -> Unit = { _, _ -> },
    ): Boolean = database.withTransaction {
        val current = dao.job(claim.job.id) ?: return@withTransaction false
        if (current.state != JobState.RUNNING || current.fence != claim.job.fence || current.leaseOwner != claim.owner) {
            Log.w(LOG_TAG, "dropping a result for job ${current.id}: the claim was lost")
            return@withTransaction false
        }
        val now = clock.now()
        val released = current.copy(leaseOwner = null, leaseUntil = null, updatedAt = now)
        // Newer content for the same key exists: this one must never be sent after it.
        val superseded = result !is DispatchResult.Done &&
            dao.activeJobs(current.kind, current.fingerprint, current.dedupeKey).any { it.sequence > current.sequence }
        val next = when {
            superseded -> released.copy(state = JobState.SUPERSEDED, error = "superseded")
            else -> transition(released, current, result, now)
        }
        dao.updateJob(next)
        dao.dropFinishedCapturePayloads()
        if (result is DispatchResult.Done) onDone(next, result)
        true
    }

    private fun transition(released: OutboxJobEntity, current: OutboxJobEntity, result: DispatchResult, now: Long): OutboxJobEntity =
        when (result) {
            is DispatchResult.Done -> released.copy(state = JobState.SUCCEEDED, remoteId = result.remoteId, error = result.note)
            is DispatchResult.Retry -> when {
                // A server out of reach for a week is gone or misaddressed, not briefly away.
                result.delivery == Delivery.NOT_SENT && now - current.createdAt >= UNREACHABLE_LIMIT_MS ->
                    released.copy(state = JobState.FAILED, error = "$UNREACHABLE_PREFIX${result.category}_for_${UNREACHABLE_LIMIT_DAYS}_days")
                // Nothing was sent: claim() counted this attempt, but it does not use up the limit.
                // A save waits for its server, a capture until it expires; the wait still backs off.
                result.delivery == Delivery.NOT_SENT -> released.copy(
                    state = JobState.RETRY_WAIT,
                    attempts = (current.attempts - 1).coerceAtLeast(0),
                    error = UNREACHABLE_PREFIX + result.category,
                    nextAttemptAt = now + retry.delayWithoutConnectionMs(now - current.createdAt),
                )
                current.attempts >= retry.maxAttempts ->
                    released.copy(state = JobState.FAILED, error = "${result.category}_after_${current.attempts}_attempts")
                else -> released.copy(
                    state = JobState.RETRY_WAIT,
                    error = result.category,
                    nextAttemptAt = now + retry.delayMs(current.attempts, result.retryAfterMs),
                )
            }
            is DispatchResult.AuthRequired -> released.copy(state = JobState.AUTH_REQUIRED, error = result.category)
            is DispatchResult.Rejected -> released.copy(state = JobState.FAILED, error = result.category)
            // Refused on purpose: retrying cannot help, and captured text is not kept for it.
            is DispatchResult.Declined -> released.copy(state = JobState.CANCELLED, error = result.category)
            is DispatchResult.Cancelled -> released.copy(state = JobState.CANCELLED, error = result.category)
        }

    /** User action: try a failed or paused job again now, for the destination as configured now. */
    suspend fun retryNow(jobId: String) = database.withTransaction {
        val job = dao.job(jobId) ?: return@withTransaction
        val service = dao.service(job.service)
        if (job.state !in RESUMABLE || service == null || service.fingerprint != job.fingerprint) return@withTransaction
        val now = clock.now()
        dao.updateJob(job.copy(state = JobState.PENDING, generation = service.generation, attempts = 0, nextAttemptAt = now, error = null, updatedAt = now).restarted(now))
    }

    /** User action: give up on a job. A job being sent cannot be recalled; it is left alone. */
    suspend fun discard(jobId: String) = database.withTransaction {
        val job = dao.job(jobId) ?: return@withTransaction
        if (job.state !in DISCARDABLE) return@withTransaction
        dao.updateJob(job.copy(state = JobState.CANCELLED, error = "discarded", updatedAt = clock.now()))
        dao.dropFinishedCapturePayloads()
    }

    /**
     * Destination or credential changed (inside the configuration transaction). A different
     * destination parks unfinished saves (see [PARKED_ON_DESTINATION_CHANGE]) and cancels other
     * jobs; a removal cancels all. Nothing is sent with new settings unless the user says so. Same
     * destination, new credential: jobs pause until the user resumes them explicitly.
     */
    suspend fun onServiceChanged(previous: ServiceEntity?, current: ServiceEntity?) {
        // Added after a removal: only jobs queued around the removal can exist, for no destination.
        val kind = previous?.kind ?: current?.kind ?: return
        val now = clock.now()
        // Jobs being sent are changed too: their sender's result is then not applied (the claim
        // no longer matches), and nothing old is left behind unclaimable.
        for (job in dao.openJobs(kind)) {
            val sameDestination = current != null && current.fingerprint == job.fingerprint
            val update = when {
                !sameDestination && current != null && job.kind in PARKED_ON_DESTINATION_CHANGE ->
                    job.copy(state = JobState.DESTINATION_CHANGED, error = "destination_changed")
                !sameDestination -> job.copy(state = JobState.CANCELLED, error = "destination_changed")
                // A failed job keeps its real reason; Retry rebinds it to the new generation.
                job.state == JobState.FAILED -> continue
                job.generation != current?.generation -> job.copy(state = JobState.AUTH_REQUIRED, error = "credential_changed")
                else -> continue
            }
            dao.updateJob(update.copy(updatedAt = now, leaseOwner = null, leaseUntil = null))
        }
        dao.dropFinishedCapturePayloads()
    }

    /** User action after fixing a credential: paused jobs for this destination go out again. */
    suspend fun resume(kind: ServiceKind) = database.withTransaction {
        val service = dao.service(kind) ?: return@withTransaction
        val now = clock.now()
        for (job in dao.unfinishedJobs(service.kind)) {
            if (job.state != JobState.AUTH_REQUIRED || job.fingerprint != service.fingerprint) continue
            // Like retryNow: the user's action starts the attempt budget over.
            dao.updateJob(
                job.copy(
                    state = JobState.PENDING, generation = service.generation, attempts = 0, nextAttemptAt = now, error = null, updatedAt = now,
                    leaseOwner = null, leaseUntil = null,
                ).restarted(now),
            )
        }
    }

    /**
     * User action after a main address change: parked jobs are bound to the destination and
     * generation configured now and go out with its credential. A key already queued (or failed)
     * there keeps its own job; the parked copy is superseded rather than sent twice.
     */
    suspend fun sendParkedToCurrent(kind: ServiceKind) = database.withTransaction {
        val service = dao.service(kind) ?: return@withTransaction
        val now = clock.now()
        // Newest first: when two earlier addresses parked the same key, the newer content wins.
        for (job in dao.parkedJobs(kind).sortedByDescending { it.sequence }) {
            val queuedThere = dao.activeJobs(job.kind, service.fingerprint, job.dedupeKey).isNotEmpty() ||
                dao.failedJobs(job.kind, service.fingerprint, job.dedupeKey).isNotEmpty()
            val update = when {
                queuedThere -> job.copy(state = JobState.SUPERSEDED, error = "superseded")
                else -> job.copy(
                    state = JobState.PENDING, fingerprint = service.fingerprint, generation = service.generation,
                    attempts = 0, nextAttemptAt = now, error = null,
                ).restarted(now)
            }
            dao.updateJob(update.copy(updatedAt = now))
        }
    }

    /** User action: give up on every job parked by a main address change. */
    suspend fun discardParked(kind: ServiceKind) = database.withTransaction {
        val now = clock.now()
        dao.parkedJobs(kind).forEach { dao.updateJob(it.copy(state = JobState.CANCELLED, error = "discarded", updatedAt = now)) }
    }

    suspend fun nextWakeup(): Long? = dao.nextWakeup(clock.now())

    /**
     * A user action starts a save's week of waiting for its server over: measured from the
     * original [OutboxJobEntity.createdAt], a re-saved eight-day-old failure would fail again
     * after one offline attempt. A capture keeps its createdAt, which also bounds how long its
     * page text may be kept and which forget requests cover it.
     */
    private fun OutboxJobEntity.restarted(now: Long): OutboxJobEntity =
        if (kind == JobKind.KARAKEEP_SAVE) copy(createdAt = now) else this

    /** Unsent captures expire after a week; finished rows are kept a week for status, then removed. */
    suspend fun prune() {
        val now = clock.now()
        expireCaptures(now)
        dao.deleteFinishedBefore(now - FINISHED_RETENTION_MS)
    }

    private suspend fun expireCaptures(now: Long) {
        dao.expireCapturesBefore(now - CAPTURE_MAX_AGE_MS, now)
        dao.dropFinishedCapturePayloads()
    }

    /**
     * Cancels open jobs of [kind] that [matches] selects (forgetting, exclusions), including one
     * being sent: what is already on the wire cannot be recalled, but it is never retried.
     */
    suspend fun cancelOpen(kind: JobKind, reason: String, matches: (OutboxJobEntity) -> Boolean) = database.withTransaction {
        val now = clock.now()
        dao.openJobs(serviceOf(kind)).filter { it.kind == kind && matches(it) }.forEach {
            dao.updateJob(it.copy(state = JobState.CANCELLED, error = reason, updatedAt = now, leaseOwner = null, leaseUntil = null))
        }
        dao.dropFinishedCapturePayloads()
    }

    /** The next generation for a (re)configured service: never one an existing job carries. */
    suspend fun nextGeneration(kind: ServiceKind, previous: ServiceEntity?): Long =
        maxOf(previous?.generation ?: 0, dao.maxJobGeneration(kind)) + 1

    companion object {
        private const val LOG_TAG = "CombrayOutbox"
        private const val FINISHED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Error prefix of a job whose last attempt could not connect, as in `unreachable_network`. */
        private const val UNREACHABLE_PREFIX = "unreachable_"

        /** How long unsent work waits for its server before it needs the user's attention. */
        private const val UNREACHABLE_LIMIT_DAYS = 7L
        private const val UNREACHABLE_LIMIT_MS = UNREACHABLE_LIMIT_DAYS * 24 * 60 * 60 * 1000

        /** Proposed transient body limit: captured text is not kept as a hidden archive. */
        const val CAPTURE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun serviceOf(kind: JobKind) = when (kind) {
            JobKind.KARAKEEP_SAVE -> ServiceKind.KARAKEEP
            JobKind.HISTER_ADD -> ServiceKind.HISTER
        }

        /** Whether [job] waits for its server to be reachable: its last attempt sent nothing. */
        fun waitsForConnection(job: OutboxJobEntity) =
            job.state == JobState.RETRY_WAIT && job.error?.startsWith(UNREACHABLE_PREFIX) == true

        private val RESUMABLE = setOf(JobState.FAILED, JobState.AUTH_REQUIRED, JobState.RETRY_WAIT)
        private val DISCARDABLE = setOf(JobState.PENDING, JobState.RETRY_WAIT, JobState.AUTH_REQUIRED, JobState.FAILED)

        /**
         * Saves are deliberate and usually stuck because of the address being corrected, so they
         * wait for the user. Captures are automatic and made under the old server's policy: not
         * worth sending to another one, so they are cancelled.
         */
        private val PARKED_ON_DESTINATION_CHANGE = setOf(JobKind.KARAKEEP_SAVE)
    }
}
