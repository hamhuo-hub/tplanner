package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.DraftConflict
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import com.hamhuo.tplanner.persistence.DraftRecoveryDecision
import com.hamhuo.tplanner.persistence.DraftRevision
import com.hamhuo.tplanner.persistence.DraftTarget
import com.hamhuo.tplanner.persistence.EventWireMapper
import com.hamhuo.tplanner.persistence.EventDraftRecovery
import com.hamhuo.tplanner.persistence.EventEditDraftCodec
import com.hamhuo.tplanner.persistence.EventEditStage
import com.hamhuo.tplanner.persistence.PendingActionCommitResult
import com.hamhuo.tplanner.persistence.RoomDraftRepository
import com.hamhuo.tplanner.persistence.RoomEventRepository
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.VersionedDraft
import com.hamhuo.tplanner.persistence.decideDraftRecovery
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CheckItem(val id: String, val text: String, val completed: Boolean)

data class TaskEvent(
    val id: String,
    val title: String,
    val type: String,
    val start: Instant,
    val end: Instant,
    val completed: Boolean,
    val checklist: List<CheckItem>,
    val colorId: Int,
    val note: String,
    val deletedAt: Long,
    val updatedAt: Long = 0L,
    val alarmEnabled: Boolean = false,
    val alarmOffsetMinutes: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Unknown desktop/server fields must round-trip unchanged. */
    val extras: Map<String, Any?> = mapOf("timezone" to APP_TIME_ZONE_ID),
)

