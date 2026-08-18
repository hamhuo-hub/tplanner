package com.hamhuo.tplanner

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pulls the phone's latest durable schedule snapshot into the watch on user request. */
internal object WatchManualSync {
    internal enum class Result {
        COMPLETED,
        WAITING_FOR_PHONE,
        FAILED,
    }

    private const val TAG = "TplannerManualSync"
    private const val SCHEDULE_PATH = "/tplanner/schedule"
    private const val READ_TIMEOUT_SECONDS = 12L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-manual-sync").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun request(context: Context, onComplete: (Result) -> Unit) {
        val appContext = context.applicationContext

        // A manual refresh also retries watch-originated creates/deletes and keeps the
        // GMS-free phone -> watch receiver alive while we query the Data Layer cache.
        WatchTaskOutbox.resumePending(appContext)
        BluetoothScheduleBridgeService.startIfAllowed(appContext)

        worker.execute {
            val result = pullLatestSnapshot(appContext)
            mainHandler.post { onComplete(result) }
        }
    }

    private fun pullLatestSnapshot(context: Context): Result {
        val buffer = try {
            Tasks.await(
                Wearable.getDataClient(context).getDataItems(
                    Uri.parse("wear://*$SCHEDULE_PATH"),
                ),
                READ_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.FAILED
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read the phone schedule DataItem", error)
            return Result.WAITING_FOR_PHONE
        }

        val payloads = try {
            buildList {
                for (item in buffer) {
                    if (item.uri.path != SCHEDULE_PATH) continue
                    item.data?.copyOf()?.let { add(it) }
                }
            }
        } finally {
            buffer.release()
        }

        if (payloads.isEmpty()) return Result.WAITING_FOR_PHONE

        var accepted = false
        payloads.forEach { payload ->
            when (ScheduleStore.store(context, String(payload, Charsets.UTF_8))) {
                ScheduleStore.StoreResult.STORED,
                ScheduleStore.StoreResult.ALREADY_CURRENT,
                ScheduleStore.StoreResult.STALE
                -> accepted = true

                ScheduleStore.StoreResult.REJECTED,
                ScheduleStore.StoreResult.COMMIT_FAILED
                -> Unit
            }
        }
        return if (accepted) Result.COMPLETED else Result.FAILED
    }
}
