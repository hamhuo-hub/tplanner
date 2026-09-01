package com.hamhuo.tplanner.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TPlannerDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(TPlannerDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `3 to 4 preserves legacy offline intent before retiring V1 transport tables`() {
        // AndroidX SQLite compares driver names with '/' only. Supplying the absolute name keeps
        // this test portable on Windows Robolectric, whose database path otherwise contains '\\'.
        val databasePath = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getDatabasePath(DATABASE_NAME)
            .absolutePath
        helper.createDatabase(databasePath, 3).use { database ->
            database.execSQL(
                "INSERT INTO sync_shadows " +
                    "(dataset, entity_id, content_key, key_format, payload_json, synced_at) " +
                    "VALUES ('EVENTS', 'task-1', 'old', 1, '{\"title\":\"before\"}', 1)"
            )
            database.execSQL(
                "INSERT INTO sync_outbox " +
                    "(dataset, entity_id, mutation_token, payload_json, content_key, " +
                    "is_tombstone, updated_at, created_at, attempt_count, next_attempt_at, last_error) " +
                    "VALUES ('EVENTS', 'task-1', 'm1', '{\"title\":\"after\"}', 'new', " +
                    "0, 2, 2, 0, 0, NULL)"
            )
            database.execSQL(
                "INSERT INTO sync_state " +
                    "(singleton_id, device_id, next_client_sequence, installed_snapshot_version, " +
                    "installed_snapshot_hash, server_instance_id) " +
                    "VALUES (1, 'old-device', 4, 3, 'sha256:old', 'old-server')"
            )
        }

        helper.runMigrationsAndValidate(
            databasePath,
            4,
            // v3_cutover_intents deliberately survives until the verified V3 baseline consumes it.
            // Validate the Room-owned schema here; explicit assertions below validate retired tables.
            false,
            TPlannerDatabase.MIGRATION_3_4,
        ).use { database ->
            database.query(
                "SELECT payload_json, base_payload_json FROM v3_cutover_intents " +
                    "WHERE dataset = 'EVENTS' AND entity_id = 'task-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"title\":\"after\"}", cursor.getString(0))
                assertEquals("{\"title\":\"before\"}", cursor.getString(1))
            }
            assertFalse(tableExists(database, "sync_outbox"))
            assertFalse(tableExists(database, "sync_shadows"))
            assertTrue(columnExists(database, "sync_state", "installed_broker_to_sequence"))
            assertTrue(columnExists(database, "sync_state", "watch_projection_broker_to_sequence"))
            assertTrue(columnExists(database, "sync_receipts", "broker_sequence"))
        }
    }

    private fun tableExists(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        name: String,
    ): Boolean = database.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun columnExists(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        column: String,
    ): Boolean = database.query("PRAGMA table_info($table)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
            .any { it == column }
    }

    private companion object {
        const val DATABASE_NAME = "migration-v3-v4-test"
    }
}
