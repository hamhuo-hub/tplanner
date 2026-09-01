package com.hamhuo.tplanner

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Transport-neutral Watch -> Phone contract.
 *
 * A request is an immutable semantic-command envelope. Data Layer and RFCOMM may deliver the
 * same envelope in any order; [requestId] and the child [SemanticCommand.commandId] values remain
 * unchanged across retries. Transport delivery is not completion. PHONE_STORED only says the
 * phone durably owns the envelope; a watch outbox may finish it only after SNAPSHOT_PUBLISHED and
 * installation of a projection sourced from that snapshot.
 */
object WatchTaskProtocol {
    const val SCHEMA_VERSION = 2
    const val OPERATION_CREATE_TASK = "createTask"
    const val OPERATION_DELETE_TASK = "deleteTask"
    const val REQUEST_PATH_PREFIX = "/tplanner/task/create/"
    const val ACK_PATH_PREFIX = "/tplanner/task/ack/"
    const val DELETE_REQUEST_PATH_PREFIX = "/tplanner/task/delete/"
    const val MAX_JSON_BYTES = 8 * 1024
    const val MAX_BATCH_JSON_BYTES = 256 * 1024
    const val MAX_BATCH_REQUESTS = 32
    const val MAX_TITLE_CODE_POINTS = 80
    const val MAX_TITLE_UTF8_BYTES = 256
    const val MAX_ID_UTF8_BYTES = 256
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_ALARM_OFFSET_MINUTES = 7 * 24 * 60
    const val DEFAULT_TIME_ZONE_ID = "Asia/Shanghai"

    val RFCOMM_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")

    enum class Status(val wireValue: String, val terminal: Boolean) {
        PHONE_STORED("phone_stored", false),
        SNAPSHOT_PUBLISHED("snapshot_published", true),
        REJECTED("rejected", true),
        RETRY("retry", false),
        ;

