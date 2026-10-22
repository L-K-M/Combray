package ch.lkmc.combray.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserDao {
    @Insert
    suspend fun insertTab(tab: TabEntity)

    @Update
    suspend fun updateTab(tab: TabEntity)

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun tab(id: String): TabEntity?

    @Query("SELECT * FROM tabs WHERE state = 'OPEN' ORDER BY lastFocusedAt DESC")
    suspend fun openTabs(): List<TabEntity>

    /** The open-tab count without materializing every row; publish() runs this per event. */
    @Query("SELECT COUNT(*) FROM tabs WHERE state = 'OPEN'")
    suspend fun openTabCount(): Int

    @Query("SELECT * FROM tabs WHERE state = 'OPEN' ORDER BY lastFocusedAt DESC")
    fun observeOpenTabs(): Flow<List<TabEntity>>

    @Insert
    suspend fun insertVisit(visit: VisitEntity)

    @Update
    suspend fun updateVisit(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun visit(id: String): VisitEntity?

    /**
     * Visits shown as history: not failed, seen since [since], and not the page an open tab is
     * currently showing (that one is represented by its tab row).
     */
    @Query(
        """
        SELECT v.* FROM visits v JOIN tabs t ON t.id = v.tabId
        WHERE v.failed = 0 AND v.lastSeenAt >= :since
          AND NOT (t.state = 'OPEN' AND t.currentVisitId IS v.id)
        ORDER BY v.lastSeenAt DESC
        LIMIT :limit
        """,
    )
    fun observeHistory(since: Long, limit: Int): Flow<List<VisitEntity>>

    @Upsert
    suspend fun setSession(session: SessionEntity)

    @Query("SELECT * FROM session WHERE id = 0")
    suspend fun session(): SessionEntity?

    @Query("SELECT * FROM session WHERE id = 0")
    fun observeSession(): Flow<SessionEntity?>

    /** Expired history: never an open tab's current page, whatever its age. */
    @Query(
        """
        DELETE FROM visits WHERE lastSeenAt < :cutoff
          AND id NOT IN (SELECT currentVisitId FROM tabs WHERE state = 'OPEN' AND currentVisitId IS NOT NULL)
        """,
    )
    suspend fun deleteVisitsSeenBefore(cutoff: Long): Int

    /** Closed tabs whose pages have all expired or been forgotten. */
    @Query("DELETE FROM tabs WHERE state = 'CLOSED' AND id NOT IN (SELECT DISTINCT tabId FROM visits)")
    suspend fun deleteEmptyClosedTabs(): Int

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun deleteVisit(id: String): Int

    /**
     * An open tab may still be showing a page of a forgotten site. Keep that address on the
     * tab, but stop pointing at the visit so the page cannot return to Recent later.
     */
    @Query(
        """
        UPDATE tabs SET currentVisitId = NULL
        WHERE currentVisitId IN (
            SELECT id FROM visits
            WHERE host = :host OR (:subdomainSuffix IS NOT NULL AND substr(host, -length(:subdomainSuffix)) = :subdomainSuffix)
        )
        """,
    )
    suspend fun detachVisitsForHost(host: String, subdomainSuffix: String?)

    /**
     * Removes history for a host, including the page an open tab is showing. Call
     * [detachVisitsForHost] first in the same transaction, or that tab keeps a dangling
     * `currentVisitId`.
     */
    @Query(
        """
        DELETE FROM visits WHERE host = :host OR (:subdomainSuffix IS NOT NULL AND substr(host, -length(:subdomainSuffix)) = :subdomainSuffix)
        """,
    )
    suspend fun deleteVisitsForHost(host: String, subdomainSuffix: String?): Int
}
