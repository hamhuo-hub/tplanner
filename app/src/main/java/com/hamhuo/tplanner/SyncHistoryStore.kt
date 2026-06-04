package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SyncHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_sync_history", Context.MODE_PRIVATE)

    fun saveSuccess(peer: LanSyncManager.Peer) {
        val list = getHistory().toMutableList()
        list.removeAll { it.ip == peer.ip && it.port == peer.port }
        list.add(0, peer)
        val arr = JSONArray()
        list.take(5).forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("ip", p.ip)
                put("port", p.port)
            })
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun getHistory(): List<LanSyncManager.Peer> {
        val json = prefs.getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val ip = obj.optString("ip", "")
                if (ip.isBlank()) null
                else LanSyncManager.Peer(
                    name         = obj.optString("name", ip),
                    ip           = ip,
                    port         = obj.optInt("port", 37401),
                    journalCount = 0
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