        companion object {
            fun fromWireValue(value: String): Status = entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("INVALID_STATUS", "Unsupported status '$value'")
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

    data class SemanticCommand(
        val commandId: String,
        val type: String,
        val aggregateId: String?,
        val argumentsJson: String,
    )

    data class Request(
        val requestId: String,
        val createdAtEpochMs: Long,
        val task: Task? = null,
        val taskId: String? = null,
        val commands: List<SemanticCommand> = emptyList(),
        /**
         * Optional causal barrier used by a delete that follows a still-pending watch create.
         * The watch must not publish this request until the phone has durably acknowledged the
         * predecessor with PHONE_STORED.
         */
        val dependsOnRequestId: String? = null,
        /** Changes on every transport retry, but is excluded from request identity. */
        val attempt: Int = 0,
        val publishedAtEpochMs: Long = createdAtEpochMs,
    ) {
        val operation: String get() = if (task != null) OPERATION_CREATE_TASK else OPERATION_DELETE_TASK
        val isDelete: Boolean get() = task == null
    }

    data class RequestBatch(val batchId: String, val requests: List<Request>)

    data class Response(
        val requestId: String,
        val status: Status,
        val commandIds: List<String> = emptyList(),
        val snapshotVersion: Long? = null,
        val errorCode: String? = null,
        val acknowledgedAtEpochMs: Long = System.currentTimeMillis(),
    )

    data class ResponseBatch(val batchId: String, val responses: List<Response>)

    class ProtocolException(
        val errorCode: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    fun newEnvelopeId(): String = uuidV7()
    fun newCommandId(): String = uuidV7()

    fun requestPath(requestId: String): String = REQUEST_PATH_PREFIX + validatedRequestId(requestId)
    fun requestPath(request: Request): String =
        if (request.isDelete) deleteRequestPath(request.requestId) else requestPath(request.requestId)
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

    /** Builds immutable child command ids once, before the envelope enters the watch outbox. */
    fun withSemanticCommands(request: Request): Request {
        val basic = validateBasicRequest(request)
        if (basic.commands.isNotEmpty()) return validateRequest(basic)
        return validateRequest(basic.copy(commands = commandsForTask(basic) { newCommandId() }))
    }

    fun encodeRequest(request: Request): String {
        val normalized = withSemanticCommands(request)
        return requestObject(normalized).toString().also(::requireBoundedJson)
    }

    fun decodeRequest(raw: String): Request {
        requireBoundedJson(raw)
        val root = parseObject(raw)
        val schema = root.strictInt("schemaVersion")
        if (schema != SCHEMA_VERSION) {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Unsupported schema $schema")
        }
        val decoded = when (val operation = root.strictString("operation")) {
            OPERATION_CREATE_TASK -> decodeCreateRequest(root)
            OPERATION_DELETE_TASK -> decodeDeleteRequest(root)
            else -> throw ProtocolException("UNSUPPORTED_OPERATION", "Unsupported operation '$operation'")
        }
        return validateRequest(decoded.copy(commands = decodeCommands(root)))
    }

    /**
     * One-release adapter for persisted/transmitted schema-v1 envelopes. Child command ids are
     * derived, not randomized, so conversion on the watch and conversion on the phone produce the
     * same idempotency identity even when both transports race.
     */
    fun decodeCompatibleRequest(raw: String): Request {
        requireBoundedJson(raw)
        val root = parseObject(raw)
        return when (val schema = root.strictInt("schemaVersion")) {
            SCHEMA_VERSION -> decodeRequest(raw)
            LEGACY_SCHEMA_VERSION -> {
                val decoded = when (val operation = root.strictString("operation")) {
                    OPERATION_CREATE_TASK -> decodeCreateRequest(root)
                    OPERATION_DELETE_TASK -> decodeDeleteRequest(root)
                    else -> throw ProtocolException(
                        "UNSUPPORTED_OPERATION",
                        "Unsupported operation '$operation'",
                    )
                }
                withDeterministicSemanticCommands(decoded)
            }
            else -> throw ProtocolException("UNSUPPORTED_SCHEMA", "Unsupported schema $schema")
        }
    }

    fun encodeRequestBatch(batch: RequestBatch): String {
        val batchId = validatedRequestId(batch.batchId)
        if (batch.requests.isEmpty() || batch.requests.size > MAX_BATCH_REQUESTS) {
            throw ProtocolException("INVALID_BATCH", "Batch request count is invalid")
        }
        val normalized = batch.requests.map(::withSemanticCommands)
        if (normalized.map(Request::requestId).distinct().size != normalized.size) {
            throw ProtocolException("DUPLICATE_REQUEST", "Batch contains duplicate requestId")
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", "requestBatch")
            put("batchId", batchId)
            put("requests", JSONArray().apply { normalized.forEach { put(requestObject(it)) } })
        }.toString().also(::requireBoundedBatchJson)
    }

    fun decodeRequestBatch(raw: String): RequestBatch {
        requireBoundedBatchJson(raw)
        val root = parseObject(raw)
        if (root.strictInt("schemaVersion") != SCHEMA_VERSION || root.strictString("kind") != "requestBatch") {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Not a v2 request batch")
        }
        val array = root.optJSONArray("requests")
            ?: throw ProtocolException("INVALID_BATCH", "requests must be an array")
        if (array.length() !in 1..MAX_BATCH_REQUESTS) {
            throw ProtocolException("INVALID_BATCH", "Batch request count is invalid")
        }
        val requests = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw ProtocolException("INVALID_BATCH", "requests[$index] must be an object")
            decodeRequest(item.toString())
        }
        if (requests.map(Request::requestId).distinct().size != requests.size) {
            throw ProtocolException("DUPLICATE_REQUEST", "Batch contains duplicate requestId")
        }
        return RequestBatch(validatedRequestId(root.strictString("batchId")), requests)
    }

    fun encodeResponse(response: Response): String =
        responseObject(validateResponse(response)).toString().also(::requireBoundedJson)

    fun decodeResponse(raw: String): Response {
        requireBoundedJson(raw)
        val root = parseObject(raw)
        val schema = root.strictInt("schemaVersion")
        if (schema != SCHEMA_VERSION) {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Unsupported schema $schema")
        }
        return validateResponse(
            Response(
                requestId = validatedResponseRequestId(root.strictString("requestId")),
                status = Status.fromWireValue(root.strictString("status")),
                commandIds = root.optJSONArray("commandIds")?.let(::stringList).orEmpty(),
                snapshotVersion = root.optionalNullableLong("snapshotVersion"),
                errorCode = root.optionalStrictString("errorCode", "").takeIf { it.isNotEmpty() },
                acknowledgedAtEpochMs = root.optionalStrictLong("acknowledgedAtEpochMs", 0L),
            ),
        )
    }

    fun encodeResponseBatch(batch: ResponseBatch): String {
        val responses = batch.responses.map(::validateResponse)
        if (responses.isEmpty() || responses.size > MAX_BATCH_REQUESTS) {
            throw ProtocolException("INVALID_BATCH", "Batch response count is invalid")
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", "responseBatch")
            put("batchId", validatedRequestId(batch.batchId))
            put("responses", JSONArray().apply { responses.forEach { put(responseObject(it)) } })
        }.toString().also(::requireBoundedBatchJson)
    }

    fun decodeResponseBatch(raw: String): ResponseBatch {
        requireBoundedBatchJson(raw)
        val root = parseObject(raw)
        if (root.strictInt("schemaVersion") != SCHEMA_VERSION || root.strictString("kind") != "responseBatch") {
            throw ProtocolException("UNSUPPORTED_SCHEMA", "Not a v2 response batch")
        }
        val array = root.optJSONArray("responses")
            ?: throw ProtocolException("INVALID_BATCH", "responses must be an array")
        if (array.length() !in 1..MAX_BATCH_REQUESTS) {
            throw ProtocolException("INVALID_BATCH", "Batch response count is invalid")
        }
        val responses = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw ProtocolException("INVALID_BATCH", "responses[$index] must be an object")
            decodeResponse(item.toString())
        }
        return ResponseBatch(validatedRequestId(root.strictString("batchId")), responses)
    }

