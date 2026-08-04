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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Durable Watch -> Phone wake queue.
 *
 * The queue is committed before transport starts. Its head is published as a DataItem so
 * Google Play services can deliver it after reconnection; MessageClient carries the same
 * request as an optional low-latency fast path. Only a phone ACK removes the request.
 */
object PhoneWaker {
    internal const val MESSAGE_PATH = "/tplanner/wake"
    internal const val REQUEST_PATH = "/tplanner/wake/request"
    internal const val ACK_PATH = "/tplanner/wake/ack"

    private const val TAG = "TplannerWearWake"
    private const val SCHEMA_VERSION = 1
    private const val PREFS = "tplanner_wake_outbox"
    private const val KEY_PENDING = "pending_requests"
    private const val INITIAL_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 5 * 60_000L
    private const val REQUEST_TTL_MS = 30 * 60_000L
    private const val MAX_PENDING_REQUESTS = 16
    private const val OUTBOX_JOB_ID = 0x545057

    private data class PendingRequest(
        val requestId: String,
        val createdAtEpochMs: Long,
        val attempt: Int,
        val legacyMessageSent: Boolean,
    )

    private val stateLock = Any()
    private val transportLock = Any()
    private val workerLock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tplanner-wake-outbox").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var running = false
    private var runAgainImmediately = false

    fun wakeUpPhone(context: Context) {
        val appContext = context.applicationContext
        val request = PendingRequest(
            requestId = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            attempt = 0,
            legacyMessageSent = false,
        )
        synchronized(stateLock) {
            val queue = readQueue(appContext)
            pruneExpired(queue, request.createdAtEpochMs)
            while (queue.size >= MAX_PENDING_REQUESTS) {
                val dropped = queue.removeAt(0)
                Log.w(TAG, "wakeUpPhone: dropped old request=${dropped.requestId}")
            }
            queue += request
            if (!writeQueue(appContext, queue)) {
                Log.e(TAG, "wakeUpPhone: failed to persist request=${request.requestId}")
            }
        }
        Log.d(TAG, "wakeUpPhone: queued request=${request.requestId}")
        schedulePersistentJob(appContext)
        scheduleFlush(appContext, 0L)
    }

    /** Re-publishes a request left pending by a previous process. */
    fun resumePending(context: Context) {
        val appContext = context.applicationContext
        val hasPending = synchronized(stateLock) { readQueue(appContext).isNotEmpty() }
        if (hasPending) {
            schedulePersistentJob(appContext)
            scheduleFlush(appContext, 0L)
        }
    }

    internal fun acknowledge(context: Context, requestId: String) {
        if (requestId.isBlank()) return
        val appContext = context.applicationContext
        // Commit the terminal ACK while WearableListenerService is still alive. Transport
        // cleanup may be retried, but a process kill must not resurrect this request.
        val result = synchronized(stateLock) {
            val queue = readQueue(appContext)
            val changed = queue.removeAll { it.requestId == requestId }
            if (!changed) {
                Pair(false, false)
            } else if (!writeQueue(appContext, queue)) {
                Log.e(TAG, "acknowledge: failed to persist ACK request=$requestId")
                Pair(false, false)
            } else {
                Pair(true, queue.isEmpty())
            }
        }
        if (!result.first) return
        Log.d(TAG, "acknowledge: completed request=$requestId")
        if (result.second) cancelPersistentJob(appContext) else schedulePersistentJob(appContext)
        // Flush the next queued request, or replace the acknowledged DataItem with idle state.
        scheduleFlush(appContext, 0L)
    }

