package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val MAX_TASK_COUNT = 128
    private const val MAX_TASK_TITLE_CODE_POINTS = 80
    private const val MAX_TASK_TITLE_UTF8_BYTES = 256
    private const val MAX_TASK_ID_UTF8_BYTES = 256
    private const val MAX_TASKS_UTF8_BYTES = 64 * 1024
    private const val PREFS = "tplanner_watch_schedule_sync"
    private const val KEY_LAST_VERSION = "last_version"
    private const val KEY_LAST_PAYLOAD = "last_v3_projection_payload"
    private const val KEY_PENDING_PAYLOAD = "pending_payload"
    private const val SYNC_JOB_ID = 0x545053
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 10_000L
    private const val RFCOMM_ACK_TIMEOUT_MS = 5_000L
    private const val DATA_LAYER_ACK_TIMEOUT_MS = 8_000L
    private const val DATA_LAYER_ACK_POLL_MS = 100L

    /** Shared RFCOMM UUID — must match the watch-side value in BluetoothScheduleBridgeService. */
    private val RFCOMM_UUID: UUID = UUID.fromString("7f8a9b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c")
    private val stateLock = Any()
    private val activeSocketLock = Any()
    private val activeSockets = mutableMapOf<Thread, BluetoothSocket>()
    private val socketWatchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-bt-socket-watchdog").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }

    private data class DaySnapshot(
        val date: String,
        val minutes: List<Int>,
    )

    private data class TaskSnapshot(
        val id: String,
        val title: String,
        val type: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val checklistJson: String,
    )

    private val taskOrder = compareBy<TaskSnapshot>(
        { it.startEpochMs },
        { it.endEpochMs },
        { it.id },
    )
    private val taskWhitespace = Regex("\\s+")

    private data class QueuedSnapshot(
        val rangeStart: String,
        val version: Long,
        val hash: String,
        val taskCount: Int,
        val payload: String,
    )

    /** Queues a durable snapshot and returns the exact payload offered to the watch. */
    fun push(
        context: Context,
        events: List<ScheduleItem>,
        sourceSnapshotVersion: Long = 0L,
        sourceBrokerToSequence: Long = 0L,
    ): String? {
        val appContext = context.applicationContext
        return try {
            // Build, version, and persist under the same lock. Otherwise an older concurrent
            // caller can allocate a lower version, finish last, and overwrite the newer fact.
            val queued = synchronized(stateLock) {
                val generatedAt = System.currentTimeMillis()
                val today = java.time.Instant.ofEpochMilli(generatedAt)
                    .atZone(APP_ZONE)
                    .toLocalDate()
                val activeTasks = events.filter { event ->
                    event.deletedAt == 0L && event.type in WATCH_TASK_TYPES && !event.completed
                }
                val windowStart = today.atStartOfDay(APP_ZONE).toInstant()
                val windowEnd = today.plusDays(SNAPSHOT_DAY_COUNT.toLong())
                    .atStartOfDay(APP_ZONE)
                    .toInstant()
                // The dial markers are an eight-day projection, while the memo title must be
                // chosen from every unfinished task. Keeping those two views separate prevents
                // overdue work and the first task beyond the marker window from disappearing.
                val markerTasks = activeTasks.filter { event ->
                    !event.start.isBefore(windowStart) && event.start.isBefore(windowEnd)
                }
                val tasks = buildTaskSnapshots(
                    activeTasks.asSequence()
                        .filter { event -> !event.end.isBefore(event.start) }
                        .toList(),
                )
                val days = (0 until SNAPSHOT_DAY_COUNT).map { offset ->
                    val day = today.plusDays(offset.toLong())
                    val minutes = markerTasks.asSequence()
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
                    // Central provenance, distinct from the phone-local projection version.
                    // The watch uses it as the final barrier for command outbox completion.
                    put("sourceSnapshotVersion", sourceSnapshotVersion.coerceAtLeast(0L))
                    put("sourceBrokerToSequence", sourceBrokerToSequence.coerceAtLeast(0L))
                    put("hash", hash)
                    put("days", JSONArray().apply {
                        days.forEach { day ->
                            put(JSONObject().apply {
                                put("date", day.date)
                                put("minutes", JSONArray(day.minutes))
                            })
                        }
                    })
                    put("tasks", taskArray(tasks))
                    put("tasksHash", tasksHash(tasks))
                }.toString()
                val committed = prefs.edit()
                    .putLong(KEY_LAST_VERSION, version)
                    .putString(KEY_LAST_PAYLOAD, payload)
                    .putString(KEY_PENDING_PAYLOAD, payload)
                    .commit()
                if (committed) {
                    QueuedSnapshot(days.first().date, version, hash, tasks.size, payload)
                } else {
                    null
                }
            }
            if (queued == null) {
                Log.e(TAG, "push: failed to persist snapshot")
                return null
            }
            if (!scheduleJob(appContext)) {
                // The durable payload remains pending, but it has not crossed the phone→Watch
                // hand-off barrier. Returning null keeps the Room projection marker behind so a
                // later engine run retries instead of publishing a false SNAPSHOT_PUBLISHED ACK.
                return null
            }
            if (sourceSnapshotVersion > 0L) {
                WatchCommandBridge.onProjectionQueued(
                    appContext,
                    sourceSnapshotVersion,
                    sourceBrokerToSequence,
                )
            }
            Log.d(
                TAG,
                "push: queued rangeStart=${queued.rangeStart} days=$SNAPSHOT_DAY_COUNT " +
                    "tasks=${queued.taskCount} version=${queued.version} " +
                    "hash=${queued.hash.take(12)}",
            )
            queued.payload
        } catch (e: Exception) {
            Log.e(TAG, "push: failed to build snapshot", e)
            null
        }
    }

    /** Requeues the last projection built by SyncV3Runtime; never rebuilds from a V1/local dataset. */
    fun requeueLatestProjection(context: Context): String? {
        val appContext = context.applicationContext
        val payload = synchronized(stateLock) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val latest = prefs.getString(KEY_LAST_PAYLOAD, null) ?: return@synchronized null
            if (!prefs.edit().putString(KEY_PENDING_PAYLOAD, latest).commit()) null else latest
        } ?: return null
        return payload.takeIf { scheduleJob(appContext) }
    }

    /** Reattach the persisted hand-off after reboot, package update, or an OS-lost job. */
    fun resumePending(context: Context): Boolean {
        val appContext = context.applicationContext
        val hasPending = synchronized(stateLock) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_PAYLOAD, null) != null
        }
        return !hasPending || scheduleJob(appContext)
    }

    /** Returns true when the latest committed snapshot was durably handed off. */
    internal fun flushPending(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payload = synchronized(stateLock) {
            prefs.getString(KEY_PENDING_PAYLOAD, null)
        } ?: return true

        val deliveredViaDataLayer = try {
            val request = PutDataRequest.create(PATH).setUrgent().apply {
                data = payload.toByteArray(Charsets.UTF_8)
            }
            Tasks.await(
                Wearable.getDataClient(appContext).putDataItem(request),
                10,
                TimeUnit.SECONDS,
            )
            // putDataItem() only confirms a durable local GMS write. On a phone that has GMS
            // paired with a watch that does not, it can succeed forever without a receiver.
            // Keep the pending snapshot and try RFCOMM until at least one Wear node is connected.
            val nodes = Tasks.await(
                Wearable.getNodeClient(appContext).connectedNodes,
                3,
                TimeUnit.SECONDS,
            )
            if (nodes.isEmpty()) {
                Log.w(TAG, "flushPending: DataItem stored but no Wear node is connected; trying Bluetooth")
                false
            } else {
                awaitDataLayerAcknowledgement(appContext, payload)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (e: Exception) {
            val apiException = findApiException(e)
            if (apiException != null) {
                Log.w(
                    TAG,
                    "flushPending: Data Layer failed with status=${apiException.statusCode}; " +
                        "trying Bluetooth fallback",
                    e,
                )
            } else {
                Log.w(TAG, "flushPending: Data Layer failed; trying Bluetooth fallback", e)
            }
            false
        }

        if (!deliveredViaDataLayer) {
            return flushViaBluetooth(appContext, payload)
        }

        val metadata = JSONObject(payload)
        Log.d(
            TAG,
            "flushPending: watch ACKed version=${metadata.optLong("version")} " +
                "hash=${metadata.optString("hash").take(12)}",
        )
        return true
    }

    /** Clears only the exact snapshot the watch reports as durably accepted. */
    internal fun acknowledgeSnapshot(
        context: Context,
        version: Long,
        hash: String,
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(stateLock) {
            val pending = prefs.getString(KEY_PENDING_PAYLOAD, null) ?: return@synchronized true
            val identity = runCatching {
                WatchScheduleRefreshProtocol.snapshotIdentity(pending)
            }.getOrElse { error ->
                Log.e(TAG, "acknowledgeSnapshot: invalid pending payload", error)
                return@synchronized false
            }
            if (identity.version != version || identity.hash != hash) {
                Log.d(
                    TAG,
                    "acknowledgeSnapshot: receipt does not match pending " +
                        "receipt=$version/${hash.take(12)} " +
                        "pending=${identity.version}/${identity.hash.take(12)}",
                )
                return@synchronized false
            }
            prefs.edit().remove(KEY_PENDING_PAYLOAD).commit()
        }
    }

    private fun awaitDataLayerAcknowledgement(context: Context, payload: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DATA_LAYER_ACK_TIMEOUT_MS)
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
            val pending = synchronized(stateLock) {
                prefs.getString(KEY_PENDING_PAYLOAD, null)
            }
            if (pending == null) return true
            if (pending != payload) {
                Log.d(TAG, "flushPending: a newer snapshot replaced the one awaiting ACK")
                return false
            }
            try {
                Thread.sleep(DATA_LAYER_ACK_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(TAG, "flushPending: connected node did not ACK the schedule; trying Bluetooth")
        return false
    }

    // ── Bluetooth RFCOMM fallback ──────────────────────────────────────

    /**
     * Sends the schedule payload to the paired watch over classic Bluetooth RFCOMM.
     *
     * Returns `true` only after the watch ACKs the exact pending payload and it is cleared.
     * Every unavailable/error case returns `false`, preserving pending state so JobScheduler
     * can retry after Bluetooth or permissions become available.
     */
    @SuppressLint("MissingPermission")
    private fun flushViaBluetooth(context: Context, payload: String): Boolean {
        // Runtime CONNECT/SCAN checks precede all Bluetooth calls below. Every privileged call is
        // also inside try/runCatching so a permission-revocation race preserves pending state.
        if (Thread.currentThread().isInterrupted) return false
        val bytes = try {
            ScheduleRfcommProtocol.encodePayload(payload)
        } catch (e: IllegalArgumentException) {
            Log.e(
                TAG,
                "flushViaBluetooth: invalid pending payload",
                e,
            )
            return false
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: run {
            Log.w(TAG, "flushViaBluetooth: Bluetooth unsupported; keeping pending payload")
            return false
        }
        if (!hasBluetoothPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ||
            !hasBluetoothPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        ) {
            Log.w(TAG, "flushViaBluetooth: Bluetooth permission missing; keeping pending payload")
            return false
        }
        val bluetoothEnabled = runCatching { adapter.isEnabled }
            .onFailure { Log.w(TAG, "flushViaBluetooth: cannot read Bluetooth state", it) }
            .getOrDefault(false)
        if (!bluetoothEnabled) {
            Log.w(TAG, "flushViaBluetooth: Bluetooth disabled; keeping pending payload")
            return false
        }

        val watch = findPairedWatch(adapter)
        if (watch == null) {
            Log.w(TAG, "flushViaBluetooth: no paired watch found; keeping pending payload")
            return false
        }

        var socket: BluetoothSocket? = null
        try {
            if (Thread.currentThread().isInterrupted) return false
            val connectedSocket = watch.createRfcommSocketToServiceRecord(RFCOMM_UUID)
            socket = connectedSocket
            registerActiveSocket(connectedSocket)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("flush cancelled")
            // cancelDiscovery() itself requires BLUETOOTH_SCAN on Android 12+. Re-check at
            // the call site in case the permission was revoked after the initial guard.
            if (hasBluetoothPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
                runCatching { adapter.cancelDiscovery() }
                    .onFailure { Log.w(TAG, "flushViaBluetooth: cancelDiscovery failed", it) }
            }
            withSocketWatchdog(
                socket = connectedSocket,
                timeoutMs = RFCOMM_CONNECT_TIMEOUT_MS,
                operation = "connect",
            ) {
                connectedSocket.connect()
            }

            ScheduleRfcommProtocol.writeFrame(connectedSocket.outputStream, bytes)

            // Wait for the watch's single-byte ACK so we know the payload was fully consumed.
            val ack = withSocketWatchdog(
                socket = connectedSocket,
                timeoutMs = RFCOMM_ACK_TIMEOUT_MS,
                operation = "ACK read",
            ) {
                connectedSocket.inputStream.read()
            }
            if (ack == ScheduleRfcommProtocol.ACK_BYTE) {
                val cleared = clearPendingAfterBluetooth(context, payload)
                if (cleared) {
                    Log.d(TAG, "flushViaBluetooth: ACK received, pending payload cleared")
                    return true
                }
                Log.d(TAG, "flushViaBluetooth: ACK received, but newer or uncleared payload remains")
                return false
            } else {
                Log.w(TAG, "flushViaBluetooth: unexpected ACK byte=$ack")
                return false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.d(TAG, "flushViaBluetooth: transfer cancelled")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "flushViaBluetooth: transfer failed", e)
            return false
        } finally {
            socket?.let(::unregisterActiveSocket)
            runCatching { socket?.close() }
        }
    }

    /** Clears the pending payload only if it hasn't been replaced by a newer snapshot. */
    private fun clearPendingAfterBluetooth(context: Context, expected: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(stateLock) {
            if (prefs.getString(KEY_PENDING_PAYLOAD, null) == expected) {
                prefs.edit().remove(KEY_PENDING_PAYLOAD).commit()
            } else {
                false
            }
        }
    }

    /**
     * BluetoothSocket has no connect/read timeout API. Closing it from another thread is
     * the platform-supported way to unblock a stuck connect() or InputStream.read().
     */
    private fun <T> withSocketWatchdog(
        socket: BluetoothSocket,
        timeoutMs: Long,
        operation: String,
        block: () -> T,
    ): T {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("flush cancelled")
        val expired = AtomicBoolean(false)
        val timeout = socketWatchdog.schedule(
            {
                expired.set(true)
                Log.w(TAG, "flushViaBluetooth: $operation timed out after ${timeoutMs}ms")
                runCatching { socket.close() }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block()
        } catch (e: Exception) {
            if (expired.get()) {
                throw SocketTimeoutException("RFCOMM $operation timed out after ${timeoutMs}ms")
                    .apply { initCause(e) }
            }
            throw e
        } finally {
            timeout.cancel(false)
        }
    }

    private fun hasBluetoothPermission(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun findApiException(error: Throwable): ApiException? =
        generateSequence<Throwable>(error) { current ->
            current.cause?.takeUnless { cause -> cause === current }
        }.filterIsInstance<ApiException>().firstOrNull()

    private fun registerActiveSocket(socket: BluetoothSocket) {
        synchronized(activeSocketLock) {
            activeSockets[Thread.currentThread()] = socket
        }
    }

    private fun unregisterActiveSocket(socket: BluetoothSocket) {
        synchronized(activeSocketLock) {
            val thread = Thread.currentThread()
            if (activeSockets[thread] === socket) activeSockets.remove(thread)
        }
    }

    /** Cancels only the transport owned by [thread], leaving any newer JobScheduler run intact. */
    internal fun cancelFlush(thread: Thread) {
        thread.interrupt()
        val socket = synchronized(activeSocketLock) { activeSockets.remove(thread) }
        runCatching { socket?.close() }
    }

    /**
     * Scans bonded devices for a watch-class device.
     *
     * Wear OS devices report [BluetoothClass.Device.WEARABLE_WRIST_WATCH] (0x0704)
     * in their BluetoothClass. If none match by class, the first bonded device whose
     * name contains "watch" (case-insensitive) is returned as a best-effort fallback.
     */
    @SuppressLint("MissingPermission")
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
            runCatching {
                device.name?.contains("watch", ignoreCase = true) == true
            }.getOrDefault(false)
        }?.let { return it }

        Log.d(TAG, "findPairedWatch: no watch-class device among ${bonded.size} bonded")
        return null
    }

    private fun scheduleJob(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
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
            return false
        }
        return true
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

    private fun buildTaskSnapshots(events: List<ScheduleItem>): List<TaskSnapshot> {
        val result = ArrayList<TaskSnapshot>(minOf(events.size, MAX_TASK_COUNT))
        val candidates = events.mapNotNull { event ->
            val id = event.id.trim().takeIf { id ->
                id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= MAX_TASK_ID_UTF8_BYTES
            } ?: return@mapNotNull null
            val title = boundedTaskTitle(event.title).takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            TaskSnapshot(
                id = id,
                title = title,
                type = event.type,
                startEpochMs = event.start.toEpochMilli(),
                endEpochMs = event.end.toEpochMilli(),
                checklistJson = encodeChecklist(event.checklist),
            )
        }.sortedWith(taskOrder)
        for (task in candidates) {
            if (result.size >= MAX_TASK_COUNT) break
            val candidate = result + task
            if (taskArray(candidate).toString().toByteArray(Charsets.UTF_8).size > MAX_TASKS_UTF8_BYTES) {
                break
            }
            result += task
        }
        return result
    }

    private fun boundedTaskTitle(raw: String): String {
        val normalized = raw.trim().replace(taskWhitespace, " ")
        if (normalized.isEmpty()) return ""
        val result = StringBuilder()
        var index = 0
        var codePoints = 0
        var utf8Bytes = 0
        while (index < normalized.length && codePoints < MAX_TASK_TITLE_CODE_POINTS) {
            val codePoint = normalized.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val byteCount = text.toByteArray(Charsets.UTF_8).size
            if (utf8Bytes + byteCount > MAX_TASK_TITLE_UTF8_BYTES) break
            result.appendCodePoint(codePoint)
            utf8Bytes += byteCount
            codePoints++
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun taskArray(tasks: List<TaskSnapshot>): JSONArray = JSONArray().apply {
        tasks.forEach { task ->
            put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                // Optional in schema 3 so an older watch can ignore it while retaining the
                // established tasksHash contract. New watch builds use it for the real icon.
                put("type", task.type)
                put("startEpochMs", task.startEpochMs)
                put("endEpochMs", task.endEpochMs)
                if (task.checklistJson.isNotEmpty()) {
                    put("checklist", org.json.JSONArray(task.checklistJson))
                }
            })
        }
    }

    private fun tasksHash(tasks: List<TaskSnapshot>): String {
        val canonical = buildString {
            append("tasks=1")
            tasks.forEach { task ->
                append('|')
                appendHashField(task.id)
                appendHashField(task.title)
                append(task.startEpochMs).append(':').append(task.endEpochMs)
                appendHashField(task.checklistJson)
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.appendHashField(value: String) {
        append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(':')
    }

    private val WATCH_TASK_TYPES = setOf("event", "status", "task")

    private fun encodeChecklist(items: List<com.hamhuo.tplanner.CheckItem>): String {
        if (items.isEmpty()) return ""
        return JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("completed", item.completed)
                })
            }
        }.toString()
    }
}
