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
    private const val KEY_PUBLISHED = "snapshot_published_receipts"
    private const val KEY_INSTALLED_SOURCE_VERSION = "installed_projection_source_version"
    private const val KEY_PHONE_STORED = "phone_stored_requests"
    private const val KEY_STORAGE_VERSION = "storage_version"
    private const val KEY_CORRUPT_BACKUP = "corrupt_pending_backup"
    private const val STORAGE_VERSION = 2
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
        val request = WatchTaskProtocol.withSemanticCommands(WatchTaskProtocol.Request(
            requestId = WatchTaskProtocol.newEnvelopeId(),
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
        ))
        return enqueueRequest(context, request)
    }

    /**
     * Enqueues a durable delete. If the task still has a pending create, the delete carries a
     * persistent dependency and is held until PHONE_STORED proves that the phone owns the create.
     */
    fun enqueueDelete(context: Context, taskId: String): Boolean {
        val appContext = context.applicationContext
        val committed = synchronized(stateLock) {
            if (!ensureStorageUpgradedLocked(appContext)) return@synchronized false
            val pending = readArray(appContext, KEY_PENDING)
            if (pending.length() >= MAX_PENDING) return@synchronized false
            val predecessor = (0 until pending.length()).mapNotNull { index ->
                runCatching {
                    WatchTaskProtocol.decodeRequest(pending.optString(index))
                }.getOrNull()
            }.lastOrNull { it.task?.id == taskId }
            val now = System.currentTimeMillis()
            val request = WatchTaskProtocol.withSemanticCommands(WatchTaskProtocol.Request(
                requestId = WatchTaskProtocol.newEnvelopeId(),
                createdAtEpochMs = now,
                task = null,
                taskId = taskId,
                dependsOnRequestId = predecessor?.requestId,
                publishedAtEpochMs = now,
            ))
            val encoded = runCatching { WatchTaskProtocol.encodeRequest(request) }
                .onFailure { Log.e(TAG, "enqueueDelete: invalid request", it) }
                .getOrNull() ?: return@synchronized false
            pending.put(encoded)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING, pending.toString())
                .putInt(KEY_STORAGE_VERSION, STORAGE_VERSION)
                .commit()
        }
        if (!committed) return false
        schedulePersistentJob(appContext)
        flushAsync(appContext)
        return true
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
            if (!ensureStorageUpgradedLocked(appContext)) return@synchronized false
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

    fun resumePending(context: Context) {
        val appContext = context.applicationContext
        if (!recoverProjectionWatermark(appContext)) return
        val upgraded = synchronized(stateLock) { ensureStorageUpgradedLocked(appContext) }
        if (!upgraded) return
        if (!hasPending(appContext)) return
        schedulePersistentJob(appContext)
        flushAsync(appContext)
    }

    /** Pending tasks are merged into the watch list until the phone's refreshed snapshot arrives. */
    fun pendingTasks(context: Context): List<WatchTaskDraft> {
        val appContext = context.applicationContext
        if (!recoverProjectionWatermark(appContext)) return emptyList()
        return synchronized(stateLock) {
            if (!ensureStorageUpgradedLocked(appContext)) return@synchronized emptyList()
            fun requests(key: String): List<WatchTaskProtocol.Request> {
                val source = readArray(appContext, key)
                return (0 until source.length()).map { index -> source.optString(index) }
                    .mapNotNull { raw ->
                        raw.takeIf(String::isNotBlank)?.let { value ->
                            runCatching { WatchTaskProtocol.decodeRequest(value) }.getOrNull()
                        }
                    }
            }
            val pending = requests(KEY_PENDING)
            val failed = requests(KEY_FAILED)
            // A rejected/failed delete is not an optimistic fact. Only the durable pending queue
            // may supersede a create or hide an authoritative projection.
            val hiddenCreateIds = WatchTaskProtocol.supersededCreateRequestIds(pending)
            (pending + failed).mapNotNull { request ->
                if (request.requestId in hiddenCreateIds) return@mapNotNull null
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
    }

    /** Single durable source for optimistic delete visibility, including process restart. */
    fun pendingDeleteTaskIds(context: Context): Set<String> {
        val appContext = context.applicationContext
        return synchronized(stateLock) {
            if (!ensureStorageUpgradedLocked(appContext)) return@synchronized emptySet()
            val source = readArray(appContext, KEY_PENDING)
            val requests = (0 until source.length()).mapNotNull { index ->
                runCatching { WatchTaskProtocol.decodeRequest(source.optString(index)) }.getOrNull()
            }
            WatchTaskProtocol.pendingDeleteTaskIds(requests)
        }
    }

    internal fun handleResponse(context: Context, response: WatchTaskProtocol.Response) {
        val appContext = context.applicationContext
        if (!recoverProjectionWatermark(appContext)) return
        val request = synchronized(stateLock) {
            if (!ensureStorageUpgradedLocked(appContext)) null
            else findPending(appContext, response.requestId)
        } ?: return
        val expectedCommands = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId).toSet()
        if (response.commandIds.isNotEmpty() && response.commandIds.toSet() != expectedCommands) {
            Log.w(TAG, "Ignoring ACK with mismatched command identity request=${response.requestId}")
            return
        }

        when (response.status) {
            WatchTaskProtocol.Status.PHONE_STORED -> {
                // PHONE_STORED is deliberately non-terminal. Keep durable pending until the
                // central snapshot is projected back onto this watch. The durable barrier also
                // unlocks a dependent delete without relying on volatile callback order.
                if (synchronized(stateLock) { markPhoneStoredLocked(appContext, response.requestId) }) {
                    schedulePersistentJob(appContext)
                    flushAsync(appContext)
                }
            }

            WatchTaskProtocol.Status.RETRY -> schedulePersistentJob(appContext)

            WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED -> {
                val snapshotVersion = response.snapshotVersion ?: return
                val installed = synchronized(stateLock) {
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val receipts = readPublishedReceipts(appContext)
                    val phoneStored = readPhoneStored(appContext)
                    receipts.put(response.requestId, WatchTaskProtocol.encodeResponse(response))
                    phoneStored.put(response.requestId, response.acknowledgedAtEpochMs)
                    val committed = prefs.edit()
                        .putString(KEY_PUBLISHED, receipts.toString())
                        .putString(KEY_PHONE_STORED, phoneStored.toString())
                        .commit()
                    if (committed) prefs.getLong(KEY_INSTALLED_SOURCE_VERSION, 0L) else -1L
                }
                if (installed < 0L) return
                if (WatchOutboxCompletion.decide(request, response, installed) ==
                    WatchOutboxCompletion.Decision.COMPLETE
                ) finishPublished(appContext, response.requestId)
            }

            WatchTaskProtocol.Status.REJECTED -> {
                // A terminal central rejection also proves the predecessor reached the phone;
                // dependent cleanup must not remain blocked forever if PHONE_STORED ACK was lost.
                if (WatchOutboxCompletion.decide(request, response, 0L) ==
                    WatchOutboxCompletion.Decision.MOVE_TO_FAILED
                ) moveToFailed(appContext, response.requestId, provePhoneStored = true)
            }
        }
    }

    /** Called only after ScheduleStore atomically commits the matching global projection. */
    internal fun onProjectionInstalled(context: Context, sourceSnapshotVersion: Long) {
        if (sourceSnapshotVersion <= 0L) return
        val appContext = context.applicationContext
        val ready = synchronized(stateLock) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getLong(KEY_INSTALLED_SOURCE_VERSION, 0L)
            val installed = maxOf(previous, sourceSnapshotVersion)
            if (installed > previous && !prefs.edit()
                    .putLong(KEY_INSTALLED_SOURCE_VERSION, installed)
                    .commit()
            ) {
                return@synchronized emptyList()
            }
            val receipts = readPublishedReceipts(appContext)
            (receipts.keys().asSequence().toList()).filter { requestId ->
                runCatching {
                    WatchTaskProtocol.decodeResponse(receipts.getString(requestId))
                        .snapshotVersion?.let { it <= installed } == true
                }.getOrDefault(false)
            }
        }
        ready.forEach { finishPublished(appContext, it) }
    }

    internal fun flushFromJob(context: Context): Boolean {
        synchronized(transportLock) { flushBatch(context.applicationContext) }
        return hasPending(context.applicationContext)
    }

    internal fun cancelFlush(thread: Thread) {
        thread.interrupt()
        val socket = synchronized(activeSocketLock) { activeSockets.remove(thread) }
        runCatching { socket?.close() }
    }

    private fun flushAsync(context: Context) {
        worker.execute {
            synchronized(transportLock) { flushBatch(context.applicationContext) }
        }
    }

    private fun flushBatch(context: Context) {
        if (Thread.currentThread().isInterrupted) return
        if (!recoverProjectionWatermark(context)) return
        val requests = prepareBatch(context)
        if (requests.isEmpty()) return
        // Data Layer remains one durable item per request identity. A connection flushes the
        // complete queue instead of serially waiting for one head ACK per job run.
        requests.forEach { request ->
            if (Thread.currentThread().isInterrupted) return
            publishDataItem(context, request)
        }
        val stillPending = requests.filter { isStillPending(context, it.requestId) }
        if (!Thread.currentThread().isInterrupted && stillPending.isNotEmpty()) {
            sendViaBluetooth(context, stillPending)
        }
    }

    private fun prepareBatch(context: Context): List<WatchTaskProtocol.Request> = synchronized(stateLock) {
        if (!ensureStorageUpgradedLocked(context)) return@synchronized emptyList()
        val pending = readArray(context, KEY_PENDING)
        val failed = readArray(context, KEY_FAILED)
        val phoneStored = readPhoneStored(context)
        val phoneStoredIds = phoneStored.keys().asSequence().toSet()
        val rewritten = JSONArray()
        val prepared = mutableListOf<WatchTaskProtocol.Request>()
        val now = System.currentTimeMillis()
        for (index in 0 until pending.length()) {
            val raw = pending.optString(index)
            val decoded = runCatching { WatchTaskProtocol.decodeRequest(raw) }
                .onFailure { Log.e(TAG, "prepareBatch: corrupt request", it) }
                .getOrNull()
            if (decoded == null) {
                if (raw.isNotBlank()) failed.put(raw)
                continue
            }
            if (!WatchTaskProtocol.dependencySatisfied(decoded, phoneStoredIds)) {
                // Preserve byte-for-byte and do not increment attempt: this request was not sent.
                rewritten.put(raw)
                continue
            }
            val updated = decoded.copy(attempt = decoded.attempt + 1, publishedAtEpochMs = now)
            rewritten.put(WatchTaskProtocol.encodeRequest(updated))
            prepared += updated
        }
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAILED, failed.toString())
            .putString(KEY_PENDING, rewritten.toString())
            .putInt(KEY_STORAGE_VERSION, STORAGE_VERSION)
            .commit()
        if (!committed) {
            emptyList()
        } else {
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
    private fun sendViaBluetooth(context: Context, requests: List<WatchTaskProtocol.Request>) {
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
                WatchTaskProtocol.encodeRequestBatch(
                    WatchTaskProtocol.RequestBatch(WatchTaskProtocol.newEnvelopeId(), requests),
                ).toByteArray(Charsets.UTF_8),
            )
            val rawResponse = withSocketWatchdog(connected, RESPONSE_TIMEOUT_MS, "response") {
                ScheduleRfcommProtocol.readFrame(connected.inputStream)
            }
            val responseBatch = WatchTaskProtocol.decodeResponseBatch(rawResponse)
            responseBatch.responses.forEach { response ->
                if (requests.any { it.requestId == response.requestId }) handleResponse(context, response)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Bluetooth batch send failed count=${requests.size}", error)
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

    private fun findPending(context: Context, requestId: String): WatchTaskProtocol.Request? {
        val pending = readArray(context, KEY_PENDING)
        for (index in 0 until pending.length()) {
            val request = runCatching {
                WatchTaskProtocol.decodeRequest(pending.optString(index))
            }.getOrNull()
            if (request?.requestId == requestId) return request
        }
        return null
    }

    private fun readPublishedReceipts(context: Context): org.json.JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PUBLISHED, null)?.let { raw ->
            runCatching { org.json.JSONObject(raw) }
                .onFailure { Log.e(TAG, "Corrupt published-receipt ledger", it) }
                .getOrNull()
        } ?: org.json.JSONObject()
    }

    private fun readPhoneStored(context: Context): org.json.JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PHONE_STORED, null)?.let { raw ->
            runCatching { org.json.JSONObject(raw) }
                .onFailure { Log.e(TAG, "Corrupt PHONE_STORED ledger", it) }
                .getOrNull()
        } ?: org.json.JSONObject()
    }

    private fun markPhoneStoredLocked(context: Context, requestId: String): Boolean {
        val phoneStored = readPhoneStored(context)
        phoneStored.put(requestId, System.currentTimeMillis())
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHONE_STORED, phoneStored.toString())
            .commit()
    }

    private fun finishPublished(context: Context, requestId: String) {
        val removed = synchronized(stateLock) {
            val pending = readArray(context, KEY_PENDING)
            val retained = JSONArray()
            var found = false
            for (index in 0 until pending.length()) {
                val raw = pending.optString(index)
                val id = runCatching { WatchTaskProtocol.decodeRequest(raw).requestId }.getOrNull()
                if (id == requestId) found = true else if (raw.isNotBlank()) retained.put(raw)
            }
            val receipts = readPublishedReceipts(context).apply { remove(requestId) }
            val phoneStored = prunedPhoneStored(context, retained)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val committed = prefs.edit()
                .putString(KEY_PENDING, retained.toString())
                .putString(KEY_PUBLISHED, receipts.toString())
                .putString(KEY_PHONE_STORED, phoneStored.toString())
                .commit()
            found && committed
        }
        if (removed) cleanupDataItems(context, requestId)
        if (hasPending(context)) {
            schedulePersistentJob(context)
            flushAsync(context)
        } else {
            cancelPersistentJob(context)
        }
    }

    private fun moveToFailed(
        context: Context,
        requestId: String,
        provePhoneStored: Boolean = false,
    ) {
        val removed = synchronized(stateLock) {
            val pending = readArray(context, KEY_PENDING)
            val retained = JSONArray()
            val failed = readArray(context, KEY_FAILED)
            var found = false
            for (index in 0 until pending.length()) {
                val raw = pending.optString(index)
                val id = runCatching { WatchTaskProtocol.decodeRequest(raw).requestId }.getOrNull()
                if (id == requestId) {
                    found = true
                    failed.put(raw)
                } else if (raw.isNotBlank()) {
                    retained.put(raw)
                }
            }
            val phoneStored = prunedPhoneStored(
                context,
                retained,
                provenRequestId = requestId.takeIf { provePhoneStored },
            )
            val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FAILED, failed.toString())
                .putString(KEY_PENDING, retained.toString())
                .putString(KEY_PHONE_STORED, phoneStored.toString())
                .commit()
            found && committed
        }
        if (removed) cleanupDataItems(context, requestId)
        if (!hasPending(context)) cancelPersistentJob(context)
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
        ensureStorageUpgradedLocked(context) && readArray(context, KEY_PENDING).length() > 0
    }

    /**
     * Converts both v1 arrays in one commit. A failed commit leaves the old arrays and version
     * marker untouched, so callers stop instead of observing a half-migrated queue.
     */
    private fun ensureStorageUpgradedLocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_STORAGE_VERSION, 1) >= STORAGE_VERSION) return true
        fun upgradedArray(key: String): JSONArray? {
            val rawArray = prefs.getString(key, null)
            val source = if (rawArray == null) {
                JSONArray()
            } else {
                runCatching { JSONArray(rawArray) }.onFailure { error ->
                    Log.e(TAG, "Unable to migrate corrupt outbox array key=$key", error)
                }.getOrNull() ?: return null
            }
            val rawValues = (0 until source.length()).map { source.optString(it) }
                .filter(String::isNotBlank)
            val decodedByIndex = rawValues.mapIndexed { index, raw ->
                runCatching { WatchTaskProtocol.decodeCompatibleRequest(raw) }
                    .onFailure { error ->
                        Log.e(TAG, "Unable to migrate watch outbox key=$key index=$index", error)
                    }.getOrNull()
            }
            val linked = WatchTaskProtocol.linkPendingCreateDeleteDependencies(
                decodedByIndex.filterNotNull(),
            ).iterator()
            return JSONArray().apply {
                rawValues.zip(decodedByIndex).forEach { (raw, decoded) ->
                    // Preserve invalid bytes for the normal corrupt-entry path; never drop them
                    // as a side effect of a schema migration.
                    put(if (decoded == null) raw else WatchTaskProtocol.encodeRequest(linked.next()))
                }
            }
        }
        val pending = upgradedArray(KEY_PENDING) ?: return false
        val failed = upgradedArray(KEY_FAILED) ?: return false
        return prefs.edit()
            .putString(KEY_PENDING, pending.toString())
            .putString(KEY_FAILED, failed.toString())
            .putInt(KEY_STORAGE_VERSION, STORAGE_VERSION)
            .commit()
    }

    private fun prunedPhoneStored(
        context: Context,
        pending: JSONArray,
        provenRequestId: String? = null,
    ): org.json.JSONObject {
        val ledger = readPhoneStored(context)
        provenRequestId?.let { ledger.put(it, System.currentTimeMillis()) }
        val requests = (0 until pending.length()).mapNotNull { index ->
            runCatching { WatchTaskProtocol.decodeRequest(pending.optString(index)) }.getOrNull()
        }
        val required = requests.map(WatchTaskProtocol.Request::requestId).toMutableSet().apply {
            addAll(requests.mapNotNull(WatchTaskProtocol.Request::dependsOnRequestId))
        }
        ledger.keys().asSequence().toList()
            .filterNot(required::contains)
            .forEach { requestId -> ledger.remove(requestId) }
        return ledger
    }

    /** Recovers the outbox watermark from the already-committed schedule after process death. */
    private fun recoverProjectionWatermark(context: Context): Boolean {
        val durableVersion = ScheduleStore.installedSourceSnapshotVersion(context)
        val reconciled = synchronized(stateLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cachedVersion = prefs.getLong(KEY_INSTALLED_SOURCE_VERSION, 0L)
            // The schedule value is the durable source of truth. Its removal or restoration to an
            // older backup must not leave a cached watermark that can falsely complete receipts.
            if (durableVersion < cachedVersion) {
                prefs.edit().putLong(KEY_INSTALLED_SOURCE_VERSION, durableVersion).commit()
            } else true
        }
        if (!reconciled) return false
        if (durableVersion > 0L) onProjectionInstalled(context, durableVersion)
        return true
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
