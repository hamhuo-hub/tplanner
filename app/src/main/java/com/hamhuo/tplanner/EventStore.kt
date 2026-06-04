package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CheckItem(val id: String, val text: String, val completed: Boolean)

data class TaskEvent(
    val id: String,
    val title: String,
    val type: String,          // "task" | "event" | "reminder" | "status"
    val start: Instant,
    val end: Instant,
    val completed: Boolean,
    val checklist: List<CheckItem>,
    val colorId: Int,
    val note: String,
    val deletedAt: Long,
)

class EventStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tplanner_events", Context.MODE_PRIVATE)

    fun getAll(): List<TaskEvent> = parse(prefs.getString("events", "[]") ?: "[]")

    fun saveAll(events: List<TaskEvent>) {
        prefs.edit().putString("events", serialize(events)).apply()
    }

    fun fromJson(json: String): List<TaskEvent> = parse(json)

    fun toJson(events: List<TaskEvent>): String = serialize(events)

    private fun parse(json: String): List<TaskEvent> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            try { arr.getJSONObject(i).toEvent() } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }

    private fun serialize(events: List<TaskEvent>): String {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun JSONObject.toEvent(): TaskEvent {
        val checklistArr = optJSONArray("checklist") ?: JSONArray()
        val checklist = (0 until checklistArr.length()).map { i ->
            val o = checklistArr.getJSONObject(i)
            CheckItem(
                id        = o.optString("id", ""),
                text      = o.optString("text", ""),
                completed = o.optBoolean("completed", false)
            )
        }
        return TaskEvent(
            id        = getString("id"),
            title     = optString("title", ""),
            type      = optString("type", "event"),
            start     = Instant.parse(getString("start")),
            end       = Instant.parse(getString("end")),
            completed = optBoolean("completed", false),
            checklist = checklist,
            colorId   = optInt("colorId", 0),
            note      = optString("note", ""),
            deletedAt = optLong("deletedAt", 0L),
        )
    }

    private fun TaskEvent.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("type", type)
        obj.put("start", start.toString())
        obj.put("end", end.toString())
        obj.put("completed", completed)
        obj.put("colorId", colorId)
        obj.put("note", note)
        obj.put("deletedAt", deletedAt)
        val arr = JSONArray()
        checklist.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id); o.put("text", item.text); o.put("completed", item.completed)
            arr.put(o)
        }
        obj.put("checklist", arr)
        return obj
    }
}

fun List<TaskEvent>.forToday(): List<TaskEvent> {
    val today = LocalDate.now()
    val zone  = ZoneId.systemDefault()
    return filter { e ->
        if (e.deletedAt != 0L) return@filter false
        val s = e.start.atZone(zone).toLocalDate()
        val en = e.end.atZone(zone).toLocalDate()
        !s.isAfter(today) && !en.isBefore(today)
    }.sortedBy { it.start }
}
