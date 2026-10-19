package ch.lkmc.combray.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TabState { OPEN, CLOSED }

enum class VisitKind {
    /** A committed top-level document. */
    DOCUMENT,

    /** A same-document URL change (`pushState`, or a fragment change on another path). */
    SAME_DOCUMENT,
}

/**
 * A logical tab. It outlives native views: a restored or evicted tab is still OPEN without a
 * renderer. [url] and [title] are the recovery record (last committed page, or the address
 * requested while no renderer existed); null [url] is the internal new-tab state.
 */
@Entity(tableName = "tabs", indices = [Index("state"), Index("lastFocusedAt")])
data class TabEntity(
    @PrimaryKey val id: String,
    val state: TabState,
    val currentVisitId: String?,
    val url: String?,
    val title: String?,
    val createdAt: Long,
    val lastFocusedAt: Long,
    val closedAt: Long?,
)

/**
 * One page the user was on. Never a native handle: [documentKey] only identifies the engine
 * document within the process that recorded it, to group back/forward returns.
 */
@Entity(
    tableName = "visits",
    foreignKeys = [
        ForeignKey(entity = TabEntity::class, parentColumns = ["id"], childColumns = ["tabId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tabId"), Index("lastSeenAt"), Index("host")],
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val tabId: String,
    val url: String,
    /** Normalized host (lowercase, no trailing dot), null for URLs without one. */
    val host: String?,
    val title: String?,
    val documentKey: String?,
    val kind: VisitKind,
    val committedAt: Long,
    val lastSeenAt: Long,
    /** Servo's error page was committed for this URL: kept for the tab, hidden from Recent. */
    val failed: Boolean,
)

/** Single row: the focused tab, or none (internal home). */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: Int = ROW,
    val activeTabId: String?,
) {
    companion object {
        const val ROW = 0
    }
}
