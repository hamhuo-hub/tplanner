package com.tplanner.wear.mobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Receives toggle messages from the watch and forwards to sync server. */
class WearMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http  = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data)
        Log.d("WearMessage", "Received: ${event.path} → $payload")

        scope.launch {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val ip    = prefs.getString(KEY_SERVER_IP,   "") ?: ""
            val port  = prefs.getString(KEY_SERVER_PORT, "37401") ?: "37401"
            if (ip.isBlank()) return@launch

            try {
                when (event.path) {
                    // payload: "eventId"
                    MSG_TOGGLE_TASK -> toggleTask(ip, port, payload)
                    // payload: "eventId:subtaskId"
                    MSG_TOGGLE_SUB  -> {
                        val (evId, subId) = payload.split(":", limit = 2)
                        toggleSubtask(ip, port, evId, subId)
                    }
                }
                // Trigger immediate re-sync so watch gets fresh data
                SyncWorker.schedule(this@WearMessageService)
            } catch (e: Exception) {
                Log.w("WearMessage", "Forward failed: ${e.message}")
            }
        }
    }

    private fun toggleTask(ip: String, port: String, eventId: String) {
        val body = """{"action":"toggleTask","id":"$eventId"}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder()
            .url("http://$ip:$port/tplanner/toggle")
            .post(body).build()).execute()
    }

    private fun toggleSubtask(ip: String, port: String, eventId: String, subtaskId: String) {
        val body = """{"action":"toggleSubtask","id":"$eventId","subtaskId":"$subtaskId"}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder()
            .url("http://$ip:$port/tplanner/toggle")
            .post(body).build()).execute()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
