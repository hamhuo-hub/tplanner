package com.hamhuo.tplanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class LanSyncManager(
    private val context: Context,
    private val store: JournalStore,
    private val eventStore: EventStore? = null,
) {

    data class Peer(val name: String, val ip: String, val port: Int, val journalCount: Int)

    sealed class SyncResult {
        data class Success(val todayText: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    private val fallbackPeers = listOf(
        Peer("sync-server", "192.168.5.4", 37401, 0)
    )

    suspend fun discoverPeers(): List<Peer> = withContext(Dispatchers.IO) {
        val peers = mutableListOf<Peer>()
        try {
            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 400
                val probe = "TPLANNER_DISCOVER".toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), 37402))
                val buf      = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 2500
                val seen     = mutableSetOf<String>()
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val peer = parsePeer(String(buf, 0, pkt.length, Charsets.UTF_8)) ?: continue
                        if (seen.add("${peer.ip}:${peer.port}")) peers.add(peer)
                    } catch (_: SocketTimeoutException) {}
                }
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            // UDP blocked — fall through to HTTP probe
        }

        if (peers.isEmpty()) {
            for (p in fallbackPeers) { if (probeHttp(p)) peers.add(p) }
        }
        peers
    }

    suspend fun fetchEvents(peer: Peer): List<TaskEvent> = withContext(Dispatchers.IO) {
        try {
            val base         = "http://${peer.ip}:${peer.port}"
            val store_       = eventStore ?: return@withContext emptyList()
            val remoteEvents = store_.fromJson(httpGet("$base/tplanner/events"))
            val merged       = mergeEvents(store_.getAll(), remoteEvents)
            httpPut("$base/tplanner/events", store_.toJson(merged))
            store_.saveAll(merged)
            merged
        } catch (_: Exception) {
            eventStore?.getAll() ?: emptyList()
        }
    }

    suspend fun toggleTask(peer: Peer, eventId: String, completed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().apply {
                    put("completed", completed)
                }.toString()
                httpPatch("http://${peer.ip}:${peer.port}/tplanner/events/$eventId", body)
                true
            } catch (_: Exception) { false }
        }

    suspend fun syncJournals(peer: Peer): SyncResult = withContext(Dispatchers.IO) {
        try {
            val base           = "http://${peer.ip}:${peer.port}"
            val remoteJournals = store.fromJson(httpGet("$base/tplanner/journals"))
            val merged         = mergeJournals(store.getAll(), remoteJournals)
            httpPut("$base/tplanner/journals", store.toJson(merged))
            store.saveAll(merged)
            SyncResult.Success(store.getToday())
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: context.getString(R.string.unknown_error))
        }
    }

    private fun probeHttp(peer: Peer): Boolean = try {
        val conn = URL("http://${peer.ip}:${peer.port}/health").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 2000; conn.readTimeout = 2000
        val ok = conn.responseCode in 200..299
        conn.disconnect(); ok
    } catch (_: Exception) { false }

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

    // 等价于 JS 端 JSON.stringify({ payload: { text, updatedAt, deletedAt }, deletedAt })。
    // 不借助 org.json（其 quote() 会把 '/' 转义成 '\/'，与 JS 的 JSON.stringify
    // 不一致，导致同一段含 '/' 的文本在两端产生不同的比较键），改为手写、
    // 逐字符匹配 JS JSON.stringify 字符串转义规则的最小实现。
    // 存活时 deletedAt 序列化为 null，与线格式（见 toJson）保持一致。
    private fun JournalEntry.tieKey(): String {
        val d = if (deletedAt == 0L) "null" else deletedAt.toString()
        val t = jsonQuote(text)
        return "{\"payload\":{\"text\":$t,\"updatedAt\":$updatedAt,\"deletedAt\":$d},\"deletedAt\":$d}"
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

    private fun mergeJournals(local: Map<String, JournalEntry>, remote: Map<String, JournalEntry>): Map<String, JournalEntry> {
        val result = local.toMutableMap()
        remote.forEach { (date, entry) ->
            val existing = result[date]
            result[date] = if (existing == null) entry else pickEntry(existing, entry)
        }
        return result
    }

    // updatedAt 较大者获胜，时间戳相同时保留已存在的版本——与 electron/main.js
    // 嵌入式局域网服务端对 /tplanner/events 的合并语义保持一致。
    private fun mergeEvents(local: List<TaskEvent>, remote: List<TaskEvent>): List<TaskEvent> {
        val map = local.associateByTo(LinkedHashMap()) { it.id }
        remote.forEach { e ->
            val existing = map[e.id]
            if (existing == null || e.updatedAt > existing.updatedAt) {
                map[e.id] = e
            }
        }
        return map.values.toList()
    }

    private fun parsePeer(json: String): Peer? = try {
        val obj = JSONObject(json)
        val ip  = obj.optString("ip", "")
        if (ip.isBlank()) null
        else Peer(
            name         = obj.optString("name", ip),
            ip           = ip,
            port         = obj.optInt("port", 37401),
            journalCount = obj.optInt("journals", 0)
        )
    } catch (_: Exception) { null }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        return try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn.disconnect() }
    }

    private fun httpPatch(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        } finally { conn.disconnect() }
    }

    private fun httpPut(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        } finally { conn.disconnect() }
    }
}
