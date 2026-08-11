package com.hamhuo.tplanner

import org.json.JSONObject
import java.util.UUID

/**
 * Transport-neutral contract for watch -> phone task operations (create & delete).
 *
 * The same request/response JSON is used by Wearable Data Layer and classic Bluetooth RFCOMM.
 * Transport delivery is never an acknowledgement: the watch may remove its durable outbox entry
 * only after receiving a terminal [Response].
 */
object WatchTaskProtocol {
    const val SCHEMA_VERSION = 1
    const val OPERATION_CREATE_TASK = "createTask"
    const val OPERATION_DELETE_TASK = "deleteTask"
    const val REQUEST_PATH_PREFIX = "/tplanner/task/create/"
    const val ACK_PATH_PREFIX = "/tplanner/task/ack/"
    const val DELETE_REQUEST_PATH_PREFIX = "/tplanner/task/delete/"
    const val MAX_JSON_BYTES = 8 * 1024
    const val MAX_TITLE_CODE_POINTS = 80
    const val MAX_TITLE_UTF8_BYTES = 256
    const val MAX_ID_UTF8_BYTES = 256
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_ALARM_OFFSET_MINUTES = 7 * 24 * 60
    const val DEFAULT_TIME_ZONE_ID = "Asia/Shanghai"

    /** Kept separate from the phone -> watch schedule UUID. */
    val RFCOMM_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")

    enum class Status(val wireValue: String, val terminal: Boolean) {
        STORED("stored", true),
        ALREADY_STORED("already_stored", true),
        DELETED("deleted", true),
        REJECTED("rejected", true),
        RETRY("retry", false),
        ;

        companion object {
            fun fromWireValue(value: String): Status = entries.firstOrNull {
                it.wireValue == value
            } ?: throw ProtocolException("INVALID_STATUS", "Unsupported status '$value'")
        }
    }

    data class Task(
        val id: String,
        val title: String,
        val type: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val colorId: Int,
        val alarmEnabled: Boolean,
        val alarmOffsetMinutes: Int,
        val timeZoneId: String = DEFAULT_TIME_ZONE_ID,
    )

    data class Request(
        val requestId: String,
        val createdAtEpochMs: Long,
        val task: Task? = null,
        val taskId: String? = null,
        /** Changes on each DataItem retry so Google Play services emits TYPE_CHANGED again. */
        val attempt: Int = 0,
        val publishedAtEpochMs: Long = createdAtEpochMs,
    ) {
        val operation: String get() = if (task != null) OPERATION_CREATE_TASK else OPERATION_DELETE_TASK
        val isDelete: Boolean get() = task == null
    }

    data class Response(
        val requestId: String,
        val status: Status,
        val errorCode: String? = null,
        /** Changes when an ACK is re-published after an earlier ACK was lost. */
        val acknowledgedAtEpochMs: Long = System.currentTimeMillis(),
    )

    class ProtocolException(
        val errorCode: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    fun requestPath(requestId: String): String = REQUEST_PATH_PREFIX + validatedRequestId(requestId)

    fun requestPath(request: Request): String =
        if (request.isDelete) deleteRequestPath(request.requestId)
        else requestPath(request.requestId)

    fun deleteRequestPath(requestId: String): String =
        DELETE_REQUEST_PATH_PREFIX + validatedRequestId(requestId)

    fun ackPath(requestId: String): String = ACK_PATH_PREFIX + validatedRequestId(requestId)

    fun requestIdFromPath(path: String?, prefix: String): String? {
        val value = path?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotEmpty() && '/' !in it }
            ?: return null
        return runCatching { validatedRequestId(value) }.getOrNull()
    }

