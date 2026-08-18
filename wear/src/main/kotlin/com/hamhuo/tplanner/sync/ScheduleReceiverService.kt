package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Receives the phone's durable latest-task snapshot and commits it locally. */
class ScheduleReceiverService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.dataItem.uri.path != PATH) continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: continue
                    storeSchedule(String(bytes, Charsets.UTF_8))
                }

                DataEvent.TYPE_DELETED -> clearSchedule()
            }
        }
    }

    private fun storeSchedule(raw: String) {
        val result = ScheduleStore.store(this, raw)
        if (result.shouldAcknowledge) publishDeliveryReceipt(raw)
    }

    private fun publishDeliveryReceipt(snapshot: String) {
        try {
            val identity = WatchScheduleRefreshProtocol.snapshotIdentity(snapshot)
            val requestId = "delivery-${identity.version}"
            val receipt = WatchScheduleRefreshProtocol.receiptFor(
                requestId = requestId,
                snapshot = snapshot,
                acceptedAtEpochMs = System.currentTimeMillis(),
            )
            val request = PutDataRequest.create(
                WatchScheduleRefreshProtocol.deliveryAckPath(identity.version),
            ).setUrgent().apply {
                data = WatchScheduleRefreshProtocol.encodeReceipt(receipt)
                    .toByteArray(Charsets.UTF_8)
            }
            Tasks.await(
                Wearable.getDataClient(applicationContext).putDataItem(request),
                ACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            Log.d(TAG, "Published schedule receipt version=${identity.version}")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to publish schedule receipt", error)
        }
    }

    private fun clearSchedule() {
        val committed = getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(WATCH_MARKS_KEY)
            .commit()
        Log.d(TAG, "clearSchedule: committed=$committed")
    }

    private companion object {
        const val TAG = "TplannerScheduleRcv"
        const val PATH = "/tplanner/schedule"
        const val ACK_TIMEOUT_SECONDS = 10L
    }
}

// ── Shared schedule storage ────────────────────────────────────────────
// Reused by both the GMS Data Layer receiver and the Bluetooth RFCOMM bridge.
// All validation (schema version, hash, version monotonicity) is applied
// identically regardless of transport.

internal object ScheduleStore {
    private const val TAG = "TplannerScheduleStore"
    private const val SCHEMA_VERSION = 3
    private const val MAX_SNAPSHOT_DAYS = 31
    private const val MAX_TASK_COUNT = 128
    private const val MAX_TASK_TITLE_CODE_POINTS = 80
    private const val MAX_TASK_TITLE_UTF8_BYTES = 256
    private const val MAX_TASK_ID_UTF8_BYTES = 256
    private const val MAX_TASKS_UTF8_BYTES = 64 * 1024
    private const val SOURCE_LEGACY = "legacy"
    private const val SOURCE_PHONE = "phone"
    internal const val SOURCE_BLUETOOTH = "bluetooth"

    /**
     * A Bluetooth sender may stop retrying only for terminal, successfully handled input.
     * Invalid data and failed durable writes deliberately remain retryable failures.
     */
    internal enum class StoreResult(val shouldAcknowledge: Boolean) {
        STORED(true),
        ALREADY_CURRENT(true),
        STALE(true),
        REJECTED(false),
        COMMIT_FAILED(false),
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

    private data class TaskBundle(
        val tasks: List<TaskSnapshot>,
        val hash: String,
    )

    private val taskOrder = compareBy<TaskSnapshot>(
        { it.startEpochMs },
        { it.endEpochMs },
        { it.id },
    )
    private val whitespace = Regex("\\s+")
    private val sha256 = Regex("[0-9a-f]{64}")

