package com.hamhuo.tplanner

import org.json.JSONObject
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal const val MAX_TASK_RECURRENCE_COUNT = 50
private const val RECURRENCE_WIRE = "_syncV3Recurrence"
private const val RETIRED_LEDGER = "_retiredRecurringOccurrenceIds"
private val frequencies = setOf("daily", "weekly", "monthly")

internal data class RecurringTaskSeries(
    val seriesId: String,
    val occurrenceIndex: Int,
    val frequency: String,
    val count: Int,
    val anchorStart: Instant,
    val anchorEnd: Instant,
    val timeZone: ZoneId,
    val retiredOccurrenceIds: Set<String> = emptySet(),
)

private fun stringIds(value: Any?): Set<String> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { (value.opt(it) as? String)?.takeIf(String::isNotBlank) }.toSet()
    is Collection<*> -> value.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }.toSet()
    else -> emptySet()
}

private fun retiredLedger(event: ScheduleItem): Set<String> = stringIds(event.extras[RETIRED_LEDGER])

private fun recurrenceWire(event: ScheduleItem): JSONObject? = when (val value = event.extras[RECURRENCE_WIRE]) {
    is JSONObject -> JSONObject(value.toString())
    is Map<*, *> -> JSONObject(value)
    is String -> runCatching { JSONObject(value) }.getOrNull()
    else -> null
}

private fun frequency(event: ScheduleItem): String = event.extras["recurrenceType"]
    ?.toString()?.lowercase(Locale.ROOT)?.takeIf { it in frequencies }.orEmpty()

private fun count(event: ScheduleItem): Int = when (val value = event.extras["recurrenceCount"]) {
    is Number -> value.toInt()
    else -> value?.toString()?.toIntOrNull() ?: 1
}.coerceIn(1, MAX_TASK_RECURRENCE_COUNT)

