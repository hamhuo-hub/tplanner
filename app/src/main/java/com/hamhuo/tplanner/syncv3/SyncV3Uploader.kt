package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.util.UUID

/**
 * 批次上传器(见 docs/sync-v3.md §12/§15):
 *   - 前一批未获 BROKER_PERSISTED 不上传下一批(串行排空);
 *   - 202 只表示 broker 已持久接收 → 命令标记 uploaded,仍留在 outbox;
 *   - 回执确认后才删除 outbox 条目(不变量 #10)。
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
        val pending = store.listCommands("pending", maxBatch)
        if (pending.isEmpty()) return 0

        val batchId = uuidV7()
        val batch = buildBatch(meta.deviceId, batchId, pending)
        val response = http.post("$serverUrl/tplanner/v3/command-batches", batch.toString(), batchId)

        if (response.code == 202) {
            store.markUploaded(pending.map { it.clientSequence })
        } else {
            val errorCode = runCatching { response.json().optString("error") }.getOrNull()
            throw SyncException("command batch rejected: ${response.code} ${errorCode.orEmpty()}", response.code, errorCode)
        }
        return pending.size
    }

    /** 拉取回执并据此删除已确认的 outbox 条目。 */
    fun collectReceipts() {
        val meta = store.getSyncState() ?: return
        val url = "$serverUrl/tplanner/v3/receipts?deviceId=${encode(meta.deviceId)}&afterClientSequence=0"
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
                    ),
                )
            }
        }
        store.insertReceipts(receipts)
        store.acceptedThrough()?.let { store.deleteThroughSequence(it) }
    }

    /** 手动同步:排空所有 pending 后收受回执(快照安装由安装器接力)。 */
    fun flush() {
        while (pump() > 0) {
            // 继续排空
        }
        collectReceipts()
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
        /** UUIDv7(协议 schema 要求第 3 组以 7 开头、第 4 组以 8/9/a/b 开头)。 */
        fun uuidV7Default(): String {
            val raw = UUID.randomUUID().toString().replace("-", "")
            // 只用最后 8 个十六进制字符(32 位)构造第 3/4 组,避免 64 位溢出
            val tail = raw.substring(24).toLong(16)
            val seg3 = (0x7000L or ((tail ushr 16) and 0xFFFL)).toString(16).padStart(4, '0')
            val seg4 = (0x8000L or (tail and 0x3FFFL)).toString(16).padStart(4, '0')
            return "${raw.substring(0, 8)}-${raw.substring(8, 12)}-$seg3-$seg4-${raw.substring(20)}"
        }
    }
}
