package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 快照镜像与展示态的字符串 KV(手机端由 Room 表承载,测试用内存实现)。 */
interface SyncKeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

data class SnapshotManifest(
    val snapshotVersion: Long,
    val parentVersion: Long,
    val schemaVersion: Int,
    val stateHash: String,
    val compressedHash: String,
    val encoding: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val serverInstanceId: String?,
) {
    companion object {
        fun fromWire(json: JSONObject): SnapshotManifest = SnapshotManifest(
            snapshotVersion = json.getLong("snapshotVersion"),
            parentVersion = json.optLong("parentVersion", 0),
            schemaVersion = json.getInt("schemaVersion"),
            stateHash = json.getString("stateHash"),
            compressedHash = json.getString("compressedHash"),
            encoding = json.getString("encoding"),
            compressedBytes = json.getLong("compressedBytes"),
            uncompressedBytes = json.getLong("uncompressedBytes"),
            serverInstanceId = json.optString("serverInstanceId").takeIf { it.isNotEmpty() },
        )
    }
}

data class InstallResult(val installed: Boolean, val skipped: Boolean, val version: Long)

/**
 * 快照下载 / 校验 / 原子安装(见 docs/sync-v3.md §7/§8),与桌面 src/syncV3/snapshotInstaller.js 同语义:
 * manifest → 下载 gzip → compressedHash 校验 → 解压 → JCS stateHash 校验 → schema/版本校验
 * → 全部通过后才替换镜像、更新 installed 指针、补发 ACK。任一失败旧镜像不动(ERROR006)。
 */
class SyncV3SnapshotInstaller(
    private val store: SyncV3Store,
    private val kv: SyncKeyValueStore,
    private val http: SyncHttpClient,
    private val serverUrl: String,
) {

    class SnapshotException(message: String, val code: String?) : Exception(message)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun canonicalStateHash(state: JSONObject): String {
        val canonical = Jcs.canonicalize(state)
        return "sha256:${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
    }

    fun fetchLatestMeta(): SnapshotManifest? {
        val response = http.get("$serverUrl/tplanner/v3/snapshots/latest")
        if (response.code == 404) return null
        if (!response.isOk) throw SnapshotException("latest snapshot request failed: ${response.code}", null)
        return SnapshotManifest.fromWire(response.json())
    }

    /** 校验压缩载荷与 hash,返回解析出的权威 state(尚未安装)。 */
    fun verifyPayload(compressed: ByteArray, manifest: SnapshotManifest): JSONObject {
        val compressedHash = "sha256:${sha256Hex(compressed)}"
        if (compressedHash != manifest.compressedHash) {
            throw SnapshotException("compressed hash mismatch: $compressedHash", "ERROR006")
        }

        val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
        val envelope = JSONObject(decompressed.toString(Charsets.UTF_8))

        if (envelope.optInt("snapshotSchemaVersion") != 3 || envelope.optLong("snapshotVersion") != manifest.snapshotVersion) {
            throw SnapshotException("envelope does not match manifest", "ERROR006")
        }

        val state = envelope.getJSONObject("state")
        val stateHash = canonicalStateHash(state)
        if (stateHash != manifest.stateHash) {
            throw SnapshotException("state hash mismatch: $stateHash", "ERROR006")
        }
        return state
    }

    fun install(state: JSONObject, manifest: SnapshotManifest): InstallResult {
        val meta = store.getSyncState()
            ?: throw SnapshotException("sync state not initialized", "ERROR007")
        if (meta.installedSnapshotVersion >= manifest.snapshotVersion) {
            return InstallResult(installed = false, skipped = true, version = meta.installedSnapshotVersion)
        }
        val serverInstanceId = manifest.serverInstanceId
        if (meta.serverInstanceId != null && serverInstanceId != null && meta.serverInstanceId != serverInstanceId) {
            throw SnapshotException("server instance changed; client must re-bootstrap", "ERROR008")
        }

        // 校验全部通过后才落库:先镜像,再指针(§7 原子切换语义)
        kv.set("mirror", state.toString())
        store.upsertSyncState(
            meta.copy(
                installedSnapshotVersion = manifest.snapshotVersion,
                installedSnapshotHash = manifest.stateHash,
                serverInstanceId = serverInstanceId ?: meta.serverInstanceId,
            ),
        )

        // 安装 ACK:尽力而为,失败不致命(重启后补发,§7)
        runCatching {
            http.post(
                "$serverUrl/tplanner/v3/devices/${encode(meta.deviceId)}/snapshot-acks",
                JSONObject()
                    .put("version", manifest.snapshotVersion)
                    .put("stateHash", manifest.stateHash)
                    .toString(),
                idempotencyKey = "",
            )
        }
        return InstallResult(installed = true, skipped = false, version = manifest.snapshotVersion)
    }

    fun getServerMirror(): JSONObject? = kv.get("mirror")?.let { JSONObject(it) }

    /** 拉最新并安装;版本相同返回 skipped。 */
    fun syncToLatest(): InstallResult {
        val manifest = fetchLatestMeta()
            ?: return InstallResult(installed = false, skipped = true, version = 0)
        val meta = store.getSyncState()
        if (meta != null && meta.installedSnapshotVersion >= manifest.snapshotVersion) {
            return InstallResult(installed = false, skipped = true, version = meta.installedSnapshotVersion)
        }

        val response = http.get("$serverUrl/tplanner/v3/snapshots/${manifest.snapshotVersion}")
        if (!response.isOk) throw SnapshotException("snapshot download failed: ${response.code}", "ERROR005")

        val state = verifyPayload(response.bytes, manifest)
        return install(state, manifest)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