    fun isRequestBatch(raw: String): Boolean = runCatching {
        parseObject(raw).optString("kind") == "requestBatch"
    }.getOrDefault(false)

    fun identityHash(request: Request): String {
        val normalized = withSemanticCommands(request)
        val identity = JSONObject().apply {
            put("requestId", normalized.requestId)
            put("createdAtEpochMs", normalized.createdAtEpochMs)
            put("operation", normalized.operation)
            normalized.dependsOnRequestId?.let { put("dependsOnRequestId", it) }
            put("commands", commandsArray(normalized.commands))
        }
        return "sha256:" + sha256(canonicalJson(identity).toByteArray(Charsets.UTF_8))
    }

    fun bestEffortRequestId(raw: String): String? = runCatching {
        validatedRequestId(parseObject(raw).optString("requestId"))
    }.getOrNull()

    /** Pure queue policy shared with tests: a causal delete waits for PHONE_STORED. */
    fun dependencySatisfied(request: Request, phoneStoredRequestIds: Set<String>): Boolean =
        request.dependsOnRequestId?.let(phoneStoredRequestIds::contains) ?: true

    /**
     * Restores the causal edge that schema-v1 could not encode. The whole persisted queue must be
     * decoded before calling this function; linking entries one at a time would let an upgraded
     * delete race its still-pending create over Data Layer and RFCOMM.
     */
    fun linkPendingCreateDeleteDependencies(requests: List<Request>): List<Request> {
        val latestCreateByTaskId = mutableMapOf<String, String>()
        return requests.map { request ->
            val task = request.task
            if (task != null) {
                latestCreateByTaskId[task.id] = request.requestId
                request
            } else {
                val predecessor = request.dependsOnRequestId
                    ?: request.taskId?.let(latestCreateByTaskId::get)
                if (predecessor == null) request else request.copy(dependsOnRequestId = predecessor)
            }
        }
    }

    /** A task is hidden only when its semantic delete is present in the durable pending queue. */
    fun pendingDeleteTaskIds(requests: List<Request>): Set<String> =
        requests.mapNotNull { request -> request.taskId?.takeIf { request.isDelete } }.toSet()

    /** Creates hidden by a queued dependent delete must not reappear in optimistic Watch UI. */
    fun supersededCreateRequestIds(requests: List<Request>): Set<String> =
        requests.filter(Request::isDelete).mapNotNull(Request::dependsOnRequestId).toSet()

