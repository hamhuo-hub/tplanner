package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    val updatedAt: Long = 0L,
    // 桌面端/服务器的其余字段（timezone/groupId/recurrence* 等）原样透传。
    // 安卓端不理解的字段不代表可以丢弃——早先回写时丢字段 + 时间戳丢毫秒，
    // 会把服务器上的副本改写成与桌面端"内容相同但字节不同"的形态，导致
    // 桌面端同步预览里同一批事件反复出现、永不收敛。
    val extras: Map<String, Any?> = emptyMap(),
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
        val extras = mutableMapOf<String, Any?>()
        keys().forEach { k -> if (k !in KNOWN_KEYS) extras[k] = get(k) }
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
            updatedAt = optLong("updatedAt", 0L),
            extras    = extras,
        )
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            "id", "title", "type", "start", "end", "completed",
            "checklist", "colorId", "note", "deletedAt", "updatedAt",
        )
    }
}

internal val ISO_MS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

// 与桌面端 Date.toISOString() 逐字一致：恒带毫秒的 UTC ISO。
// Instant.toString() 在毫秒为零时会省略小数部分，两端字节不一致
// 会被同步比较判为"内容不同"，是同步永不收敛的根源之一。
internal fun TaskEvent.toJson(): JSONObject {
    val obj = JSONObject()
    extras.forEach { (k, v) -> obj.put(k, v) }
    obj.put("id", id)
    obj.put("title", title)
    obj.put("type", type)
    obj.put("start", ISO_MS.format(start))
    obj.put("end", ISO_MS.format(end))
    obj.put("completed", completed)
    obj.put("colorId", colorId)
    obj.put("note", note)
    obj.put("deletedAt", deletedAt)
    obj.put("updatedAt", updatedAt)
    val arr = JSONArray()
    checklist.forEach { item ->
        val o = JSONObject()
        o.put("id", item.id); o.put("text", item.text); o.put("completed", item.completed)
        arr.put(o)
    }
    obj.put("checklist", arr)
    return obj
}

fun List<TaskEvent>.forToday(): List<TaskEvent> = forDate(LocalDate.now())

fun List<TaskEvent>.forDate(date: LocalDate): List<TaskEvent> {
    val zone  = ZoneId.systemDefault()
    return filter { e ->
        if (e.deletedAt != 0L) return@filter false
        val s = e.start.atZone(zone).toLocalDate()
        val en = e.end.atZone(zone).toLocalDate()
        !s.isAfter(date) && !en.isBefore(date)
    }.sortedBy { it.start }
}
