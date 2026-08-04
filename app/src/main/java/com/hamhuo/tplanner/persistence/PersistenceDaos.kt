package com.hamhuo.tplanner.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY sort_index")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY sort_index")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM events")
    suspend fun maxSortIndex(): Long

    @Upsert
    suspend fun upsert(row: EventEntity)

    @Upsert
    suspend fun upsertAll(rows: List<EventEntity>)

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
interface SyncDao {
    @Query("SELECT * FROM sync_shadows WHERE dataset = :dataset")
    suspend fun shadows(dataset: String): List<SyncShadowEntity>

    @Query("SELECT * FROM sync_shadows WHERE dataset = :dataset AND entity_id = :entityId")
    suspend fun shadow(dataset: String, entityId: String): SyncShadowEntity?

    @Query("SELECT COUNT(*) FROM sync_shadows")
    suspend fun shadowCount(): Int

    @Upsert
    suspend fun upsertShadow(row: SyncShadowEntity)

    @Upsert
    suspend fun upsertShadows(rows: List<SyncShadowEntity>)

    @Query("DELETE FROM sync_shadows WHERE dataset = :dataset AND entity_id = :entityId")
    suspend fun deleteShadow(dataset: String, entityId: String): Int

    @Upsert
    suspend fun enqueue(row: SyncOutboxEntity)

    @Query(
        "SELECT * FROM sync_outbox WHERE next_attempt_at <= :now " +
            "ORDER BY created_at, dataset, entity_id LIMIT :limit"
    )
    suspend fun due(now: Long, limit: Int = 100): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE dataset = :dataset AND entity_id = :entityId")
    suspend fun outboxEntry(dataset: String, entityId: String): SyncOutboxEntity?

    @Query("SELECT * FROM sync_outbox WHERE dataset = :dataset ORDER BY created_at, entity_id")
    suspend fun outbox(dataset: String): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCountNow(): Int

    @Query(
        "DELETE FROM sync_outbox WHERE dataset = :dataset AND entity_id = :entityId " +
            "AND mutation_token = :mutationToken"
    )
    suspend fun acknowledge(dataset: String, entityId: String, mutationToken: String): Int

    @Query(
        "UPDATE sync_outbox SET attempt_count = attempt_count + 1, next_attempt_at = :nextAttemptAt, " +
            "last_error = :error WHERE dataset = :dataset AND entity_id = :entityId " +
            "AND mutation_token = :mutationToken"
    )
    suspend fun recordFailure(
        dataset: String,
        entityId: String,
        mutationToken: String,
        nextAttemptAt: Long,
        error: String,
    ): Int
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