    private fun requestObject(request: Request): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("operation", request.operation)
        put("requestId", request.requestId)
        put("createdAtEpochMs", request.createdAtEpochMs)
        put("attempt", request.attempt)
        put("publishedAtEpochMs", request.publishedAtEpochMs)
        request.dependsOnRequestId?.let { put("dependsOnRequestId", it) }
        request.task?.let { task ->
            put("task", JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("type", task.type)
                put("startEpochMs", task.startEpochMs)
                put("endEpochMs", task.endEpochMs)
                put("colorId", task.colorId)
                put("alarmEnabled", task.alarmEnabled)
                put("alarmOffsetMinutes", task.alarmOffsetMinutes)
                put("timeZoneId", task.timeZoneId)
            })
        }
        request.taskId?.let { put("taskId", it) }
        put("commands", commandsArray(request.commands))
    }

    private fun responseObject(response: Response): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("requestId", response.requestId)
        put("status", response.status.wireValue)
        put("acknowledgedAtEpochMs", response.acknowledgedAtEpochMs)
        put("commandIds", JSONArray(response.commandIds))
        response.snapshotVersion?.let { put("snapshotVersion", it) }
        response.errorCode?.let { put("errorCode", it) }
    }

    private fun commandsArray(commands: List<SemanticCommand>): JSONArray = JSONArray().apply {
        commands.forEach { command ->
            put(JSONObject().apply {
                put("commandId", command.commandId)
                put("type", command.type)
                command.aggregateId?.let { put("aggregateId", it) }
                put("arguments", JSONObject(command.argumentsJson))
            })
        }
    }

    private fun decodeCommands(root: JSONObject): List<SemanticCommand> {
        val array = root.optJSONArray("commands")
            ?: throw ProtocolException("INVALID_COMMANDS", "commands must be an array")
        if (array.length() !in 1..MAX_COMMANDS_PER_REQUEST) {
            throw ProtocolException("INVALID_COMMANDS", "Command count is invalid")
        }
        return (0 until array.length()).map { index ->
            val value = array.optJSONObject(index)
                ?: throw ProtocolException("INVALID_COMMAND", "commands[$index] must be an object")
            SemanticCommand(
                commandId = value.strictString("commandId"),
                type = value.strictString("type"),
                aggregateId = value.optionalStrictString("aggregateId", "").takeIf(String::isNotEmpty),
                argumentsJson = (value.optJSONObject("arguments")
                    ?: throw ProtocolException("INVALID_COMMAND", "arguments must be an object")).toString(),
            )
        }
    }

    private fun decodeCreateRequest(root: JSONObject): Request {
        val task = root.optJSONObject("task")
            ?: throw ProtocolException("INVALID_TASK", "task must be an object")
        val createdAt = root.strictLong("createdAtEpochMs")
        return validateBasicRequest(
            Request(
                requestId = root.strictString("requestId"),
                createdAtEpochMs = createdAt,
                attempt = root.optionalStrictInt("attempt", 0),
                publishedAtEpochMs = root.optionalStrictLong("publishedAtEpochMs", createdAt),
                dependsOnRequestId = root.optionalStrictString("dependsOnRequestId", "")
                    .takeIf(String::isNotEmpty),
                task = Task(
                    id = task.strictString("id"),
                    title = task.strictString("title"),
                    type = task.strictString("type"),
                    startEpochMs = task.strictLong("startEpochMs"),
                    endEpochMs = task.strictLong("endEpochMs"),
                    colorId = task.strictInt("colorId"),
                    alarmEnabled = task.strictBoolean("alarmEnabled"),
                    alarmOffsetMinutes = task.strictInt("alarmOffsetMinutes"),
                    timeZoneId = task.optionalStrictString("timeZoneId", DEFAULT_TIME_ZONE_ID),
                ),
            ),
        )
    }

    private fun decodeDeleteRequest(root: JSONObject): Request {
        val createdAt = root.strictLong("createdAtEpochMs")
        return validateBasicRequest(
            Request(
                requestId = root.strictString("requestId"),
                createdAtEpochMs = createdAt,
                attempt = root.optionalStrictInt("attempt", 0),
                publishedAtEpochMs = root.optionalStrictLong("publishedAtEpochMs", createdAt),
                dependsOnRequestId = root.optionalStrictString("dependsOnRequestId", "")
                    .takeIf(String::isNotEmpty),
                taskId = root.strictString("taskId"),
            ),
        )
    }

    private fun commandsForTask(request: Request, idAt: () -> String): List<SemanticCommand> {
        request.taskId?.let { taskId ->
            return listOf(command(idAt(), "task.delete", taskId, JSONObject()))
        }
        val task = request.task!!
        return listOf(
            command(idAt(), "task.create", task.id, JSONObject()
                .put("title", task.title).put("itemType", task.type)),
            command(idAt(), "task.setSchedule", task.id, JSONObject().put(
                "schedule",
                JSONObject().put("startAt", Instant.ofEpochMilli(task.startEpochMs).toString())
                    .put("endAt", Instant.ofEpochMilli(task.endEpochMs).toString()),
            )),
            command(idAt(), "task.setAlarm", task.id, JSONObject()
                .put("enabled", task.alarmEnabled).put("offsetMinutes", task.alarmOffsetMinutes)),
            command(idAt(), "task.setAppearance", task.id, JSONObject().put("colorId", task.colorId)),
            command(idAt(), "task.setExtras", task.id, JSONObject().put(
                "extras",
                JSONObject().put("timezone", task.timeZoneId).put("origin", "wear")
                    .put("watchCreateRequestId", request.requestId)
                    .put("watchCreatedAtEpochMs", request.createdAtEpochMs),
            )),
        )
    }

    private fun withDeterministicSemanticCommands(request: Request): Request {
        val basic = validateBasicRequest(request)
        var ordinal = 0
        val commands = commandsForTask(basic) {
            val current = ordinal++
            deterministicUuidV7(
                basic.createdAtEpochMs,
                listOf(
                    LEGACY_COMMAND_NAMESPACE,
                    basic.requestId,
                    basic.operation,
                    current.toString(),
                    legacyCommandType(basic, current),
                ).joinToString("\u0000"),
            )
        }
        return validateRequest(basic.copy(commands = commands))
    }

    private fun legacyCommandType(request: Request, ordinal: Int): String = if (request.isDelete) {
        "task.delete"
    } else {
        LEGACY_CREATE_COMMAND_TYPES.getOrElse(ordinal) {
            throw ProtocolException("INVALID_COMMANDS", "Legacy command ordinal is invalid")
        }
    }

    private fun command(commandId: String, type: String, aggregateId: String, arguments: JSONObject) =
        SemanticCommand(commandId, type, aggregateId, arguments.toString())

    private fun validateBasicRequest(request: Request): Request {
        val requestId = validatedRequestId(request.requestId)
        val dependsOnRequestId = request.dependsOnRequestId?.let(::validatedRequestId)
        if (dependsOnRequestId == requestId) {
            throw ProtocolException("INVALID_DEPENDENCY", "A request cannot depend on itself")
        }
        if (request.task != null && dependsOnRequestId != null) {
            throw ProtocolException("INVALID_DEPENDENCY", "Only delete requests may carry a dependency")
        }
        if (request.createdAtEpochMs <= 0L) {
            throw ProtocolException("INVALID_CREATED_AT", "createdAtEpochMs must be positive")
        }
        if (request.attempt !in 0..1_000_000) {
            throw ProtocolException("INVALID_ATTEMPT", "attempt is outside the supported range")
        }
        if (request.publishedAtEpochMs <= 0L) {
            throw ProtocolException("INVALID_PUBLISHED_AT", "publishedAtEpochMs must be positive")
        }
        return when {
            request.task != null && request.taskId == null -> validateCreateRequest(
                requestId,
                request.copy(dependsOnRequestId = dependsOnRequestId),
            )
            request.task == null && request.taskId != null -> validateDeleteRequest(
                requestId,
                request.copy(dependsOnRequestId = dependsOnRequestId),
            )
            else -> throw ProtocolException("INVALID_REQUEST", "request must carry exactly one of task/taskId")
        }
    }

    private fun validateRequest(request: Request): Request {
        val basic = validateBasicRequest(request)
        if (basic.commands.isEmpty() || basic.commands.size > MAX_COMMANDS_PER_REQUEST) {
            throw ProtocolException("INVALID_COMMANDS", "Command count is invalid")
        }
        val commands = basic.commands.map { value ->
            val commandId = validatedCommandId(value.commandId)
            val type = value.type.trim()
            if (type !in COMMAND_TYPES) {
                throw ProtocolException("INVALID_COMMAND_TYPE", "Unsupported command type '$type'")
            }
            val aggregateId = value.aggregateId?.trim()?.takeIf(String::isNotEmpty)
            if (aggregateId == null || aggregateId.toByteArray(Charsets.UTF_8).size > MAX_ID_UTF8_BYTES) {
                throw ProtocolException("INVALID_AGGREGATE_ID", "aggregateId is invalid")
            }
            val arguments = runCatching { JSONObject(value.argumentsJson) }.getOrElse {
                throw ProtocolException("INVALID_ARGUMENTS", "arguments must be a JSON object", it)
            }
            value.copy(commandId = commandId, type = type, aggregateId = aggregateId, argumentsJson = arguments.toString())
        }
        if (commands.map(SemanticCommand::commandId).distinct().size != commands.size) {
            throw ProtocolException("DUPLICATE_COMMAND", "Envelope contains duplicate commandId")
        }
        if (commands.any { it.aggregateId != (basic.task?.id ?: basic.taskId) }) {
            throw ProtocolException("AGGREGATE_MISMATCH", "Command aggregate does not match task")
        }
        return basic.copy(commands = commands)
    }

    private fun validateCreateRequest(requestId: String, request: Request): Request {
        val task = request.task!!
        val id = task.id.trim()
        if (id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > MAX_ID_UTF8_BYTES) {
            throw ProtocolException("INVALID_TASK_ID", "task.id is empty or too large")
        }
        val title = task.title.trim().replace(WHITESPACE, " ")
        if (title.isEmpty() || title.codePointCount(0, title.length) > MAX_TITLE_CODE_POINTS ||
            title.toByteArray(Charsets.UTF_8).size > MAX_TITLE_UTF8_BYTES
        ) throw ProtocolException("INVALID_TITLE", "task.title is empty or too large")
        val type = task.type.trim()
        if (type !in TASK_TYPES) throw ProtocolException("INVALID_TYPE", "Unsupported task.type '$type'")
        if (task.startEpochMs <= 0L || task.endEpochMs < task.startEpochMs) {
            throw ProtocolException("INVALID_TIME", "Task time range is invalid")
        }
        if (task.colorId !in 0..7) throw ProtocolException("INVALID_COLOR", "colorId must be in 0..7")
        if (task.alarmOffsetMinutes !in 0..MAX_ALARM_OFFSET_MINUTES) {
            throw ProtocolException("INVALID_ALARM_OFFSET", "alarmOffsetMinutes is invalid")
        }
        val zone = task.timeZoneId.trim()
        if (zone != DEFAULT_TIME_ZONE_ID) {
            throw ProtocolException("INVALID_TIME_ZONE", "Unsupported timeZoneId '$zone'")
        }
        return request.copy(requestId = requestId, task = task.copy(id = id, title = title, type = type, timeZoneId = zone))
    }

    private fun validateDeleteRequest(requestId: String, request: Request): Request {
        val taskId = request.taskId!!.trim()
        if (taskId.isEmpty() || taskId.toByteArray(Charsets.UTF_8).size > MAX_ID_UTF8_BYTES) {
            throw ProtocolException("INVALID_TASK_ID", "taskId is empty or too large")
        }
        return request.copy(requestId = requestId, taskId = taskId, task = null)
    }

    private fun validateResponse(response: Response): Response {
        val requestId = validatedResponseRequestId(response.requestId)
        val commandIds = response.commandIds.map(::validatedCommandId).distinct()
        val snapshotVersion = response.snapshotVersion
        if (snapshotVersion != null && snapshotVersion <= 0L) {
            throw ProtocolException("INVALID_SNAPSHOT_VERSION", "snapshotVersion must be positive")
        }
        if (response.status == Status.SNAPSHOT_PUBLISHED && (commandIds.isEmpty() || snapshotVersion == null)) {
            throw ProtocolException("INCOMPLETE_RECEIPT", "SNAPSHOT_PUBLISHED needs commandIds and snapshotVersion")
        }
        val error = response.errorCode?.trim()?.takeIf(String::isNotEmpty)
        if (error != null && error.length > 80) throw ProtocolException("INVALID_ERROR_CODE", "errorCode is too long")
        return response.copy(requestId = requestId, commandIds = commandIds, snapshotVersion = snapshotVersion, errorCode = error)
    }

    private fun validatedRequestId(raw: String): String {
        val value = raw.trim()
        if (value.length !in 1..MAX_REQUEST_ID_LENGTH || !REQUEST_ID.matches(value)) {
            throw ProtocolException("INVALID_REQUEST_ID", "requestId is invalid")
        }
        return value
    }

    private fun validatedResponseRequestId(raw: String): String =
        raw.trim().takeIf { it == UNKNOWN_REQUEST_ID } ?: validatedRequestId(raw)

    private fun validatedCommandId(raw: String): String {
        val value = raw.trim().lowercase()
        if (!UUID_V7.matches(value)) throw ProtocolException("INVALID_COMMAND_ID", "commandId must be UUIDv7")
        return value
    }

    private fun uuidV7(): String {
        val source = ByteArray(16).also(secureRandom::nextBytes).apply {
            val timestamp = System.currentTimeMillis() and 0xFFFF_FFFF_FFFFL
            for (index in 0 until 6) {
                this[5 - index] = (timestamp ushr (index * 8)).toByte()
            }
        }
        source[6] = ((source[6].toInt() and 0x0f) or 0x70).toByte()
        source[8] = ((source[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(source)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun deterministicUuidV7(timestampEpochMs: Long, identity: String): String {
        val source = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .copyOf(16)
        val timestamp = timestampEpochMs and 0xFFFF_FFFF_FFFFL
        for (index in 0 until 6) {
            source[5 - index] = (timestamp ushr (index * 8)).toByte()
        }
        source[6] = ((source[6].toInt() and 0x0f) or 0x70).toByte()
        source[8] = ((source[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(source)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun requireBoundedJson(raw: String) {
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (size !in 1..MAX_JSON_BYTES) throw ProtocolException("PAYLOAD_TOO_LARGE", "JSON payload is $size bytes")
    }

    private fun requireBoundedBatchJson(raw: String) {
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (size !in 1..MAX_BATCH_JSON_BYTES) throw ProtocolException("PAYLOAD_TOO_LARGE", "Batch JSON is $size bytes")
    }

    private fun parseObject(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (error: Exception) {
        throw ProtocolException("MALFORMED_JSON", "Payload is not a JSON object", error)
    }

    private fun stringList(array: JSONArray): List<String> = (0 until array.length()).map { index ->
        array.optString(index).takeIf(String::isNotEmpty)
            ?: throw ProtocolException("INVALID_FIELD", "Array item must be a string")
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JSONObject.strictString(key: String): String = when (val value = getOrNull(key)) {
        is String -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be a string")
    }
    private fun JSONObject.optionalStrictString(key: String, default: String): String = if (!has(key)) default else strictString(key)
    private fun JSONObject.strictBoolean(key: String): Boolean = when (val value = getOrNull(key)) {
        is Boolean -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be a boolean")
    }
    private fun JSONObject.strictLong(key: String): Long = when (val value = getOrNull(key)) {
        is Byte -> value.toLong(); is Short -> value.toLong(); is Int -> value.toLong(); is Long -> value
        else -> throw ProtocolException("INVALID_FIELD", "$key must be an integer")
    }
    private fun JSONObject.optionalStrictLong(key: String, default: Long): Long = if (!has(key)) default else strictLong(key)
    private fun JSONObject.optionalNullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else strictLong(key)
    private fun JSONObject.strictInt(key: String): Int {
        val value = strictLong(key)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw ProtocolException("INVALID_FIELD", "$key is outside Int range")
        return value.toInt()
    }
    private fun JSONObject.optionalStrictInt(key: String, default: Int): Int = if (!has(key)) default else strictInt(key)
    private fun JSONObject.getOrNull(key: String): Any? = try { if (!has(key) || isNull(key)) null else get(key) } catch (_: Exception) { null }

    const val UNKNOWN_REQUEST_ID = "unknown"
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val LEGACY_COMMAND_NAMESPACE = "tplanner-watch-v1-command"
    private val LEGACY_CREATE_COMMAND_TYPES = listOf(
        "task.create",
        "task.setSchedule",
        "task.setAlarm",
        "task.setAppearance",
        "task.setExtras",
    )
    private const val MAX_COMMANDS_PER_REQUEST = 8
    private val REQUEST_ID = Regex("[A-Za-z0-9._-]+")
    private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val WHITESPACE = Regex("\\s+")
    private val TASK_TYPES = setOf("event", "status", "task")
    private val COMMAND_TYPES = setOf("task.create", "task.setSchedule", "task.setAlarm", "task.setAppearance", "task.setExtras", "task.delete")
    private val secureRandom = SecureRandom()
}
