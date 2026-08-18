package com.hamhuo.tplanner

import org.json.JSONObject

/** Live watch <-> phone handshake used by manual refresh and schedule delivery receipts. */
object WatchScheduleRefreshProtocol {
    const val SCHEMA_VERSION = 1
    const val OPERATION_REQUEST = "requestSchedule"
    const val OPERATION_RESPONSE = "scheduleSnapshot"
    const val OPERATION_RECEIPT = "scheduleReceipt"

    const val REQUEST_MESSAGE_PATH = "/tplanner/schedule/refresh"
    const val RESPONSE_MESSAGE_PATH_PREFIX = "/tplanner/schedule/refresh/response/"
    const val RECEIPT_MESSAGE_PATH_PREFIX = "/tplanner/schedule/refresh/receipt/"
    const val DELIVERY_ACK_PATH_PREFIX = "/tplanner/schedule/ack/"

    private const val MAX_REQUEST_ID_LENGTH = 128
    private val requestIdPattern = Regex("[A-Za-z0-9._-]{1,$MAX_REQUEST_ID_LENGTH}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    data class Request(
        val requestId: String,
        val requestedAtEpochMs: Long,
    )

    data class Response(
        val requestId: String,
        val snapshot: String? = null,
        val errorCode: String? = null,
    )

    data class Receipt(
        val requestId: String,
        val version: Long,
        val hash: String,
        val acceptedAtEpochMs: Long,
    )

    data class SnapshotIdentity(val version: Long, val hash: String)

    fun responseMessagePath(requestId: String): String =
        RESPONSE_MESSAGE_PATH_PREFIX + validatedRequestId(requestId)

    fun receiptMessagePath(requestId: String): String =
        RECEIPT_MESSAGE_PATH_PREFIX + validatedRequestId(requestId)

    fun deliveryAckPath(version: Long): String {
        require(version >= 0L) { "version must be non-negative" }
        return DELIVERY_ACK_PATH_PREFIX + version
    }

    fun requestIdFromPath(path: String?, prefix: String): String? {
        val value = path?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotEmpty() && '/' !in it }
            ?: return null
        return runCatching { validatedRequestId(value) }.getOrNull()
    }

    fun encodeRequest(request: Request): String {
        val requestId = validatedRequestId(request.requestId)
        require(request.requestedAtEpochMs > 0L) { "requestedAtEpochMs must be positive" }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("operation", OPERATION_REQUEST)
            put("requestId", requestId)
            put("requestedAtEpochMs", request.requestedAtEpochMs)
        }.toString()
    }

    fun decodeRequest(raw: String): Request {
        val root = parseObject(raw)
        requireEnvelope(root, OPERATION_REQUEST)
        return Request(
            requestId = validatedRequestId(root.getString("requestId")),
            requestedAtEpochMs = root.strictPositiveLong("requestedAtEpochMs"),
        )
    }

    fun isRefreshRequest(raw: String): Boolean = runCatching {
        val root = parseObject(raw)
        root.optInt("schemaVersion", -1) == SCHEMA_VERSION &&
            root.optString("operation") == OPERATION_REQUEST
    }.getOrDefault(false)

    fun encodeResponse(response: Response): String {
        val requestId = validatedRequestId(response.requestId)
        require((response.snapshot != null) xor (response.errorCode != null)) {
            "response must contain exactly one of snapshot or errorCode"
        }
        response.snapshot?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= ScheduleRfcommProtocol.MAX_PAYLOAD_BYTES) {
                "snapshot is too large"
            }
            snapshotIdentity(it)
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("operation", OPERATION_RESPONSE)
            put("requestId", requestId)
            response.snapshot?.let { put("snapshot", it) }
            response.errorCode?.let { put("errorCode", validatedErrorCode(it)) }
        }.toString()
    }

    fun decodeResponse(raw: String): Response {
        val root = parseObject(raw)
        requireEnvelope(root, OPERATION_RESPONSE)
        val snapshot = root.optString("snapshot").takeIf(String::isNotEmpty)
        val errorCode = root.optString("errorCode").takeIf(String::isNotEmpty)
        require((snapshot != null) xor (errorCode != null)) {
            "response must contain exactly one of snapshot or errorCode"
        }
        snapshot?.let(::snapshotIdentity)
        return Response(
            requestId = validatedRequestId(root.getString("requestId")),
            snapshot = snapshot,
            errorCode = errorCode?.let(::validatedErrorCode),
        )
    }

    fun encodeReceipt(receipt: Receipt): String {
        val requestId = validatedRequestId(receipt.requestId)
        require(receipt.version >= 0L) { "version must be non-negative" }
        require(sha256Pattern.matches(receipt.hash)) { "hash must be lowercase SHA-256" }
        require(receipt.acceptedAtEpochMs > 0L) { "acceptedAtEpochMs must be positive" }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("operation", OPERATION_RECEIPT)
            put("requestId", requestId)
            put("version", receipt.version)
            put("hash", receipt.hash)
            put("acceptedAtEpochMs", receipt.acceptedAtEpochMs)
        }.toString()
    }

    fun decodeReceipt(raw: String): Receipt {
        val root = parseObject(raw)
        requireEnvelope(root, OPERATION_RECEIPT)
        val version = root.strictNonNegativeLong("version")
        val hash = root.getString("hash")
        require(sha256Pattern.matches(hash)) { "hash must be lowercase SHA-256" }
        return Receipt(
            requestId = validatedRequestId(root.getString("requestId")),
            version = version,
            hash = hash,
            acceptedAtEpochMs = root.strictPositiveLong("acceptedAtEpochMs"),
        )
    }

    fun receiptFor(requestId: String, snapshot: String, acceptedAtEpochMs: Long): Receipt {
        val identity = snapshotIdentity(snapshot)
        return Receipt(
            requestId = validatedRequestId(requestId),
            version = identity.version,
            hash = identity.hash,
            acceptedAtEpochMs = acceptedAtEpochMs,
        )
    }

    fun snapshotIdentity(snapshot: String): SnapshotIdentity {
        require(snapshot.toByteArray(Charsets.UTF_8).size <= ScheduleRfcommProtocol.MAX_PAYLOAD_BYTES) {
            "snapshot is too large"
        }
        val root = parseObject(snapshot)
        val version = root.strictNonNegativeLong("version")
        val hash = root.getString("hash")
        require(sha256Pattern.matches(hash)) { "snapshot hash must be lowercase SHA-256" }
        return SnapshotIdentity(version, hash)
    }

    private fun requireEnvelope(root: JSONObject, operation: String) {
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
            "unsupported schemaVersion"
        }
        require(root.optString("operation") == operation) { "unexpected operation" }
    }

    private fun parseObject(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= ScheduleRfcommProtocol.MAX_PAYLOAD_BYTES) {
            "message is too large"
        }
        return JSONObject(raw)
    }

    private fun JSONObject.strictPositiveLong(field: String): Long =
        strictNonNegativeLong(field).also { require(it > 0L) { "$field must be positive" } }

    private fun JSONObject.strictNonNegativeLong(field: String): Long {
        val value = get(field)
        val number = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$field must be an integer")
        }
        require(number >= 0L) { "$field must be non-negative" }
        return number
    }

    private fun validatedRequestId(value: String): String = value.also {
        require(requestIdPattern.matches(it)) { "invalid requestId" }
    }

    private fun validatedErrorCode(value: String): String = value.also {
        require(Regex("[A-Z0-9_]{1,64}").matches(it)) { "invalid errorCode" }
    }
}
