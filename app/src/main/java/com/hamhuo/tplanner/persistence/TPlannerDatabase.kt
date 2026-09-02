package com.hamhuo.tplanner.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hamhuo.tplanner.syncv3.SyncCommandEntity
import com.hamhuo.tplanner.syncv3.SyncReceiptEntity
import com.hamhuo.tplanner.syncv3.SyncStateEntity
import com.hamhuo.tplanner.syncv3.SyncV3Dao

@Database(
    entities = [
        ScheduleItemEntity::class,
        JournalEntity::class,
        EditDraftEntity::class,
        PendingActionEntity::class,
        MigrationMarkerEntity::class,
        UserListEntity::class,
        SyncCommandEntity::class,
        SyncStateEntity::class,
        SyncReceiptEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TPlannerDatabase : RoomDatabase() {
    abstract fun eventDao(): ScheduleItemDao
    abstract fun journalDao(): JournalDao
    abstract fun draftDao(): DraftDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun migrationDao(): MigrationDao
    abstract fun userListDao(): UserListDao
    abstract fun syncV3Dao(): SyncV3Dao

    companion object {
        @Volatile
        private var instance: TPlannerDatabase? = null

        fun get(context: Context): TPlannerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TPlannerDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }

        internal const val DATABASE_NAME = "tplanner.db"

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS user_lists (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "sort_order INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_lists_sort_order ON user_lists(sort_order)")
                db.execSQL("ALTER TABLE events ADD COLUMN list_id TEXT NOT NULL DEFAULT ''")
            }
        }

        // V3 同步:命令 outbox / 设备元数据 / 回执(见 docs/sync-v3.md §15)
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_commands (" +
                        "command_id TEXT NOT NULL PRIMARY KEY, " +
                        "batch_id TEXT NOT NULL, " +
                        "client_sequence INTEGER NOT NULL, " +
                        "command_type TEXT NOT NULL, " +
                        "aggregate_id TEXT, " +
                        "arguments_json TEXT NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "attempt_count INTEGER NOT NULL, " +
                        "next_attempt_at INTEGER NOT NULL, " +
                        "last_error_code TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_commands_client_sequence " +
                        "ON sync_commands(client_sequence)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_commands_state ON sync_commands(state)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_state (" +
                        "singleton_id INTEGER NOT NULL PRIMARY KEY, " +
                        "device_id TEXT NOT NULL, " +
                        "next_client_sequence INTEGER NOT NULL, " +
                        "installed_snapshot_version INTEGER NOT NULL, " +
                        "installed_snapshot_hash TEXT, " +
                        "server_instance_id TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_receipts (" +
                        "command_id TEXT NOT NULL PRIMARY KEY, " +
                        "client_sequence INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "snapshot_version INTEGER, " +
                        "error_code TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_receipts_client_sequence " +
                        "ON sync_receipts(client_sequence)"
                )
            }
        }

        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Preserve unsent V1-local intent before retiring its transport tables. The V3
                // bootstrap converts payload-vs-shadow diffs into semantic commands, then drops
                // this staging table in the same transaction as its marker.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS v3_cutover_intents (" +
                        "dataset TEXT NOT NULL, entity_id TEXT NOT NULL, " +
                        "payload_json TEXT NOT NULL, is_tombstone INTEGER NOT NULL, " +
                        "base_payload_json TEXT, created_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(dataset, entity_id))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO v3_cutover_intents " +
                        "(dataset, entity_id, payload_json, is_tombstone, base_payload_json, created_at) " +
                        "SELECT o.dataset, o.entity_id, o.payload_json, o.is_tombstone, " +
                        "s.payload_json, o.created_at FROM sync_outbox o " +
                        "LEFT JOIN sync_shadows s ON s.dataset = o.dataset AND s.entity_id = o.entity_id"
                )
                db.execSQL("DROP TABLE IF EXISTS sync_outbox")
                db.execSQL("DROP TABLE IF EXISTS sync_shadows")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN server_mirror_json TEXT")
                db.execSQL(
                    "ALTER TABLE sync_state ADD COLUMN " +
                        "watch_projection_snapshot_version INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE sync_state ADD COLUMN sync_phase TEXT NOT NULL DEFAULT 'idle'"
                )
                db.execSQL("ALTER TABLE sync_state ADD COLUMN sync_error_code TEXT")
                db.execSQL(
                    "ALTER TABLE sync_state ADD COLUMN sync_updated_at INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE sync_state ADD COLUMN " +
                        "installed_broker_to_sequence INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE sync_state ADD COLUMN " +
                        "watch_projection_broker_to_sequence INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE sync_receipts ADD COLUMN broker_sequence INTEGER")
            }
        }

        // V4 delta-v1:opaque journal cursor(见 docs/sync-v3.md §9.3)。cursor 与
        // 它证明的 server mirror 在同一个 Room transaction 内更新,绝不单独推进。
        internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN cursor TEXT")
            }
        }
    }
}

