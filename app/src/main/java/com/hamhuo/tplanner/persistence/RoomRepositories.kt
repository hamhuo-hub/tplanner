package com.hamhuo.tplanner.persistence

import androidx.room.withTransaction
import com.hamhuo.tplanner.JournalEntry
import com.hamhuo.tplanner.TaskEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

sealed interface DraftCommitResult {
    data object Saved : DraftCommitResult
    data object AlreadySaved : DraftCommitResult
    data class Conflict(val details: DraftConflict) : DraftCommitResult
}

class RoomEventRepository(private val db: TPlannerDatabase) {
    fun observeAll(): Flow<List<TaskEvent>> = db.eventDao().observeAll().map { rows ->
        rows.map(PersistenceMapper::eventToDomain)
    }

    suspend fun getAll(): List<TaskEvent> = db.eventDao().getAll().map(
        PersistenceMapper::eventToDomain,
    )

    suspend fun get(id: String): TaskEvent? = db.eventDao().get(id)?.let(
        PersistenceMapper::eventToDomain,
    )

    suspend fun beginEdit(requested: TaskEvent): TaskEvent = db.withTransaction {
        val target = DraftTarget.event(requested.id)
        val current = db.eventDao().get(requested.id)?.let(PersistenceMapper::eventToDomain)
        val initial = current ?: requested
        if (db.draftDao().get(target.storageKey) == null) {
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
        event: TaskEvent,
        clearDraftKey: String? = null,
        clearPendingActionId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean = db.withTransaction {
            if (clearPendingActionId != null) {
                val pending = db.pendingActionDao().get(clearPendingActionId)
                if (pending == null ||
                    pending.kind != CONFIRMABLE_PENDING_KIND ||
                    pending.state != CONFIRMABLE_PENDING_STATE
                ) {
                    return@withTransaction false
                }
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
            true
    }

    suspend fun commitDraft(
        event: TaskEvent,
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
        merged: List<TaskEvent>,
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

    private suspend fun enqueue(event: TaskEvent, now: Long) {
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
        initialText: String,
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

        val draft = newDraft(target, currentRow, initialText, changedAt)
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
}
