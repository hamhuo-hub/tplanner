package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Durable watch-side queue for new tasks. A transport send never removes an entry. */
object WatchTaskOutbox {
    private const val TAG = "TplannerTaskOutbox"
    private const val PREFS = "tplanner_watch_task_outbox"
    private const val KEY_PENDING = "pending_requests"
    private const val KEY_FAILED = "failed_requests"
    private const val KEY_CORRUPT_BACKUP = "corrupt_pending_backup"
    private const val MAX_PENDING = 32
    private const val JOB_ID = 0x545054
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val RESPONSE_TIMEOUT_MS = 25_000L

    private val stateLock = Any()
    private val transportLock = Any()
    private val activeSocketLock = Any()
    private val activeSockets = mutableMapOf<Thread, BluetoothSocket>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-task-outbox").apply { isDaemon = true }
    }
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-task-watchdog").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }

    fun enqueue(context: Context, draft: WatchTaskDraft): Boolean {
        val now = System.currentTimeMillis()
        val request = WatchTaskProtocol.Request(
            requestId = UUID.randomUUID().toString(),
            createdAtEpochMs = draft.updatedAtEpochMs.takeIf { it > 0L } ?: now,
            task = WatchTaskProtocol.Task(
                id = draft.id,
                title = draft.title,
                type = draft.type,
                startEpochMs = draft.startEpochMs,
                endEpochMs = draft.endEpochMs,
                colorId = draft.colorId,
                alarmEnabled = draft.alarmEnabled,
                alarmOffsetMinutes = draft.alarmOffsetMinutes,
            ),
            publishedAtEpochMs = now,
        )
        return enqueueRequest(context, request)
    }

    /** Enqueues a durable delete request. Cancels any still-pending create for the same task. */
    fun enqueueDelete(context: Context, taskId: String): Boolean {
        removePendingCreates(context, taskId)
        val request = WatchTaskProtocol.Request(
            requestId = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            task = null,
            taskId = taskId,
            publishedAtEpochMs = System.currentTimeMillis(),
        )
        return enqueueRequest(context, request)
    }

    private fun enqueueRequest(context: Context, request: WatchTaskProtocol.Request): Boolean {
        val encoded = try {
            WatchTaskProtocol.encodeRequest(request)
        } catch (error: Exception) {
            Log.e(TAG, "enqueueRequest: invalid request", error)
            return false
        }
        val appContext = context.applicationContext
        val committed = synchronized(stateLock) {
            val pending = readArray(appContext, KEY_PENDING)
            if (pending.length() >= MAX_PENDING) return@synchronized false
            pending.put(encoded)
            writeArray(appContext, KEY_PENDING, pending)
        }
        if (!committed) return false
        schedulePersistentJob(appContext)
        flushAsync(appContext)
        return true
    }

    /** Drops still-pending create entries for [taskId] so they don't race with the delete. */
    private fun removePendingCreates(context: Context, taskId: String) {
        synchronized(stateLock) {
            listOf(KEY_PENDING, KEY_FAILED).forEach { key ->
                val arr = readArray(context, key)
                val retained = JSONArray()
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i)
                    val isMatchingCreate = runCatching {
                        val r = WatchTaskProtocol.decodeRequest(raw)
                        r.task?.id == taskId
                    }.getOrDefault(false)
                    if (isMatchingCreate) {
                        runCatching {
                            WatchTaskProtocol.decodeRequest(raw).requestId
                        }.getOrNull()?.let { cleanupDataItems(context, it) }
                    } else if (raw.isNotBlank()) {
                        retained.put(raw)
                    }
                }
                if (retained.length() != arr.length()) writeArray(context, key, retained)
            }
        }
    }

    fun resumePending(context: Context) {
        val appContext = context.applicationContext
        if (!hasPending(appContext)) return
        schedulePersistentJob(appContext)
        flushAsync(appContext)
    }

    /** Pending tasks are merged into the watch list until the phone's refreshed snapshot arrives. */
    fun pendingTasks(context: Context): List<WatchTaskDraft> = synchronized(stateLock) {
        val appContext = context.applicationContext
        val queued = listOf(KEY_PENDING, KEY_FAILED).flatMap { key ->
            val source = readArray(appContext, key)
            (0 until source.length()).map { index -> source.optString(index) }
        }
        queued.mapNotNull { raw ->
            val value = raw.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val request = runCatching { WatchTaskProtocol.decodeRequest(value) }.getOrNull()
                ?: return@mapNotNull null
            val task = request.task ?: return@mapNotNull null // skip deletes
            WatchTaskDraft(
                id = task.id,
                title = task.title,
                type = task.type,
                startEpochMs = task.startEpochMs,
                endEpochMs = task.endEpochMs,
                alarmEnabled = task.alarmEnabled,
                alarmOffsetMinutes = task.alarmOffsetMinutes,
                colorId = task.colorId,
                updatedAtEpochMs = request.createdAtEpochMs,
            )
        }
    }

    internal fun handleResponse(context: Context, response: WatchTaskProtocol.Response) {
        if (!response.status.terminal) return
        val appContext = context.applicationContext
        var removed = false
        synchronized(stateLock) {
            val pending = readArray(appContext, KEY_PENDING)
            val retained = JSONArray()
            var rejectedRaw: String? = null
            for (index in 0 until pending.length()) {
                val raw = pending.optString(index)
                val requestId = runCatching {
                    WatchTaskProtocol.decodeRequest(raw).requestId
                }.getOrNull()
                if (requestId == response.requestId) {
                    removed = true
                    if (response.status == WatchTaskProtocol.Status.REJECTED) {
                        rejectedRaw = raw
                    }
                } else if (raw.isNotBlank()) {
                    retained.put(raw)
                }
            }
            if (removed && writeArray(appContext, KEY_PENDING, retained) && rejectedRaw != null) {
                val failed = readArray(appContext, KEY_FAILED)
                failed.put(rejectedRaw)
                writeArray(appContext, KEY_FAILED, failed)
            }
        }
        if (!removed) return
        cleanupDataItems(appContext, response.requestId)
        if (hasPending(appContext)) {
            schedulePersistentJob(appContext)
            flushAsync(appContext)
        } else {
            cancelPersistentJob(appContext)
        }
    }

    internal fun flushFromJob(context: Context): Boolean {
        synchronized(transportLock) { flushHead(context.applicationContext) }
        return hasPending(context.applicationContext)
    }

    internal fun cancelFlush(thread: Thread) {
        thread.interrupt()
        val socket = synchronized(activeSocketLock) { activeSockets.remove(thread) }
        runCatching { socket?.close() }
    }

    private fun flushAsync(context: Context) {
        worker.execute {
            synchronized(transportLock) { flushHead(context.applicationContext) }
        }
    }

    private fun flushHead(context: Context) {
        if (Thread.currentThread().isInterrupted) return
        val request = prepareHead(context) ?: return
        publishDataItem(context, request)
        if (!Thread.currentThread().isInterrupted && isStillPending(context, request.requestId)) {
            sendViaBluetooth(context, request)
        }
    }

    private fun prepareHead(context: Context): WatchTaskProtocol.Request? {
        return synchronized(stateLock) {
            var prepared: WatchTaskProtocol.Request? = null
            while (prepared == null) {
                val pending = readArray(context, KEY_PENDING)
                val raw = pending.optString(0).takeIf(String::isNotBlank)
                    ?: return@synchronized null
                val decoded = runCatching { WatchTaskProtocol.decodeRequest(raw) }
                    .onFailure { Log.e(TAG, "flushHead: corrupt request", it) }
                    .getOrNull()
                if (decoded == null) {
                    pending.remove(0)
                    val failed = readArray(context, KEY_FAILED).apply { put(raw) }
                    if (!writeArray(context, KEY_FAILED, failed) ||
                        !writeArray(context, KEY_PENDING, pending)
                    ) {
                        return@synchronized null
                    }
                    continue
                }
                val updated = decoded.copy(
                    attempt = decoded.attempt + 1,
                    publishedAtEpochMs = System.currentTimeMillis(),
                )
                pending.put(0, WatchTaskProtocol.encodeRequest(updated))
                if (!writeArray(context, KEY_PENDING, pending)) return@synchronized null
                prepared = updated
            }
            prepared
        }
    }

    private fun publishDataItem(context: Context, request: WatchTaskProtocol.Request) {
        runCatching {
            val bytes = WatchTaskProtocol.encodeRequest(request).toByteArray(Charsets.UTF_8)
            val put = PutDataRequest.create(
                WatchTaskProtocol.requestPath(request),
            ).setUrgent().apply { data = bytes }
            Tasks.await(Wearable.getDataClient(context).putDataItem(put), 10, TimeUnit.SECONDS)
            Log.d(TAG, "DataItem stored request=${request.requestId}")
        }.onFailure { error ->
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Data Layer unavailable; using Bluetooth", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBluetooth(context: Context, request: WatchTaskProtocol.Request) {
        if (!hasBluetoothConnectPermission(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return
        val phone = findPairedPhone(adapter) ?: return
        var socket: BluetoothSocket? = null
        try {
            val connected = phone.createRfcommSocketToServiceRecord(WatchTaskProtocol.RFCOMM_UUID)
            socket = connected
            registerActiveSocket(connected)
            if (Thread.currentThread().isInterrupted) return
            withSocketWatchdog(connected, CONNECT_TIMEOUT_MS, "connect") { connected.connect() }
            ScheduleRfcommProtocol.writeFrame(
                connected.outputStream,
                WatchTaskProtocol.encodeRequest(request).toByteArray(Charsets.UTF_8),
            )
            val rawResponse = withSocketWatchdog(connected, RESPONSE_TIMEOUT_MS, "response") {
                ScheduleRfcommProtocol.readFrame(connected.inputStream)
            }
            val response = WatchTaskProtocol.decodeResponse(rawResponse)
            if (response.requestId == request.requestId) {
                handleResponse(context, response)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Bluetooth send failed request=${request.requestId}", error)
        } finally {
            socket?.let(::unregisterActiveSocket)
            runCatching { socket?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findPairedPhone(adapter: BluetoothAdapter): BluetoothDevice? = runCatching {
        adapter.bondedDevices.firstOrNull { device ->
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        }
    }.getOrNull()

    private fun <T> withSocketWatchdog(
        socket: BluetoothSocket,
        timeoutMs: Long,
        operation: String,
        block: () -> T,
    ): T {
        val expired = AtomicBoolean(false)
        val timeout = watchdog.schedule(
            {
                expired.set(true)
                runCatching { socket.close() }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block()
        } catch (error: Exception) {
            if (expired.get()) {
                throw SocketTimeoutException("RFCOMM $operation timed out").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            timeout.cancel(false)
        }
    }

    private fun cleanupDataItems(context: Context, requestId: String) {
        worker.execute {
            listOf(
                WatchTaskProtocol.requestPath(requestId),
                WatchTaskProtocol.deleteRequestPath(requestId),
                WatchTaskProtocol.ackPath(requestId),
            ).forEach { path ->
                runCatching {
                    Tasks.await(
                        Wearable.getDataClient(context).deleteDataItems(Uri.parse("wear://*$path")),
                        5,
                        TimeUnit.SECONDS,
                    )
                }
            }
        }
    }

    private fun isStillPending(context: Context, requestId: String): Boolean = synchronized(stateLock) {
        val pending = readArray(context, KEY_PENDING)
        (0 until pending.length()).any { index ->
            runCatching {
                WatchTaskProtocol.decodeRequest(pending.optString(index)).requestId == requestId
            }.getOrDefault(false)
        }
    }

    private fun hasPending(context: Context): Boolean = synchronized(stateLock) {
        readArray(context, KEY_PENDING).length() > 0
    }

    private fun readArray(context: Context, key: String): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { error ->
            Log.e(TAG, "Corrupt outbox array key=$key; preserving raw backup", error)
            if (key == KEY_PENDING) {
                prefs.edit().putString(KEY_CORRUPT_BACKUP, raw).commit()
            }
            JSONArray()
        }
    }

    private fun writeArray(context: Context, key: String, value: JSONArray): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value.toString())
            .commit()

    private fun schedulePersistentJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WatchTaskOutboxJobService::class.java),
        ).setPersisted(true)
            .setMinimumLatency(5_000L)
            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = runCatching { scheduler.schedule(job) }
            .onFailure { Log.e(TAG, "Unable to schedule persistent retry", it) }
            .getOrDefault(JobScheduler.RESULT_FAILURE)
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.e(TAG, "Persistent retry scheduling failed result=$result")
        }
    }

    private fun cancelPersistentJob(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    private fun hasBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerActiveSocket(socket: BluetoothSocket) {
        synchronized(activeSocketLock) { activeSockets[Thread.currentThread()] = socket }
    }

    private fun unregisterActiveSocket(socket: BluetoothSocket) {
        synchronized(activeSocketLock) {
            if (activeSockets[Thread.currentThread()] === socket) {
                activeSockets.remove(Thread.currentThread())
            }
        }
    }
}

/** System-owned retry entry point for commands that have not received a phone ACK. */
class WatchTaskOutboxJobService : JobService() {
    private val runLock = Any()
    private var activeThread: Thread? = null
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val thread = Thread({
            val shouldRetry = runCatching {
                WatchTaskOutbox.flushFromJob(applicationContext)
            }.onFailure { Log.e(TAG, "flush failed", it) }
                .getOrDefault(true)
            val shouldFinish = synchronized(runLock) {
                if (activeThread === Thread.currentThread() && activeParams === params) {
                    activeThread = null
                    activeParams = null
                    true
                } else {
                    false
                }
            }
            if (shouldFinish) jobFinished(params, shouldRetry)
        }, "tplanner-task-outbox-job")
        val previous = synchronized(runLock) {
            val old = activeThread
            activeThread = thread
            activeParams = params
            old
        }
        previous?.let(WatchTaskOutbox::cancelFlush)
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val thread = synchronized(runLock) {
            if (activeParams === params) {
                activeThread.also {
                    activeThread = null
                    activeParams = null
                }
            } else {
                null
            }
        }
        thread?.let(WatchTaskOutbox::cancelFlush)
        return true
    }

    private companion object {
        const val TAG = "TplannerTaskJob"
    }
}
