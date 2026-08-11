package com.hamhuo.tplanner.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "events",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["sort_index"]),
        Index(value = ["updated_at"]),
        Index(value = ["deleted_at"]),
    ],
)
data class EventEntity(
    val id: String,
    val title: String,
    val type: String,
    @ColumnInfo(name = "start_epoch_ms") val startEpochMs: Long,
    @ColumnInfo(name = "end_epoch_ms") val endEpochMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "checklist_json") val checklistJson: String,
    @ColumnInfo(name = "color_id") val colorId: Int,
    val note: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "alarm_enabled") val alarmEnabled: Boolean,
    @ColumnInfo(name = "alarm_offset_minutes") val alarmOffsetMinutes: Int,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "extras_json") val extrasJson: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Long,
)

@Entity(
    tableName = "user_lists",
    primaryKeys = ["id"],
    indices = [Index(value = ["sort_order"])],
)
data class UserListEntity(
    val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "journals",
    primaryKeys = ["date"],
    indices = [Index(value = ["updated_at"]), Index(value = ["deleted_at"])],
)
data class JournalEntity(
    val date: String,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

/**
 * A complete, versioned recovery payload. `content` can be journal text, an event note, or a
 * deterministic JSON snapshot of a complete event/new-event editor.
 */
@Entity(
    tableName = "edit_drafts",
    primaryKeys = ["storage_key"],
    indices = [
        Index(value = ["entity_kind", "entity_id"]),
        Index(value = ["draft_updated_at"]),
    ],
)
data class EditDraftEntity(
    @ColumnInfo(name = "storage_key") val storageKey: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val content: String,
    @ColumnInfo(name = "base_hash") val baseHash: String,
    @ColumnInfo(name = "base_updated_at") val baseUpdatedAt: Long,
    @ColumnInfo(name = "base_entity_exists") val baseEntityExists: Boolean,
    @ColumnInfo(name = "base_deleted_at") val baseDeletedAt: Long,
    @ColumnInfo(name = "initial_content_hash") val initialContentHash: String,
    @ColumnInfo(name = "draft_updated_at") val draftUpdatedAt: Long,
    val state: String,
    @ColumnInfo(name = "target_hash") val targetHash: String?,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
)

@Entity(
    tableName = "sync_shadows",
    primaryKeys = ["dataset", "entity_id"],
)
data class SyncShadowEntity(
    val dataset: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "key_format") val keyFormat: Int = 1,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Long = 0L,
)

/**
 * One coalesced mutation per entity. A newer local write replaces mutationToken; an old worker can
 * then acknowledge only its own token and can never consume a newer edit.
 */
@Entity(
    tableName = "sync_outbox",
    primaryKeys = ["dataset", "entity_id"],
    indices = [Index(value = ["next_attempt_at"]), Index(value = ["created_at"])],
)
data class SyncOutboxEntity(
    val dataset: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "mutation_token") val mutationToken: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "is_tombstone") val isTombstone: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0L,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/** Durable state for multi-step work such as an AI proposal awaiting confirmation. */
@Entity(
    tableName = "pending_actions",
    primaryKeys = ["request_id"],
    indices = [Index(value = ["kind", "state"]), Index(value = ["updated_at"])],
)
data class PendingActionEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    val kind: String,
    val state: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Entity(tableName = "migration_markers", primaryKeys = ["id"])
data class MigrationMarkerEntity(
    val id: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
    @ColumnInfo(name = "source_digest") val sourceDigest: String,
    @ColumnInfo(name = "event_count") val eventCount: Int,
    @ColumnInfo(name = "journal_count") val journalCount: Int,
    @ColumnInfo(name = "draft_count") val draftCount: Int,
)

object SyncDatasets {
    const val EVENTS = "EVENTS"
    const val JOURNALS = "JOURNALS"
}
