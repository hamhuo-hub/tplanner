package com.hamhuo.tplanner.syncv3

import com.hamhuo.tplanner.CheckItem
import com.hamhuo.tplanner.ISO_MS
import com.hamhuo.tplanner.ScheduleItem
import org.json.JSONArray
import org.json.JSONObject

/** A semantic command before the repository assigns durable identity and sequence. */
data class SyncCommandDraft(
    val type: SyncCommandType,
    val aggregateId: String?,
    val arguments: JSONObject = JSONObject(),
)

/** Room-only carrier for recurrence fields this phone release does not understand yet. */
internal const val SYNC_V3_RECURRENCE_WIRE_EXTRA = "_syncV3Recurrence"
internal const val SYNC_V3_SCHEDULE_WIRE_EXTRA = "_syncV3Schedule"
internal const val SYNC_V3_ALARM_WIRE_EXTRA = "_syncV3Alarm"
internal const val SYNC_V3_LOCATION_WIRE_EXTRA = "_syncV3Location"

/**
 * Converts phone-domain mutations to the canonical V3 command vocabulary.
 *
 * This is deliberately pure. Room owns atomic persistence; the reducer owns optimistic display;
 * this planner only describes the user's intent. Checklist text is emitted as canonical `title`.
 */
object SyncV3CommandPlanner {
    private val supportedRecurrenceFrequencies = setOf("daily", "weekly", "monthly")
    private val recurrenceKeys = setOf(
        "recurrenceType",
        "recurrenceCount",
        SYNC_V3_RECURRENCE_WIRE_EXTRA,
        SYNC_V3_SCHEDULE_WIRE_EXTRA,
        SYNC_V3_ALARM_WIRE_EXTRA,
        SYNC_V3_LOCATION_WIRE_EXTRA,
    )

    fun taskChange(before: ScheduleItem?, after: ScheduleItem): List<SyncCommandDraft> {
        if (before == null) {
            val active = after.copy(deletedAt = 0L)
            return fullTaskUpsert(active) + if (after.deletedAt != 0L) {
                listOf(draft(SyncCommandType.TASK_DELETE, after.id))
            } else {
                emptyList()
            }
        }

        if (after.deletedAt != 0L) {
            return if (before.deletedAt == 0L) {
                listOf(draft(SyncCommandType.TASK_DELETE, after.id))
            } else {
                emptyList()
            }
        }

        val commands = mutableListOf<SyncCommandDraft>()
        if (before.deletedAt != 0L) commands += draft(SyncCommandType.TASK_RESTORE, after.id)
        if (before.title != after.title) {
            commands += draft(SyncCommandType.TASK_SET_TITLE, after.id, "title" to after.title)
        }
        if (before.note != after.note) {
            commands += draft(SyncCommandType.TASK_SET_NOTE, after.id, "note" to after.note)
        }
        if (before.completed != after.completed) {
            commands += draft(
                SyncCommandType.TASK_SET_COMPLETED,
                after.id,
                "completed" to after.completed,
            )
        }
        if (before.type != after.type) {
            commands += draft(SyncCommandType.TASK_CHANGE_TYPE, after.id, "itemType" to after.type)
        }
        if (before.start != after.start || before.end != after.end) {
            commands += schedule(after)
        }
        if (!jsonEqual(recurrence(before), recurrence(after))) {
            commands += recurrenceCommand(after)
        }
        if (before.alarmEnabled != after.alarmEnabled ||
            before.alarmOffsetMinutes != after.alarmOffsetMinutes
        ) {
            commands += alarm(after)
        }
        if (before.colorId != after.colorId) commands += appearance(after)
        if (before.lat != after.lat || before.lng != after.lng) commands += location(after)
        if (!jsonEqual(extras(before), extras(after))) commands += extrasCommand(after)
        if (before.listId != after.listId) commands += assignList(after)
        commands += checklistChanges(after.id, before.checklist, after.checklist)
        return commands
    }

