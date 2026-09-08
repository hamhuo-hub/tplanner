package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv3.LocalReducer
import com.hamhuo.tplanner.syncv3.SyncCommand
import com.hamhuo.tplanner.syncv3.SyncCommandType
import com.hamhuo.tplanner.syncv3.SyncV3CommandPlanner
import com.hamhuo.tplanner.syncv3.SyncV3ProjectionCodec
import org.json.JSONObject
import java.time.Instant

/** Standalone assertions against production codecs; run with scripts/check-recurring-series-sync.py. */
fun main() {
    val recurrence = JSONObject()
        .put("frequency", "monthly").put("count", 5)
        .put("seriesId", "series-root").put("occurrenceIndex", 2)
        .put("anchorStartAt", "2026-01-31T01:00:00.000Z")
        .put("anchorEndAt", "2026-01-31T02:00:00.000Z")
        .put("timeZone", "Asia/Shanghai")
        .put("futureExtension", JSONObject().put("preserve", true))
    val event = ScheduleItem(
        id = "occurrence-2", title = "Series member", type = "task",
        start = Instant.parse("2026-03-31T01:00:00Z"), end = Instant.parse("2026-03-31T02:00:00Z"),
        completed = false, checklist = emptyList(), colorId = 0, note = "", deletedAt = 0,
        extras = mapOf(
            "_syncV3Recurrence" to recurrence,
            "recurrenceType" to "monthly", "recurrenceCount" to 5,
        ),
    )
    var state = LocalReducer.emptyState()
    SyncV3CommandPlanner.fullTaskUpsert(event).forEachIndexed { index, draft ->
        val result = LocalReducer.apply(state, SyncCommand(
            "smoke-$index", index + 1L, draft.type, draft.aggregateId, draft.arguments,
        ), index + 1L)
        check(result.receipt.status in setOf("APPLIED", "NOOP")) { result.receipt }
        state = result.state
    }
    val canonical = LocalReducer.toJson(state)
    SyncV3ProjectionCodec.validateAuthoritativeState(canonical)
    val projected = SyncV3ProjectionCodec.project(canonical).events.single()
    val projectedWire = projected.extras["_syncV3Recurrence"] as JSONObject
    check(projectedWire.similar(recurrence)) { "Projection lost recurrence extensions" }
    val unrelated = SyncV3CommandPlanner.taskChange(projected, projected.copy(title = "Renamed"))
    check(unrelated.size == 1 && unrelated.single().type == SyncCommandType.TASK_SET_TITLE)
    val edited = projected.copy(extras = projected.extras + ("recurrenceCount" to 6))
    val nextWire = SyncV3CommandPlanner.taskChange(projected, edited).single()
        .arguments.getJSONObject("recurrence")
    check(nextWire.similar(JSONObject(recurrence.toString()).put("count", 6)))
    check(recurrence.getInt("count") == 5) { "Planner mutated preserved source JSON" }
    val cleared = projected.copy(extras = projected.extras - "recurrenceType" - "recurrenceCount")
    check(SyncV3CommandPlanner.taskChange(projected, cleared).single().arguments.isNull("recurrence"))
    val unsupported = JSONObject(recurrence.toString()).put("frequency", "future-rule")
    val foreign = projected.copy(extras = mapOf("_syncV3Recurrence" to unsupported))
    val foreignWire = SyncV3CommandPlanner.fullTaskUpsert(foreign)
        .single { it.type == SyncCommandType.TASK_SET_RECURRENCE }.arguments.getJSONObject("recurrence")
    check(foreignWire.similar(unsupported))
    println("PASS V3 recurrence projection, unrelated edit, known-field merge, explicit clear, future extension")

    val series = WatchTaskSeriesMetadata("series-root", 2)
    val taskJson = JSONObject().put("id", "occurrence-2")
    WatchTaskSeriesCodec.write(taskJson, series)
    check(WatchTaskSeriesCodec.read(JSONObject(taskJson.toString())) == series)
    check(WatchTaskSeriesCodec.read(JSONObject().put("id", "legacy")) == null)
    val binding = listOf("occurrence-2" to series, "ordinary" to null)
    val digest = WatchTaskSeriesCodec.hash(binding)
    check(digest == WatchTaskSeriesCodec.hash(binding.reversed()))
    check(digest != WatchTaskSeriesCodec.hash(listOf("occurrence-2" to series.copy(occurrenceIndex = 3), "ordinary" to null)))
    check(digest != WatchTaskSeriesCodec.hash(listOf("ordinary" to series, "occurrence-2" to null)))
    check(digest != WatchTaskSeriesCodec.hash(listOf("occurrence-2" to null, "ordinary" to null)))
    listOf(
        JSONObject().put("seriesId", "root"),
        JSONObject().put("seriesId", "").put("occurrenceIndex", 0),
        JSONObject().put("seriesId", "root").put("occurrenceIndex", -1),
        JSONObject().put("seriesId", "root").put("occurrenceIndex", 1.5),
        JSONObject().put("seriesId", "root").put("occurrenceIndex", "1"),
    ).forEach { malformed -> check(runCatching { WatchTaskSeriesCodec.read(malformed) }.isFailure) }
    println("PASS Watch metadata JSON, legacy omission, order-independent hash, changed binding and malformed data")

    fun task(id: String, start: Long, index: Int? = null, type: String = "task", end: Long = start + 10) =
        WatchEventMarks.NextTask(id, "Same title", type, start, end, "", index?.let { WatchTaskSeriesMetadata("root", it) })
    val past = task("past", 10, 0)
    val today = task("today", 110, 1)
    val future = task("future", 210, 2)
    val ordinary = task("ordinary", 115)
    val otherType = task("reminder", 116, 0, "event")
    val all = listOf(future, ordinary, today, past, otherType)
    check(collapseWatchTaskSeries(all).map { it.id }.toSet() == setOf("past", "ordinary", "reminder"))
    check(collapseWatchTaskSeries(all.filterNot { it.id == "past" }).map { it.id }.toSet() == setOf("today", "ordinary", "reminder"))
    val day = all.filter { watchTaskFallsInWindow(it, 100, 200) }
    check(collapseWatchTaskSeries(day).map { it.id }.toSet() == setOf("today", "ordinary", "reminder"))
    check(all.size == 5) { "List projection changed the timeline dataset" }
    check(watchTaskFallsInWindow(task("midnight", 100, end = 100), 100, 200))
    check(!watchTaskFallsInWindow(task("tomorrow", 200, end = 200), 100, 200))
    check(!watchTaskFallsInWindow(task("ended", 90, end = 100), 100, 200))
    println("PASS Wear list-only collapse, local-delete advance, filter-first selection, same-title independence, point boundaries")
}
