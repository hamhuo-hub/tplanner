package com.hamhuo.tplanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 同步管理器：目标是固定地址的服务器（树莓派 + Cloudflare Tunnel），
// 不再做 UDP 局域网扫描/发现——与 PC 端（src/hooks/useLanSync.js）保持一致。
class LanSyncManager(
    private val context: Context,
    private val store: JournalStore,
    private val eventStore: EventStore? = null,
    private val insightStore: InsightStore? = null,
) {

    sealed class SyncResult {
        data class Success(val todayText: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    private val prefs = context.getSharedPreferences("tplanner_sync_config", Context.MODE_PRIVATE)

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL

    fun saveServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        prefs.edit().putString(KEY_SERVER_URL, normalized.ifBlank { DEFAULT_SERVER_URL }).apply()
    }

    // ── base 快照持久化（id → contentKey）─────────────────────────────────
    // 每次成功同步后保存合并结果的 contentKey；下次同步时据此做三方对比
    //（本地 vs base vs 远端），区分"只有一边改了"与"两边都改了"。
    // 与 PC 端 src/hooks/useLanSync.js 的 loadBaseKeys / saveBaseKeys 一致。
    private fun loadBaseKeys(type: String): Map<String, String>? {
        val json = prefs.getString("sync_base_$type", null) ?: return null
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
        } catch (_: Exception) { null }
    }

    private fun saveBaseKeys(type: String, keys: Map<String, String>) {
        val obj = JSONObject()
        keys.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("sync_base_$type", obj.toString()).apply()
    }

    // ── 内容比较键（base 快照用；需与服务器/PC 端 contentKey 同义）────────
    // 各类型的 contentKey 只需对相同内容产生相同字符串即可，不必与 JS 端
    // stableStringify 逐字一致——Android 只跟自己的快照比较，不跨端比较。

    private fun TaskEvent.contentKey(): String = toJson().toString()

    private fun JournalEntry.contentKey(): String = tieKey()

    private fun StructuredEntry.contentKey(): String = toJson().toString()

    private fun DayReport.contentKey(): String = toJson().toString()

    // 拉取远端事件、与本地做三方合并（本地 vs base 快照 vs 远端）、
    // 把合并结果推回并落盘。勾选/删除等本地改动也通过这条全量合并路径传播
    //（本地改动带着更新的 updatedAt，合并时获胜），服务端只需要 GET/PUT。
    suspend fun fetchEvents(serverUrl: String): List<TaskEvent> = withContext(Dispatchers.IO) {
        try {
            val base         = normalizeServerUrl(serverUrl)
            val store_       = eventStore ?: return@withContext emptyList()
            val localEvents  = store_.getAll()
            val remoteEvents = store_.fromJson(httpGet("$base/tplanner/events"))
            val baseKeys     = loadBaseKeys("events")
            val merged       = mergeEventsWithBase(localEvents, remoteEvents, baseKeys)
            httpPut("$base/tplanner/events", store_.toJson(merged))
            store_.saveAll(merged)
            saveBaseKeys("events", merged.associate { it.id to it.contentKey() })
            merged
        } catch (_: Exception) {
            eventStore?.getAll() ?: emptyList()
        }
    }

    suspend fun syncJournals(serverUrl: String): SyncResult = withContext(Dispatchers.IO) {
        try {
            val base           = normalizeServerUrl(serverUrl)
            val localJournals  = store.getAll()
            val remoteJournals = store.fromJson(httpGet("$base/tplanner/journals"))
            val baseKeys       = loadBaseKeys("journals")
            val merged         = mergeJournalsWithBase(localJournals, remoteJournals, baseKeys)
            httpPut("$base/tplanner/journals", store.toJson(merged))
            store.saveAll(merged)
            saveBaseKeys("journals", merged.mapValues { it.value.contentKey() })
            SyncResult.Success(store.getToday())
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: context.getString(R.string.unknown_error))
        }
    }

    // ── 洞察数据同步 ──────────────────────────────────────────────────────
    // 焦虑记录（StructuredEntry，按 id 合并）+ 日终报告（DayReport，按日期合并），
    // 线格式 { entries: [...], reports: {date: {...}} }，与服务器/桌面端一致。
    // 三方合并（base 快照）：有基线时按"仅本地改/仅远端改/两边都改"裁决；
    // 无基线时回退 updatedAt LWW + 内容键平局。失败静默。
    suspend fun syncInsights(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        val ist = insightStore ?: return@withContext true
        try {
            val base = normalizeServerUrl(serverUrl)
            val remote = JSONObject(httpGet("$base/tplanner/insights"))

            val remoteEntries = mutableListOf<StructuredEntry>()
            remote.optJSONArray("entries")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).toStructuredEntry()?.let { remoteEntries += it }
            }
            val remoteReports = mutableMapOf<String, DayReport>()
            remote.optJSONObject("reports")?.let { obj ->
                obj.keys().forEach { d ->
                    obj.optJSONObject(d)?.toDayReport()?.let { remoteReports[d] = it }
                }
            }

            val localEntries = ist.getAllEventsRaw()
            val localReports = ist.getAllReportsRaw()
            val entryBaseKeys = loadBaseKeys("insights_entries")
            val reportBaseKeys = loadBaseKeys("insights_reports")

            val mergedEntries = mergeEntriesWithBase(localEntries, remoteEntries, entryBaseKeys)
            val mergedReports = mergeReportsWithBase(localReports, remoteReports, reportBaseKeys)

            val body = JSONObject().apply {
                put("entries", org.json.JSONArray().apply { mergedEntries.forEach { put(it.toJson()) } })
                put("reports", JSONObject().apply { mergedReports.forEach { (d, r) -> put(d, r.toJson()) } })
            }.toString()
            httpPut("$base/tplanner/insights", body)
            ist.replaceAllFromSync(mergedEntries, mergedReports)
            saveBaseKeys("insights_entries", mergedEntries.associate { it.id to it.contentKey() })
            saveBaseKeys("insights_reports", mergedReports.mapValues { it.value.contentKey() })
            true
        } catch (_: Exception) { false }
    }

    // updatedAt 较大者获胜（与 sync-server / PC 端保持一致）；删除是携带更新
    // updatedAt 的 tombstone，因此能在合并中正常战胜对端尚存的旧内容，
    // 不会再出现"软删除时间戳失效"导致的回环恢复。
    //
    // updatedAt 相同时（典型情况：三端都持有迁移自旧版纯字符串、值为 0 的
    // 记录，但内容已分别独立改动而产生分歧）必须用与"谁是 local/remote"
    // 无关、三端结果一致的方式打破平局——否则 PC/服务端/Android 各自偏向
    // 自己的本地内容，永远收敛不到同一结果，形成死锁式分歧。
    // pickEntry 的比较键必须与 src/utils/syncLogic.js 的 pickEntity
    // （对 journal 而言 payload = { text, updatedAt, deletedAt }）逐字一致。
    private fun pickEntry(a: JournalEntry, b: JournalEntry): JournalEntry {
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
        return if (a.tieKey() >= b.tieKey()) a else b
    }

    // 等价于 JS 端 stableStringify({ payload: { text, updatedAt, deletedAt }, deletedAt })
    // （见 src/utils/syncLogic.js —— 键按字典序排序：deletedAt < payload；
    // payload 内 deletedAt < text < updatedAt）。不借助 org.json（其 quote()
    // 会把 '/' 转义成 '\/'，与 JS 不一致，导致同一段含 '/' 的文本在两端产生
    // 不同的比较键），改为手写、逐字符匹配 JS 字符串转义规则的最小实现。
    // 存活时 deletedAt 序列化为 null，与线格式（见 toJson）保持一致。
    private fun JournalEntry.tieKey(): String {
        val d = if (deletedAt == 0L) "null" else deletedAt.toString()
        val t = jsonQuote(text)
        return "{\"deletedAt\":$d,\"payload\":{\"deletedAt\":$d,\"text\":$t,\"updatedAt\":$updatedAt}}"
    }

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ── 三方合并（事件）──────────────────────────────────────────────────
    // baseKeys: 上次同步后的 { id → contentKey } 快照；null 时回退纯 LWW。
    private fun mergeEventsWithBase(
        local: List<TaskEvent>, remote: List<TaskEvent>, baseKeys: Map<String, String>?
    ): List<TaskEvent> {
        val localMap  = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val allIds    = LinkedHashSet(localMap.keys).apply { addAll(remoteMap.keys) }
        return allIds.map { id ->
            val le = localMap[id]; val re = remoteMap[id]
            when {
                le == null -> re!!
                re == null -> le
                else -> pickEventWithBase(le, re, baseKeys?.get(id))
            }
        }
    }

    // updatedAt 较大者胜；时间戳相同时按内容键做确定性裁决。
    // 有基线时：仅本地改 → 保留本地；仅远端改 → 采用远端；两边都改 → LWW。
    // 这与 PC 端 mergeEntitiesWithBase 的无人工裁决路径等价。
    private fun pickEventWithBase(a: TaskEvent, b: TaskEvent, baseKey: String?): TaskEvent {
        val ak = a.contentKey(); val bk = b.contentKey()
        if (ak == bk) return a
        if (baseKey != null) {
            if (ak == baseKey) return b   // 仅远端改
            if (bk == baseKey) return a   // 仅本地改
            // 两边都改 → 回退 LWW
        }
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
        // 平局：取远端（服务器已做确定性裁决）；若远端即本地（同源）则无所谓
        return b
    }

    // ── 三方合并（日志）──────────────────────────────────────────────────
    private fun mergeJournalsWithBase(
        local: Map<String, JournalEntry>, remote: Map<String, JournalEntry>,
        baseKeys: Map<String, String>?
    ): Map<String, JournalEntry> {
        val allKeys = LinkedHashSet(local.keys).apply { addAll(remote.keys) }
        val result = LinkedHashMap<String, JournalEntry>()
        for (date in allKeys) {
            val le = local[date]; val re = remote[date]
            result[date] = when {
                le == null -> re!!
                re == null -> le
                else -> pickJournalWithBase(le, re, baseKeys?.get(date))
            }
        }
        return result
    }

    // 对日志而言 contentKey 即 tieKey()，已与 JS 端 stableStringify 逐字一致。
    private fun pickJournalWithBase(a: JournalEntry, b: JournalEntry, baseKey: String?): JournalEntry {
        val ak = a.contentKey(); val bk = b.contentKey()
        if (ak == bk) return a
        if (baseKey != null) {
            if (ak == baseKey) return b   // 仅远端改
            if (bk == baseKey) return a   // 仅本地改
        }
        return pickEntry(a, b)  // 两边都改（或无基线）→ LWW + 确定性平局
    }

    // ── 三方合并（洞察条目 / 报告）────────────────────────────────────────
    private fun mergeEntriesWithBase(
        local: List<StructuredEntry>, remote: List<StructuredEntry>,
        baseKeys: Map<String, String>?
    ): List<StructuredEntry> {
        val localMap  = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val allIds    = LinkedHashSet(localMap.keys).apply { addAll(remoteMap.keys) }
        return allIds.map { id ->
            val le = localMap[id]; val re = remoteMap[id]
            when {
                le == null -> re!!
                re == null -> le
                else -> pickEntryWithBase(le, re, baseKeys?.get(id))
            }
        }
    }

    private fun mergeReportsWithBase(
        local: Map<String, DayReport>, remote: Map<String, DayReport>,
        baseKeys: Map<String, String>?
    ): Map<String, DayReport> {
        val allKeys = LinkedHashSet(local.keys).apply { addAll(remote.keys) }
        val result = LinkedHashMap<String, DayReport>()
        for (date in allKeys) {
            val le = local[date]; val re = remote[date]
            result[date] = when {
                le == null -> re!!
                re == null -> le
                else -> pickReportWithBase(le, re, baseKeys?.get(date))
            }
        }
        return result
    }

    private fun pickEntryWithBase(a: StructuredEntry, b: StructuredEntry, baseKey: String?): StructuredEntry {
        val ak = a.contentKey(); val bk = b.contentKey()
        if (ak == bk) return a
        if (baseKey != null) {
            if (ak == baseKey) return b   // 仅远端改
            if (bk == baseKey) return a   // 仅本地改
        }
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
        return b  // 平局取远端
    }

    private fun pickReportWithBase(a: DayReport, b: DayReport, baseKey: String?): DayReport {
        val ak = a.contentKey(); val bk = b.contentKey()
        if (ak == bk) return a
        if (baseKey != null) {
            if (ak == baseKey) return b   // 仅远端改
            if (bk == baseKey) return a   // 仅本地改
        }
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
        return b  // 平局取远端
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
        return try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn.disconnect() }
    }

    private fun httpPut(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"; conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        } finally { conn.disconnect() }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://sync.hamhuo.top"
        private const val KEY_SERVER_URL = "serverUrl"

        // 归一化服务器地址：补全协议、去掉末尾斜杠。裸主机名默认按 https 处理。
        // 与 src/utils/syncLogic.js 的 normalizeServerUrl 保持一致。
        fun normalizeServerUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed
                             else "https://$trimmed"
            return withScheme.trimEnd('/')
        }
    }
}
