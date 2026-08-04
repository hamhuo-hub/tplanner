package com.hamhuo.tplanner

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phone -> Watch durable latest-task snapshot.
 *
 * The latest payload is committed before scheduling a persisted job. The job publishes a
 * DataItem without requiring a currently connected node, so Google Play services can
 * deliver it after reconnection and process death cannot lose an accepted local update.
 *
 * When the Wearable Data Layer API is unavailable (e.g. Chinese Samsung devices without
 * GMS), the job falls back to a classic Bluetooth RFCOMM socket connection to the paired
 * watch running [com.hamhuo.tplanner.BluetoothScheduleBridgeService].
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

    /** Shared RFCOMM UUID — must match the watch-side value in BluetoothScheduleBridgeService. */
    private val RFCOMM_UUID: UUID = UUID.fromString("7f8a9b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c")
    private const val ACK_BYTE: Int = 0x06

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
                    // One-release rolling-upgrade bridge: old watch builds read only this field.
                    put("minutes", JSONArray(days.first().minutes))
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
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.API_NOT_CONNECTED) {
                Log.w(TAG, "flushPending: Wearable API unavailable, trying Bluetooth fallback")
                flushViaBluetooth(appContext, payload)
            } else {
                Log.e(TAG, "flushPending: DataItem publish failed", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushPending: DataItem publish failed", e)
            false
        }
    }

    // ── Bluetooth RFCOMM fallback ──────────────────────────────────────

    /**
     * Sends the schedule payload to the paired watch over classic Bluetooth RFCOMM.
     *
     * Returns `true` when the transfer succeeded and the pending payload may be cleared,
     * or when Bluetooth is unavailable and no further retries are useful. Returns `false`
     * on transient failures so the JobScheduler retries.
     */
    private fun flushViaBluetooth(context: Context, payload: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "flushViaBluetooth: Bluetooth disabled or unsupported, giving up")
            return true
        }

        val watch = findPairedWatch(adapter)
        if (watch == null) {
            Log.w(TAG, "flushViaBluetooth: no paired watch found, giving up")
            return true
        }

        var socket: BluetoothSocket? = null
        try {
            socket = watch.createRfcommSocketToServiceRecord(RFCOMM_UUID)
            adapter.cancelDiscovery()
            socket.connect()
            // Read timeout handled via a separate watchdog; the socket itself has none.
            val bytes = payload.toByteArray(Charsets.UTF_8)
            socket.outputStream.write(bytes)
            socket.outputStream.flush()

            // Wait for the watch's single-byte ACK so we know the payload was fully consumed.
            val ack = socket.inputStream.read()
            if (ack == ACK_BYTE) {
                Log.d(TAG, "flushViaBluetooth: ACK received, clearing pending payload")
                clearPendingAfterBluetooth(context, payload)
                return true
            } else {
                Log.w(TAG, "flushViaBluetooth: unexpected ACK byte=$ack")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushViaBluetooth: transfer failed", e)
            return false
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Clears the pending payload only if it hasn't been replaced by a newer snapshot. */
    private fun clearPendingAfterBluetooth(context: Context, expected: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(stateLock) {
            if (prefs.getString(KEY_PENDING_PAYLOAD, null) == expected) {
                prefs.edit().remove(KEY_PENDING_PAYLOAD).commit()
            }
        }
    }

    /**
     * Scans bonded devices for a watch-class device.
     *
     * Wear OS devices report [BluetoothClass.Device.WEARABLE_WRIST_WATCH] (0x0704)
     * in their BluetoothClass. If none match by class, the first bonded device whose
     * name contains "watch" (case-insensitive) is returned as a best-effort fallback.
     */
    private fun findPairedWatch(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded: Set<BluetoothDevice> = runCatching { adapter.bondedDevices }
            .getOrNull() ?: emptySet()
        if (bonded.isEmpty()) return null

        // Prefer a device whose BluetoothClass reports it as a watch.
        bonded.firstOrNull { device ->
            runCatching {
                device.bluetoothClass.deviceClass == BluetoothClass.Device.WEARABLE_WRIST_WATCH
            }.getOrDefault(false)
        }?.let { return it }

        // Fallback: any bonded device with "watch" in its name.
        bonded.firstOrNull { device ->
            device.name?.contains("watch", ignoreCase = true) == true
        }?.let { return it }

        Log.d(TAG, "findPairedWatch: no watch-class device among ${bonded.size} bonded")
        return null
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
