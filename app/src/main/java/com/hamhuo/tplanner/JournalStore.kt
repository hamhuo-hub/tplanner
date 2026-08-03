package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.DraftConflict
import com.hamhuo.tplanner.persistence.DraftRecoveryDecision
import com.hamhuo.tplanner.persistence.JournalWireMapper
import com.hamhuo.tplanner.persistence.RoomJournalRepository
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

class JournalStore(
    context: Context,
    database: TPlannerDatabase = TPlannerDatabase.get(context),
) {
    private val appContext = context.applicationContext
    private val repository = RoomJournalRepository(database)

    fun observe(date: String): Flow<JournalEntry?> = repository.observe(date)

    suspend fun getAll(): Map<String, JournalEntry> = repository.getAll()

    suspend fun getToday(): String = repository.get(appToday().toString())
        ?.takeIf { it.deletedAt == 0L }
        ?.text
        .orEmpty()

    /** Returns recovery details without making a conflict draft inaccessible to the UI. */
    suspend fun getTodayDraftRecovery(): JournalDraftRecovery =
        repository.loadRecoverableDraft(appToday().toString()).toJournalRecovery()

    /**
     * Compatibility accessor used by the current editor. A conflict still returns the retained
     * draft text; committing it performs a fresh check and cannot overwrite the current fact.
     */
    suspend fun getTodayDraft(): String? = when (val recovery = getTodayDraftRecovery()) {
        JournalDraftRecovery.None -> null
        is JournalDraftRecovery.Recovered -> recovery.text
        is JournalDraftRecovery.Conflict -> recovery.text
    }

    suspend fun beginTodayDraft(initialText: String): JournalDraftRecovery =
        repository.beginDraft(appToday().toString(), initialText).toJournalRecovery()

    suspend fun saveTodayDraft(text: String) {
        repository.saveDraft(appToday().toString(), text)
    }

    suspend fun commitTodayDraft(text: String): DraftCommitResult {
        val result = repository.commitDraft(appToday().toString(), text)
        if (result is DraftCommitResult.Saved) SyncOutboxScheduler.enqueue(appContext)
        return result
    }

    suspend fun saveToday(text: String) {
        repository.saveLocal(appToday().toString(), text)
        SyncOutboxScheduler.enqueue(appContext)
    }

    suspend fun appendToday(line: String) {
        repository.append(appToday().toString(), line)
        SyncOutboxScheduler.enqueue(appContext)
    }

    suspend fun appendTodayOnce(idempotencyToken: String, line: String): Boolean {
        val appended = repository.appendOnce(appToday().toString(), idempotencyToken, line)
        if (appended) SyncOutboxScheduler.enqueue(appContext)
        return appended
    }

    suspend fun replaceInToday(target: String, replacement: String): Boolean {
        val replaced = repository.replaceLine(appToday().toString(), target, replacement)
        if (replaced) SyncOutboxScheduler.enqueue(appContext)
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
}
