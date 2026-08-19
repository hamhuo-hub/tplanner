package com.hamhuo.tplanner

import android.content.Context
import android.widget.Toast
import com.hamhuo.tplanner.persistence.EventDraftRecovery
import com.hamhuo.tplanner.persistence.EventEditStage
import com.hamhuo.tplanner.persistence.DraftConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

class ScheduleItemActions(
    private val scope: CoroutineScope,
    private val context: Context,
    private val eventStore: ScheduleItemStore,
    private val eventWriteMutex: Mutex,
    private val fetchEvents: suspend (String) -> List<ScheduleItem>,
    private val serverUrl: () -> String,
) {
    fun beginNewItem(
        type: String,
        listId: String = "",
        initialStart: Instant = Instant.now(),
        onPending: (ScheduleItem) -> Unit,
    ) {
        val now = Instant.now()
        val draft = ScheduleItem(
            id = UUID.randomUUID().toString(),
            title = "",
            type = type,
            start = initialStart,
            end = initialStart.plusSeconds(3_600),
            completed = false,
            checklist = emptyList(),
            colorId = 0,
            note = "",
            deletedAt = 0L,
            updatedAt = now.toEpochMilli(),
            alarmEnabled = type == "event",
            alarmOffsetMinutes = 0,
            listId = listId,
        )
        scope.launch {
            try {
                eventWriteMutex.withLock {
                    eventStore.saveEventDraft(draft, EventEditStage.NAMING)
                }
                onPending(draft)
            } catch (_: Exception) {
                Toast.makeText(context, "无法保存新事项草稿，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openItem(
        event: ScheduleItem,
        onPending: (ScheduleItem) -> Unit,
        onEdit: (ScheduleItem) -> Unit,
        onConflict: (EventDraftRecovery.Conflict) -> Unit,
    ) {
        scope.launch {
            try {
                when (val recovery = eventStore.recoverEventDraft(event.id)) {
                    is EventDraftRecovery.Recovered -> {
                        if (recovery.stage == EventEditStage.NAMING) {
                            onPending(recovery.event)
                        } else {
                            onEdit(recovery.event)
                        }
                    }
                    is EventDraftRecovery.Conflict -> {
                        onConflict(recovery)
                        Toast.makeText(
                            context,
                            "该事项已在其他设备修改，请选择如何处理已保留的草稿",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    EventDraftRecovery.None -> {
                        val current = eventWriteMutex.withLock {
                            eventStore.beginEventEdit(event)
                        }
                        onEdit(current)
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开事项草稿，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleCompleted(
        events: List<ScheduleItem>,
        eventId: String,
        completed: Boolean,
        onEventsChanged: (List<ScheduleItem>) -> Unit,
    ) {
        val nextEvents = events.map {
            if (it.id == eventId) it.copy(completed = completed, updatedAt = System.currentTimeMillis()) else it
        }
        onEventsChanged(nextEvents)
        nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
            scope.launch {
                eventWriteMutex.withLock { eventStore.save(updated) }
                onEventsChanged(fetchEvents(serverUrl()))
            }
        }
    }

    fun softDelete(
        events: List<ScheduleItem>,
        eventId: String,
        onEventsChanged: (List<ScheduleItem>) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val nextEvents = events.map {
            if (it.id == eventId) it.copy(deletedAt = now, updatedAt = now) else it
        }
        onEventsChanged(nextEvents)
        nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
            scope.launch {
                eventWriteMutex.withLock { eventStore.save(updated) }
                onEventsChanged(fetchEvents(serverUrl()))
            }
        }
    }

    fun deleteFromEditor(
        events: List<ScheduleItem>,
        eventId: String,
        onEventsChanged: (List<ScheduleItem>) -> Unit,
        onFinished: (Boolean) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val storedEvent = events.firstOrNull { it.id == eventId }
        val nextEvents = events.map {
            if (it.id == eventId) it.copy(deletedAt = now, updatedAt = now) else it
        }
        scope.launch {
            try {
                eventWriteMutex.withLock {
                    if (storedEvent != null) {
                        eventStore.save(storedEvent.copy(deletedAt = now, updatedAt = now))
                    }
                    // A detail editor always owns a durable draft. Clear it after the
                    // deletion so a cold start cannot restore a deleted or cancelled item.
                    eventStore.discardEventDraft(eventId)
                }
                onEventsChanged(nextEvents)
                onFinished(true)
            } catch (_: Exception) {
                Toast.makeText(context, "删除失败，事项草稿仍保留在本机", Toast.LENGTH_LONG).show()
                onFinished(false)
            }
        }
    }

    fun changeType(
        events: List<ScheduleItem>,
        eventId: String,
        newType: String,
        onEventsChanged: (List<ScheduleItem>) -> Unit,
    ) {
        val nextEvents = events.map {
            if (it.id == eventId) {
                it.copy(
                    type = newType,
                    completed = if (newType == "task") it.completed else false,
                    updatedAt = System.currentTimeMillis(),
                )
            } else it
        }
        onEventsChanged(nextEvents)
        nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
            scope.launch {
                eventWriteMutex.withLock { eventStore.save(updated) }
                onEventsChanged(fetchEvents(serverUrl()))
            }
        }
    }

    fun resolveConflictSaveAsCopy(
        conflict: EventDraftRecovery.Conflict,
        onRevealNext: () -> Unit,
        onToast: (String) -> Unit,
    ) {
        val event = conflict.event ?: return
        scope.launch {
            try {
                val saved = eventWriteMutex.withLock {
                    eventStore.saveConflictAsCopy(event, conflict.details)
                } != null
                onRevealNext()
                if (saved) onToast("草稿已另存为冲突副本")
            } catch (_: Exception) {
                onToast("另存失败，原草稿仍已保留")
            }
        }
    }

    fun resolveConflictDiscard(
        conflict: EventDraftRecovery.Conflict,
        onRevealNext: () -> Unit,
        onToast: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val discarded = eventWriteMutex.withLock {
                    eventStore.discardEventDraft(
                        conflict.details.target.entityId, conflict.details,
                    )
                }
                onRevealNext()
                if (!discarded) onToast("读取当前版本失败")
            } catch (_: Exception) {
                onToast("读取当前版本失败")
            }
        }
    }

    fun revealNextDraft(
        onPending: (ScheduleItem) -> Unit,
        onEdit: (ScheduleItem) -> Unit,
        onConflict: (EventDraftRecovery.Conflict) -> Unit,
    ) {
        scope.launch {
            when (val next = eventStore.latestEventDraftRecovery()) {
                is EventDraftRecovery.Recovered -> {
                    if (next.stage == EventEditStage.NAMING) onPending(next.event)
                    else onEdit(next.event)
                }
                is EventDraftRecovery.Conflict -> onConflict(next)
                else -> {}
            }
        }
    }
}
