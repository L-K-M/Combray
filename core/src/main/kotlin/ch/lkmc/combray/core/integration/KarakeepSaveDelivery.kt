package ch.lkmc.combray.core.integration

import androidx.room.withTransaction
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.SavedItemEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A creation receipt, bound to the destination that acknowledged it. The remote id alone is not
 * enough: a bookmark id from one server must never authorize a list change on another.
 */
@Serializable
internal data class SaveReceipt(val fingerprint: String, val remoteId: String?)

@Serializable
internal data class SavePayload(val url: String, val title: String? = null, val receipt: SaveReceipt? = null)

/**
 * Durably separates bookmark creation from the optional, idempotent list-membership step. The
 * receipt travels inside the job's own payload, so it keeps the original URL/title and cannot
 * follow a parked job to another destination. A lost claim or a changed configuration cannot
 * write one: [checkpoint] re-checks the fence and the service generation in one transaction.
 */
internal class KarakeepSaveDelivery(
    private val database: CombrayDatabase,
    private val routes: ServiceRoutes,
    private val clock: Clock,
) {
    private val dao = database.integrations()

    suspend fun payloadForSave(url: String, title: String?, service: ServiceEntity): String {
        // A second tap retries a failed job with the current title. Keep that job's creation
        // progress, but never borrow an unrelated older successful save's bookmark identity.
        val failed = dao.failedJobs(JobKind.KARAKEEP_SAVE, service.fingerprint, url).maxByOrNull { it.sequence }
        val previous = failed?.let { runCatching { json.decodeFromString(SavePayload.serializer(), it.payload) }.getOrNull() }
        val receipt = previous?.takeIf { it.url == url }?.receipt?.takeIf { it.fingerprint == service.fingerprint }
        return json.encodeToString(SavePayload.serializer(), SavePayload(url, title, receipt))
    }

    suspend fun dispatch(job: OutboxJobEntity, service: ServiceEntity, key: String): DispatchResult {
        val payload = json.decodeFromString(SavePayload.serializer(), job.payload)
        // The receipt is trusted only for the destination it was written under. A parked job
        // rebound to another server keeps its old receipt, which then matches nothing here.
        val knownId = payload.receipt?.takeIf { it.fingerprint == job.fingerprint }?.remoteId
        val created: DispatchResult = if (knownId != null) {
            DispatchResult.Done(knownId)
        } else {
            routes.call(service) { transport, root -> KarakeepClient(transport).createLink(root, key, payload.url, payload.title) }
        }

        // Without a list there is no second step: the acknowledged link is recorded when the
        // job finishes. A lost answer stays ambiguous and the duplicate-safe retry follows.
        val listId = service.listId ?: return created
        if (created !is DispatchResult.Done) return created
        if (!checkpoint(job, payload, created)) return DispatchResult.Cancelled("claim_lost")

        // A malformed acknowledgment still proves creation, but only another duplicate-safe
        // create can recover the ID needed for membership.
        val bookmarkId = created.remoteId ?: return DispatchResult.Retry(BOOKMARK_ID_MISSING)
        val added = routes.call(service) { transport, root -> KarakeepClient(transport).addToList(root, key, listId, bookmarkId) }
        return when {
            added is DispatchResult.Done -> created
            // Creation is acknowledged, so a later connection failure is not an unsent save.
            added is DispatchResult.Retry && added.delivery == Delivery.NOT_SENT -> added.copy(delivery = Delivery.MAY_HAVE_ARRIVED)
            else -> added
        }
    }

    /**
     * Commits the acknowledged bookmark and the receipt before the second request, so a process
     * death or a lost claim cannot lose the creation or repeat it. Returns false when the claim
     * no longer owns the job or the configuration moved: the caller then sends nothing further.
     */
    private suspend fun checkpoint(job: OutboxJobEntity, payload: SavePayload, created: DispatchResult.Done): Boolean = database.withTransaction {
        val current = dao.job(job.id) ?: return@withTransaction false
        if (current.state != JobState.RUNNING || current.fence != job.fence || current.leaseOwner != job.leaseOwner) return@withTransaction false
        val service = dao.service(ServiceKind.KARAKEEP) ?: return@withTransaction false
        if (service.fingerprint != job.fingerprint || service.generation != job.generation) return@withTransaction false
        val stored = json.encodeToString(SavePayload.serializer(), payload.copy(receipt = SaveReceipt(job.fingerprint, created.remoteId)))
        val now = clock.now()
        dao.updateJob(current.copy(payload = stored, payloadBytes = stored.toByteArray(Charsets.UTF_8).size, updatedAt = now))
        dao.setSaved(SavedItemEntity(job.fingerprint, payload.url, payload.title, created.remoteId, now))
        true
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val BOOKMARK_ID_MISSING = "bookmark_id_missing"
    }
}
