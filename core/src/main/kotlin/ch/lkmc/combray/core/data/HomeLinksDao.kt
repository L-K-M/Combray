package ch.lkmc.combray.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Durable new-tab quick links: ordered pins and hidden site keys. */
@Dao
interface HomeLinksDao {
    @Query("SELECT * FROM home_pins ORDER BY position")
    fun observePins(): Flow<List<HomePinEntity>>

    /** The ordered pins once, for renumbering inside a transaction after removals. */
    @Query("SELECT * FROM home_pins ORDER BY position")
    suspend fun pinsInOrder(): List<HomePinEntity>

    @Query("SELECT * FROM home_hidden_sites ORDER BY site")
    fun observeHiddenSites(): Flow<List<HomeHiddenSiteEntity>>

    @Query("SELECT * FROM home_pins WHERE id = :id")
    suspend fun pin(id: String): HomePinEntity?

    @Query("SELECT * FROM home_pins WHERE url = :url")
    suspend fun pinForUrl(url: String): HomePinEntity?

    @Query("SELECT * FROM home_pins WHERE position = :position")
    suspend fun pinAt(position: Int): HomePinEntity?

    @Query("SELECT COUNT(*) FROM home_pins")
    suspend fun pinCount(): Int

    @Insert
    suspend fun insertPin(pin: HomePinEntity)

    /** Label and destination only: position edits go through swap/delete so order stays gap-free. */
    @Query("UPDATE home_pins SET url = :url, site = :site, title = :title WHERE id = :id")
    suspend fun updatePinFields(id: String, url: String, site: String, title: String)

    @Query("UPDATE home_pins SET position = :position WHERE id = :id")
    suspend fun movePinTo(id: String, position: Int)

    @Query("DELETE FROM home_pins WHERE id = :id")
    suspend fun deletePin(id: String): Int

    @Query("UPDATE home_pins SET position = position - 1 WHERE position > :position")
    suspend fun closePositionGap(position: Int)

    /** Hiding what is already hidden changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideSite(site: HomeHiddenSiteEntity)

    @Query("DELETE FROM home_hidden_sites WHERE site = :site")
    suspend fun restoreSite(site: String): Int

    /**
     * Pins of a forgotten site: [site] is the www-merged key pins store, so both spellings go.
     * Other subdomains keep their pins unless [subdomainSuffix] covers them.
     */
    @Query(
        """
        DELETE FROM home_pins
        WHERE site = :site OR (:subdomainSuffix IS NOT NULL AND substr(site, -length(:subdomainSuffix)) = :subdomainSuffix)
        """,
    )
    suspend fun deletePinsForSite(site: String, subdomainSuffix: String?): Int
}
