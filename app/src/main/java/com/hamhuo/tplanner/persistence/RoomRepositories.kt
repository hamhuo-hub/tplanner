package com.hamhuo.tplanner.persistence

import androidx.room.withTransaction
import com.hamhuo.tplanner.JournalEntry
import com.hamhuo.tplanner.ScheduleItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

sealed interface DraftCommitResult {
    data object Saved : DraftCommitResult
    data object AlreadySaved : DraftCommitResult
    data class Conflict(val details: DraftConflict) : DraftCommitResult
}

enum class PendingActionCommitResult { SAVED, ALREADY_HANDLED, INVALID_STATE }

/** Result of the idempotent watch-create transaction. */
enum class WatchTaskCommitResult { STORED, ALREADY_STORED, ID_CONFLICT }

class RoomEventRepository(private val db: TPlannerDatabase) {
    fun observeAll(): Flow<List<ScheduleItem>> = db.eventDao().observeAll().map { rows ->
        rows.map(PersistenceMapper::eventToDomain)
    }

    suspend fun getAll(): List<ScheduleItem> = db.eventDao().getAll().map(
        PersistenceMapper::eventToDomain,
    )

    suspend fun get(id: String): ScheduleItem? = db.eventDao().get(id)?.let(
        PersistenceMapper::eventToDomain,
    )

    suspend fun beginEdit(requested: ScheduleItem): ScheduleItem = db.withTransaction {
        val target = DraftTarget.event(requested.id)
        val current = db.eventDao().get(requested.id)?.let(PersistenceMapper::eventToDomain)
        val initial = current ?: requested
        val existingDraft = db.draftDao().get(target.storageKey)
            ?.let(PersistenceMapper::draftToDomain)
        val needsRebase = existingDraft != null &&
            !existingDraft.baseEntityExists &&
            current != null
        if (existingDraft == null || needsRebase) {
            if (needsRebase) {
                db.draftDao().delete(target.storageKey)
            }
            val base = current?.let { event ->
                DraftRevision(
                    content = EventEditDraftCodec.encode(event),
                    updatedAt = event.updatedAt,
                    entityExists = true,
                    deletedAt = event.deletedAt,
                )
            }?.takeUnless { it.isDeleted } ?: DraftRevision.missing()
            db.draftDao().upsert(
                PersistenceMapper.draftToEntity(
                    VersionedDraft.start(
                        target = target,
                        base = base,
                        initialContent = EventEditDraftCodec.encode(initial),
                        changedAt = System.currentTimeMillis(),
                    )
                )
            )
        }
        initial
    }

    suspend fun saveOneLocal(
        event: ScheduleItem,
        clearDraftKey: String? = null,
        clearPendingActionId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): PendingActionCommitResult = db.withTransaction {
            if (clearPendingActionId != null) {
                val pending = db.pendingActionDao().get(clearPendingActionId)
                if (pending == null) {
                    // A missing pending row is terminal only when this deterministic event ID was
                    // already committed. A dismiss/delete race must not masquerade as success.
                    return@withTransaction if (db.eventDao().get(event.id) != null) {
                        PendingActionCommitResult.ALREADY_HANDLED
                    } else {
                        PendingActionCommitResult.INVALID_STATE
                    }
                }
                if (pending.kind != CONFIRMABLE_PENDING_KIND ||
                    pending.state != CONFIRMABLE_PENDING_STATE
                ) return@withTransaction PendingActionCommitResult.INVALID_STATE
            }
            val existing = db.eventDao().get(event.id)
            val sortIndex = existing?.sortIndex ?: (db.eventDao().maxSortIndex() + 1L)
            db.eventDao().upsert(PersistenceMapper.eventToEntity(event, sortIndex))
            val changed = existing == null ||
                EventWireMapper.contentKey(PersistenceMapper.eventToDomain(existing)) !=
                EventWireMapper.contentKey(event)
            if (changed) enqueue(event, now)
            clearDraftKey?.let { db.draftDao().delete(it) }
            clearPendingActionId?.let { db.pendingActionDao().delete(it) }
            PendingActionCommitResult.SAVED
    }