    fun encodeRequest(request: Request): String {
        val normalized = validateRequest(request)
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("operation", normalized.operation)
            put("requestId", normalized.requestId)
            put("createdAtEpochMs", normalized.createdAtEpochMs)
            put("attempt", normalized.attempt)
            put("publishedAtEpochMs", normalized.publishedAtEpochMs)
            if (normalized.task != null) {
                put("task", JSONObject().apply {
                    put("id", normalized.task.id)
                    put("title", normalized.task.title)
                    put("type", normalized.task.type)
                    put("startEpochMs", normalized.task.startEpochMs)
                    put("endEpochMs", normalized.task.endEpochMs)
                    put("colorId", normalized.task.colorId)
                    put("alarmEnabled", normalized.task.alarmEnabled)
                    put("alarmOffsetMinutes", normalized.task.alarmOffsetMinutes)
                    put("timeZoneId", normalized.task.timeZoneId)
                })
            }
            if (normalized.taskId != null) {
                put("taskId", normalized.taskId)
            }
        }.toString().also(::requireBoundedJson)
    }

    fun decodeRequest(raw: String): Request {
        requireBoundedJson(raw)
        val root = parseObject(raw)
        val schema = root.strictInt("schemaVersion")
        if (schema != SCHEMA_VERSION) {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Unsupported schema $schema")
        }
        val operation = root.strictString("operation")
        return when (operation) {
            OPERATION_CREATE_TASK -> decodeCreateRequest(root)
            OPERATION_DELETE_TASK -> decodeDeleteRequest(root)
            else -> throw ProtocolException("UNSUPPORTED_OPERATION", "Unsupported operation '$operation'")
        }
    }

    private fun decodeCreateRequest(root: JSONObject): Request {
        val task = root.optJSONObject("task")
            ?: throw ProtocolException("INVALID_TASK", "task must be an object")
        return validateRequest(
            Request(
                requestId = root.strictString("requestId"),
                createdAtEpochMs = root.strictLong("createdAtEpochMs"),
                attempt = root.optionalStrictInt("attempt", 0),
                publishedAtEpochMs = root.optionalStrictLong(
                    "publishedAtEpochMs",
                    root.strictLong("createdAtEpochMs"),
                ),
                task = Task(
                    id = task.strictString("id"),
                    title = task.strictString("title"),
                    type = task.strictString("type"),
                    startEpochMs = task.strictLong("startEpochMs"),
                    endEpochMs = task.strictLong("endEpochMs"),
                    colorId = task.strictInt("colorId"),
                    alarmEnabled = task.strictBoolean("alarmEnabled"),
                    alarmOffsetMinutes = task.strictInt("alarmOffsetMinutes"),
                    timeZoneId = task.optionalStrictString(
                        "timeZoneId",
                        DEFAULT_TIME_ZONE_ID,
                    ),
                ),
            ),
        )
    }

    private fun decodeDeleteRequest(root: JSONObject): Request {
        val taskId = root.strictString("taskId")
        return validateRequest(
            Request(
                requestId = root.strictString("requestId"),
                createdAtEpochMs = root.strictLong("createdAtEpochMs"),
                attempt = root.optionalStrictInt("attempt", 0),
                publishedAtEpochMs = root.optionalStrictLong(
                    "publishedAtEpochMs",
                    root.strictLong("createdAtEpochMs"),
                ),
                task = null,
                taskId = taskId,
            ),
        )
    }

    fun encodeResponse(response: Response): String {
        val requestId = validatedResponseRequestId(response.requestId)
        val errorCode = response.errorCode?.trim()?.takeIf { it.isNotEmpty() }
        if (errorCode != null && errorCode.length > 80) {
            throw ProtocolException("INVALID_ERROR_CODE", "errorCode is too long")
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("requestId", requestId)
            put("status", response.status.wireValue)
            put("acknowledgedAtEpochMs", response.acknowledgedAtEpochMs)
            if (errorCode != null) put("errorCode", errorCode)
        }.toString().also(::requireBoundedJson)
    }

    fun decodeResponse(raw: String): Response {
        requireBoundedJson(raw)
        val root = parseObject(raw)
        val schema = root.strictInt("schemaVersion")
        if (schema != SCHEMA_VERSION) {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Unsupported schema $schema")
        }
        return Response(
            requestId = validatedResponseRequestId(root.strictString("requestId")),
            status = Status.fromWireValue(root.strictString("status")),
            errorCode = root.optionalStrictString("errorCode", "").takeIf { it.isNotEmpty() },
            acknowledgedAtEpochMs = root.optionalStrictLong("acknowledgedAtEpochMs", 0L),
        )
    }

    /** Extracts a correlation id from otherwise invalid input so a terminal rejection can be ACKed. */
    fun bestEffortRequestId(raw: String): String? = runCatching {
        validatedRequestId(parseObject(raw).optString("requestId"))
    }.getOrNull()

    private fun validateRequest(request: Request): Request {
        val requestId = validatedRequestId(request.requestId)
        if (request.createdAtEpochMs <= 0L) {
            throw ProtocolException("INVALID_CREATED_AT", "createdAtEpochMs must be positive")
        }
        if (request.attempt !in 0..1_000_000) {
            throw ProtocolException("INVALID_ATTEMPT", "attempt is outside the supported range")
        }
        if (request.publishedAtEpochMs <= 0L) {
            throw ProtocolException("INVALID_PUBLISHED_AT", "publishedAtEpochMs must be positive")
        }
        return if (request.task != null) {
            validateCreateRequest(requestId, request)
        } else if (request.taskId != null) {
            validateDeleteRequest(requestId, request)
        } else {
            throw ProtocolException("INVALID_REQUEST", "request must carry either task or taskId")
        }
    }

    private fun validateCreateRequest(requestId: String, request: Request): Request {
        val task = request.task!!
        val id = task.id.trim()
        if (id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > MAX_ID_UTF8_BYTES) {
            throw ProtocolException("INVALID_TASK_ID", "task.id is empty or too large")
        }
        val title = task.title.trim().replace(WHITESPACE, " ")
        if (title.isEmpty() ||
            title.codePointCount(0, title.length) > MAX_TITLE_CODE_POINTS ||
            title.toByteArray(Charsets.UTF_8).size > MAX_TITLE_UTF8_BYTES
        ) {
            throw ProtocolException("INVALID_TITLE", "task.title is empty or too large")
        }
        val type = task.type.trim()
        if (type !in TASK_TYPES) {
            throw ProtocolException("INVALID_TYPE", "Unsupported task.type '$type'")
        }
        if (task.startEpochMs <= 0L || task.endEpochMs < task.startEpochMs) {
            throw ProtocolException("INVALID_TIME", "Task time range is invalid")
        }
        if (task.colorId !in 0..7) {
            throw ProtocolException("INVALID_COLOR", "colorId must be in 0..7")
        }
        if (task.alarmOffsetMinutes !in 0..MAX_ALARM_OFFSET_MINUTES) {
            throw ProtocolException("INVALID_ALARM_OFFSET", "alarmOffsetMinutes is invalid")
        }
        val timeZoneId = task.timeZoneId.trim()
        if (timeZoneId != DEFAULT_TIME_ZONE_ID) {
            throw ProtocolException("INVALID_TIME_ZONE", "Unsupported timeZoneId '$timeZoneId'")
        }
        return request.copy(
            requestId = requestId,
            task = task.copy(
                id = id,
                title = title,
                type = type,
                timeZoneId = timeZoneId,
            ),
        )
    }

    private fun validateDeleteRequest(requestId: String, request: Request): Request {
        val taskId = request.taskId!!.trim()
        if (taskId.isEmpty() || taskId.toByteArray(Charsets.UTF_8).size > MAX_ID_UTF8_BYTES) {
            throw ProtocolException("INVALID_TASK_ID", "taskId is empty or too large")
        }
        return request.copy(requestId = requestId, taskId = taskId, task = null)
    }

    private fun validatedRequestId(raw: String): String {
        val value = raw.trim()
        if (value.length !in 1..MAX_REQUEST_ID_LENGTH || !REQUEST_ID.matches(value)) {
            throw ProtocolException("INVALID_REQUEST_ID", "requestId is invalid")
        }
        return value
    }

    private fun validatedResponseRequestId(raw: String): String {
        val value = raw.trim()
        if (value == UNKNOWN_REQUEST_ID) return value
        return validatedRequestId(value)
    }

    private fun requireBoundedJson(raw: String) {
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (size !in 1..MAX_JSON_BYTES) {
            throw ProtocolException("PAYLOAD_TOO_LARGE", "JSON payload is $size bytes")
        }
    }

    private fun parseObject(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (error: Exception) {
        throw ProtocolException("MALFORMED_JSON", "Payload is not a JSON object", error)
    }

    private fun JSONObject.strictString(key: String): String = when (val value = getOrNull(key)) {
        is String -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be a string")
    }

    private fun JSONObject.optionalStrictString(key: String, default: String): String =
        if (!has(key)) default else strictString(key)

    private fun JSONObject.strictBoolean(key: String): Boolean = when (val value = getOrNull(key)) {
        is Boolean -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be a boolean")
    }

    private fun JSONObject.strictLong(key: String): Long = when (val value = getOrNull(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be an integer")
    }

    private fun JSONObject.optionalStrictLong(key: String, default: Long): Long =
        if (!has(key)) default else strictLong(key)

    private fun JSONObject.strictInt(key: String): Int {
        val value = strictLong(key)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ProtocolException("INVALID_FIELD", "$key is outside the Int range")
        }
        return value.toInt()
    }

    private fun JSONObject.optionalStrictInt(key: String, default: Int): Int =
        if (!has(key)) default else strictInt(key)

    private fun JSONObject.getOrNull(key: String): Any? = try {
        if (!has(key) || isNull(key)) null else get(key)
    } catch (_: Exception) {
        null
    }

    const val UNKNOWN_REQUEST_ID = "unknown"
    private val REQUEST_ID = Regex("[A-Za-z0-9._-]+")
    private val WHITESPACE = Regex("\\s+")
    private val TASK_TYPES = setOf("event", "status", "task")
}
