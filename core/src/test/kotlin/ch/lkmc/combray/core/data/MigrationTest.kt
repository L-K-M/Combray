package ch.lkmc.combray.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every schema version opens a database of the previous one with its data kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CombrayDatabase::class.java)

    @Test
    fun version2KeepsServicesAndAddsNoAlternate() {
        helper.createDatabase(DB, 1).use {
            it.execSQL(
                "INSERT INTO services (kind, baseUrl, fingerprint, generation, captureEnabled, updatedAt) " +
                    "VALUES ('KARAKEEP', 'https://keep.example', 'fp', 3, 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT baseUrl, fingerprint, generation, alternateUrl FROM services").use { row ->
                row.moveToFirst()
                assertEquals("https://keep.example", row.getString(0))
                assertEquals("fp", row.getString(1))
                assertEquals(3L, row.getLong(2))
                assertNull(row.getString(3))
            }
        }
    }

    @Test
    fun version3KeepsServicesAndChoosesNoList() {
        helper.createDatabase(DB, 2).use {
            it.execSQL(
                "INSERT INTO services (kind, baseUrl, fingerprint, generation, captureEnabled, updatedAt, alternateUrl) " +
                    "VALUES ('KARAKEEP', 'https://keep.example', 'fp', 3, 0, 1, 'http://keep.lan')",
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT baseUrl, alternateUrl, listId, listName FROM services").use { row ->
                row.moveToFirst()
                assertEquals("https://keep.example", row.getString(0))
                assertEquals("http://keep.lan", row.getString(1))
                assertNull(row.getString(2))
                assertNull(row.getString(3))
            }
        }
    }

    @Test
    fun version4KeepsTabsAndMarksNoPageForgotten() {
        helper.createDatabase(DB, 3).use {
            it.execSQL(
                "INSERT INTO tabs (id, state, currentVisitId, url, title, createdAt, lastFocusedAt, closedAt) " +
                    "VALUES ('t', 'OPEN', NULL, 'https://a.example/', 'A', 1, 2, NULL)",
            )
        }
        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT url, title, pageForgotten FROM tabs").use { row ->
                row.moveToFirst()
                assertEquals("https://a.example/", row.getString(0))
                assertEquals("A", row.getString(1))
                assertEquals(0, row.getInt(2))
            }
        }
    }

    @Test
    fun version5KeepsEverythingAndAddsUsableHomeLinks() {
        helper.createDatabase(DB, 4).use {
            it.execSQL(
                "INSERT INTO tabs (id, state, currentVisitId, url, title, createdAt, lastFocusedAt, closedAt, pageForgotten) " +
                    "VALUES ('t', 'OPEN', NULL, 'https://a.example/', 'A', 1, 2, NULL, 0)",
            )
            it.execSQL(
                "INSERT INTO services (kind, baseUrl, fingerprint, generation, captureEnabled, updatedAt, alternateUrl, listId, listName) " +
                    "VALUES ('KARAKEEP', 'https://keep.example', 'fp', 3, 0, 1, 'http://keep.lan', 'list', 'Later')",
            )
        }
        helper.runMigrationsAndValidate(DB, 5, true).use { db ->
            db.query("SELECT url, title FROM tabs").use { row ->
                row.moveToFirst()
                assertEquals("https://a.example/", row.getString(0))
                assertEquals("A", row.getString(1))
            }
            db.query("SELECT listName FROM services").use { row ->
                row.moveToFirst()
                assertEquals("Later", row.getString(0))
            }
            // The new tables hold pins and hidden sites; the index rejects a second pin of an address.
            db.execSQL("INSERT INTO home_pins (id, url, site, title, position) VALUES ('p', 'https://a.example/', 'a.example', 'A', 0)")
            db.execSQL("INSERT INTO home_hidden_sites (site) VALUES ('b.example')")
            db.query("SELECT url, position FROM home_pins").use { row ->
                row.moveToFirst()
                assertEquals("https://a.example/", row.getString(0))
                assertEquals(0, row.getInt(1))
            }
            db.query("SELECT site FROM home_hidden_sites").use { row ->
                row.moveToFirst()
                assertEquals("b.example", row.getString(0))
            }
        }
    }

    /**
     * Room rewrites a version's schema file whenever an entity changes, and the tests above read
     * those files, so an entity change without a version bump would pass them and then fail to open
     * every existing database of that version. A committed version's hash never changes; a new
     * version adds its own.
     */
    @Test
    fun committedSchemasAreFrozen() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = CombrayDatabase::class.java.name
        val versions = assets.list(directory).orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json").toInt() }.toSet()
        assertEquals("a new schema version needs its identity hash here", FROZEN_IDENTITY_HASHES.keys, versions)

        for ((version, hash) in FROZEN_IDENTITY_HASHES) {
            val schema = assets.open("$directory/$version.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
            val database = schema.jsonObject.getValue("database").jsonObject
            assertEquals(version, database.getValue("version").jsonPrimitive.int)
            assertEquals(
                "schema $version changed: bump the database version and add a migration instead",
                hash,
                database.getValue("identityHash").jsonPrimitive.content,
            )
        }
    }

    private companion object {
        const val DB = "migration-test.db"

        /** Identity hashes of the committed schemas in core/schemas, by version. */
        val FROZEN_IDENTITY_HASHES = mapOf(
            1 to "8991a47d48450bc74524c22aa282d21f",
            2 to "1fbbc5b0e479253d149c896b338615ec",
            3 to "ca194eaf23c7010cf9ee87695093f140",
            4 to "783659b8d5966f6a926345b2bf922f59",
            5 to "212d54a2850997a2dffd0c90a1878867",
        )
    }
}
