package ch.lkmc.combray.core.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Durable logical state. Schema version 1 is the first one with user data; later versions
 * need real migrations (exported schemas in core/schemas), never a destructive fallback.
 */
@Database(
    entities = [
        TabEntity::class, VisitEntity::class, SessionEntity::class,
        ServiceEntity::class, OutboxJobEntity::class, SavedItemEntity::class, ExclusionEntity::class,
        HomePinEntity::class, HomeHiddenSiteEntity::class,
    ],
    version = 5,
    autoMigrations = [
        // Services gained an optional alternate address.
        AutoMigration(from = 1, to = 2),
        // Karakeep gained a list for saves.
        AutoMigration(from = 2, to = 3),
        // Tabs remember that their page was forgotten.
        AutoMigration(from = 3, to = 4),
        // The new-tab page gained durable pins and hidden sites.
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class CombrayDatabase : RoomDatabase() {
    abstract fun browser(): BrowserDao

    abstract fun integrations(): IntegrationDao

    abstract fun homeLinks(): HomeLinksDao

    companion object {
        const val FILE_NAME = "combray.db"

        fun open(context: Context): CombrayDatabase =
            Room.databaseBuilder(context, CombrayDatabase::class.java, FILE_NAME).build()
    }
}
