package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class SyncV3SnapshotInstallerTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeStore : SyncV3Store {
        var state: SyncStateEntity? = null
        val receipts = mutableListOf<SyncReceiptEntity>()
        override fun getSyncState(): SyncStateEntity? = state
        override fun upsertSyncState(s: SyncStateEntity) { state = s }
        override fun listCommands(state: String, limit: Int): List<SyncCommandEntity> = emptyList()
        override fun markUploaded(sequences: List<Long>) {}
        override fun deleteThroughSequence(through: Long) {}
        override fun insertReceipts(r: List<SyncReceiptEntity>) { receipts.addAll(r) }
        override fun acceptedThrough(): Long? = null
    }

    private class MemoryKv : SyncKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    private class FakeHttp : SyncHttpClient {
        var latest: (() -> SyncHttpResponse)? = null
        var snapshot: ((Int) -> SyncHttpResponse)? = null
        val acks = mutableListOf<String>()
        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse {
            if (url.contains("/snapshot-acks")) {
                acks.add(body)
                return SyncHttpResponse.text(202, "{}")
            }
            return SyncHttpResponse.text(400, "{}")
        }
        override fun get(url: String, timeoutMs: Int): SyncHttpResponse =
            if (url.contains("/snapshots/latest")) latest?.invoke() ?: SyncHttpResponse.text(404, "{}")
            else {
                val version = url.substringAfterLast('/').toInt()
                snapshot?.invoke(version) ?: SyncHttpResponse.text(404, "{}")
            }
    }

    private fun buildSnapshot(version: Int, state: JSONObject, serverInstanceId: String = "srv-test"): Pair<ByteArray, SnapshotManifest> {
        val envelope = JSONObject()
            .put("snapshotSchemaVersion", 3)
            .put("snapshotVersion", version.toLong())
            .put("parentVersion", (version - 1).toLong())
            .put("serverInstanceId", serverInstanceId)
            .put("brokerFromSequence", 0)
            .put("brokerToSequence", 0)
            .put("createdAt", "2026-08-20T00:00:00.000Z")
            .put("state", state)

        val uncompressed = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(uncompressed) }
        val compressed = out.toByteArray()

        val stateHash = "sha256:${sha256(Jcs.canonicalize(state).toByteArray(StandardCharsets.UTF_8))}"
        val manifest = SnapshotManifest(
            snapshotVersion = version.toLong(),
            parentVersion = (version - 1).toLong(),
            schemaVersion = 3,
            stateHash = stateHash,
            compressedHash = "sha256:${sha256(compressed)}",
            encoding = "gzip",
            compressedBytes = compressed.size.toLong(),
            uncompressedBytes = uncompressed.size.toLong(),
            serverInstanceId = serverInstanceId,
        )
        return compressed to manifest
    }

    private fun stateWith(title: String): JSONObject = JSONObject()
        .put(
            "tasks",
            JSONObject().put(
                "task-1",
                JSONObject()
                    .put("title", title)
                    .put("note", "")
                    .put("completed", false)
                    .put("itemType", "task")
                    .put("lifecycle", "active")
                    .put("deletedAt", JSONObject.NULL),
            ),
        )
        .put("customLists", JSONObject())
        .put("journals", JSONObject())
        .put("goals", JSONObject())
        .put("insights", JSONObject())

    @Test
    fun `downloads, verifies and atomically installs a snapshot`() {
        val store = FakeStore()
        store.state = SyncStateEntity(1, "phone-test", 1, 0, null, null)
        val kv = MemoryKv()
        val http = FakeHttp()
        val (compressed, manifest) = buildSnapshot(7, stateWith("开会"))
        http.latest = { SyncHttpResponse.text(200, manifestToJson(manifest).toString()) }
        http.snapshot = { SyncHttpResponse(200, compressed) }

        val installer = SyncV3SnapshotInstaller(store, kv, http, "https://sync.example")
        val result = installer.syncToLatest()

        assertTrue(result.installed)
        assertEquals(7L, result.version)
        assertEquals("开会", installer.getServerMirror()!!.getJSONObject("tasks").getJSONObject("task-1").getString("title"))
        assertEquals(7L, store.state!!.installedSnapshotVersion)
        assertEquals(manifest.stateHash, store.state!!.installedSnapshotHash)
        assertEquals("srv-test", store.state!!.serverInstanceId)
        assertEquals(1, http.acks.size)
    }

    @Test
    fun `skips when already installed`() {
        val store = FakeStore()
        store.state = SyncStateEntity(1, "phone-test", 1, 7, null, "srv-test")
        val http = FakeHttp()
        val (_, manifest) = buildSnapshot(7, stateWith("开会"))
        http.latest = { SyncHttpResponse.text(200, manifestToJson(manifest).toString()) }
        val installer = SyncV3SnapshotInstaller(store, MemoryKv(), http, "https://sync.example")

        val result = installer.syncToLatest()
        assertTrue(result.skipped)
        assertEquals(7L, result.version)
        assertNull("已装版本不再下载载荷", http.snapshot)
    }

    @Test
    fun `corrupted payload fails with ERROR006 and leaves the old mirror untouched`() {
        val store = FakeStore()
        store.state = SyncStateEntity(1, "phone-test", 1, 0, null, null)
        val kv = MemoryKv()
        val http = FakeHttp()
        val (compressed, manifest) = buildSnapshot(7, stateWith("开会"))
        http.latest = { SyncHttpResponse.text(200, manifestToJson(manifest).toString()) }
        http.snapshot = { SyncHttpResponse(200, compressed.copyOfRange(0, compressed.size - 8)) }

        val installer = SyncV3SnapshotInstaller(store, kv, http, "https://sync.example")
        val e = runCatching { installer.syncToLatest() }.exceptionOrNull() as? SyncV3SnapshotInstaller.SnapshotException

        assertEquals("ERROR006", e?.code)
        assertNull("失败不得写入镜像", kv.get("mirror"))
        assertEquals("旧指针不动", 0L, store.state!!.installedSnapshotVersion)
    }

    @Test
    fun `state hash mismatch is detected even when gzip is intact`() {
        val store = FakeStore()
        store.state = SyncStateEntity(1, "phone-test", 1, 0, null, null)
        val http = FakeHttp()
        val (compressed, manifest) = buildSnapshot(7, stateWith("开会"))
        val tampered = manifest.copy(stateHash = "sha256:${"0".repeat(64)}")
        http.latest = { SyncHttpResponse.text(200, manifestToJson(tampered).toString()) }
        http.snapshot = { SyncHttpResponse(200, compressed) }

        val installer = SyncV3SnapshotInstaller(store, MemoryKv(), http, "https://sync.example")
        val e = runCatching { installer.syncToLatest() }.exceptionOrNull() as? SyncV3SnapshotInstaller.SnapshotException
        assertEquals("ERROR006", e?.code)
    }

    @Test
    fun `JCS hash matches the server-side canonicalize reference value`() {
        // 基准值:服务器用 canonicalize(npm) 对 sequence-01 expected-state 计算的 sha256
        val fixture = JSONObject(
            javaClass.classLoader!!.getResourceAsStream("syncv3/sequence-01.expected-state.json")
                .readBytes().toString(StandardCharsets.UTF_8),
        )
        val hash = SyncV3SnapshotInstaller(
            FakeStore().apply { state = SyncStateEntity(1, "d", 1, 0, null, null) },
            MemoryKv(),
            FakeHttp(),
            "https://x",
        ).canonicalStateHash(fixture)
        assertEquals("sha256:a2fce93fa47c53108b9f5c02e0e1ecccf6df637c56171991dcdf76fa8a1947f6", hash)
    }

    private fun manifestToJson(m: SnapshotManifest): JSONObject = JSONObject()
        .put("snapshotVersion", m.snapshotVersion)
        .put("parentVersion", m.parentVersion)
        .put("schemaVersion", m.schemaVersion)
        .put("stateHash", m.stateHash)
        .put("compressedHash", m.compressedHash)
        .put("encoding", m.encoding)
        .put("compressedBytes", m.compressedBytes)
        .put("uncompressedBytes", m.uncompressedBytes)
        .put("serverInstanceId", m.serverInstanceId)
}
