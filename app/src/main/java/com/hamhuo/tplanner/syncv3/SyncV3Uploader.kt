package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * 批次上传器(见 docs/sync-v3.md §12/§15):
 *   - 前一批未获 BROKER_PERSISTED 不上传下一批(串行排空);
 *   - 202 只表示 broker 已持久接收 → 命令标记 uploaded,仍留在 outbox;
 *   - 回执只持久化，不在这里删除 outbox；对应权威快照原子安装后才可清理(§8)。
 * 与桌面 src/syncV3/uploader.js 同一套协议行为。
 */
class SyncV3Uploader(
    private val store: SyncV3Store,
    private val http: SyncHttpClient,
    private val serverUrl: String,
    private val uuidV7: () -> String = { uuidV7Default() },
) {

    class SyncException(message: String, val status: Int, val errorCode: String?) : Exception(message)

    /** 排空一轮:上传一批 pending 命令并收集回执。返回上传条数。 */
    fun pump(maxBatch: Int = 100): Int {
        val meta = store.getSyncState() ?: return 0
        val candidates = store.listCommands("pending", maxBatch)
        if (candidates.isEmpty()) return 0
        val persistedBatchId = candidates.first().batchId
        val pending = if (persistedBatchId.isBlank()) {
            candidates
        } else {
            candidates.takeWhile { it.batchId == persistedBatchId }
        }

        val batchId = persistedBatchId.ifBlank(uuidV7)
        val batch = buildBatch(meta.deviceId, batchId, pending)
        val response = http.post("$serverUrl/tplanner/v3/command-batches", batch.toString(), batchId)

        if (response.code == 202) {
            val acknowledgement = runCatching { response.json() }.getOrElse {
                throw SyncException("invalid broker acknowledgement", response.code, "INVALID_ACK")
            }
            if (acknowledgement.optString("state") != "BROKER_PERSISTED" ||
                acknowledgement.optString("batchId") != batchId ||
                acknowledgement.optLong("brokerSequence", 0L) < 1L
            ) {
                throw SyncException("invalid broker acknowledgement", response.code, "INVALID_ACK")
            }
            store.markUploaded(pending.map { it.clientSequence })
        } else {
            val errorCode = runCatching { response.json().optString("error") }.getOrNull()
            throw SyncException("command batch rejected: ${response.code} ${errorCode.orEmpty()}", response.code, errorCode)
        }
        return pending.size
    }

    /** 拉取并持久化回执。Outbox 清理由 Room 快照投影事务完成。 */
    fun collectReceipts(): Int {
        val meta = store.getSyncState() ?: return 0
        // 无未终态命令时不可能存在新回执(回执只为本设备已上传的命令生成):
        // 省掉一次 /receipts round-trip。手动同步"空上传"阶段此前为此白付 ~1.5s。
        if (store.pendingCount() == 0 && store.uploadedCount() == 0) return 0
        val after = store.acceptedThrough() ?: 0L
        val url = "$serverUrl/tplanner/v3/receipts?deviceId=${encode(meta.deviceId)}&afterClientSequence=$after"
        val response = http.get(url)
        if (!response.isOk) throw SyncException("receipts request failed: ${response.code}", response.code, null)

        val body = response.json()
        val results = body.optJSONArray("results")
        val receipts = mutableListOf<SyncReceiptEntity>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val r = SyncReceipt.fromWire(results.getJSONObject(i))
                receipts.add(
                    SyncReceiptEntity(
                        commandId = r.commandId,
                        clientSequence = r.clientSequence,
                        status = r.status,
                        snapshotVersion = r.snapshotVersion,
                        errorCode = r.errorCode,
                        brokerSequence = r.brokerSequence,
                    ),
                )
            }
        }
        store.insertReceipts(receipts)
        return receipts.size
    }

    /** 手动同步:排空所有 pending 后收受回执(快照安装由安装器接力)。 */
    fun flush() {
        while (pump() > 0) {
            // 继续排空
        }
        while (collectReceipts() >= RECEIPT_PAGE_SIZE) {
            // Continue from the persisted cursor; bootstrap can exceed one 200-receipt page.
        }
    }

    private fun buildBatch(deviceId: String, batchId: String, commands: List<SyncCommandEntity>): JSONObject {
        val array = org.json.JSONArray()
        commands.forEach { entity ->
            array.put(
                JSONObject().apply {
                    put("commandId", entity.commandId)
                    put("clientSequence", entity.clientSequence)
                    put("type", entity.commandType)
                    if (entity.aggregateId != null) put("aggregateId", entity.aggregateId)
                    put("arguments", JSONObject(entity.argumentsJson))
                },
            )
        }
        return JSONObject().apply {
            put("protocolVersion", 3)
            put("batchId", batchId)
            put("deviceId", deviceId)
            put("firstClientSequence", commands.first().clientSequence)
            put("lastClientSequence", commands.last().clientSequence)
            put("commands", array)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val RECEIPT_PAGE_SIZE = 200
        private val secureRandom = SecureRandom()

        /** RFC 9562 UUIDv7: 48-bit Unix milliseconds followed by cryptographic random bits. */
        fun uuidV7Default(): String {
            val bytes = ByteArray(16).also(secureRandom::nextBytes)
            val timestamp = System.currentTimeMillis() and 0xFFFF_FFFF_FFFFL
            for (index in 0 until 6) {
                bytes[5 - index] = (timestamp ushr (index * 8)).toByte()
            }
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long).toString()
        }
    }
}
