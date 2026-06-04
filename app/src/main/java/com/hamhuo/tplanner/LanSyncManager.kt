package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class LanSyncManager(private val store: JournalStore, private val eventStore: EventStore? = null) {

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
            val json = httpGet("http://${peer.ip}:${peer.port}/tplanner/events")
            val events = eventStore?.fromJson(json) ?: emptyList()
            eventStore?.saveAll(events)
            events
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
            SyncResult.Error(e.message ?: "未知错误")
        }
    }

    private fun probeHttp(peer: Peer): Boolean = try {
        val conn = URL("http://${peer.ip}:${peer.port}/health").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 2000; conn.readTimeout = 2000
        val ok = conn.responseCode in 200..299
        conn.disconnect(); ok
    } catch (_: Exception) { false }

    private fun mergeJournals(local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val result = local.toMutableMap()
        remote.forEach { (date, text) ->
            if (text.length > (result[date]?.length ?: 0)) result[date] = text
        }
        return result
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
