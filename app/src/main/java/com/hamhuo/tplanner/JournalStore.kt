package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

// 与 events 的 tombstone 模型一致：deletedAt == 0L 表示存活。
// 同步服务器已改为 { text, updatedAt, deletedAt } 格式以修复软删除回环恢复问题，
// Android 端需要理解该格式才能正确参与 updatedAt-wins 合并并尊重远端的删除。
data class JournalEntry(val text: String, val updatedAt: Long = 0L, val deletedAt: Long = 0L)

class JournalStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_journals", Context.MODE_PRIVATE)

    fun getAll(): Map<String, JournalEntry> =
        prefs.all.mapNotNull { (date, value) -> value?.let { date to parseEntry(it) } }.toMap()

    fun saveAll(journals: Map<String, JournalEntry>) {
        prefs.edit().apply {
            clear()
            journals.forEach { (date, entry) -> putString(date, entry.toJson().toString()) }
        }.apply()
    }

    fun getToday(): String {
        val entry = prefs.getString(LocalDate.now().toString(), null)?.let { parseEntry(it) }
        return if (entry == null || entry.deletedAt != 0L) "" else entry.text
    }

    fun fromJson(json: String): Map<String, JournalEntry> {
        val obj = JSONObject(json)
        return buildMap { obj.keys().forEach { key -> put(key, parseEntry(obj.get(key))) } }
    }

    fun toJson(journals: Map<String, JournalEntry>): String {
        val obj = JSONObject()
        journals.forEach { (k, v) -> obj.put(k, v.toJson()) }
        return obj.toString()
    }

    // 兼容新格式对象 { text, updatedAt, deletedAt } 与旧版纯字符串两种来源
    // （旧数据/旧服务端）。旧格式迁移为 updatedAt = 0，确保会被任何带时间戳
    // 的写入/删除覆盖，避免回环恢复。
    private fun parseEntry(value: Any?): JournalEntry = when (value) {
        is JSONObject -> JournalEntry(
            text      = value.optString("text", ""),
            updatedAt = value.optLong("updatedAt", 0L),
            deletedAt = value.optLong("deletedAt", 0L),
        )
        is String -> try {
            parseEntry(JSONObject(value))
        } catch (_: Exception) {
            JournalEntry(text = value)
        }
        else -> JournalEntry(text = "")
    }

    // PC/服务端的存活哨兵是 JSON null，而本类内部用 0L 表示存活
    // （与 events 模型一致，便于 Kotlin 端处理）。序列化时必须把 0L 还原成
    // null，否则上传后服务端/PC 收到的是 deletedAt: 0 而不是 null——
    // 同一份内容在两端呈现不同的 JSON，会让 pickEntity 的平局打破
    // （按序列化结果做字符串比较）在两端给出不同胜者，造成死锁式分歧。
    private fun JournalEntry.toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("updatedAt", updatedAt)
        put("deletedAt", if (deletedAt == 0L) JSONObject.NULL else deletedAt)
    }
}
