package com.hamhuo.tplanner.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        JournalEntity::class,
        EditDraftEntity::class,
        SyncShadowEntity::class,
        SyncOutboxEntity::class,
        PendingActionEntity::class,
        MigrationMarkerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TPlannerDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun journalDao(): JournalDao
    abstract fun draftDao(): DraftDao
    abstract fun syncDao(): SyncDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun migrationDao(): MigrationDao

    companion object {
        @Volatile
        private var instance: TPlannerDatabase? = null

        fun get(context: Context): TPlannerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TPlannerDatabase::class.java,
                DATABASE_NAME,
            ).build().also { instance = it }
        }

        internal const val DATABASE_NAME = "tplanner.db"
    }
}