    /**
     * Inserts a watch-created event exactly once.
     *
     * The request id is stored in the event's lossless extras map. A retry after a lost ACK can
     * therefore recognize the original insert without overwriting edits made later on the phone.
     * A different request attempting to reuse the same event id is rejected.
     */
    suspend fun saveWatchCreated(
        event: ScheduleItem,
        requestId: String,
        now: Long = System.currentTimeMillis(),
    ): WatchTaskCommitResult = db.withTransaction {
        val existingReceipt = db.pendingActionDao().get(requestId)
        if (existingReceipt != null) {
            val receiptEventId = if (
                existingReceipt.kind == WATCH_CREATE_RECEIPT_KIND &&
                existingReceipt.state == WATCH_CREATE_RECEIPT_STATE
            ) {
                runCatching {
                    JSONObject(existingReceipt.payloadJson).optString("eventId")
                }.getOrNull()
            } else {
                null
            }
            return@withTransaction if (receiptEventId == event.id) {
                WatchTaskCommitResult.ALREADY_STORED
            } else {
                WatchTaskCommitResult.ID_CONFLICT
            }
        }

        val existingRow = db.eventDao().get(event.id)
        if (existingRow != null) {
            val existing = PersistenceMapper.eventToDomain(existingRow)
            val sameRequest =
                existing.extras[WATCH_CREATE_REQUEST_ID]?.toString() == requestId
            if (sameRequest) {
                db.pendingActionDao().upsert(watchCreateReceipt(requestId, event.id, now))
                return@withTransaction WatchTaskCommitResult.ALREADY_STORED
            }
            return@withTransaction WatchTaskCommitResult.ID_CONFLICT
        }

        val stored = event.copy(
            extras = event.extras + (WATCH_CREATE_REQUEST_ID to requestId),
        )
        val sortIndex = db.eventDao().maxSortIndex() + 1L
        db.eventDao().upsert(PersistenceMapper.eventToEntity(stored, sortIndex))
        enqueue(stored, now)
        db.pendingActionDao().upsert(watchCreateReceipt(requestId, stored.id, now))
        WatchTaskCommitResult.STORED
    }

    private fun watchCreateReceipt(
        requestId: String,
        eventId: String,
        now: Long,
    ): PendingActionEntity = PendingActionEntity(
        requestId = requestId,
        kind = WATCH_CREATE_RECEIPT_KIND,
        state = WATCH_CREATE_RECEIPT_STATE,
        payloadJson = JSONObject().put("eventId", eventId).toString(),
        createdAt = now,
        updatedAt = now,
    )

