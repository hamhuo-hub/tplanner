package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    /** Envelope-only coverage, populated after payload verification. */
    val brokerToSequence: Long = 0L,
    /** delta-v1 bootstrap cursor(§9.3);旧服务器不带,客户端保持旧值。 */
    val cursor: String? = null,
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
            cursor = json.optString("cursor").takeIf { it.isNotEmpty() },
        )
    }
}

data class InstallResult(val installed: Boolean, val skipped: Boolean, val version: Long)

data class VerifiedSnapshot(
    val state: JSONObject,
    val serverInstanceId: String,
    val brokerToSequence: Long,
)

data class FetchedSnapshot(
    val manifest: SnapshotManifest,
    val verified: VerifiedSnapshot,
)

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
    private val projectionInstaller: SyncV3ProjectionInstaller? = null,
    private val onDisplayedInstalled: ((DisplayedStateProjection, DisplayedStateProjection, Long, Long) -> Unit)? = null,
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
        val manifest = runCatching { SnapshotManifest.fromWire(response.json()) }
            .getOrElse { error ->
                throw SnapshotException("latest snapshot is not a V3 manifest", "ERROR008")
                    .apply { initCause(error) }
            }
        if (manifest.schemaVersion != 3 || manifest.encoding != "gzip" ||
            manifest.snapshotVersion < 1L || manifest.compressedBytes < 1L ||
            manifest.compressedBytes > MAX_COMPRESSED_BYTES ||
            manifest.uncompressedBytes < 1L || manifest.uncompressedBytes > MAX_UNCOMPRESSED_BYTES ||
            !SHA256_PATTERN.matches(manifest.stateHash) ||
            !SHA256_PATTERN.matches(manifest.compressedHash)
        ) {
            throw SnapshotException("unsupported snapshot manifest", "ERROR008")
        }
        return manifest
    }

    /** 校验压缩载荷、envelope identity 与 hash,返回尚未安装的权威 state。 */
    fun verifyPayload(compressed: ByteArray, manifest: SnapshotManifest): VerifiedSnapshot {
        if (!SHA256_PATTERN.matches(manifest.stateHash) ||
            !SHA256_PATTERN.matches(manifest.compressedHash)
        ) {
            throw SnapshotException("snapshot manifest contains an invalid hash", "ERROR008")
        }
        if (compressed.size.toLong() != manifest.compressedBytes) {
            throw SnapshotException("compressed byte count does not match manifest", "ERROR006")
        }
        val compressedHash = "sha256:${sha256Hex(compressed)}"
        if (compressedHash != manifest.compressedHash) {
            throw SnapshotException("compressed hash mismatch: $compressedHash", "ERROR006")
        }

        val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            val output = ByteArrayOutputStream(minOf(manifest.uncompressedBytes, 64L * 1024L).toInt())
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = gzip.read(buffer)
                if (count < 0) break
                total += count
                if (total > manifest.uncompressedBytes || total > MAX_UNCOMPRESSED_BYTES) {
                    throw SnapshotException("snapshot expands beyond its declared limit", "ERROR006")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (decompressed.size.toLong() != manifest.uncompressedBytes ||
            decompressed.size.toLong() > MAX_UNCOMPRESSED_BYTES
        ) {
            throw SnapshotException("uncompressed byte count does not match manifest", "ERROR006")
        }
        val envelope = JSONObject(decompressed.toString(Charsets.UTF_8))

        if (envelope.optInt("snapshotSchemaVersion") != 3 ||
            envelope.optLong("snapshotVersion") != manifest.snapshotVersion
        ) {
            throw SnapshotException("envelope does not match manifest", "ERROR006")
        }
        val envelopeServerInstanceId = envelope.optString("serverInstanceId")
            .takeIf(String::isNotBlank)
            ?: throw SnapshotException("snapshot envelope has no serverInstanceId", "ERROR006")
        if (manifest.serverInstanceId != null &&
            envelopeServerInstanceId != manifest.serverInstanceId
        ) {
            throw SnapshotException("snapshot envelope serverInstanceId does not match manifest", "ERROR006")
        }
        val brokerToSequence = envelope.optLong("brokerToSequence", -1L)
        if (brokerToSequence < 0L) {
            throw SnapshotException("snapshot envelope has invalid broker coverage", "ERROR006")
        }

        val state = envelope.getJSONObject("state")
        val stateHash = canonicalStateHash(state)
        if (stateHash != manifest.stateHash) {
            throw SnapshotException("state hash mismatch: $stateHash", "ERROR006")
        }
        SyncV3ProjectionCodec.validateAuthoritativeState(state)
        return VerifiedSnapshot(state, envelopeServerInstanceId, brokerToSequence)
    }

    /** Fetch and verify a bootstrap baseline without exposing it to Room yet. */
    fun fetchLatestVerified(): FetchedSnapshot? {
        val manifest = fetchLatestMeta() ?: return null
        val response = http.getBounded(
            "$serverUrl/tplanner/v3/snapshots/${manifest.snapshotVersion}",
            manifest.compressedBytes,
        )
        if (!response.isOk) throw SnapshotException(
            "snapshot download failed: ${response.code}",
            "ERROR005",
        )
        return FetchedSnapshot(manifest, verifyPayload(response.bytes, manifest))
    }

    fun install(
        state: JSONObject,
        manifest: SnapshotManifest,
        verifiedServerInstanceId: String = manifest.serverInstanceId.orEmpty(),
        verifiedBrokerToSequence: Long = manifest.brokerToSequence,
    ): InstallResult {
        val meta = store.getSyncState()
            ?: throw SnapshotException("sync state not initialized", "ERROR007")
        if (meta.installedSnapshotVersion > manifest.snapshotVersion) {
            throw SnapshotException("snapshot version regressed below installed version", "ERROR008")
        }
        if (meta.installedSnapshotVersion == manifest.snapshotVersion) {
            if (meta.installedSnapshotHash != manifest.stateHash) {
                throw SnapshotException("same snapshot version has conflicting stateHash", "ERROR008")
            }
            return InstallResult(installed = false, skipped = true, version = meta.installedSnapshotVersion)
        }
        val serverInstanceId = verifiedServerInstanceId.ifBlank {
            manifest.serverInstanceId.orEmpty()
        }.takeIf(String::isNotBlank)
            ?: throw SnapshotException("snapshot has no verified serverInstanceId", "ERROR006")
        if (meta.serverInstanceId != null && meta.serverInstanceId != serverInstanceId) {
            throw SnapshotException("server instance changed; client must re-bootstrap", "ERROR008")
        }

        // The envelope identity is authoritative when an older manifest omitted the optional
        // field. Carry it into the atomic Room install instead of discarding it after validation.
        val resolvedManifest = manifest.copy(
            serverInstanceId = serverInstanceId,
            brokerToSequence = verifiedBrokerToSequence,
        )
        val roomResult = projectionInstaller?.installAtomically(state, resolvedManifest)
        if (roomResult == null) {
            // JVM/non-Room implementation retained for protocol tests.
            kv.set("mirror", state.toString())
            store.upsertSyncState(
                meta.copy(
                    installedSnapshotVersion = manifest.snapshotVersion,
                    installedSnapshotHash = manifest.stateHash,
                    serverInstanceId = serverInstanceId ?: meta.serverInstanceId,
                    serverMirrorJson = state.toString(),
                    installedBrokerToSequence = verifiedBrokerToSequence,
                    cursor = manifest.cursor ?: meta.cursor,
                ),
            )
        } else if (roomResult.installed) {
            // Wear mirrors the central snapshot, never the phone's optimistic overlay.
            onDisplayedInstalled?.invoke(
                roomResult.displayed,
                roomResult.authoritative,
                roomResult.version,
                resolvedManifest.brokerToSequence,
            )
        }

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
        return if (roomResult != null && !roomResult.installed) {
            InstallResult(installed = false, skipped = true, version = roomResult.version)
        } else {
            InstallResult(installed = true, skipped = false, version = manifest.snapshotVersion)
        }
    }

    fun getServerMirror(): JSONObject? = kv.get("mirror")?.let { JSONObject(it) }

    /** 拉最新并安装;版本相同返回 skipped。 */
    fun syncToLatest(): InstallResult {
        val manifest = fetchLatestMeta()
            ?: return InstallResult(installed = false, skipped = true, version = 0)
        val meta = store.getSyncState()
        if (meta != null && meta.installedSnapshotVersion > manifest.snapshotVersion) {
            throw SnapshotException("latest snapshot version regressed below installed version", "ERROR008")
        }
        if (meta != null && meta.installedSnapshotVersion == manifest.snapshotVersion) {
            if (meta.installedSnapshotHash != manifest.stateHash) {
                throw SnapshotException("latest manifest conflicts with installed snapshot", "ERROR008")
            }
            if (meta.serverInstanceId != null && manifest.serverInstanceId != null &&
                meta.serverInstanceId != manifest.serverInstanceId
            ) {
                throw SnapshotException("latest manifest changed serverInstanceId", "ERROR008")
            }
            return InstallResult(false, true, meta.installedSnapshotVersion)
        }

        val response = http.getBounded(
            "$serverUrl/tplanner/v3/snapshots/${manifest.snapshotVersion}",
            manifest.compressedBytes,
        )
        if (!response.isOk) throw SnapshotException("snapshot download failed: ${response.code}", "ERROR005")
        val verified = verifyPayload(response.bytes, manifest)
        return install(
            verified.state,
            manifest,
            verified.serverInstanceId,
            verified.brokerToSequence,
        )
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L
        const val MAX_COMPRESSED_BYTES = 16L * 1024L * 1024L
        val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
    }
}