    /** Full semantic repair used during the one-time V3 bootstrap. */
    fun fullTaskUpsert(event: ScheduleItem): List<SyncCommandDraft> = buildList {
        add(
            draft(
                SyncCommandType.TASK_CREATE,
                event.id,
                "title" to event.title,
                "itemType" to event.type,
            )
        )
        // create is intentionally idempotent. Restore + explicit setters repair a task that
        // already exists centrally (including a stale tombstone) instead of trusting create.
        add(draft(SyncCommandType.TASK_RESTORE, event.id))
        add(draft(SyncCommandType.TASK_SET_TITLE, event.id, "title" to event.title))
        add(draft(SyncCommandType.TASK_CHANGE_TYPE, event.id, "itemType" to event.type))
        add(draft(SyncCommandType.TASK_SET_NOTE, event.id, "note" to event.note))
        add(schedule(event))
        add(draft(SyncCommandType.TASK_SET_COMPLETED, event.id, "completed" to event.completed))
        add(recurrenceCommand(event))
        add(alarm(event))
        add(appearance(event))
        add(location(event))
        add(extrasCommand(event))
        add(assignList(event))
        event.checklist.forEach { item ->
            add(createChecklistItem(event.id, item))
            add(
                draft(
                    SyncCommandType.CHECKLIST_SET_TITLE,
                    event.id,
                    "checklistItemId" to item.id,
                    "title" to item.text,
                )
            )
            add(
                draft(
                    SyncCommandType.CHECKLIST_SET_COMPLETED,
                    event.id,
                    "checklistItemId" to item.id,
                    "completed" to item.completed,
                )
            )
        }
        // Moving from the tail towards the head establishes the exact requested order even when
        // the task already existed with the same checklist items in a different order.
        event.checklist.indices.reversed().forEach { index ->
            add(
                draft(
                    SyncCommandType.CHECKLIST_REORDER_ITEM,
                    event.id,
                    "checklistItemId" to event.checklist[index].id,
                    "beforeItemId" to event.checklist.getOrNull(index + 1)?.id,
                )
            )
        }
        if (event.deletedAt != 0L) add(draft(SyncCommandType.TASK_DELETE, event.id))
    }

    /**
     * Central-aware one-time repair. Existing non-default central fields always win. Commands
     * carry reducer guards so a central edit racing the bootstrap also wins at apply time.
     */
    fun bootstrapTaskRepair(
        event: ScheduleItem,
        authoritative: JSONObject?,
        canAssignLocalList: Boolean,
    ): List<SyncCommandDraft> = buildList {
        if (event.deletedAt != 0L) return@buildList
        if (authoritative == null) {
            addAll(fullTaskUpsert(event))
            return@buildList
        }
        if (authoritative.optString("lifecycle", "active") != "active") return@buildList

        val localSchedule = scheduleValue(event)
        if (authoritative.isNull("schedule") && localSchedule != null) {
            add(draft(
                SyncCommandType.TASK_SET_SCHEDULE,
                event.id,
                "schedule" to localSchedule,
                "ifMissing" to true,
            ))
        }

        val localRecurrence = recurrence(event)
        if (authoritative.isNull("recurrence") && localRecurrence != null) {
            add(draft(
                SyncCommandType.TASK_SET_RECURRENCE,
                event.id,
                "recurrence" to localRecurrence,
                "ifMissing" to true,
            ))
        }

        val remoteAlarm = authoritative.optJSONObject("alarm") ?: JSONObject()
        val localAlarm = alarmObject(event)
        val alarmIsCanonicalDefault = !remoteAlarm.optBoolean("enabled", false) &&
            remoteAlarm.optInt("offsetMinutes", 0) == 0 && remoteAlarm.length() <= 2
        if (alarmIsCanonicalDefault && !jsonEqual(remoteAlarm, localAlarm)) {
            localAlarm.put("ifMissing", true)
            add(SyncCommandDraft(SyncCommandType.TASK_SET_ALARM, event.id, localAlarm))
        }

        val remoteLocation = authoritative.optJSONObject("location") ?: JSONObject()
        val localLocation = locationObject(event)
        val locationIsCanonicalDefault = remoteLocation.isNull("lat") &&
            remoteLocation.isNull("lng") && remoteLocation.length() <= 2
        if (locationIsCanonicalDefault && !jsonEqual(remoteLocation, localLocation)) {
            localLocation.put("ifMissing", true)
            add(SyncCommandDraft(SyncCommandType.TASK_SET_LOCATION, event.id, localLocation))
        }

        val remoteExtras = authoritative.optJSONObject("extras") ?: JSONObject()
        val missingExtras = JSONObject()
        val localExtras = extras(event)
        localExtras.keys().forEach { key ->
            if (!remoteExtras.has(key)) missingExtras.put(key, localExtras.get(key))
        }
        if (missingExtras.length() > 0) {
            add(draft(
                SyncCommandType.TASK_SET_EXTRAS,
                event.id,
                "extras" to missingExtras,
                "mergeMissing" to true,
            ))
        }

        if (authoritative.isNull("listId") && canAssignLocalList) {
            add(draft(
                SyncCommandType.TASK_ASSIGN_LIST,
                event.id,
                "listId" to event.listId,
                "ifUnassigned" to true,
            ))
        }

        val remoteChecklist = authoritative.optJSONArray("checklist") ?: JSONArray()
        val remoteIds = (0 until remoteChecklist.length()).mapNotNull { index ->
            remoteChecklist.optJSONObject(index)?.optString("id")?.takeIf(String::isNotEmpty)
        }.toSet()
        event.checklist.filter { it.id !in remoteIds }.forEach { item ->
            add(createChecklistItem(event.id, item))
            if (item.completed) {
                add(draft(
                    SyncCommandType.CHECKLIST_SET_COMPLETED,
                    event.id,
                    "checklistItemId" to item.id,
                    "completed" to true,
                ))
            }
        }
    }