    private fun scheduleFlush(context: Context, delayMs: Long) {
        synchronized(workerLock) {
            if (running) {
                if (delayMs == 0L) runAgainImmediately = true
                return
            }
            val current = scheduled
            if (current != null && !current.isDone) {
                if (delayMs > 0L) return
                current.cancel(false)
            }
            scheduled = worker.schedule(
                {
                    synchronized(workerLock) {
                        scheduled = null
                        running = true
                    }
                    val retryDelay = try {
                        synchronized(transportLock) { flushHead(context) }
                    } catch (e: Exception) {
                        Log.e(TAG, "flushHead: unexpected failure", e)
                        INITIAL_RETRY_MS
                    }
                    val runImmediately = synchronized(workerLock) {
                        running = false
                        val requested = runAgainImmediately
                        runAgainImmediately = false
                        requested
                    }
                    when {
                        runImmediately -> scheduleFlush(context, 0L)
                        retryDelay != null -> scheduleFlush(context, retryDelay)
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Returns the delay before the next retry, or null when the queue is empty. */
    private fun flushHead(context: Context): Long? {
        val head = synchronized(stateLock) {
            val queue = readQueue(context)
            val expired = pruneExpired(queue, System.currentTimeMillis())
            if (expired && !writeQueue(context, queue)) {
                Log.e(TAG, "flushHead: failed to persist expired-request cleanup")
            }
            val current = queue.firstOrNull()
            if (current == null) {
                null
            } else {
                val updated = current.copy(attempt = current.attempt + 1)
                queue[0] = updated
                if (!writeQueue(context, queue)) {
                    Log.e(TAG, "flushHead: failed to persist attempt=${updated.attempt}")
                }
                updated
            }
        }
        if (head == null) {
            publishIdle(context)
            return null
        }

        val payload = encodeRequest(head)

        try {
            val request = PutDataRequest.create(REQUEST_PATH).setUrgent().apply { data = payload }
            Tasks.await(
                Wearable.getDataClient(context).putDataItem(request),
                10,
                TimeUnit.SECONDS,
            )
            Log.d(TAG, "flushHead: DataItem stored request=${head.requestId} attempt=${head.attempt}")
        } catch (e: Exception) {
            Log.e(TAG, "flushHead: DataItem publish failed request=${head.requestId}", e)
        }

        // Every queued request gets its own legacy fast path. An old phone never ACKs the durable
        // DataItem, so limiting MessageClient to the queue head would block all later watch taps
        // behind that head until its TTL expired.
        flushLegacyMessages(context)

        val exponent = (head.attempt - 1).coerceIn(0, 6)
        return (INITIAL_RETRY_MS * (1L shl exponent)).coerceAtMost(MAX_RETRY_MS)
    }

    private fun flushLegacyMessages(context: Context) {
        val unsent = synchronized(stateLock) {
            readQueue(context).filterNot(PendingRequest::legacyMessageSent)
        }
        if (unsent.isEmpty()) return
        val nodes = try {
            Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                3,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "no legacy connection", e)
            return
        }
        unsent.forEach { pending ->
            var sent = false
            for (node in nodes) {
                runCatching {
                    Tasks.await(
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, MESSAGE_PATH, encodeRequest(pending)),
                        3,
                        TimeUnit.SECONDS,
                    )
                }.onSuccess {
                    sent = true
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "legacy fast path failed request=${pending.requestId} " +
                            "node=${node.displayName}",
                        error,
                    )
                }
            }
            if (sent) {
                synchronized(stateLock) {
                    val queue = readQueue(context)
                    val index = queue.indexOfFirst { it.requestId == pending.requestId }
                    if (index >= 0 && !queue[index].legacyMessageSent) {
                        queue[index] = queue[index].copy(legacyMessageSent = true)
                        if (!writeQueue(context, queue)) {
                            Log.e(TAG, "flushHead: failed to persist legacy send marker")
                        }
                    }
                }
            }
        }
    }

    private fun encodeRequest(request: PendingRequest): ByteArray = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("requestId", request.requestId)
        put("createdAtEpochMs", request.createdAtEpochMs)
        put("attempt", request.attempt)
        put("publishedAtEpochMs", System.currentTimeMillis())
    }.toString().toByteArray(Charsets.UTF_8)

    private fun publishIdle(context: Context) {
        try {
            val payload = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("state", "idle")
                put("updatedAtEpochMs", System.currentTimeMillis())
            }
            val request = PutDataRequest.create(REQUEST_PATH).setUrgent().apply {
                data = payload.toString().toByteArray(Charsets.UTF_8)
            }
            Tasks.await(
                Wearable.getDataClient(context).putDataItem(request),
                10,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "publishIdle: failed", e)
        }
    }

    /** Entry point held alive by [WakeOutboxJobService]. */
    internal fun flushFromJob(context: Context): Boolean {
        val appContext = context.applicationContext
        return synchronized(transportLock) { flushHead(appContext) != null }
    }

    private fun schedulePersistentJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            OUTBOX_JOB_ID,
            ComponentName(context, WakeOutboxJobService::class.java),
        )
            .setPersisted(true)
            .setMinimumLatency(0L)
            .setOverrideDeadline(INITIAL_RETRY_MS)
            .setBackoffCriteria(INITIAL_RETRY_MS * 2, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "schedulePersistentJob: scheduler rejected job")
        }
    }

    private fun cancelPersistentJob(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(OUTBOX_JOB_ID)
    }

    private fun pruneExpired(queue: MutableList<PendingRequest>, now: Long): Boolean {
        return queue.removeAll { request ->
            request.createdAtEpochMs <= 0L || now - request.createdAtEpochMs > REQUEST_TTL_MS
        }
    }

    private fun readQueue(context: Context): MutableList<PendingRequest> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null)
            ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNullTo(mutableListOf()) { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNullTo null
                val requestId = item.optString("requestId")
                if (requestId.isBlank()) return@mapNotNullTo null
                PendingRequest(
                    requestId = requestId,
                    createdAtEpochMs = item.optLong("createdAtEpochMs", 0L),
                    attempt = item.optInt("attempt", 0).coerceAtLeast(0),
                    legacyMessageSent = item.optBoolean("legacyMessageSent", false),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "readQueue: invalid outbox", e)
            mutableListOf()
        }
    }

    private fun writeQueue(context: Context, queue: List<PendingRequest>): Boolean {
        val array = JSONArray()
        queue.forEach { request ->
            array.put(JSONObject().apply {
                put("requestId", request.requestId)
                put("createdAtEpochMs", request.createdAtEpochMs)
                put("attempt", request.attempt)
                put("legacyMessageSent", request.legacyMessageSent)
            })
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING, array.toString())
            .commit()
    }
}