    @Synchronized
    fun store(
        context: Context,
        raw: String,
        sourceOverride: String? = null,
    ): StoreResult {
        if (raw.toByteArray(Charsets.UTF_8).size > ScheduleRfcommProtocol.MAX_PAYLOAD_BYTES) {
            Log.w(
                TAG,
                "storeSchedule: payload exceeds ${ScheduleRfcommProtocol.MAX_PAYLOAD_BYTES} bytes",
            )
            return StoreResult.REJECTED
        }
        try {
            val payload = JSONObject(raw)
            val prefs = context.getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(WATCH_MARKS_KEY, null)
                ?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            val existingVersion = existing?.optLong("version", -1L) ?: -1L
            val existingHash = existing?.optString("hash")
            val existingTasksHash = existing?.optString("tasksHash")
                ?.takeIf { it.isNotBlank() }
            val existingWasLegacy = existing != null && (
                existing.optString("source") == SOURCE_LEGACY ||
                    existing.optLong("generatedAtEpochMs", 0L) <= 0L
                )
            val schemaVersion = payload.optInt("schemaVersion", -1)
            val days: List<DaySnapshot>
            val version: Long
            val hash: String
            val tasks: List<TaskSnapshot>?
            val taskContentHash: String?
            val isLegacy: Boolean
            if (schemaVersion == SCHEMA_VERSION) {
                isLegacy = false
                version = payload.optLong("version", -1L)
                hash = payload.optString("hash")
                if (version < 0L || hash.isBlank()) {
                    Log.w(TAG, "storeSchedule: invalid version/hash version=$version")
                    return StoreResult.REJECTED
                }
                val inputDays = payload.optJSONArray("days") ?: run {
                    Log.w(TAG, "storeSchedule: missing days version=$version")
                    return StoreResult.REJECTED
                }
                if (inputDays.length() !in 1..MAX_SNAPSHOT_DAYS) {
                    Log.w(TAG, "storeSchedule: invalid day count=${inputDays.length()}")
                    return StoreResult.REJECTED
                }
                val seenDates = mutableSetOf<String>()
                days = (0 until inputDays.length()).map { index ->
                    val inputDay = inputDays.optJSONObject(index)
                        ?: throw IllegalArgumentException("days[$index] is not an object")
                    val date = LocalDate.parse(inputDay.optString("date")).toString()
                    if (!seenDates.add(date)) {
                        throw IllegalArgumentException("duplicate date=$date")
                    }
                    DaySnapshot(
                        date,
                        normalizedMinutes(inputDay.optJSONArray("minutes"), "days[$index].minutes"),
                    )
                }.sortedBy { it.date }
                if (hash != scheduleHash(days)) {
                    Log.w(TAG, "storeSchedule: hash mismatch version=$version")
                    return StoreResult.REJECTED
                }
                val taskBundle = normalizedTasks(payload)
                tasks = taskBundle?.tasks
                taskContentHash = taskBundle?.hash
            } else if (!payload.has("schemaVersion") && payload.has("minutes")) {
                isLegacy = true
                days = listOf(
                    DaySnapshot(
                        java.time.ZonedDateTime.now(APP_ZONE).toLocalDate().toString(),
                        normalizedMinutes(payload.optJSONArray("minutes"), "minutes"),
                    )
                )
                version = maxOf(System.currentTimeMillis(), existingVersion + 1L)
                hash = scheduleHash(days)
                tasks = null
                taskContentHash = null
                Log.i(TAG, "storeSchedule: normalized legacy payload version=$version")
            } else {
                Log.w(TAG, "storeSchedule: ignored unsupported schema=$schemaVersion")
                return StoreResult.REJECTED
            }

            if (isLegacy && existing != null && !existingWasLegacy) {
                Log.i(TAG, "storeSchedule: ignored legacy payload after versioned snapshot")
                return StoreResult.STALE
            }
            if (!(existingWasLegacy && !isLegacy)) {
                if (existingVersion > version) {
                    Log.w(TAG, "storeSchedule: ignored stale version=$version current=$existingVersion")
                    return StoreResult.STALE
                }
                if (existingVersion == version) {
                    if (existingHash != hash) {
                        Log.e(
                            TAG,
                            "storeSchedule: rejected divergent content for version=$version " +
                                "currentHash=${existingHash?.take(12)} incomingHash=${hash.take(12)}",
                        )
                        return StoreResult.REJECTED
                    }
                    when {
                        existingTasksHash == taskContentHash -> return StoreResult.ALREADY_CURRENT
                        existingTasksHash == null && taskContentHash != null -> {
                            Log.i(TAG, "storeSchedule: enriching version=$version with task details")
                        }
                        existingTasksHash != null && taskContentHash == null -> {
                            Log.i(TAG, "storeSchedule: ignored task-detail downgrade version=$version")
                            return StoreResult.STALE
                        }
                        else -> {
                            Log.e(
                                TAG,
                                "storeSchedule: rejected divergent tasks for version=$version " +
                                    "currentTasksHash=${existingTasksHash?.take(12)} " +
                                    "incomingTasksHash=${taskContentHash?.take(12)}",
                            )
                            return StoreResult.REJECTED
                        }
                    }
                }
            }

            val source = sourceOverride ?: if (isLegacy) SOURCE_LEGACY else SOURCE_PHONE
            val normalizedPayload = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("version", version)
                put("generatedAtEpochMs", payload.optLong("generatedAtEpochMs", 0L))
                put("hash", hash)
                put("source", source)
                put("days", JSONArray().apply {
                    days.forEach { day ->
                        put(JSONObject().apply {
                            put("date", day.date)
                            put("minutes", JSONArray(day.minutes))
                        })
                    }
                })
                if (tasks != null && taskContentHash != null) {
                    put("tasks", taskArray(tasks))
                    put("tasksHash", taskContentHash)
                }
            }
            val committed = prefs.edit()
                .putString(WATCH_MARKS_KEY, normalizedPayload.toString())
                .commit()
            if (committed) {
                Log.d(
                    TAG,
                    "storeSchedule: source=$source version=$version " +
                        "days=${days.first().date}..${days.last().date} tasks=${tasks?.size ?: 0}",
                )
                return StoreResult.STORED
            } else {
                Log.e(TAG, "storeSchedule: SharedPreferences commit failed")
                return StoreResult.COMMIT_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "storeSchedule: invalid payload", e)
            return StoreResult.REJECTED
        }
    }