    fun listCreate(id: String, title: String): SyncCommandDraft = draft(
        SyncCommandType.LIST_CREATE,
        id,
        "title" to title,
        "color" to null,
    )

    fun listRename(id: String, title: String): SyncCommandDraft =
        draft(SyncCommandType.LIST_RENAME, id, "title" to title)

    fun listDelete(id: String): SyncCommandDraft = draft(SyncCommandType.LIST_DELETE, id)

    fun journalSetText(date: String, text: String, ifMissing: Boolean = false): SyncCommandDraft =
        draft(
            SyncCommandType.JOURNAL_SET_TEXT,
            date,
            "text" to text,
            *if (ifMissing) arrayOf("ifMissing" to true) else emptyArray(),
        )

    fun journalDelete(date: String): SyncCommandDraft = draft(SyncCommandType.JOURNAL_DELETE, date)

    private fun checklistChanges(
        taskId: String,
        before: List<CheckItem>,
        after: List<CheckItem>,
    ): List<SyncCommandDraft> = buildList {
        val beforeById = before.associateBy(CheckItem::id)
        val afterById = after.associateBy(CheckItem::id)

        before.filter { it.id !in afterById }.forEach { item ->
            add(
                draft(
                    SyncCommandType.CHECKLIST_DELETE_ITEM,
                    taskId,
                    "checklistItemId" to item.id,
                )
            )
        }
        after.forEach { item ->
            val old = beforeById[item.id]
            if (old == null) {
                add(createChecklistItem(taskId, item))
                if (item.completed) {
                    add(
                        draft(
                            SyncCommandType.CHECKLIST_SET_COMPLETED,
                            taskId,
                            "checklistItemId" to item.id,
                            "completed" to true,
                        )
                    )
                }
            } else {
                if (old.text != item.text) {
                    add(
                        draft(
                            SyncCommandType.CHECKLIST_SET_TITLE,
                            taskId,
                            "checklistItemId" to item.id,
                            "title" to item.text,
                        )
                    )
                }
                if (old.completed != item.completed) {
                    add(
                        draft(
                            SyncCommandType.CHECKLIST_SET_COMPLETED,
                            taskId,
                            "checklistItemId" to item.id,
                            "completed" to item.completed,
                        )
                    )
                }
            }
        }

        // New items are appended by create. Transform that intermediate order to the requested
        // order with the protocol's stable "move before token" operation.
        val current = before.map(CheckItem::id)
            .filter { it in afterById }
            .toMutableList()
            .apply { after.map(CheckItem::id).filterNot(::contains).forEach(::add) }
        after.map(CheckItem::id).forEachIndexed { targetIndex, itemId ->
            val currentIndex = current.indexOf(itemId)
            if (currentIndex == targetIndex || currentIndex < 0) return@forEachIndexed
            current.removeAt(currentIndex)
            val beforeId = current.getOrNull(targetIndex)
            add(
                draft(
                    SyncCommandType.CHECKLIST_REORDER_ITEM,
                    taskId,
                    "checklistItemId" to itemId,
                    "beforeItemId" to beforeId,
                )
            )
            if (beforeId == null) current.add(itemId) else current.add(targetIndex, itemId)
        }
    }

    private fun createChecklistItem(taskId: String, item: CheckItem): SyncCommandDraft = draft(
        SyncCommandType.CHECKLIST_CREATE_ITEM,
        taskId,
        "checklistItemId" to item.id,
        "title" to item.text,
    )

    private fun schedule(event: ScheduleItem): SyncCommandDraft = draft(
        SyncCommandType.TASK_SET_SCHEDULE,
        event.id,
        "schedule" to scheduleValue(event),
    )

    private fun scheduleValue(event: ScheduleItem): JSONObject? {
        val raw = event.extras[SYNC_V3_SCHEDULE_WIRE_EXTRA]
        if (raw === JSONObject.NULL &&
            event.start == java.time.Instant.EPOCH && event.end == java.time.Instant.EPOCH
        ) {
            return null
        }
        return (preservedObject(raw) ?: JSONObject())
            .put("startAt", ISO_MS.format(event.start))
            .put("endAt", ISO_MS.format(event.end))
    }