    /** Saves a recovered conflict exactly once, provided the dialog still names the current draft. */
    suspend fun saveConflictAsCopy(
        source: ScheduleItem,
        expected: DraftConflict,
        now: Long = System.currentTimeMillis(),
    ): ScheduleItem? = db.withTransaction {
        val sourceTarget = DraftTarget.event(source.id)
        if (expected.target != sourceTarget) return@withTransaction null
        val stored = db.draftDao().get(sourceTarget.storageKey)
            ?.let(PersistenceMapper::draftToDomain)
            ?: return@withTransaction null
        if (stored.contentHash != expected.draftHash ||
            stored.draftUpdatedAt != expected.draftUpdatedAt
        ) return@withTransaction null

        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            title = source.title + "（冲突副本）",
            deletedAt = 0L,
            updatedAt = now,
        )
        val sortIndex = db.eventDao().maxSortIndex() + 1L
        db.eventDao().upsert(PersistenceMapper.eventToEntity(copy, sortIndex))
        enqueue(copy, now)
        db.draftDao().delete(sourceTarget.storageKey)
        copy
    }

    suspend fun commitDraft(
        event: ScheduleItem,
        now: Long = System.currentTimeMillis(),
    ): DraftCommitResult = db.withTransaction {
        val target = DraftTarget.event(event.id)
        val stored = db.draftDao().get(target.storageKey)?.let(PersistenceMapper::draftToDomain)
        val currentRow = db.eventDao().get(event.id)
        val current = currentRow?.let(PersistenceMapper::eventToDomain)
        val currentRevision = current?.let { value ->
            DraftRevision(
                content = EventEditDraftCodec.encode(value),
                updatedAt = value.updatedAt,
                entityExists = true,
                deletedAt = value.deletedAt,
            )
        } ?: DraftRevision.missing()
        val storedSnapshot = stored?.content?.let(EventEditDraftCodec::decodeOrNull)
        val comparisonPayload = EventEditDraftCodec.encode(
            event.copy(updatedAt = storedSnapshot?.updatedAt ?: current?.updatedAt ?: event.updatedAt)
        )
        val candidate = if (stored != null && storedSnapshot != null) {
            stored.withContent(comparisonPayload, now)
        } else {
            VersionedDraft.start(
                target = target,
                base = currentRevision.takeUnless { it.isDeleted } ?: DraftRevision.missing(),
                initialContent = comparisonPayload,
                changedAt = now,
            )
        }
        db.draftDao().upsert(PersistenceMapper.draftToEntity(candidate))

        when (val decision = decideDraftRecovery(candidate, currentRevision)) {
            is DraftRecoveryDecision.Conflict -> DraftCommitResult.Conflict(decision.conflict)
            is DraftRecoveryDecision.ClearDraft -> {
                db.draftDao().delete(target.storageKey)
                DraftCommitResult.AlreadySaved
            }
            DraftRecoveryDecision.NoDraft -> error("Candidate event draft cannot be absent")
            is DraftRecoveryDecision.AutoRestore -> {
                val existingSortIndex = currentRow?.sortIndex
                val sortIndex = existingSortIndex ?: (db.eventDao().maxSortIndex() + 1L)
                db.eventDao().upsert(PersistenceMapper.eventToEntity(event, sortIndex))
                enqueue(event, now)
                db.draftDao().delete(target.storageKey)
                DraftCommitResult.Saved
            }
        }
    }

    /**
     * Applies a successful server round-trip without overwriting edits made while HTTP was in
     * flight. Shadows always describe the server response; token-guarded acknowledgements consume
     * only mutations captured before the request.
     */
    suspend fun applySync(
        merged: List<ScheduleItem>,
        captured: Map<String, String>,
        syncedAt: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            merged.forEachIndexed { index, event ->
                val currentOutbox = db.syncDao().outboxEntry(SyncDatasets.EVENTS, event.id)
                val capturedToken = captured[event.id]
                val hasNewerLocalMutation = currentOutbox != null &&
                    (capturedToken == null || currentOutbox.mutationToken != capturedToken)
                if (!hasNewerLocalMutation) {
                    db.eventDao().upsert(PersistenceMapper.eventToEntity(event, index.toLong()))
                }
                db.syncDao().upsertShadow(
                    SyncShadowEntity(
                        dataset = SyncDatasets.EVENTS,
                        entityId = event.id,
                        contentKey = EventWireMapper.contentKey(event),
                        payloadJson = EventWireMapper.encodeObject(event).toString(),
                        syncedAt = syncedAt,
                    )
                )
                if (capturedToken != null) {
                    db.syncDao().acknowledge(SyncDatasets.EVENTS, event.id, capturedToken)
                }
            }
        }
    }

    suspend fun baseKeys(): Map<String, String>? {
        val rows = db.syncDao().shadows(SyncDatasets.EVENTS)
        return rows.takeIf { it.isNotEmpty() }?.associate { it.entityId to it.contentKey }
    }

    suspend fun capturedMutations(): Map<String, String> =
        db.syncDao().outbox(SyncDatasets.EVENTS).associate { it.entityId to it.mutationToken }

    private suspend fun enqueue(event: ScheduleItem, now: Long) {
        val existing = db.syncDao().outboxEntry(SyncDatasets.EVENTS, event.id)
        val payload = EventWireMapper.encodeObject(event).toString()
        db.syncDao().enqueue(
            SyncOutboxEntity(
                dataset = SyncDatasets.EVENTS,
                entityId = event.id,
                mutationToken = UUID.randomUUID().toString(),
                payloadJson = payload,
                contentKey = EventWireMapper.contentKey(event),
                isTombstone = event.deletedAt != 0L,
                updatedAt = event.updatedAt,
                createdAt = existing?.createdAt ?: now,
            )
        )
    }

    private companion object {
        const val CONFIRMABLE_PENDING_KIND = "UNTANGLE"
        const val CONFIRMABLE_PENDING_STATE = "PROPOSAL"
        const val WATCH_CREATE_REQUEST_ID = "watchCreateRequestId"
        const val WATCH_CREATE_RECEIPT_KIND = "WATCH_TASK_CREATE"
        const val WATCH_CREATE_RECEIPT_STATE = "COMPLETED"
    }
}

