package com.tplanner.wear

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

data class WearEvent(
    val id: String,
    val title: String,
    val type: String,
    val start: String,
    val end: String,
    val completed: Boolean = false,
    val deletedAt: Long = 0,
    val colorId: Int = 0,
    val checklist: List<CheckItem> = emptyList(),
)

data class CheckItem(val id: String, val text: String, val completed: Boolean)

private val gson = Gson()
private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

fun Context.saveEvents(json: String) =
    getSharedPreferences("tplanner_wear", MODE_PRIVATE).edit()
        .putString("events", json).apply()

fun Context.saveJournals(json: String) =
    getSharedPreferences("tplanner_wear", MODE_PRIVATE).edit()
        .putString("journals", json).apply()

fun Context.loadEvents(): List<WearEvent> {
    val json = getSharedPreferences("tplanner_wear", MODE_PRIVATE)
        .getString("events", "[]") ?: "[]"
    return try {
        gson.fromJson(json, object : TypeToken<List<WearEvent>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

fun Context.loadJournalToday(): String {
    val json = getSharedPreferences("tplanner_wear", MODE_PRIVATE)
        .getString("journals", "{}") ?: "{}"
    val key  = fmt.format(Date())
    return try {
        val map: Map<String, String> = gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
        map[key] ?: ""
    } catch (e: Exception) { "" }
}

fun List<WearEvent>.todayActive(): List<WearEvent> {
    val now  = Date()
    val sdf  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val todayStr = sdf.format(now)
    return filter { e ->
        e.deletedAt == 0L &&
        (e.start.startsWith(todayStr) || e.end.startsWith(todayStr))
    }.sortedBy { it.start }
}

fun List<WearEvent>.currentEvent(): WearEvent? {
    val now = System.currentTimeMillis()
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return firstOrNull { e ->
        try {
            val s = sdf.parse(e.start.substring(0, 19))?.time ?: return@firstOrNull false
            val en = sdf.parse(e.end.substring(0, 19))?.time   ?: return@firstOrNull false
            s <= now && now <= en
        } catch (_: Exception) { false }
    }
}

fun List<WearEvent>.nextEvent(): WearEvent? {
    val now = System.currentTimeMillis()
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return filter { e ->
        try { (sdf.parse(e.start.substring(0, 19))?.time ?: 0) > now }
        catch (_: Exception) { false }
    }.minByOrNull { e ->
        try { sdf.parse(e.start.substring(0, 19))?.time ?: Long.MAX_VALUE }
        catch (_: Exception) { Long.MAX_VALUE }
    }
}
