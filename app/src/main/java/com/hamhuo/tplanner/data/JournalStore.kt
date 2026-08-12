package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.DraftConflict
import com.hamhuo.tplanner.persistence.DraftRecoveryDecision
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import com.hamhuo.tplanner.persistence.DraftEntityKind
import com.hamhuo.tplanner.persistence.JournalWireMapper
import com.hamhuo.tplanner.persistence.RoomJournalRepository
import com.hamhuo.tplanner.persistence.RoomDraftRepository
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import kotlinx.coroutines.flow.Flow

/** `deletedAt == 0` is alive internally; the wire mapper restores JSON null on output. */
data class JournalEntry(
    val text: String,
    val updatedAt: Long = 0L,
    val deletedAt: Long = 0L,
)

sealed interface JournalDraftRecovery {
    data object None : JournalDraftRecovery
    data class Recovered(val text: String) : JournalDraftRecovery
    data class Conflict(val details: DraftConflict) : JournalDraftRecovery {
        val text: String get() = details.draftContent
    }
}

data class DatedJournalDraftRecovery(
    val date: String,
    val recovery: JournalDraftRecovery,
)

class JournalStore(
    context: Context,
    database: TPlannerDatabase = TPlannerDatabase.get(context),
) {
    private val appContext = context.applicationContext
    private val repository = RoomJournalRepository(database)
    private val drafts = RoomDraftRepository(database)

    fun observe(date: String): Flow<JournalEntry?> = repository.observe(date)

    suspend fun getAll(): Map<String, JournalEntry> = repository.getAll()

    suspend fun getToday(): String = repository.get(appToday().toString())
        ?.takeIf { it.deletedAt == 0L }
        ?.text
        .orEmpty()

    suspend fun get(date: String): String = repository.get(date)
        ?.takeIf { it.deletedAt == 0L }
        ?.text
        .orEmpty()

    /** Returns recovery details without making a conflict draft inaccessible to the UI. */
    suspend fun getTodayDraftRecovery(): JournalDraftRecovery =
        getDraftRecovery(appToday().toString())

    /** Finds work from a previous date that a post-midnight process restart must not hide. */
    suspend fun latestDraftRecovery(): DatedJournalDraftRecovery? {
        DurableWriteQueue.flushPrefix(JOURNAL_QUEUE_PREFIX)
        val candidates = drafts.getAll()
            .filter { it.target.kind == DraftEntityKind.JOURNAL }
            .sortedByDescending { it.draftUpdatedAt }
        candidates.forEach { draft ->
            val recovery = getDraftRecovery(draft.target.entityId)
            if (recovery !is JournalDraftRecovery.None) {
                return DatedJournalDraftRecovery(draft.target.entityId, recovery)
            }
        }
        return null
    }

    suspend fun getDraftRecovery(date: String): JournalDraftRecovery =
        DurableWriteQueue.readAfterPending(draftQueueKey(date)) {
            repository.loadRecoverableDraft(date).toJournalRecovery()
        }

    /**
     * Compatibility accessor used by the current editor. A conflict still returns the retained
     * draft text; committing it performs a fresh check and cannot overwrite the current fact.
     */
    suspend fun getTodayDraft(): String? = when (val recovery = getTodayDraftRecovery()) {
        JournalDraftRecovery.None -> null
        is JournalDraftRecovery.Recovered -> recovery.text
        is JournalDraftRecovery.Conflict -> recovery.text
    }

    suspend fun getDraft(date: String): String? = when (val recovery = getDraftRecovery(date)) {
        JournalDraftRecovery.None -> null
        is JournalDraftRecovery.Recovered -> recovery.text
        is JournalDraftRecovery.Conflict -> recovery.text
    }

    suspend fun beginTodayDraft(): JournalDraftRecovery =
        beginDraft(appToday().toString())

    suspend fun beginDraft(date: String): JournalDraftRecovery =
        DurableWriteQueue.readAfterPending(draftQueueKey(date)) {
            repository.beginDraft(date).toJournalRecovery()
        }

    suspend fun saveTodayDraft(text: String) {
        saveDraft(appToday().toString(), text)
    }

    fun enqueueDraft(date: String, text: String) {
        DurableWriteQueue.submit(draftQueueKey(date)) { repository.saveDraft(date, text) }
    }

    suspend fun saveDraft(date: String, text: String) {
        DurableWriteQueue.submitAndAwait(draftQueueKey(date)) {
            repository.saveDraft(date, text)
        }
    }

    suspend fun commitTodayDraft(text: String): DraftCommitResult {
        return commitDraft(appToday().toString(), text)
    }

    suspend fun commitDraft(date: String, text: String): DraftCommitResult {
        val result = DurableWriteQueue.submitAndAwait(draftQueueKey(date)) {
            repository.commitDraft(date, text)
        }
        if (result is DraftCommitResult.Saved) scheduleSync()
        return result
    }

    suspend fun overwriteDraft(conflict: DraftConflict): Boolean {
        val date = conflict.target.entityId
        val overwritten = DurableWriteQueue.submitAndAwait(
            key = draftQueueKey(date),
            clearsPreviousFailure = { it },
        ) {
            repository.overwriteDraft(
                date = date,
                text = conflict.draftContent,
                expectedDraftHash = conflict.draftHash,
                expectedDraftUpdatedAt = conflict.draftUpdatedAt,
            )
        }
        if (overwritten) scheduleSync()
        return overwritten
    }

    suspend fun discardDraft(conflict: DraftConflict): Boolean {
        val date = conflict.target.entityId
        return DurableWriteQueue.submitAndAwait(
            key = draftQueueKey(date),
            clearsPreviousFailure = { it },
        ) {
            repository.discardDraft(
                date = date,
                expectedDraftHash = conflict.draftHash,
                expectedDraftUpdatedAt = conflict.draftUpdatedAt,
            )
        }
    }

    suspend fun saveToday(text: String) {
        repository.saveLocal(appToday().toString(), text)
        scheduleSync()
    }

    suspend fun appendToday(line: String) {
        repository.append(appToday().toString(), line)
        scheduleSync()
    }

    suspend fun appendTodayOnce(idempotencyToken: String, line: String): Boolean {
        return appendOnce(appToday().toString(), idempotencyToken, line)
    }

    suspend fun appendOnce(date: String, idempotencyToken: String, line: String): Boolean {
        val appended = repository.appendOnce(date, idempotencyToken, line)
        if (appended) scheduleSync()
        return appended
    }

    suspend fun replaceInToday(target: String, replacement: String): Boolean {
        val replaced = repository.replaceLine(appToday().toString(), target, replacement)
        if (replaced) scheduleSync()
        return replaced
    }

    suspend fun applySync(
        journals: Map<String, JournalEntry>,
        captured: Map<String, String>,
    ) {
        repository.applySync(journals, captured)
    }

    suspend fun baseKeys(): Map<String, String>? = repository.baseKeys()

    suspend fun capturedMutations(): Map<String, String> = repository.capturedMutations()

    fun fromJson(json: String): Map<String, JournalEntry> = JournalWireMapper.decodeMapStrict(json)

    fun toJson(journals: Map<String, JournalEntry>): String = JournalWireMapper.encodeMap(journals)

    private fun DraftRecoveryDecision.toJournalRecovery(): JournalDraftRecovery = when (this) {
        is DraftRecoveryDecision.AutoRestore -> JournalDraftRecovery.Recovered(draft.content)
        is DraftRecoveryDecision.Conflict -> JournalDraftRecovery.Conflict(conflict)
        is DraftRecoveryDecision.ClearDraft,
        DraftRecoveryDecision.NoDraft,
        -> JournalDraftRecovery.None
    }

    private fun draftQueueKey(date: String): String = "$JOURNAL_QUEUE_PREFIX$date"

    /** The durable outbox is already committed; scheduler startup is retryable on next launch. */
    private fun scheduleSync() {
        runCatching { SyncOutboxScheduler.enqueue(appContext) }
    }

    private companion object {
        const val JOURNAL_QUEUE_PREFIX = "journal:"
    }
}