class RoomJournalRepository(private val db: TPlannerDatabase) {
    fun observe(date: String): Flow<JournalEntry?> = db.journalDao().observe(date).map { row ->
        row?.let(PersistenceMapper::journalToDomain)
    }

    suspend fun get(date: String): JournalEntry? = db.journalDao().get(date)?.let(
        PersistenceMapper::journalToDomain,
    )

    suspend fun getAll(): LinkedHashMap<String, JournalEntry> = linkedMapOf<String, JournalEntry>().apply {
        db.journalDao().getAll().forEach { row -> put(row.date, PersistenceMapper.journalToDomain(row)) }
    }

    /** Captures the authoritative revision before editing can race with synchronization. */
    suspend fun beginDraft(
        date: String,
        changedAt: Long = System.currentTimeMillis(),
    ): DraftRecoveryDecision = db.withTransaction {
        val target = DraftTarget.journal(date)
        val currentRow = db.journalDao().get(date)
        val existing = db.draftDao().get(target.storageKey)
            ?.let(PersistenceMapper::draftToDomain)
        if (existing != null) {
            when (val decision = decideDraftRecovery(existing, currentRow.toDraftRevision())) {
                is DraftRecoveryDecision.ClearDraft -> db.draftDao().delete(target.storageKey)
                else -> return@withTransaction decision
            }
        }

        // The database revision and the editor's initial text must come from the same read. A
        // caller-side Compose value may lag a just-completed sync and must never be paired with a
        // newer base, or an unchanged Done action could overwrite that sync without a conflict.
        val draft = newDraft(
            target,
            currentRow,
            currentRow.editorTextAtSessionStart(),
            changedAt,
        )
        db.draftDao().upsert(PersistenceMapper.draftToEntity(draft))
        DraftRecoveryDecision.AutoRestore(draft)
    }

