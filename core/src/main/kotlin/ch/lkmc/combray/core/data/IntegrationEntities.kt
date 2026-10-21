package ch.lkmc.combray.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ServiceKind { KARAKEEP, HISTER }

/**
 * A configured service. The secret (API key, token) is not here: it lives in the protected
 * credential store. [fingerprint] is a non-secret identity of the destination (its main address);
 * [generation] changes with every URL or credential change, and jobs carry the generation they
 * were made for.
 */
@Entity(tableName = "services")
data class ServiceEntity(
    @PrimaryKey val kind: ServiceKind,
    val baseUrl: String,
    val fingerprint: String,
    val generation: Long,
    /** Hister only: the user switched automatic page capture on. */
    val captureEnabled: Boolean,
    val updatedAt: Long,
    /** Another address of the same server, tried when [baseUrl] cannot be reached (or the reverse). */
    val alternateUrl: String? = null,
    /** Karakeep only: the list saves are added to, and its name when it was chosen. */
    val listId: String? = null,
    val listName: String? = null,
) {
    /** Every address of the server, main first. */
    val roots get() = listOfNotNull(baseUrl, alternateUrl)
}

enum class JobKind { KARAKEEP_SAVE, HISTER_ADD }

enum class JobState {
    PENDING,

    /** Claimed under a lease; only the current claim owner (matching fence) may change it. */
    RUNNING,
    RETRY_WAIT,

    /** Paused: the service rejected the credentials (or the CSRF convention). */
    AUTH_REQUIRED,

    /** Needs the user: permanent rejection, too many attempts, or an unexpected answer. */
    FAILED,
    SUCCEEDED,
    CANCELLED,

    /** A newer capture of the same document replaced this unsent one. */
    SUPERSEDED,

    /** Unsent content past its maximum age. */
    EXPIRED,
    ;

    val isActive get() = this == PENDING || this == RUNNING || this == RETRY_WAIT || this == AUTH_REQUIRED
}

/**
 * One durable integration action with its immutable payload and destination. Delivery is at
 * least once; [fence] increases with every claim so a worker that lost its lease cannot change
 * the job afterwards.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("state", "nextAttemptAt"), Index("kind", "fingerprint", "dedupeKey")],
)
data class OutboxJobEntity(
    @PrimaryKey val id: String,
    val kind: JobKind,
    val service: ServiceKind,
    val fingerprint: String,
    val generation: Long,
    /** Jobs with the same key go out one at a time and newer content supersedes older. */
    val dedupeKey: String,
    /** Creation order from the coordinator (not wall-clock time). */
    val sequence: Long,
    val payload: String,
    val payloadBytes: Int,
    val state: JobState,
    val attempts: Int,
    val nextAttemptAt: Long,
    val leaseOwner: String?,
    val leaseUntil: Long?,
    val fence: Long,
    /** Error category for status and diagnostics: never a response body or a credential. */
    val error: String?,
    val remoteId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A link the destination acknowledged (201 new or 200 existing). Independent of tabs. */
@Entity(tableName = "saved_items", primaryKeys = ["fingerprint", "url"])
data class SavedItemEntity(
    val fingerprint: String,
    val url: String,
    val title: String?,
    val remoteId: String?,
    val acknowledgedAt: Long,
)

/** Site exclusions for automatic capture (exact host, or with its subdomains). */
@Entity(tableName = "exclusions")
data class ExclusionEntity(
    @PrimaryKey val host: String,
    val includeSubdomains: Boolean,
    val createdAt: Long,
)
