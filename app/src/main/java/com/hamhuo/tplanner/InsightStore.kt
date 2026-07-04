package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

// 统计存储：独立于 JournalStore，用独立的 SharedPreferences 文件。
// 存两类数据：
//   1) 每个焦虑事件的结构化记录（思维钢印标签、强度、时间、位置）
//   2) 每日日终报告
//
// 数据结构：
//   KEY_EVENTS_dd → JSONArray of StructuredEntry
//   KEY_REPORT_dd → JSONObject DayReport
class InsightStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_insights", Context.MODE_PRIVATE)

    // ── 事件存取 ──────────────────────────────────────────────────────────

    fun getTodayEvents(): List<StructuredEntry> =
        getEvents(LocalDate.now().toString())

    fun getEvents(date: String): List<StructuredEntry> {
        val json = prefs.getString("$KEY_EVENTS$date", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.getJSONObject(i).toStructuredEntry()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun addEvent(entry: StructuredEntry) {
        val date = epochToDate(entry.timestamp)
        val existing = getEvents(date).toMutableList()
        // 防止重复：同 id 不重复添加
        if (existing.any { it.id == entry.id }) return
        existing.add(entry)
        saveEvents(date, existing)
    }

    fun updateEvent(updated: StructuredEntry) {
        val date = epochToDate(updated.timestamp)
        val list = getEvents(date).map { if (it.id == updated.id) updated else it }
        saveEvents(date, list)
    }

    // ── 统计查询 ──────────────────────────────────────────────────────────

    fun getDistortionCounts(date: String): Map<String, Int> {
        val events = getEvents(date)
        val counts = mutableMapOf<String, Int>()
        events.forEach { e ->
            e.distortions.forEach { d ->
                counts[d] = (counts[d] ?: 0) + 1
            }
        }
        return counts
    }

    fun getAvgIntensity(date: String): Int {
        val events = getEvents(date)
        if (events.isEmpty()) return 0
        return events.map { it.intensity }.average().roundToInt()
    }

    fun getTopLocation(date: String): String {
        val events = getEvents(date)
        return events.groupBy { it.location }
            .maxByOrNull { it.value.size }?.key ?: ""
    }

    fun getTopTimeSlot(date: String): String {
        val events = getEvents(date)
        if (events.isEmpty()) return ""
        val slots = events.groupBy { e ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = e.timestamp }
            when (cal.get(java.util.Calendar.HOUR_OF_DAY)) {
                in 6..11 -> "上午"
                in 12..13 -> "午间"
                in 14..17 -> "下午"
                in 18..22 -> "晚间"
                else -> "深夜"
            }
        }
        return slots.maxByOrNull { it.value.size }?.key ?: ""
    }

    // 最近 N 天某个地点的焦虑事件次数
    fun getLocationFrequency(location: String, days: Int = 7): Int {
        var count = 0
        val today = LocalDate.now()
        for (i in 0 until days) {
            val date = today.minusDays(i.toLong()).toString()
            count += getEvents(date).count { it.location == location }
        }
        return count
    }

    // ── 日终报告 ──────────────────────────────────────────────────────────

    fun getDayReport(date: String): DayReport? {
        val json = prefs.getString("$KEY_REPORT$date", null) ?: return null
        return try { JSONObject(json).toDayReport() } catch (_: Exception) { null }
    }

    fun saveDayReport(report: DayReport) {
        prefs.edit().putString("$KEY_REPORT${report.date}", report.toJson().toString()).apply()
    }

    fun getTodayReport(): DayReport? = getDayReport(LocalDate.now().toString())

    // ── 监听器（复用 JournalStore 的模式） ─────────────────────────────────

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun saveEvents(date: String, entries: List<StructuredEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("$KEY_EVENTS$date", arr.toString()).apply()
    }

    private fun epochToDate(epochMs: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochMs))
    }

    companion object {
        private const val KEY_EVENTS = "insight_events_"
        private const val KEY_REPORT = "insight_report_"
    }
}