    private fun normalizedMinutes(input: JSONArray?, fieldName: String): List<Int> {
        val minutes = input
            ?: throw IllegalArgumentException("$fieldName is missing or is not an array")
        return (0 until minutes.length())
            .map { index ->
                val value = minutes.get(index)
                val minute = when (value) {
                    is Byte -> value.toInt()
                    is Short -> value.toInt()
                    is Int -> value
                    is Long -> value.toInt().takeIf { it.toLong() == value }
                    else -> null
                }
                require(minute != null && minute in 0..1439) {
                    "$fieldName[$index] is not an integer minute in 0..1439"
                }
                minute
            }
            .distinct()
            .sorted()
    }

    private fun normalizedTasks(payload: JSONObject): TaskBundle? {
        val hasTasks = payload.has("tasks")
        val hasTasksHash = payload.has("tasksHash")
        require(hasTasks == hasTasksHash) {
            "tasks and tasksHash must either both be present or both be absent"
        }
        if (!hasTasks) return null

        val input = payload.optJSONArray("tasks")
            ?: throw IllegalArgumentException("tasks is not an array")
        require(input.length() <= MAX_TASK_COUNT) {
            "tasks contains ${input.length()} items; maximum is $MAX_TASK_COUNT"
        }
        val expectedHashValue = payload.get("tasksHash")
        require(expectedHashValue is String && sha256.matches(expectedHashValue)) {
            "tasksHash is not a lowercase SHA-256 value"
        }

        val seenIds = mutableSetOf<String>()
        val tasks = (0 until input.length()).map { index ->
            val item = input.optJSONObject(index)
                ?: throw IllegalArgumentException("tasks[$index] is not an object")
            val idValue = item.get("id")
            require(idValue is String) { "tasks[$index].id is not a string" }
            val id = idValue.trim()
            require(id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= MAX_TASK_ID_UTF8_BYTES) {
                "tasks[$index].id is empty or too large"
            }
            require(seenIds.add(id)) { "duplicate task id=$id" }

            val titleValue = item.get("title")
            require(titleValue is String) { "tasks[$index].title is not a string" }
            val title = titleValue.trim().replace(whitespace, " ")
            require(title.isNotEmpty()) { "tasks[$index].title is empty" }
            require(title.codePointCount(0, title.length) <= MAX_TASK_TITLE_CODE_POINTS) {
                "tasks[$index].title has too many code points"
            }
            require(title.toByteArray(Charsets.UTF_8).size <= MAX_TASK_TITLE_UTF8_BYTES) {
                "tasks[$index].title is too large"
            }

            val type = item.optString("type", "task")
            require(type in SUPPORTED_TASK_TYPES) {
                "tasks[$index].type is unsupported"
            }

            val startEpochMs = item.strictLong("startEpochMs", "tasks[$index].startEpochMs")
            val endEpochMs = item.strictLong("endEpochMs", "tasks[$index].endEpochMs")
            require(endEpochMs >= startEpochMs) {
                "tasks[$index] ends before it starts"
            }
            val checklistJson = item.optJSONArray("checklist")?.toString().orEmpty()
            TaskSnapshot(id, title, type, startEpochMs, endEpochMs, checklistJson)
        }.sortedWith(taskOrder)

        require(taskArray(tasks).toString().toByteArray(Charsets.UTF_8).size <= MAX_TASKS_UTF8_BYTES) {
            "normalized tasks payload is too large"
        }
        val actualHash = tasksHash(tasks)
        require(expectedHashValue == actualHash) { "tasksHash mismatch" }
        return TaskBundle(tasks, actualHash)
    }

    private fun JSONObject.strictLong(field: String, description: String): Long {
        return when (val value = get(field)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$description is not an integer")
        }
    }

    private fun taskArray(tasks: List<TaskSnapshot>): JSONArray = JSONArray().apply {
        tasks.forEach { task ->
            put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("type", task.type)
                put("startEpochMs", task.startEpochMs)
                put("endEpochMs", task.endEpochMs)
                if (task.checklistJson.isNotEmpty()) {
                    put("checklist", JSONArray(task.checklistJson))
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

    private val SUPPORTED_TASK_TYPES = setOf("event", "status", "task")
}
