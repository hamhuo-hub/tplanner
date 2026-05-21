package com.tplanner.wear.mobile

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches events from the LAN sync server and pushes them to the watch via Data Layer. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip    = prefs.getString(KEY_SERVER_IP,   "") ?: ""
        val port  = prefs.getString(KEY_SERVER_PORT, "37401") ?: "37401"
        if (ip.isBlank()) return Result.success()

        return try {
            val eventsJson  = get("http://$ip:$port/tplanner/events")
            val journalJson = get("http://$ip:$port/tplanner/journals")

            pushToWatch(eventsJson, journalJson)
            Log.d("SyncWorker", "Pushed data to watch")
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Sync failed: ${e.message}")
            Result.retry()
        }
    }

    private fun get(url: String): String {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        return resp.body?.string() ?: "[]"
    }

    private suspend fun pushToWatch(eventsJson: String, journalJson: String) {
        val dataClient = Wearable.getDataClient(applicationContext)
        val req = PutDataMapRequest.create(WEAR_PATH_EVENTS).apply {
            dataMap.putString("events",  eventsJson)
            dataMap.putString("journal", journalJson)
            dataMap.putLong("ts", System.currentTimeMillis())
        }
        dataClient.putDataItem(req.asPutDataRequest().setUrgent()).await()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tplanner_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                    .build()
            )
        }
    }
}
