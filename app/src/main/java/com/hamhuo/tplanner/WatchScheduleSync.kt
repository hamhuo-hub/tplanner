package com.hamhuo.tplanner

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Phone -> Watch durable latest-task snapshot.
 *
 * The latest payload is committed before scheduling a persisted job. The job publishes a
 * DataItem without requiring a currently connected node, so Google Play services can
 * deliver it after reconnection and process death cannot lose an accepted local update.
 */
object WatchScheduleSync {
    private const val TAG = "TplannerWatchSync"
    private const val PATH = "/tplanner/schedule"
    private const val SCHEMA_VERSION = 3
    private const val SNAPSHOT_DAY_COUNT = 8
    private const val PREFS = "tplanner_watch_schedule_sync"
    private const val KEY_LAST_VERSION = "last_version"
    private const val KEY_PENDING_PAYLOAD = "pending_payload"
    private const val SYNC_JOB_ID = 0x545053

    private val stateLock = Any()

    private data class DaySnapshot(
        val date: String,
        val minutes: List<Int>,
    )

    private data class QueuedSnapshot(
        val rangeStart: String,
        val version: Long,
        val hash: String,
    )

    fun push(context: Context, events: List<TaskEvent>) {
        val appContext = context.applicationContext
        try {
            // Build, version, and persist under the same lock. Otherwise an older concurrent
            // caller can allocate a lower version, finish last, and overwrite the newer fact.
            val queued = synchronized(stateLock) {
                val generatedAt = System.currentTimeMillis()
                val today = java.time.Instant.ofEpochMilli(generatedAt)
                    .atZone(APP_ZONE)
                    .toLocalDate()
                val activeTasks = events.filter { event ->
                    event.deletedAt == 0L && event.type == "task" && !event.completed
                }
                val days = (0 until SNAPSHOT_DAY_COUNT).map { offset ->
                    val day = today.plusDays(offset.toLong())
                    val minutes = activeTasks.asSequence()
                        .filter { event -> event.start.atZone(APP_ZONE).toLocalDate() == day }
                        .map { event ->
                            val local = event.start.atZone(APP_ZONE)
                            local.hour * 60 + local.minute
                        }
                        .filter { it in 0..1439 }
                        .distinct()
                        .sorted()
                        .toList()
                    DaySnapshot(day.toString(), minutes)
                }
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previousVersion = prefs.getLong(KEY_LAST_VERSION, 0L)
                val version = maxOf(generatedAt, previousVersion + 1L)
                val hash = scheduleHash(days)
                val payload = JSONObject().apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("version", version)
                    put("generatedAtEpochMs", generatedAt)
                    put("hash", hash)
                    put("days", JSONArray().apply {
                        days.forEach { day ->
                            put(JSONObject().apply {
                                put("date", day.date)
                                put("minutes", JSONArray(day.minutes))
                            })
                        }
                    })
                }.toString()
                val committed = prefs.edit()
                    .putLong(KEY_LAST_VERSION, version)
                    .putString(KEY_PENDING_PAYLOAD, payload)
                    .commit()
                if (committed) {
                    QueuedSnapshot(days.first().date, version, hash)
                } else {
                    null
                }
            }
            if (queued == null) {
                Log.e(TAG, "push: failed to persist snapshot")
                return
            }
            scheduleJob(appContext)
            Log.d(
                TAG,
                "push: queued rangeStart=${queued.rangeStart} days=$SNAPSHOT_DAY_COUNT " +
                    "version=${queued.version} hash=${queued.hash.take(12)}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "push: failed to build snapshot", e)
        }
    }

    /** Returns true when the latest committed snapshot was handed to Data Layer. */
    internal fun flushPending(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payload = synchronized(stateLock) {
            prefs.getString(KEY_PENDING_PAYLOAD, null)
        } ?: return true
        return try {
            val request = PutDataRequest.create(PATH).setUrgent().apply {
                data = payload.toByteArray(Charsets.UTF_8)
            }
            Tasks.await(
                Wearable.getDataClient(appContext).putDataItem(request),
                10,
                TimeUnit.SECONDS,
            )
            val cleared = synchronized(stateLock) {
                if (prefs.getString(KEY_PENDING_PAYLOAD, null) != payload) {
                    false
                } else {
                    prefs.edit().remove(KEY_PENDING_PAYLOAD).commit()
                }
            }
            if (!cleared) {
                // A newer snapshot arrived while this one was publishing, or the clear
                // failed. Keep/reschedule the persisted job for the latest payload.
                Log.d(TAG, "flushPending: newer or uncleared payload remains")
                false
            } else {
                val metadata = JSONObject(payload)
                Log.d(
                    TAG,
                    "flushPending: stored version=${metadata.optLong("version")} " +
                        "hash=${metadata.optString("hash").take(12)}",
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushPending: DataItem publish failed", e)
            false
        }
    }

    private fun scheduleJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            SYNC_JOB_ID,
            ComponentName(context, WatchScheduleSyncJobService::class.java),
        )
            .setPersisted(true)
            .setMinimumLatency(0L)
            .setOverrideDeadline(1_000L)
            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "scheduleJob: scheduler rejected job")
        }
    }

    /** Hashes every field consumed by the watch face, in a deterministic order. */
    private fun scheduleHash(days: List<DaySnapshot>): String {
        val canonical = buildString {
            append("schema=").append(SCHEMA_VERSION)
            days.forEach { day ->
                append('|').append(day.date).append('[')
                append(day.minutes.joinToString(","))
                append(']')
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