/** Room-backed façade used by UI, alarms, Wear, and synchronization. */
class EventStore(
    context: Context,
    database: TPlannerDatabase = TPlannerDatabase.get(context),
) {
    private val appContext = context.applicationContext
    private val repository = RoomEventRepository(database)
    private val drafts = RoomDraftRepository(database)

    fun observeAll(): Flow<List<TaskEvent>> = repository.observeAll()

    suspend fun getAll(): List<TaskEvent> =
        DurableWriteQueue.readAfterPending(EVENT_FACT_QUEUE_KEY) { repository.getAll() }

    suspend fun save(event: TaskEvent) {
        // The writer belongs to the application process, not the Activity coroutine awaiting it.
        // Rotation/onStop may cancel the waiter but cannot cancel an accepted fact mutation.
        DurableWriteQueue.submitAndAwait(EVENT_FACT_QUEUE_KEY) {
            repository.saveOneLocal(event)
            reconcileAlarms(repository.getAll())
            scheduleSync()
        }
    }

    /** Captures the authoritative base before the editor can diverge from it. */
    suspend fun beginEventEdit(event: TaskEvent): TaskEvent =
        DurableWriteQueue.readAfterPending(draftQueueKey(event.id)) {
            repository.beginEdit(event)
        }

    suspend fun saveEventDraft(
        event: TaskEvent,
        stage: EventEditStage = EventEditStage.DETAIL,
    ) {
        DurableWriteQueue.submitAndAwait(draftQueueKey(event.id)) {
            saveEventDraftNow(event, stage)
        }
    }

    fun enqueueEventDraft(
        event: TaskEvent,
        stage: EventEditStage = EventEditStage.DETAIL,
    ) {
        DurableWriteQueue.submit(draftQueueKey(event.id)) {
            saveEventDraftNow(event, stage)
        }
    }

    private suspend fun saveEventDraftNow(event: TaskEvent, stage: EventEditStage) {
        val target = DraftTarget.event(event.id)
        val changedAt = System.currentTimeMillis()
        val payload = EventEditDraftCodec.encode(
            event.copy(updatedAt = event.updatedAt),
            stage,
        )
        val existing = drafts.get(target)
        val current = repository.get(event.id)
        val draft = if (
            existing != null &&
            EventEditDraftCodec.decodeSnapshotOrNull(existing.content) != null
        ) {
            existing.withContent(payload, changedAt)
        } else {
            VersionedDraft.start(
                target = target,
                base = current?.let { stored ->
                    DraftRevision(
                        content = EventEditDraftCodec.encode(stored),
                        updatedAt = stored.updatedAt,
                        entityExists = true,
                        deletedAt = stored.deletedAt,
                    )
                }?.takeUnless { it.isDeleted } ?: DraftRevision.missing(),
                initialContent = payload,
                changedAt = changedAt,
            )
        }
        drafts.save(draft)
    }

    suspend fun recoverEventDraft(eventId: String): EventDraftRecovery =
        DurableWriteQueue.readAfterPending(draftQueueKey(eventId)) {
            recoverEventDraftNow(eventId)
        }

    private suspend fun recoverEventDraftNow(eventId: String): EventDraftRecovery {
        val target = DraftTarget.event(eventId)
        val draft = drafts.get(target) ?: return EventDraftRecovery.None
        val current = repository.get(eventId)
        val fullDraft = EventEditDraftCodec.decodeSnapshotOrNull(draft.content)
        if (fullDraft != null) {
            val revision = current?.let { stored ->
                DraftRevision(
                    content = EventEditDraftCodec.encode(stored),
                    updatedAt = stored.updatedAt,
                    entityExists = true,
                    deletedAt = stored.deletedAt,
                )
            } ?: DraftRevision.missing()
            return when (val decision = decideDraftRecovery(draft, revision)) {
                is DraftRecoveryDecision.AutoRestore -> EventDraftRecovery.Recovered(
                    event = fullDraft.event,
                    isNew = !draft.baseEntityExists,
                    stage = fullDraft.stage,
                )
                is DraftRecoveryDecision.ClearDraft -> {
                    drafts.delete(target)
                    EventDraftRecovery.None
                }
                is DraftRecoveryDecision.Conflict -> EventDraftRecovery.Conflict(
                    details = decision.conflict,
                    event = fullDraft.event,
                    stage = fullDraft.stage,
                )
                DraftRecoveryDecision.NoDraft -> EventDraftRecovery.None
            }
        }

        // v0 stored only the note text. Upgrade it in memory without guessing missing event fields.
        val noteRevision = current.toNoteRevision()
        return when (val decision = decideDraftRecovery(draft, noteRevision)) {
            is DraftRecoveryDecision.AutoRestore -> current?.let {
                EventDraftRecovery.Recovered(
                    event = it.copy(note = decision.draft.content),
                    isNew = false,
                    stage = EventEditStage.DETAIL,
                )
            } ?: EventDraftRecovery.None
            is DraftRecoveryDecision.ClearDraft -> {
                drafts.delete(target)
                EventDraftRecovery.None
            }
            is DraftRecoveryDecision.Conflict -> EventDraftRecovery.Conflict(decision.conflict)
            DraftRecoveryDecision.NoDraft -> EventDraftRecovery.None
        }
    }

    suspend fun latestEventDraftRecovery(): EventDraftRecovery? {
        DurableWriteQueue.flushPrefix(EVENT_QUEUE_PREFIX)
        val candidates = drafts.getAll()
            .filter { it.target.kind == com.hamhuo.tplanner.persistence.DraftEntityKind.EVENT }
            .sortedByDescending { it.draftUpdatedAt }
        candidates.forEach { draft ->
            val recovered = recoverEventDraft(draft.target.entityId)
            if (recovered !is EventDraftRecovery.None) {
                return recovered
            }
        }
        return null
    }

    suspend fun discardEventDraft(eventId: String, expected: DraftConflict? = null): Boolean {
        return DurableWriteQueue.submitAndAwait(
            key = draftQueueKey(eventId),
            clearsPreviousFailure = { it },
        ) {
            if (expected == null) {
                drafts.delete(DraftTarget.event(eventId))
            } else {
                drafts.deleteIfMatches(DraftTarget.event(eventId), expected)
            }
        }
    }

    suspend fun saveAndClearEventDraft(event: TaskEvent): DraftCommitResult {
        val result = DurableWriteQueue.submitAndAwait(draftQueueKey(event.id)) {
            DurableWriteQueue.submitAndAwait(EVENT_FACT_QUEUE_KEY) {
                repository.commitDraft(event).also { committed ->
                    if (committed is DraftCommitResult.Saved) {
                        reconcileAlarms(repository.getAll())
                        scheduleSync()
                    }
                }
            }
        }
        return result
    }

    /** Explicit conflict resolution that preserves both the current fact and the recovered edit. */
    suspend fun saveConflictAsCopy(event: TaskEvent, conflict: DraftConflict): TaskEvent? {
        val copy = DurableWriteQueue.submitAndAwait(
            key = draftQueueKey(event.id),
            clearsPreviousFailure = { it != null },
        ) {
            DurableWriteQueue.submitAndAwait(
                key = EVENT_FACT_QUEUE_KEY,
                clearsPreviousFailure = { it != null },
            ) {
                repository.saveConflictAsCopy(event, conflict).also { savedCopy ->
                    if (savedCopy != null) {
                        reconcileAlarms(repository.getAll())
                        scheduleSync()
                    }
                }
            }
        }
        return copy
    }

    suspend fun saveAndClearPendingAction(
        event: TaskEvent,
        requestId: String,
    ): PendingActionCommitResult {
        val result = DurableWriteQueue.submitAndAwait(
            key = UNTANGLE_QUEUE_KEY,
            clearsPreviousFailure = { it != PendingActionCommitResult.INVALID_STATE },
        ) {
            DurableWriteQueue.submitAndAwait(
                key = EVENT_FACT_QUEUE_KEY,
                clearsPreviousFailure = { it != PendingActionCommitResult.INVALID_STATE },
            ) {
                repository.saveOneLocal(event, clearPendingActionId = requestId).also { committed ->
                    if (committed == PendingActionCommitResult.SAVED) {
                        reconcileAlarms(repository.getAll())
                        scheduleSync()
                    }
                }
            }
        }
        return result
    }

    suspend fun applySync(events: List<TaskEvent>, captured: Map<String, String>) {
        DurableWriteQueue.submitAndAwait(EVENT_FACT_QUEUE_KEY) {
            repository.applySync(events, captured)
            reconcileAlarms(repository.getAll())
        }
    }

    suspend fun baseKeys(): Map<String, String>? =
        DurableWriteQueue.readAfterPending(EVENT_FACT_QUEUE_KEY) { repository.baseKeys() }

    suspend fun capturedMutations(): Map<String, String> =
        DurableWriteQueue.readAfterPending(EVENT_FACT_QUEUE_KEY) {
            repository.capturedMutations()
        }

    fun fromJson(json: String): List<TaskEvent> = EventWireMapper.decodeArrayStrict(json)

    fun toJson(events: List<TaskEvent>): String = EventWireMapper.encodeArray(events)

    private fun TaskEvent?.toNoteRevision(): DraftRevision = this?.let { event ->
        DraftRevision(
            content = event.note,
            updatedAt = event.updatedAt,
            entityExists = true,
            deletedAt = event.deletedAt,
        )
    } ?: DraftRevision.missing()

    private fun reconcileAlarms(events: List<TaskEvent>) {
        runCatching { TaskAlarmScheduler.reconcile(appContext, events) }
    }

    private fun draftQueueKey(eventId: String): String = "$EVENT_QUEUE_PREFIX$eventId"

    /** The Room outbox is authoritative; WorkManager can be started again on the next launch. */
    private fun scheduleSync() {
        runCatching { SyncOutboxScheduler.enqueue(appContext) }
    }

    private companion object {
        const val EVENT_QUEUE_PREFIX = "event:"
        const val EVENT_FACT_QUEUE_KEY = "event-facts"
    }
}

internal val ISO_MS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Matches desktop `Date.toISOString()` including a fixed millisecond component. */
internal fun TaskEvent.toJson(): JSONObject = EventWireMapper.encodeObject(this)

internal const val MAX_ALARM_OFFSET_MINUTES = 7 * 24 * 60

fun List<TaskEvent>.forToday(): List<TaskEvent> = forDate(appToday())

fun List<TaskEvent>.forDate(date: LocalDate): List<TaskEvent> = filter { event ->
    if (event.deletedAt != 0L) return@filter false
    val startDate = event.start.atZone(APP_ZONE).toLocalDate()
    val endDate = event.end.atZone(APP_ZONE).toLocalDate()
    !startDate.isAfter(date) && !endDate.isBefore(date)
}.sortedBy { it.start }