internal fun recurringSeriesMetadata(event: ScheduleItem): RecurringTaskSeries? = runCatching {
    val wire = recurrenceWire(event) ?: return null
    val id = (wire.opt("seriesId") as? String)?.takeIf { it.isNotBlank() } ?: return null
    fun integer(key: String): Int? {
        val number = (wire.opt(key) as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
    }
    val index = integer("occurrenceIndex")?.takeIf { it in 0 until MAX_TASK_RECURRENCE_COUNT } ?: return null
    val total = integer("count")?.takeIf { it in 1..MAX_TASK_RECURRENCE_COUNT } ?: return null
    val kind = wire.optString("frequency").lowercase(Locale.ROOT).takeIf { it in frequencies } ?: return null
    RecurringTaskSeries(
        id, index, kind, total,
        Instant.parse(wire.getString("anchorStartAt")), Instant.parse(wire.getString("anchorEndAt")),
        ZoneId.of(wire.optString("timeZone", APP_TIME_ZONE_ID)),
        stringIds(wire.opt("retiredOccurrenceIds")),
    )
}.getOrNull()

private fun withSeries(event: ScheduleItem, series: RecurringTaskSeries): ScheduleItem {
    val wire = (recurrenceWire(event) ?: JSONObject())
        .put("frequency", series.frequency).put("count", series.count)
        .put("seriesId", series.seriesId).put("occurrenceIndex", series.occurrenceIndex)
        .put("anchorStartAt", series.anchorStart.toString()).put("anchorEndAt", series.anchorEnd.toString())
        .put("timeZone", series.timeZone.id)
    if (series.retiredOccurrenceIds.isNotEmpty() || wire.has("retiredOccurrenceIds")) {
        wire.put("retiredOccurrenceIds", JSONArray(series.retiredOccurrenceIds.sorted()))
    }
    return event.copy(extras = (event.extras - "groupId") + mapOf(
        "recurrenceType" to series.frequency, "recurrenceCount" to series.count, RECURRENCE_WIRE to wire,
    ))
}

internal fun withoutRecurringTaskSeries(event: ScheduleItem, additionallyRetired: Set<String> = emptySet()): ScheduleItem {
    val retired = retiredLedger(event) + stringIds(recurrenceWire(event)?.opt("retiredOccurrenceIds")) + additionallyRetired
    val extras = (event.extras - setOf("groupId", "recurrenceType", "recurrenceCount", RECURRENCE_WIRE)).toMutableMap()
    if (retired.isNotEmpty()) extras[RETIRED_LEDGER] = JSONArray(retired.sorted())
    return event.copy(extras = extras)
}

private fun occurrenceId(seriesId: String, index: Int, after: String? = null): String = UUID.nameUUIDFromBytes(
    "$seriesId:recurrence:$index${after?.let { ":after:$it" }.orEmpty()}".toByteArray(StandardCharsets.UTF_8),
).toString()

private fun shift(value: Instant, frequency: String, index: Int, zone: ZoneId): Instant {
    val local = value.atZone(zone)
    return when (frequency) {
        "daily" -> local.plusDays(index.toLong())
        "weekly" -> local.plusWeeks(index.toLong())
        else -> local.plusMonths(index.toLong())
    }.toInstant()
}

/** Read-only repair: legacy Android UUIDs are evidence; matching titles or dates never are. */
internal fun recoverRecurringTaskSeries(items: List<ScheduleItem>): List<ScheduleItem> {
    val byId = items.associateBy { it.id }
    val recovered = mutableMapOf<String, RecurringTaskSeries>()
    items.filter {
        val metadata = recurringSeriesMetadata(it)
        it.type == "task" && frequency(it).isNotEmpty() &&
            (metadata == null || metadata.occurrenceIndex == 0 && metadata.seriesId == it.id)
    }
        .forEach { root ->
            val memberIds = (1 until MAX_TASK_RECURRENCE_COUNT).mapNotNull { index ->
                byId[occurrenceId(root.id, index)]?.takeIf {
                    it.type == "task" && recurringSeriesMetadata(it) == null
                }?.let { it.id to index }
            }
            if (memberIds.isEmpty()) return@forEach
            val series = recurringSeriesMetadata(root) ?: RecurringTaskSeries(root.id, 0, frequency(root), count(root), root.start, root.end,
                runCatching { ZoneId.of(root.extras["timezone"]?.toString() ?: APP_TIME_ZONE_ID) }.getOrDefault(APP_ZONE))
            recovered.putIfAbsent(root.id, series)
            memberIds.forEach { (id, index) -> recovered.putIfAbsent(id, series.copy(occurrenceIndex = index)) }
        }
    return items.map { event -> recovered[event.id]?.let { withSeries(event, it) } ?: event }
}

/** Call after the view/date filter. The underlying facts and timeline retain every occurrence. */
internal fun collapseRecurringTaskSeries(items: List<ScheduleItem>): List<ScheduleItem> {
    val groups = items.groupBy { event ->
        val seriesId = if (event.type == "task") recurringSeriesMetadata(event)?.seriesId else null
        (seriesId != null) to (seriesId ?: event.id)
    }
    return groups.values.map { members ->
        members.filter { !it.completed }.minWithOrNull(compareBy<ScheduleItem> { it.start }.thenBy { it.id })
            ?: members.maxWithOrNull(compareBy<ScheduleItem> { it.start }.thenBy { it.id })!!
    }
}

private fun checklistContent(items: List<CheckItem>) = items.map { it.id to it.text }

/** Apply only changed fields to the latest record, retaining each occurrence's progress. */
private fun mergeContent(latest: ScheduleItem, before: ScheduleItem, after: ScheduleItem, own: Boolean): ScheduleItem {
    val checklistChanged = checklistContent(before.checklist) != checklistContent(after.checklist)
    val targetById = latest.checklist.associateBy { it.id }
    val sourceById = before.checklist.associateBy { it.id }
    val matchedIds = mutableSetOf<String>()
    val checklist = if (checklistChanged) {
        after.checklist.map { editedItem ->
            val old = sourceById[editedItem.id]
            val previousIndex = before.checklist.indexOfFirst { it.id == editedItem.id }
            val current = targetById[editedItem.id] ?: latest.checklist.getOrNull(previousIndex)
            current?.let { matchedIds += it.id }
            CheckItem(
                current?.id ?: editedItem.id,
                if (old == null || old.text != editedItem.text) editedItem.text else current?.text ?: editedItem.text,
                if (own && (old == null || old.completed != editedItem.completed)) editedItem.completed else current?.completed ?: false,
            )
        } + latest.checklist.filter { item ->
            // Preserve independently added sibling rows, not rows explicitly removed in this edit.
            item.id !in matchedIds && item.id !in sourceById && latest.checklist.indexOf(item) >= before.checklist.size
        }
    } else if (own) {
        latest.checklist.map { item ->
            val old = sourceById[item.id]
            val edited = after.checklist.firstOrNull { it.id == item.id }
            if (old != null && edited != null && old.completed != edited.completed) item.copy(completed = edited.completed) else item
        }
    } else latest.checklist
    return latest.copy(
        title = if (before.title != after.title) after.title else latest.title,
        note = if (before.note != after.note) after.note else latest.note,
        type = if (before.type != after.type) after.type else latest.type,
        colorId = if (before.colorId != after.colorId) after.colorId else latest.colorId,
        listId = if (before.listId != after.listId) after.listId else latest.listId,
        alarmEnabled = if (before.alarmEnabled != after.alarmEnabled) after.alarmEnabled else latest.alarmEnabled,
        alarmOffsetMinutes = if (before.alarmOffsetMinutes != after.alarmOffsetMinutes) after.alarmOffsetMinutes else latest.alarmOffsetMinutes,
        lat = if (before.lat != after.lat) after.lat else latest.lat,
        lng = if (before.lng != after.lng) after.lng else latest.lng,
        completed = if (own && before.completed != after.completed) after.completed else latest.completed,
        checklist = checklist,
    )
}

/** Pure plan; facts must be read inside the same Room write transaction that applies this plan. */
internal fun planRecurringTaskChange(
    facts: List<ScheduleItem>, before: ScheduleItem?, edited: ScheduleItem, now: Long,
): List<ScheduleItem> {
    val normalized = recoverRecurringTaskSeries(facts)
    val latest = normalized.firstOrNull { it.id == edited.id }
    val original = before ?: latest ?: edited
    val source = if (latest == null) edited else mergeContent(latest, original, edited, own = true).copy(
        start = if (original.start != edited.start) edited.start else latest.start,
        end = if (original.end != edited.end) edited.end else latest.end,
        deletedAt = if (original.deletedAt != edited.deletedAt) edited.deletedAt else latest.deletedAt,
        extras = latest.extras.toMutableMap().apply {
            (original.extras.keys + edited.extras.keys).forEach { key ->
                if (original.extras[key]?.toString() != edited.extras[key]?.toString()) {
                    if (key in edited.extras) put(key, edited.extras[key]) else remove(key)
                }
            }
        },
    )
    fun changed(event: ScheduleItem) = event.copy(updatedAt = maxOf(now, event.updatedAt + 1L))
    // Single deletion never expands the series or revives an existing tombstone.
    if (latest != null && latest.deletedAt != 0L) return emptyList()
    val previous = latest?.let(::recurringSeriesMetadata)
    if (source.deletedAt != 0L) {
        if (previous == null) return listOf(changed(source))
        val members = normalized.filter { recurringSeriesMetadata(it)?.seriesId == previous.seriesId }
        val retired = members.flatMap { recurringSeriesMetadata(it)?.retiredOccurrenceIds.orEmpty() + retiredLedger(it) }.toSet() +
            members.filter { it.deletedAt != 0L }.map { it.id } + source.id
        return members.filter { it.deletedAt == 0L }.map { member ->
            changed(withSeries(if (member.id == source.id) source else member,
                recurringSeriesMetadata(member)!!.copy(retiredOccurrenceIds = retired)))
        }
    }
    val kind = frequency(source)
    if (previous != null && (source.type != "task" || kind.isEmpty())) {
        val members = normalized.filter { recurringSeriesMetadata(it)?.seriesId == previous.seriesId }
        // The ledger survives recurrence=null without participating in series grouping. A later
        // re-enable cannot reuse a deleted ID after the local tombstone has been garbage-collected.
        val retired = members.flatMap { recurringSeriesMetadata(it)?.retiredOccurrenceIds.orEmpty() + retiredLedger(it) }.toSet() + members.map { it.id }
        return members.filter { it.deletedAt == 0L }
            .map { member ->
                changed(withoutRecurringTaskSeries(when {
                    member.id == source.id -> source
                    member.completed -> member
                    else -> member.copy(deletedAt = now)
                }, retired))
            }
    }
    if (source.type != "task" || kind.isEmpty()) return listOf(changed(source))
    // Historical Web instances used random IDs and supplied no recoverable series identity.
    // A title edit (or Save without changes) is not permission to expand that old hint again.
    if (previous == null && latest != null && frequency(original) == kind && count(original) == count(source)) {
        return listOf(changed(source))
    }

    val zone = previous?.timeZone ?: runCatching {
        ZoneId.of(source.extras["timezone"]?.toString() ?: APP_TIME_ZONE_ID)
    }.getOrDefault(APP_ZONE)
    val oldSeries = previous ?: RecurringTaskSeries(source.id, 0, kind, count(source), source.start, source.end, zone)
    val newCount = count(source)
    val members = normalized.filter { recurringSeriesMetadata(it)?.seriesId == oldSeries.seriesId }.ifEmpty { listOf(source) }
    val retired = members.flatMap { recurringSeriesMetadata(it)?.retiredOccurrenceIds.orEmpty() + retiredLedger(it) }.toSet() +
        retiredLedger(source) + members.filter {
            it.deletedAt != 0L || ((recurringSeriesMetadata(it)?.occurrenceIndex ?: 0) >= newCount &&
                !(if (it.id == source.id) source.completed else it.completed))
        }.map { it.id }
    val scheduleChanged = original.start != edited.start || original.end != edited.end
    fun adjustedAnchor(anchor: Instant, oldValue: Instant, newValue: Instant): Instant =
        anchor.atZone(zone).toLocalDateTime().plus(Duration.between(oldValue.atZone(zone).toLocalDateTime(), newValue.atZone(zone).toLocalDateTime()))
            .atZone(zone).toInstant()
    val series = oldSeries.copy(
        frequency = kind, count = newCount, retiredOccurrenceIds = retired,
        anchorStart = when {
            previous != null && oldSeries.frequency != kind -> shift(source.start, kind, -oldSeries.occurrenceIndex, zone)
            previous != null && original.start != edited.start -> adjustedAnchor(oldSeries.anchorStart, original.start, edited.start)
            else -> oldSeries.anchorStart
        },
        anchorEnd = when {
            previous != null && oldSeries.frequency != kind -> shift(source.end, kind, -oldSeries.occurrenceIndex, zone)
            previous != null && original.end != edited.end -> adjustedAnchor(oldSeries.anchorEnd, original.end, edited.end)
            else -> oldSeries.anchorEnd
        },
    )
    val result = mutableListOf<ScheduleItem>()
    members.filter { it.deletedAt == 0L }.forEach { member ->
        val index = recurringSeriesMetadata(member)?.occurrenceIndex ?: 0
        var next = if (member.id == source.id) source else mergeContent(member, original, edited, own = false)
        if (index >= newCount) {
            if (!next.completed) next = next.copy(deletedAt = now)
        } else if (previous != null && member.id != source.id && !member.completed &&
            (scheduleChanged || oldSeries.frequency != kind)) {
            next = next.copy(start = shift(series.anchorStart, kind, index, zone), end = shift(series.anchorEnd, kind, index, zone))
        }
        result += changed(withSeries(next, series.copy(occurrenceIndex = index)))
    }
    // Unrelated edits do not recreate deleted instances. Extension can replace a truncated slot
    // using a new deterministic ID; the old tombstone remains untouched.
    val firstMissing = if (previous == null) 0 else oldSeries.count
    for (index in firstMissing until newCount) {
        if (members.any { it.deletedAt == 0L && (recurringSeriesMetadata(it)?.occurrenceIndex ?: 0) == index }) continue
        var id = if (index == 0) series.seriesId else occurrenceId(series.seriesId, index)
        val occupied = (facts + result).map { it.id }.toSet() + retired
        while (id in occupied) id = occurrenceId(series.seriesId, index, id)
        val next = source.copy(
            id = id, start = shift(series.anchorStart, kind, index, zone), end = shift(series.anchorEnd, kind, index, zone),
            completed = false, checklist = source.checklist.map { it.copy(completed = false) }, deletedAt = 0L,
        )
        result += changed(withSeries(next, series.copy(occurrenceIndex = index)))
    }
    return result
}