    private fun recurrenceCommand(event: ScheduleItem): SyncCommandDraft = draft(
        SyncCommandType.TASK_SET_RECURRENCE,
        event.id,
        "recurrence" to recurrence(event),
    )

    private fun recurrence(event: ScheduleItem): JSONObject? {
        val preserved = preservedObject(event.extras[SYNC_V3_RECURRENCE_WIRE_EXTRA])
        val frequency = event.extras["recurrenceType"]?.toString()?.lowercase().orEmpty()
        if (frequency.isEmpty()) {
            // Unsupported future frequencies are invisible to today's editor but must survive
            // unrelated edits and a device-identity re-bootstrap. A supported recurrence whose
            // UI keys were removed was explicitly cleared and therefore becomes null.
            return preserved?.takeIf {
                it.optString("frequency").lowercase() !in supportedRecurrenceFrequencies
            }
        }
        if (frequency !in supportedRecurrenceFrequencies) {
            return preserved?.takeIf { it.optString("frequency").equals(frequency, true) }
        }
        val rawCount = event.extras["recurrenceCount"]
        val count = when (rawCount) {
            is Number -> rawCount.toInt()
            else -> rawCount?.toString()?.toIntOrNull() ?: 1
        }.coerceAtLeast(1)
        return (preserved ?: JSONObject())
            .put("frequency", frequency)
            .put("count", count)
    }

    private fun preservedObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is Map<*, *> -> jsonValue(value) as JSONObject
        is String -> runCatching(::JSONObject).getOrNull()
        else -> null
    }

    private fun alarm(event: ScheduleItem): SyncCommandDraft {
        return SyncCommandDraft(SyncCommandType.TASK_SET_ALARM, event.id, alarmObject(event))
    }

    private fun alarmObject(event: ScheduleItem): JSONObject =
        (preservedObject(event.extras[SYNC_V3_ALARM_WIRE_EXTRA]) ?: JSONObject())
            .put("enabled", event.alarmEnabled)
            .put("offsetMinutes", event.alarmOffsetMinutes)

    private fun appearance(event: ScheduleItem): SyncCommandDraft = draft(
        SyncCommandType.TASK_SET_APPEARANCE,
        event.id,
        "colorId" to event.colorId.coerceAtLeast(0),
    )

    private fun location(event: ScheduleItem): SyncCommandDraft {
        return SyncCommandDraft(SyncCommandType.TASK_SET_LOCATION, event.id, locationObject(event))
    }

    private fun locationObject(event: ScheduleItem): JSONObject {
        val preserved = preservedObject(event.extras[SYNC_V3_LOCATION_WIRE_EXTRA])
        val unchangedProjection = preserved != null &&
            (preserved.coordinate("lat") ?: 0.0) == event.lat &&
            (preserved.coordinate("lng") ?: 0.0) == event.lng
        val location = if (unchangedProjection) {
            preserved
        } else {
            val absent = event.lat == 0.0 && event.lng == 0.0
            (preserved ?: JSONObject())
                .put("lat", if (absent) JSONObject.NULL else event.lat)
                .put("lng", if (absent) JSONObject.NULL else event.lng)
        }
        return location
    }

    private fun JSONObject.coordinate(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun extrasCommand(event: ScheduleItem): SyncCommandDraft = draft(
        SyncCommandType.TASK_SET_EXTRAS,
        event.id,
        "extras" to extras(event),
    )

    private fun extras(event: ScheduleItem): JSONObject = JSONObject().apply {
        event.extras.forEach { (key, value) ->
            if (key !in recurrenceKeys && key != "groupId") put(key, jsonValue(value))
        }
    }

    private fun assignList(event: ScheduleItem): SyncCommandDraft = draft(
        SyncCommandType.TASK_ASSIGN_LIST,
        event.id,
        "listId" to event.listId.takeIf(String::isNotEmpty),
    )

    private fun draft(
        type: SyncCommandType,
        aggregateId: String,
        vararg arguments: Pair<String, Any?>,
    ): SyncCommandDraft = SyncCommandDraft(
        type = type,
        aggregateId = aggregateId,
        arguments = JSONObject().apply {
            arguments.forEach { (key, value) -> put(key, jsonValue(value)) }
        },
    )

    private fun jsonEqual(left: JSONObject?, right: JSONObject?): Boolean = when {
        left == null || right == null -> left == null && right == null
        else -> Jcs.canonicalize(left) == Jcs.canonicalize(right)
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) -> if (key is String) put(key, jsonValue(item)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        else -> value.toString()
    }
}
