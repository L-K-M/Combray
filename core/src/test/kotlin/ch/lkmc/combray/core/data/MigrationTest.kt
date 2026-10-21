package ch.lkmc.combray.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val DB = "migration-test.db"
    }
}
