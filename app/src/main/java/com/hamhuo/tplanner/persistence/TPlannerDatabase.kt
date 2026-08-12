package com.hamhuo.tplanner.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScheduleItemEntity::class,
        JournalEntity::class,
        EditDraftEntity::class,
        SyncShadowEntity::class,
        SyncOutboxEntity::class,
        PendingActionEntity::class,
        MigrationMarkerEntity::class,
        UserListEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TPlannerDatabase : RoomDatabase() {
    abstract fun eventDao(): ScheduleItemDao
    abstract fun journalDao(): JournalDao
    abstract fun draftDao(): DraftDao
    abstract fun syncDao(): SyncDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun migrationDao(): MigrationDao
    abstract fun userListDao(): UserListDao

    companion object {
        @Volatile
        private var instance: TPlannerDatabase? = null

        fun get(context: Context): TPlannerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TPlannerDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2)
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
    }
}

