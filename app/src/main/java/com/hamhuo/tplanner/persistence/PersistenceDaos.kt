package com.hamhuo.tplanner.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface ScheduleItemDao {
    @Query("SELECT * FROM events ORDER BY sort_index")
    fun observeAll(): Flow<List<ScheduleItemEntity>>

    @Query("SELECT * FROM events ORDER BY sort_index")
    suspend fun getAll(): List<ScheduleItemEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): ScheduleItemEntity?

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM events")
    suspend fun maxSortIndex(): Long

    @Upsert
    suspend fun upsert(row: ScheduleItemEntity)

    @Upsert
    suspend fun upsertAll(rows: List<ScheduleItemEntity>)

    @Query("DELETE FROM events WHERE id = :id AND deleted_at = :expectedDeletedAt")
    suspend fun purgeTombstone(id: String, expectedDeletedAt: Long): Int
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journals ORDER BY date")
    fun observeAll(): Flow<List<JournalEntity>>

    @Query("SELECT * FROM journals ORDER BY date")
    suspend fun getAll(): List<JournalEntity>

    @Query("SELECT * FROM journals WHERE date = :date")
    fun observe(date: String): Flow<JournalEntity?>

    @Query("SELECT * FROM journals WHERE date = :date")
    suspend fun get(date: String): JournalEntity?

    @Query("SELECT COUNT(*) FROM journals")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(row: JournalEntity)

    @Upsert
    suspend fun upsertAll(rows: List<JournalEntity>)

    @Query("DELETE FROM journals WHERE date = :date AND deleted_at = :expectedDeletedAt")
    suspend fun purgeTombstone(date: String, expectedDeletedAt: Long): Int
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM edit_drafts WHERE storage_key = :storageKey")
    suspend fun get(storageKey: String): EditDraftEntity?

    @Query("SELECT * FROM edit_drafts WHERE entity_kind = :entityKind AND entity_id = :entityId")
    suspend fun find(entityKind: String, entityId: String): List<EditDraftEntity>

    @Query("SELECT * FROM edit_drafts ORDER BY draft_updated_at")
    suspend fun getAll(): List<EditDraftEntity>

    @Query("SELECT COUNT(*) FROM edit_drafts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(row: EditDraftEntity)

    @Upsert
    suspend fun upsertAll(rows: List<EditDraftEntity>)

    @Query("DELETE FROM edit_drafts WHERE storage_key = :storageKey")
    suspend fun delete(storageKey: String): Int
}

@Dao
interface PendingActionDao {
    @Query("SELECT * FROM pending_actions WHERE request_id = :requestId")
    suspend fun get(requestId: String): PendingActionEntity?

    @Query("SELECT * FROM pending_actions WHERE kind = :kind ORDER BY updated_at DESC LIMIT 1")
    suspend fun latest(kind: String): PendingActionEntity?

    @Query("SELECT * FROM pending_actions WHERE kind = :kind ORDER BY updated_at DESC, created_at DESC")
    suspend fun all(kind: String): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE kind = :kind AND state = :state ORDER BY updated_at")
    suspend fun find(kind: String, state: String): List<PendingActionEntity>

    @Query("SELECT COUNT(*) FROM pending_actions")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(row: PendingActionEntity)

    @Query("DELETE FROM pending_actions WHERE request_id = :requestId")
    suspend fun delete(requestId: String): Int
}

@Dao
interface MigrationDao {
    @Query("SELECT * FROM migration_markers WHERE id = :id")
    suspend fun marker(id: String): MigrationMarkerEntity?

    @Query("SELECT COUNT(*) FROM migration_markers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarker(row: MigrationMarkerEntity)
}

@Dao
interface UserListDao {
    @Query("SELECT * FROM user_lists ORDER BY sort_order")
    fun observeAll(): Flow<List<UserListEntity>>

    @Query("SELECT * FROM user_lists ORDER BY sort_order")
    suspend fun getAll(): List<UserListEntity>

    @Query("SELECT * FROM user_lists WHERE id = :id")
    suspend fun get(id: String): UserListEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM user_lists")
    suspend fun nextSortOrder(): Int

    @Upsert
    suspend fun upsert(row: UserListEntity)

    @Query("DELETE FROM user_lists WHERE id = :id")
    suspend fun delete(id: String): Int
}
