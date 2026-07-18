package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate

// 与 events 的 tombstone 模型一致：deletedAt == 0L 表示存活。
// 同步服务器已改为 { text, updatedAt, deletedAt } 格式以修复软删除回环恢复问题，
// Android 端需要理解该格式才能正确参与 updatedAt-wins 合并并尊重远端的删除。
data class JournalEntry(val text: String, val updatedAt: Long = 0L, val deletedAt: Long = 0L)

class JournalStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_journals", Context.MODE_PRIVATE)

    // 同步锁：appendToday / replaceInToday 读-改-写序列为原子操作，
    // 防止后台线程与 UI 主线并发写入丢失数据。
    private val lock = Any()

    fun getAll(): Map<String, JournalEntry> =
        prefs.all.mapNotNull { (date, value) -> value?.let { date to parseEntry(it) } }.toMap()

    // saveAll 做全量覆写（clear + repopulate）。apply() 是异步的——进程
    // 在 apply() 排队后、磁盘写入前被 kill 会导致全部日记永久丢失。
    // commit() 同步写盘，杜绝这个窗口。调用方已在 Dispatchers.IO 上执行
    //（LanSyncManager.syncJournals），不阻塞主线程。
    fun saveAll(journals: Map<String, JournalEntry>) {
        prefs.edit().apply {
            clear()
            journals.forEach { (date, entry) -> putString(date, entry.toJson().toString()) }
        }.commit()
    }

    fun getToday(): String {
        val entry = prefs.getString(LocalDate.now().toString(), null)?.let { parseEntry(it) }
        return if (entry == null || entry.deletedAt != 0L) "" else entry.text
    }

    // 只更新今天这一条。直接 putString 单个 key，不做全量读-改-写：
    //   1) 避免 saveAll 先 clear 再 repopulate 时丢失其他线程的并发写入；
    //   2) eliminated 全量 getAll() 的读放大。
    // 与 appendToday / replaceInToday 共用同一把锁，防止 UI 主线写入与
    // 蓝牙后台线程的 read-modify-write 交错（丢失用户编辑）。
    // synchronized 在 JVM 上是可重入的，从 appendToday 等同步块内调用安全。
    fun saveToday(text: String) {
        synchronized(lock) {
            val today = LocalDate.now().toString()
            val entry = JournalEntry(text = text, updatedAt = System.currentTimeMillis(), deletedAt = 0L)
            prefs.edit().putString(today, entry.toJson().toString()).apply()
        }
    }

    // 手表唤醒打点：向今日随笔末尾追加一行，保留已有内容。
    // 同步锁保证读-改-写序列不被打断，避免蓝牙线程和 UI 主线并发导致丢失。
    fun appendToday(line: String) {
        synchronized(lock) {
            val current = getToday()
            val newText = if (current.isBlank()) line else current.trimEnd() + "\n" + line
            saveToday(newText)
        }
    }

    // 定位异步到达后补充打点行：只在 target 仍以"整行"存在时替换其最后一次
    // 出现（同一分钟两次打点会产生相同的行，位置归属最新那次）。用户在这
    // 期间改动了该行则放弃替换，不覆盖用户编辑。
    // 同步锁保证 read-modify-write 原子性。
    fun replaceInToday(target: String, replacement: String) {
        synchronized(lock) {
            val current = getToday()
            val idx = current.lastIndexOf(target)
            if (idx < 0) return
            // 确认 target 位于行首：必须是文本开头，或前一个字符是换行符
            if (idx > 0 && current[idx - 1] != '\n') return
            // 确认 target 位于行尾：必须是文本结尾，或后一个字符是换行符
            val end = idx + target.length
            if (end < current.length && current[end] != '\n') return
            saveToday(current.substring(0, idx) + replacement + current.substring(end))
        }
    }

    // 监听随笔变化，界面据此实时刷新。
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

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