    suspend fun saveDraft(
        date: String,
        text: String,
        changedAt: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val target = DraftTarget.journal(date)
            val currentRow = db.journalDao().get(date)
            val existing = db.draftDao().get(target.storageKey)
                ?.let(PersistenceMapper::draftToDomain)
            // Do not rebase an active no-change session here. The authoritative fact may have
            // moved after beginDraft but before the first keystroke; retaining the original base
            // is what lets the eventual commit report that race as a conflict.
            val session = existing ?: newDraft(
                target = target,
                currentRow = currentRow,
                // Lazy-begin compatibility: deleted/missing dates are displayed as an empty editor.
                initialText = currentRow.editorTextAtSessionStart(),
                changedAt = changedAt,
            )
            val draft = session.withContent(text, changedAt)
            db.draftDao().upsert(PersistenceMapper.draftToEntity(draft))
        }
    }

    suspend fun loadRecoverableDraft(date: String): DraftRecoveryDecision = db.withTransaction {
        val draft = db.draftDao().get(DraftTarget.journal(date).storageKey)
            ?.let(PersistenceMapper::draftToDomain)
        val decision = decideDraftRecovery(draft, db.journalDao().get(date).toDraftRevision())
        if (decision is DraftRecoveryDecision.ClearDraft) {
            db.draftDao().delete(decision.draft.target.storageKey)
        }
        decision
    }

    suspend fun commitDraft(
        date: String,
        text: String,
        now: Long = System.currentTimeMillis(),
    ): DraftCommitResult = db.withTransaction {
        val target = DraftTarget.journal(date)
        val stored = db.draftDao().get(target.storageKey)?.let(PersistenceMapper::draftToDomain)
        val currentRow = db.journalDao().get(date)
        val current = currentRow.toDraftRevision()
        val session = stored ?: newDraft(
            target = target,
            currentRow = currentRow,
            initialText = currentRow.editorTextAtSessionStart(),
            changedAt = now,
        )
        val candidate = session.withContent(text, now)
        // Preserve the exact final editor payload even when the fresh conflict check rejects commit.
        db.draftDao().upsert(PersistenceMapper.draftToEntity(candidate))
        when (val decision = decideDraftRecovery(candidate, current)) {
            is DraftRecoveryDecision.Conflict -> DraftCommitResult.Conflict(decision.conflict)
            is DraftRecoveryDecision.ClearDraft -> {
                db.draftDao().delete(target.storageKey)
                DraftCommitResult.AlreadySaved
            }
            DraftRecoveryDecision.NoDraft -> error("Candidate draft cannot be absent")
            is DraftRecoveryDecision.AutoRestore -> {
                val entry = JournalEntry(text = text, updatedAt = now, deletedAt = 0L)
                db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
                enqueue(date, entry, now)
                db.draftDao().delete(target.storageKey)
                DraftCommitResult.Saved
            }
        }
    }

    /** Explicit conflict-resolution action: overwrite only after the UI obtains confirmation. */
    suspend fun overwriteDraft(
        date: String,
        text: String,
        expectedDraftHash: String,
        expectedDraftUpdatedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = db.withTransaction {
        val target = DraftTarget.journal(date)
        val stored = db.draftDao().get(target.storageKey)
            ?.let(PersistenceMapper::draftToDomain)
            ?: return@withTransaction false
        if (stored.contentHash != expectedDraftHash ||
            stored.draftUpdatedAt != expectedDraftUpdatedAt
        ) return@withTransaction false
        val entry = JournalEntry(text = text, updatedAt = now, deletedAt = 0L)
        db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
        enqueue(date, entry, now)
        db.draftDao().delete(target.storageKey)
        true
    }

    suspend fun discardDraft(
        date: String,
        expectedDraftHash: String,
        expectedDraftUpdatedAt: Long,
    ): Boolean = db.withTransaction {
        val target = DraftTarget.journal(date)
        val stored = db.draftDao().get(target.storageKey)
            ?.let(PersistenceMapper::draftToDomain)
            ?: return@withTransaction false
        if (stored.contentHash != expectedDraftHash ||
            stored.draftUpdatedAt != expectedDraftUpdatedAt
        ) return@withTransaction false
        db.draftDao().delete(target.storageKey) > 0
    }

    suspend fun saveLocal(date: String, text: String, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val entry = JournalEntry(text = text, updatedAt = now, deletedAt = 0L)
            db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
            enqueue(date, entry, now)
        }
    }

    suspend fun append(
        date: String,
        line: String,
        now: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val current = db.journalDao().get(date)?.let(PersistenceMapper::journalToDomain)
                ?.takeIf { it.deletedAt == 0L }?.text.orEmpty()
            val text = if (current.isBlank()) line else current.trimEnd() + "\n" + line
            val entry = JournalEntry(text = text, updatedAt = now, deletedAt = 0L)
            db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
            enqueue(date, entry, now)
        }
    }

    /**
     * Appends an entry exactly once. The hidden marker, fact write, and outbox replacement share
     * one Room transaction, so retrying after an abrupt process death cannot duplicate the entry.
     */
    suspend fun appendOnce(
        date: String,
        idempotencyToken: String,
        line: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(idempotencyToken.isNotBlank()) { "Idempotency token must not be blank" }
        val marker = journalOnceMarker(idempotencyToken)
        return db.withTransaction {
            val current = db.journalDao().get(date)?.let(PersistenceMapper::journalToDomain)
                ?.takeIf { it.deletedAt == 0L }?.text.orEmpty()
            if (marker in current) return@withTransaction false

            val durableLine = buildString(line.length + marker.length + 1) {
                append(line)
                if (isNotEmpty() && last() != '\n') append('\n')
                append(marker)
            }
            val text = if (current.isBlank()) {
                durableLine
            } else {
                current.trimEnd() + "\n" + durableLine
            }
            val entry = JournalEntry(text = text, updatedAt = now, deletedAt = 0L)
            db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
            enqueue(date, entry, now)
            true
        }
    }

    suspend fun replaceLine(
        date: String,
        target: String,
        replacement: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = db.withTransaction {
        val current = db.journalDao().get(date)?.let(PersistenceMapper::journalToDomain)
            ?.takeIf { it.deletedAt == 0L }?.text.orEmpty()
        val index = current.lastIndexOf(target)
        val targetEnd = index + target.length
        val isWholeLine = index >= 0 &&
            (index == 0 || current[index - 1] == '\n') &&
            (targetEnd == current.length || current[targetEnd] == '\n')
        if (!isWholeLine) return@withTransaction false

        val entry = JournalEntry(
            text = current.substring(0, index) + replacement + current.substring(targetEnd),
            updatedAt = now,
            deletedAt = 0L,
        )
        db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
        enqueue(date, entry, now)
        true
    }

    suspend fun applySync(
        merged: Map<String, JournalEntry>,
        captured: Map<String, String>,
        syncedAt: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            merged.forEach { (date, entry) ->
                val currentOutbox = db.syncDao().outboxEntry(SyncDatasets.JOURNALS, date)
                val capturedToken = captured[date]
                val hasNewerLocalMutation = currentOutbox != null &&
                    (capturedToken == null || currentOutbox.mutationToken != capturedToken)
                if (!hasNewerLocalMutation) {
                    db.journalDao().upsert(PersistenceMapper.journalToEntity(date, entry))
                }
                db.syncDao().upsertShadow(
                    SyncShadowEntity(
                        dataset = SyncDatasets.JOURNALS,
                        entityId = date,
                        contentKey = JournalWireMapper.contentKey(entry),
                        payloadJson = JournalWireMapper.encodeObject(entry).toString(),
                        syncedAt = syncedAt,
                    )
                )
                if (capturedToken != null) {
                    db.syncDao().acknowledge(SyncDatasets.JOURNALS, date, capturedToken)
                }
            }
        }
    }

    suspend fun baseKeys(): Map<String, String>? {
        val rows = db.syncDao().shadows(SyncDatasets.JOURNALS)
        return rows.takeIf { it.isNotEmpty() }?.associate { it.entityId to it.contentKey }
    }

    suspend fun capturedMutations(): Map<String, String> =
        db.syncDao().outbox(SyncDatasets.JOURNALS).associate { it.entityId to it.mutationToken }

    private suspend fun enqueue(date: String, entry: JournalEntry, now: Long) {
        val existing = db.syncDao().outboxEntry(SyncDatasets.JOURNALS, date)
        val payload = JournalWireMapper.encodeObject(entry).toString()
        db.syncDao().enqueue(
            SyncOutboxEntity(
                dataset = SyncDatasets.JOURNALS,
                entityId = date,
                mutationToken = UUID.randomUUID().toString(),
                payloadJson = payload,
                contentKey = JournalWireMapper.contentKey(entry),
                isTombstone = entry.deletedAt != 0L,
                updatedAt = entry.updatedAt,
                createdAt = existing?.createdAt ?: now,
            )
        )
    }

    private fun newDraft(
        target: DraftTarget,
        currentRow: JournalEntity?,
        initialText: String,
        changedAt: Long,
    ): VersionedDraft = VersionedDraft.start(
        target = target,
        base = currentRow.toDraftRevision(),
        initialContent = initialText,
        changedAt = changedAt,
    )

    private fun JournalEntity?.toDraftRevision(): DraftRevision = this?.let { row ->
        DraftRevision(
            content = row.text,
            updatedAt = row.updatedAt,
            entityExists = true,
            deletedAt = row.deletedAt,
        )
    } ?: DraftRevision.missing()

    private fun JournalEntity?.editorTextAtSessionStart(): String =
        this?.takeIf { it.deletedAt == 0L }?.text.orEmpty()

}

