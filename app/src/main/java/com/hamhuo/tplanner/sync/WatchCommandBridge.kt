package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.syncv3.ExternalSyncCommand
import com.hamhuo.tplanner.syncv3.SyncCommandType
import com.hamhuo.tplanner.syncv3.SyncV3CommandRepository
import com.hamhuo.tplanner.syncv3.SyncV3Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Durable phone boundary shared by the Data Layer and RFCOMM transports.
 *
 * The Room command outbox is the source of business writes. This small ledger owns only bridge
 * identity and acknowledgement stage, so process death or delivery through both transports can
 * never enqueue the same semantic envelope twice.
 */
internal object WatchCommandBridge {
    private const val TAG = "TplannerWatchBridge"
    private const val PREFS = "tplanner_watch_command_bridge_v3"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_CORRUPT_BACKUP = "corrupt_entries_backup"
    private const val MAX_LEDGER_ENTRIES = 256
    private const val STAGE_PHONE_STORED = "phone_stored"
    private const val STAGE_SNAPSHOT_PUBLISHED = "snapshot_published"
    private const val STAGE_REJECTED = "rejected"

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-watch-command-bridge").apply { isDaemon = true }
    }

    fun importBlocking(context: Context, request: WatchTaskProtocol.Request): WatchTaskProtocol.Response =
        synchronized(lock) {
            val appContext = context.applicationContext
            val identity = WatchTaskProtocol.identityHash(request)
            val commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId)
            val ledger = readLedger(appContext)
            val gateDecision = WatchBridgeIdentityGate.decide(
                ledgerIdentities(ledger),
                WatchBridgeIdentityGate.Identity(request.requestId, identity, commandIds),
            )
            if (gateDecision == WatchBridgeIdentityGate.Decision.CONFLICT) {
                return@synchronized rejected(request, "IDENTITY_CONFLICT")
            }
            val existing = ledger.optJSONObject(request.requestId)
            if (gateDecision == WatchBridgeIdentityGate.Decision.DUPLICATE && existing != null) {
                val refreshed = responseFromReceipts(appContext, request, existing)
                if (refreshed != null) {
                    putEntry(ledger, request, identity, refreshed)
                    if (!writeLedger(appContext, ledger)) return@synchronized retry(request)
                    if (refreshed.status != WatchTaskProtocol.Status.PHONE_STORED) {
                        WatchTaskAckPublisher.publishAsync(appContext, refreshed)
                    }
                    return@synchronized refreshed
                }
                requestPump(appContext)
                return@synchronized responseFromEntry(request, existing)
            }
            val persisted = runCatching {
                runBlocking(Dispatchers.IO) {
                    SyncV3CommandRepository(appContext, TPlannerDatabase.get(appContext))
                        .enqueueExternalBatch(
                            envelopeId = request.requestId,
                            commands = request.commands.map { command ->
                                ExternalSyncCommand(
                                    commandId = command.commandId,
                                    type = SyncCommandType.fromWire(command.type),
                                    aggregateId = command.aggregateId,
                                    arguments = JSONObject(command.argumentsJson),
                                )
                            },
                        )
                }
            }.onFailure { error ->
                Log.e(TAG, "Unable to persist watch envelope=${request.requestId}", error)
            }.isSuccess
            if (!persisted) return@synchronized retry(request)

            val response = WatchTaskProtocol.Response(
                requestId = request.requestId,
                status = WatchTaskProtocol.Status.PHONE_STORED,
                commandIds = commandIds,
            )
            putEntry(ledger, request, identity, response)
            pruneLedger(ledger)
            if (!writeLedger(appContext, ledger)) return@synchronized retry(request)
            requestPump(appContext)
            response
        }

    /** PR B:手表命令也是用户意图 —— 前台立即排空,WorkManager 兜底。 */
    private fun requestPump(appContext: Context) {
        SyncFeedbackBus.publish(SyncFeedbackEvent.Sending)
        SyncV3ForegroundPump.request(appContext)
        runCatching { SyncV3Scheduler.enqueue(appContext) }
    }

    /** Called only after a projection sourced from [sourceSnapshotVersion] is durably queued. */
    fun onProjectionQueued(
        context: Context,
        sourceSnapshotVersion: Long,
        sourceBrokerToSequence: Long,
    ) {
        if (sourceSnapshotVersion <= 0L) return
        val appContext = context.applicationContext
        worker.execute {
            val published = synchronized(lock) {
                val ledger = readLedger(appContext)
                val responses = mutableListOf<WatchTaskProtocol.Response>()
                val keys = ledger.keys().asSequence().toList()
                keys.forEach { requestId ->
                    val entry = ledger.optJSONObject(requestId) ?: return@forEach
                    if (entry.optString("stage") != STAGE_PHONE_STORED) return@forEach
                    val commandIds = stringList(entry.optJSONArray("commandIds"))
                    val receipts = SyncV3Progress.receipts(appContext, commandIds)
                    if (receipts.size != commandIds.size) return@forEach
                    val ordered = commandIds.mapNotNull(receipts::get)
                    val rejected = ordered.firstOrNull { it.status in FAILURE_RECEIPT_STATUSES }
                    val response = if (rejected != null) {
                        WatchTaskProtocol.Response(
                            requestId = requestId,
                            status = WatchTaskProtocol.Status.REJECTED,
                            commandIds = commandIds,
                            errorCode = rejected.errorCode ?: "COMMAND_REJECTED",
                        )
                    } else {
                        val versions = ordered.mapNotNull { it.snapshotVersion }
                        // A batch made entirely of NOOP/idempotent outcomes legitimately has no
                        // new snapshotVersion. The projection already queued from the current
                        // global snapshot is then the publication barrier for that envelope.
                        val uncovered = ordered.any { receipt ->
                            receipt.snapshotVersion?.let { it > sourceSnapshotVersion }
                                ?: (receipt.brokerSequence?.let { it > sourceBrokerToSequence } ?: true)
                        }
                        if (uncovered) return@forEach
                        val publishedVersion = versions.maxOrNull() ?: sourceSnapshotVersion
                        if (publishedVersion > sourceSnapshotVersion) {
                            return@forEach
                        }
                        WatchTaskProtocol.Response(
                            requestId = requestId,
                            status = WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED,
                            commandIds = commandIds,
                            snapshotVersion = publishedVersion,
                        )
                    }
                    entry.put("stage", stage(response.status))
                    entry.put("snapshotVersion", response.snapshotVersion ?: JSONObject.NULL)
                    entry.put("errorCode", response.errorCode ?: JSONObject.NULL)
                    entry.put("updatedAtEpochMs", System.currentTimeMillis())
                    responses += response
                }
                if (responses.isNotEmpty() && !writeLedger(appContext, ledger)) emptyList() else responses
            }
            published.forEach { WatchTaskAckPublisher.publish(appContext, it) }
        }
    }

    private fun responseFromReceipts(
        context: Context,
        request: WatchTaskProtocol.Request,
        entry: JSONObject,
    ): WatchTaskProtocol.Response? {
        if (entry.optString("stage") != STAGE_PHONE_STORED) return null
        val commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId)
        val receipts = SyncV3Progress.receipts(context, commandIds)
        val ordered = commandIds.mapNotNull(receipts::get)
        val rejected = ordered.firstOrNull { it.status in FAILURE_RECEIPT_STATUSES }
        if (rejected != null) {
            return WatchTaskProtocol.Response(
                requestId = request.requestId,
                status = WatchTaskProtocol.Status.REJECTED,
                commandIds = commandIds,
                errorCode = rejected.errorCode ?: "COMMAND_REJECTED",
            )
        }
        if (receipts.size != commandIds.size) return null
        val projectionVersion = SyncV3Progress.watchProjectionSnapshotVersion(context)
        if (projectionVersion <= 0L) return null
        val projectionBrokerTo = SyncV3Progress.watchProjectionBrokerToSequence(context)
        if (ordered.any { receipt ->
                receipt.snapshotVersion?.let { it > projectionVersion }
                    ?: (receipt.brokerSequence?.let { it > projectionBrokerTo } ?: true)
            }
        ) return null
        val publishedVersion = ordered.mapNotNull { it.snapshotVersion }.maxOrNull()
            ?: projectionVersion
        return WatchTaskProtocol.Response(
            requestId = request.requestId,
            status = WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED,
            commandIds = commandIds,
            snapshotVersion = publishedVersion,
        )
    }

    private fun responseFromEntry(
        request: WatchTaskProtocol.Request,
        entry: JSONObject,
    ): WatchTaskProtocol.Response {
        val status = when (entry.optString("stage")) {
            STAGE_SNAPSHOT_PUBLISHED -> WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED
            STAGE_REJECTED -> WatchTaskProtocol.Status.REJECTED
            else -> WatchTaskProtocol.Status.PHONE_STORED
        }
        return WatchTaskProtocol.Response(
            requestId = request.requestId,
            status = status,
            commandIds = stringList(entry.optJSONArray("commandIds")),
            snapshotVersion = entry.optLong("snapshotVersion", 0L).takeIf { it > 0L },
            errorCode = entry.optString("errorCode", "")
                .takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private fun putEntry(
        ledger: JSONObject,
        request: WatchTaskProtocol.Request,
        identity: String,
        response: WatchTaskProtocol.Response,
    ) {
        ledger.put(request.requestId, JSONObject().apply {
            put("identityHash", identity)
            put("commandIds", JSONArray(response.commandIds))
            put("stage", stage(response.status))
            put("snapshotVersion", response.snapshotVersion ?: JSONObject.NULL)
            put("errorCode", response.errorCode ?: JSONObject.NULL)
            put("createdAtEpochMs", request.createdAtEpochMs)
            put("updatedAtEpochMs", System.currentTimeMillis())
        })
    }

    private fun stage(status: WatchTaskProtocol.Status): String = when (status) {
        WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED -> STAGE_SNAPSHOT_PUBLISHED
        WatchTaskProtocol.Status.REJECTED -> STAGE_REJECTED
        else -> STAGE_PHONE_STORED
    }

    private fun ledgerIdentities(ledger: JSONObject): List<WatchBridgeIdentityGate.Identity> {
        val result = mutableListOf<WatchBridgeIdentityGate.Identity>()
        val iterator = ledger.keys()
        while (iterator.hasNext()) {
            val requestId = iterator.next()
            val entry = ledger.optJSONObject(requestId) ?: continue
            result += WatchBridgeIdentityGate.Identity(
                requestId = requestId,
                identityHash = entry.optString("identityHash"),
                commandIds = stringList(entry.optJSONArray("commandIds")),
            )
        }
        return result
    }

    private fun pruneLedger(ledger: JSONObject) {
        if (ledger.length() <= MAX_LEDGER_ENTRIES) return
        val finalEntries = ledger.keys().asSequence().mapNotNull { key ->
            val item = ledger.optJSONObject(key) ?: return@mapNotNull null
            if (item.optString("stage") == STAGE_PHONE_STORED) return@mapNotNull null
            key to item.optLong("updatedAtEpochMs", 0L)
        }.sortedBy { it.second }.toList()
        finalEntries.take(ledger.length() - MAX_LEDGER_ENTRIES).forEach { ledger.remove(it.first) }
    }

    private fun readLedger(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { error ->
            Log.e(TAG, "Corrupt bridge ledger; preserving backup", error)
            prefs.edit().putString(KEY_CORRUPT_BACKUP, raw).commit()
            JSONObject()
        }
    }

    private fun writeLedger(context: Context, ledger: JSONObject): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, ledger.toString()).commit()

    private fun stringList(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }

    private fun rejected(request: WatchTaskProtocol.Request, errorCode: String) =
        WatchTaskProtocol.Response(
            requestId = request.requestId,
            status = WatchTaskProtocol.Status.REJECTED,
            commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
            errorCode = errorCode,
        )

    private fun retry(request: WatchTaskProtocol.Request) = WatchTaskProtocol.Response(
        requestId = request.requestId,
        status = WatchTaskProtocol.Status.RETRY,
        commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
        errorCode = "PERSISTENCE_FAILED",
    )

    private val FAILURE_RECEIPT_STATUSES = setOf(
        "REJECTED",
        "SEQUENCE_GAP",
        "ENTITY_DELETED",
        "SCHEMA_UNSUPPORTED",
        "ID_ALREADY_EXISTS",
    )
}

/** Reusable ACK publisher so receipt finalization can notify without waiting for another request. */
internal object WatchTaskAckPublisher {
    private const val TAG = "TplannerWatchAck"
    private const val ACK_TIMEOUT_SECONDS = 10L
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-watch-ack").apply { isDaemon = true }
    }

    fun publishAsync(context: Context, response: WatchTaskProtocol.Response) {
        val appContext = context.applicationContext
        worker.execute { publish(appContext, response) }
    }

    fun publish(context: Context, response: WatchTaskProtocol.Response) {
        try {
            val payload = WatchTaskProtocol.encodeResponse(
                response.copy(acknowledgedAtEpochMs = System.currentTimeMillis()),
            ).toByteArray(Charsets.UTF_8)
            val request = PutDataRequest.create(WatchTaskProtocol.ackPath(response.requestId))
                .setUrgent().apply { data = payload }
            Tasks.await(
                Wearable.getDataClient(context.applicationContext).putDataItem(request),
                ACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            Log.d(TAG, "Published ACK request=${response.requestId} status=${response.status}")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to publish ACK request=${response.requestId}", error)
        }
    }
}
