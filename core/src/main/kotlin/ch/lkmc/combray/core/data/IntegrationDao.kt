package ch.lkmc.combray.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IntegrationDao {
    @Query("SELECT * FROM services WHERE kind = :kind")
    suspend fun service(kind: ServiceKind): ServiceEntity?

    @Query("SELECT * FROM services WHERE kind = :kind")
    fun observeService(kind: ServiceKind): Flow<ServiceEntity?>

    @Upsert
    suspend fun setService(service: ServiceEntity)

    @Query("DELETE FROM services WHERE kind = :kind")
    suspend fun deleteService(kind: ServiceKind)

    @Insert
    suspend fun insertJob(job: OutboxJobEntity)

    @Update
    suspend fun updateJob(job: OutboxJobEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun job(id: String): OutboxJobEntity?

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM outbox")
    suspend fun lastSequence(): Long

    /** Unfinished jobs for one destination key, newest first. */
    @Query(
        """
        SELECT * FROM outbox WHERE kind = :kind AND fingerprint = :fingerprint AND dedupeKey = :dedupeKey
          AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED')
        ORDER BY sequence DESC
        """,
    )
    suspend fun activeJobs(kind: JobKind, fingerprint: String, dedupeKey: String): List<OutboxJobEntity>

    /**
     * The oldest job that may be dispatched now: due, for the destination generation currently
     * configured, and with no other job of the same key under a live lease (one at a time per
     * key, so older content is never sent after newer). An expired lease makes a job claimable.
     */
    @Query(
        """
        SELECT o.* FROM outbox o JOIN services s ON s.kind = o.service
        WHERE s.fingerprint = o.fingerprint AND s.generation = o.generation
          AND (o.kind != 'HISTER_ADD' OR s.captureEnabled = 1)
          AND ((o.state IN ('PENDING', 'RETRY_WAIT') AND o.nextAttemptAt <= :now)
               OR (o.state = 'RUNNING' AND o.leaseUntil < :now))
          AND NOT EXISTS (
            SELECT 1 FROM outbox r
            WHERE r.kind = o.kind AND r.fingerprint = o.fingerprint AND r.dedupeKey = o.dedupeKey
              AND r.id != o.id AND r.state = 'RUNNING' AND r.leaseUntil >= :now
          )
        ORDER BY o.sequence
        LIMIT 1
        """,
    )
    suspend fun nextDue(now: Long): OutboxJobEntity?

    /**
     * When work next becomes due (a retry time or an expiring lease), or null if none. A job
     * waiting behind a live lease on its key wakes with that lease, not before.
     */
    @Query(
        """
        SELECT MIN(CASE WHEN o.state = 'RUNNING' THEN o.leaseUntil ELSE o.nextAttemptAt END)
        FROM outbox o JOIN services s ON s.kind = o.service
        WHERE s.fingerprint = o.fingerprint AND s.generation = o.generation
          AND (o.kind != 'HISTER_ADD' OR s.captureEnabled = 1)
          AND o.state IN ('PENDING', 'RETRY_WAIT', 'RUNNING')
          AND (o.state = 'RUNNING' OR NOT EXISTS (
            SELECT 1 FROM outbox r
            WHERE r.kind = o.kind AND r.fingerprint = o.fingerprint AND r.dedupeKey = o.dedupeKey
              AND r.id != o.id AND r.state = 'RUNNING' AND r.leaseUntil >= :now
          ))
        """,
    )
    suspend fun nextWakeup(now: Long): Long?

    /** Highest generation any job of [service] carries, so a re-added service never reuses one. */
    @Query("SELECT COALESCE(MAX(generation), 0) FROM outbox WHERE service = :service")
    suspend fun maxJobGeneration(service: ServiceKind): Long

    /** Captured text is dropped as soon as a capture is finished (sent, replaced, cancelled, expired). */
    @Query(
        """
        UPDATE outbox SET payload = '', payloadBytes = 0
        WHERE kind = 'HISTER_ADD' AND state IN ('SUCCEEDED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED') AND payload != ''
        """,
    )
    suspend fun dropFinishedCapturePayloads(): Int

    @Query("SELECT * FROM outbox WHERE service = :service AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED') ORDER BY sequence")
    fun observeOpenJobs(service: ServiceKind): Flow<List<OutboxJobEntity>>

    @Query("SELECT * FROM outbox WHERE service = :service AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED')")
    suspend fun unfinishedJobs(service: ServiceKind): List<OutboxJobEntity>

    /** Every job not finished yet, parked ones included: a removal or forgetting reaches them too. */
    @Query("SELECT * FROM outbox WHERE service = :service AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED', 'DESTINATION_CHANGED')")
    suspend fun openJobs(service: ServiceKind): List<OutboxJobEntity>

    @Query("SELECT * FROM outbox WHERE service = :service AND state = 'DESTINATION_CHANGED' ORDER BY sequence")
    suspend fun parkedJobs(service: ServiceKind): List<OutboxJobEntity>

    @Query("SELECT * FROM outbox WHERE service = :service AND state = 'DESTINATION_CHANGED' ORDER BY sequence")
    fun observeParkedJobs(service: ServiceKind): Flow<List<OutboxJobEntity>>

    @Query("SELECT * FROM outbox WHERE kind = :kind AND state = 'FAILED'")
    suspend fun failedJobs(kind: JobKind): List<OutboxJobEntity>

    @Query("SELECT * FROM outbox WHERE kind = :kind AND fingerprint = :fingerprint AND dedupeKey = :dedupeKey AND state = 'FAILED'")
    suspend fun failedJobs(kind: JobKind, fingerprint: String, dedupeKey: String): List<OutboxJobEntity>

    @Query("DELETE FROM outbox WHERE state IN ('SUCCEEDED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED') AND updatedAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int

    /** Payload bytes of captures not yet delivered (the transient content budget). */
    @Query("SELECT COALESCE(SUM(payloadBytes), 0) FROM outbox WHERE kind = 'HISTER_ADD' AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED')")
    suspend fun queuedCaptureBytes(): Long

    @Query("SELECT COALESCE(SUM(payloadBytes), 0) FROM outbox WHERE kind = 'HISTER_ADD' AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED')")
    fun observeQueuedCaptureBytes(): Flow<Long>

    /** Captured content older than the maximum age is never sent. */
    @Query(
        """
        UPDATE outbox SET state = 'EXPIRED', updatedAt = :now
        WHERE kind = 'HISTER_ADD' AND state IN ('PENDING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED') AND createdAt < :cutoff
        """,
    )
    suspend fun expireCapturesBefore(cutoff: Long, now: Long): Int

    @Upsert
    suspend fun setExclusion(exclusion: ExclusionEntity)

    @Query("DELETE FROM exclusions WHERE host = :host")
    suspend fun deleteExclusion(host: String)

    @Query("SELECT * FROM exclusions ORDER BY host")
    suspend fun exclusions(): List<ExclusionEntity>

    @Query("SELECT * FROM exclusions ORDER BY host")
    fun observeExclusions(): Flow<List<ExclusionEntity>>

    @Upsert
    suspend fun setSaved(item: SavedItemEntity)

    @Query("SELECT * FROM saved_items WHERE fingerprint = :fingerprint AND url = :url")
    fun observeSaved(fingerprint: String, url: String): Flow<SavedItemEntity?>

    @Query(
        """
        SELECT * FROM outbox WHERE kind = 'KARAKEEP_SAVE' AND fingerprint = :fingerprint AND dedupeKey = :url
          AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'AUTH_REQUIRED', 'FAILED')
        ORDER BY sequence DESC LIMIT 1
        """,
    )
    fun observeLatestSave(fingerprint: String, url: String): Flow<OutboxJobEntity?>

    /** A save of [url] parked for an earlier main address, whatever that address was. */
    @Query("SELECT * FROM outbox WHERE kind = 'KARAKEEP_SAVE' AND dedupeKey = :url AND state = 'DESTINATION_CHANGED' ORDER BY sequence DESC LIMIT 1")
    fun observeParkedSave(url: String): Flow<OutboxJobEntity?>
}