internal fun journalOnceMarker(idempotencyToken: String): String =
    "<!-- tplanner-once:${baseHash(idempotencyToken)} -->"

class RoomDraftRepository(private val db: TPlannerDatabase) {
    suspend fun get(target: DraftTarget): VersionedDraft? = db.draftDao().get(target.storageKey)
        ?.let(PersistenceMapper::draftToDomain)

    suspend fun save(draft: VersionedDraft) {
        db.draftDao().upsert(PersistenceMapper.draftToEntity(draft))
    }

    suspend fun getAll(): List<VersionedDraft> = db.draftDao().getAll().mapNotNull { row ->
        runCatching { PersistenceMapper.draftToDomain(row) }.getOrNull()
    }

    suspend fun delete(target: DraftTarget): Boolean = db.draftDao().delete(target.storageKey) > 0

    suspend fun deleteIfMatches(target: DraftTarget, expected: DraftConflict): Boolean =
        db.withTransaction {
            if (expected.target != target) return@withTransaction false
            val stored = db.draftDao().get(target.storageKey)
                ?.let(PersistenceMapper::draftToDomain)
                ?: return@withTransaction false
            if (stored.contentHash != expected.draftHash ||
                stored.draftUpdatedAt != expected.draftUpdatedAt
            ) return@withTransaction false
            db.draftDao().delete(target.storageKey) > 0
        }
}
