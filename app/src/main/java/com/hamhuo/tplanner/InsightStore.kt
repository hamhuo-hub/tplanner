package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

// Statistics store: independent of JournalStore, uses a separate SharedPreferences file.
// Stores two types of data:
//   1) Structured records for each anxiety event (distortion labels, intensity, time, location)
//   2) End-of-day reports
//
// Data structure:
//   KEY_EVENTS_dd → JSONArray of StructuredEntry
//   KEY_REPORT_dd → JSONObject DayReport
class InsightStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_insights", Context.MODE_PRIVATE)

    // Atomicity guard for read-modify-write sequences (UI main thread writes vs sync thread full replacements)
    private val lock = Any()

    // ── Event access ─────────────────────────────────────────────────────
    // getEvents returns only live records (for UI/stats); sync uses getAllEventsRaw to get everything including tombstones.

    fun getTodayEvents(): List<StructuredEntry> =
        getEvents(LocalDate.now().toString())

    fun getEvents(date: String): List<StructuredEntry> =
        getEventsRaw(date).filter { it.deletedAt == 0L }

    private fun getEventsRaw(date: String): List<StructuredEntry> {
        val json = prefs.getString("$KEY_EVENTS$date", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.getJSONObject(i).toStructuredEntry()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun addEvent(entry: StructuredEntry) {
        synchronized(lock) {
            val date = epochToDate(entry.timestamp)
            val existing = getEventsRaw(date).toMutableList()
            // Prevent duplicates: skip if id already exists
            if (existing.any { it.id == entry.id }) return
            // Stamp modification time: basis for LWW merge win/loss
            existing.add(if (entry.updatedAt == 0L) entry.copy(updatedAt = System.currentTimeMillis()) else entry)
            saveEvents(date, existing)
        }
    }

    fun updateEvent(updated: StructuredEntry) {
        synchronized(lock) {
            val date = epochToDate(updated.timestamp)
            val stamped = updated.copy(updatedAt = System.currentTimeMillis())
            val list = getEventsRaw(date).map { if (it.id == updated.id) stamped else it }
            saveEvents(date, list)
        }
    }

    // 软删除：打 deletedAt 墓碑（updatedAt 一并更新，才能在 LWW 合并中战胜对端
    // 尚存的旧版本），getEvents 会把它过滤掉。与 Notes/随笔互不影响——删这条
    // 洞察记录不触碰随笔里对应那段文字。
    fun deleteEvent(id: String, timestamp: Long) {
        synchronized(lock) {
            val date = epochToDate(timestamp)
            val now = System.currentTimeMillis()
            val list = getEventsRaw(date).map {
                if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it
            }
            saveEvents(date, list)
        }
    }

    // ── Statistics queries ───────────────────────────────────────────────

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
                in 6..11 -> "Morning"
                in 12..13 -> "Noon"
                in 14..17 -> "Afternoon"
                in 18..22 -> "Evening"
                else -> "Night"
            }
        }
        return slots.maxByOrNull { it.value.size }?.key ?: ""
    }

    // Anxiety event count at a specific location in the last N days
    fun getLocationFrequency(location: String, days: Int = 7): Int {
        var count = 0
        val today = LocalDate.now()
        for (i in 0 until days) {
            val date = today.minusDays(i.toLong()).toString()
            count += getEvents(date).count { it.location == location }
        }
        return count
    }

    // ── End-of-day report ────────────────────────────────────────────────

    fun getDayReport(date: String): DayReport? {
        val json = prefs.getString("$KEY_REPORT$date", null) ?: return null
        return try { JSONObject(json).toDayReport()?.takeIf { it.deletedAt == 0L } } catch (_: Exception) { null }
    }

    fun saveDayReport(report: DayReport) {
        synchronized(lock) {
            val stamped = report.copy(updatedAt = System.currentTimeMillis())
            prefs.edit().putString("$KEY_REPORT${report.date}", stamped.toJson().toString()).apply()
        }
    }

    fun getTodayReport(): DayReport? = getDayReport(LocalDate.now().toString())

    // ── Sync API (used by LanSyncManager) ────────────────────────────────
    // Full dump including tombstones; writes use commit() (sync is a full overwrite, data loss on process kill is unacceptable).

    fun getAllEventsRaw(): List<StructuredEntry> = synchronized(lock) {
        prefs.all.keys.filter { it.startsWith(KEY_EVENTS) }
            .flatMap { key -> getEventsRaw(key.removePrefix(KEY_EVENTS)) }
    }

    fun getAllReportsRaw(): Map<String, DayReport> = synchronized(lock) {
        buildMap {
            prefs.all.keys.filter { it.startsWith(KEY_REPORT) }.forEach { key ->
                val json = prefs.getString(key, null) ?: return@forEach
                try { JSONObject(json).toDayReport()?.let { put(it.date, it) } } catch (_: Exception) {}
            }
        }
    }

    fun replaceAllFromSync(entries: List<StructuredEntry>, reports: Map<String, DayReport>) {
        synchronized(lock) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(KEY_EVENTS) || it.startsWith(KEY_REPORT) }
                .forEach { editor.remove(it) }
            entries.groupBy { epochToDate(it.timestamp) }.forEach { (date, list) ->
                val arr = JSONArray()
                list.forEach { arr.put(it.toJson()) }
                editor.putString("$KEY_EVENTS$date", arr.toString())
            }
            reports.forEach { (date, r) -> editor.putString("$KEY_REPORT$date", r.toJson().toString()) }
            editor.commit()
        }
    }

    // ── Listeners (reusing JournalStore's pattern) ───────────────────────

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    // ── Internal ─────────────────────────────────────────────────────────

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
