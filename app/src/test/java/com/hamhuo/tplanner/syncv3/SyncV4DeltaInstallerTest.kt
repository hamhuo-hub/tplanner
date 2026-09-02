package com.hamhuo.tplanner.syncv3

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SyncV4DeltaInstallerTest {

    private class FakeStore : SyncV3Store {
        var state: SyncStateEntity? = null
        override fun getSyncState(): SyncStateEntity? = state
        override fun upsertSyncState(s: SyncStateEntity) { state = s }
        override fun listCommands(state: String, limit: Int): List<SyncCommandEntity> = emptyList()
        override fun markUploaded(sequences: List<Long>) {}
        override fun insertReceipts(receipts: List<SyncReceiptEntity>) {}
        override fun acceptedThrough(): Long? = null
    }

    private class FakeHttp(private val pages: Map<String, JSONObject>, private val failCode: Int? = null) :
        SyncHttpClient {
        val requestedCursors = mutableListOf<String>()
        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse =
            SyncHttpResponse.text(400, "{}")

        override fun get(url: String, timeoutMs: Int): SyncHttpResponse {
            val cursor = url.substringAfter("cursor=").substringBefore('&')
            requestedCursors.add(cursor)
            if (failCode != null) {
                return if (failCode == 410) {
                    SyncHttpResponse.text(410, """{"error":"CURSOR_EXPIRED","recovery":"FULL_SNAPSHOT"}""")
                } else {
                    SyncHttpResponse.text(failCode, "{}")
                }
            }
            val page = pages[cursor] ?: throw AssertionError("unexpected cursor $cursor")
            return SyncHttpResponse.text(200, page.toString())
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hashOf(state: JSONObject): String =
        "sha256:${sha256(Jcs.canonicalize(state).toByteArray(StandardCharsets.UTF_8))}"

    private fun emptyMirror(): JSONObject = JSONObject()
        .put("tasks", JSONObject())
        .put("customLists", JSONObject())
        .put("journals", JSONObject())
        .put("goals", JSONObject())
        .put("insights", JSONObject())

    private fun canonicalTask(title: String): JSONObject = JSONObject()
        .put("title", title)
        .put("note", "")
        .put("completed", false)
        .put("itemType", "task")
        .put("schedule", JSONObject.NULL)
        .put("recurrence", JSONObject.NULL)
        .put("alarm", JSONObject().put("enabled", false).put("offsetMinutes", 0))
        .put("colorId", 0)
        .put("location", JSONObject().put("lat", JSONObject.NULL).put("lng", JSONObject.NULL))
        .put("extras", JSONObject())
        .put("listId", JSONObject.NULL)
        .put("checklist", JSONArray())
        .put("lifecycle", "active")
        .put("deletedAt", JSONObject.NULL)

    private fun mirrorWithTask(title: String): JSONObject =
        emptyMirror().put("tasks", JSONObject().put("t1", canonicalTask(title)))

    private fun changeJson(type: String, entityId: String, brokerTo: Long, value: JSONObject): JSONObject =
        JSONObject()
            .put("type", type)
            .put("entityId", entityId)
            .put("entityBrokerSequence", brokerTo)
            .put("value", value)

    private fun commitJson(
        version: Long,
        parent: Long,
        brokerTo: Long,
        changes: JSONArray,
        stateAfter: JSONObject,
    ): JSONObject = JSONObject()
        .put("snapshotVersion", version)
        .put("parentVersion", parent)
        .put("brokerFromSequence", brokerTo)
        .put("brokerToSequence", brokerTo)
        .put("stateHashAfter", hashOf(stateAfter))
        .put("changes", changes)

    private fun pageJson(
        fromCursor: String,
        toCursor: String,
        commits: JSONArray,
        hasMore: Boolean,
        head: Long,
    ): JSONObject = JSONObject()
        .put("protocolVersion", 3)
        .put("deltaVersion", 1)
        .put("schemaVersion", 3)
        .put("serverInstanceId", "srv-test")
        .put("fromCursor", fromCursor)
        .put("toCursor", toCursor)
        .put("headSnapshotVersion", head)
        .put("hasMore", hasMore)
        .put("commits", commits)

    private fun installedState(version: Long = 5L, cursor: String? = "cursor-5"): SyncStateEntity =
        SyncStateEntity(
            deviceId = "dev-test",
            nextClientSequence = 6L,
            installedSnapshotVersion = version,
            installedSnapshotHash = hashOf(mirrorWithTask("旧标题")),
            serverInstanceId = "srv-test",
            serverMirrorJson = mirrorWithTask("旧标题").toString(),
            installedBrokerToSequence = version,
            cursor = cursor,
        )

    private fun installerWith(store: SyncV3Store) = SyncV4DeltaInstaller(store)

    @Test
    fun `installs a single commit atomically advancing mirror pointers and cursor`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val next = mirrorWithTask("新标题")
        val page = pageJson(
            "cursor-5",
            "cursor-6",
            JSONArray().put(
                commitJson(
                    6L,
                    5L,
                    9L,
                    JSONArray().put(changeJson("task.put", "t1", 9L, canonicalTask("新标题"))),
                    next,
                ),
            ),
            hasMore = false,
            head = 6L,
        )

        val result = installer.installPage(installer.parsePage(page))
        assertTrue(result.installed)
        assertEquals(6L, result.version)
        assertEquals(1, result.appliedCommits)

        val meta = store.state!!
        assertEquals(6L, meta.installedSnapshotVersion)
        assertEquals(hashOf(next), meta.installedSnapshotHash)
        assertEquals(9L, meta.installedBrokerToSequence)
        assertEquals("cursor-6", meta.cursor)
        assertEquals("新标题", JSONObject(meta.serverMirrorJson).getJSONObject("tasks").getJSONObject("t1").getString("title"))
    }

    @Test
    fun `installs multiple commits in strict order across one page`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val s6 = mirrorWithTask("a")
        val s7 = mirrorWithTask("b")
        val page = pageJson(
            "cursor-5",
            "cursor-7",
            JSONArray()
                .put(commitJson(6L, 5L, 10L, JSONArray().put(changeJson("task.put", "t1", 10L, canonicalTask("a"))), s6))
                .put(commitJson(7L, 6L, 11L, JSONArray().put(changeJson("task.put", "t1", 11L, canonicalTask("b"))), s7)),
            hasMore = false,
            head = 7L,
        )

        val result = installer.installPage(installer.parsePage(page))
        assertEquals(7L, result.version)
        assertEquals(2, result.appliedCommits)
        assertEquals("b", JSONObject(store.state!!.serverMirrorJson).getJSONObject("tasks").getJSONObject("t1").getString("title"))
        assertEquals("cursor-7", store.state!!.cursor)
    }

    @Test
    fun `empty commit advances version and cursor without touching the mirror`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)
        val before = store.state!!.serverMirrorJson

        val page = pageJson(
            "cursor-5",
            "cursor-6",
            JSONArray().put(commitJson(6L, 5L, 6L, JSONArray(), mirrorWithTask("旧标题"))),
            hasMore = false,
            head = 6L,
        )
        val result = installer.installPage(installer.parsePage(page))
        assertTrue(result.installed)
        assertEquals(before, store.state!!.serverMirrorJson)
        assertEquals(6L, store.state!!.installedSnapshotVersion)
        assertEquals("cursor-6", store.state!!.cursor)
    }

    @Test
    fun `unknown change type fails closed to snapshot fallback`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val page = pageJson(
            "cursor-5",
            "cursor-6",
            JSONArray().put(
                commitJson(
                    6L,
                    5L,
                    6L,
                    JSONArray().put(changeJson("task.title.patch", "t1", 6L, JSONObject().put("title", "x"))),
                    mirrorWithTask("旧标题"),
                ),
            ),
            hasMore = false,
            head = 6L,
        )
        try {
            installer.installPage(installer.parsePage(page))
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("UNKNOWN_DELTA_TYPE:task.title.patch", error.reason)
        }
        assertEquals("cursor-5", store.state!!.cursor)
        assertEquals(5L, store.state!!.installedSnapshotVersion)
    }

    @Test
    fun `parent version gap fails closed to snapshot fallback`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val page = pageJson(
            "cursor-5",
            "cursor-6",
            JSONArray().put(commitJson(6L, 4L, 6L, JSONArray(), mirrorWithTask("旧标题"))),
            hasMore = false,
            head = 6L,
        )
        try {
            installer.installPage(installer.parsePage(page))
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertTrue(error.reason.startsWith("DELTA_VERSION_GAP"))
        }
        assertEquals("cursor-5", store.state!!.cursor)
    }

    @Test
    fun `stateHashAfter mismatch fails closed without writing anything`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)
        val beforeMirror = store.state!!.serverMirrorJson

        val commit = commitJson(
            6L,
            5L,
            6L,
            JSONArray().put(changeJson("task.put", "t1", 6L, canonicalTask("新标题"))),
            mirrorWithTask("新标题"),
        ).put("stateHashAfter", "sha256:${"0".repeat(64)}")
        val page = pageJson("cursor-5", "cursor-6", JSONArray().put(commit), hasMore = false, head = 6L)

        try {
            installer.installPage(installer.parsePage(page))
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("DELTA_HASH_MISMATCH:6", error.reason)
        }
        assertEquals(beforeMirror, store.state!!.serverMirrorJson)
        assertEquals("cursor-5", store.state!!.cursor)
    }

    @Test
    fun `schema deviations fail closed with DELTA_SCHEMA_UNSUPPORTED`() {
        val installer = installerWith(FakeStore().apply { state = installedState() })
        val base = pageJson("cursor-5", "cursor-6", JSONArray(), hasMore = false, head = 6L)
        try {
            installer.parsePage(base.put("deltaVersion", 2))
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("DELTA_SCHEMA_UNSUPPORTED", error.reason)
        }
        try {
            installer.parsePage(base.put("deltaVersion", 1).put("serverInstanceId", ""))
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("DELTA_SCHEMA_UNSUPPORTED", error.reason)
        }
    }

    @Test
    fun `server instance mismatch forces re-bootstrap instead of fallback`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)
        val page = pageJson("cursor-5", "cursor-6", JSONArray(), hasMore = false, head = 6L)
            .put("serverInstanceId", "srv-other")
        try {
            installer.installPage(installer.parsePage(page))
            fail("expected SnapshotException")
        } catch (error: SyncV3SnapshotInstaller.SnapshotException) {
            assertEquals("ERROR008", error.code)
        }
    }

    @Test
    fun `re-delivering an already-installed commit is idempotent`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val next = mirrorWithTask("新标题")
        val commit = commitJson(
            6L,
            5L,
            6L,
            JSONArray().put(changeJson("task.put", "t1", 6L, canonicalTask("新标题"))),
            next,
        )
        val page = pageJson("cursor-5", "cursor-6", JSONArray().put(commit), hasMore = false, head = 6L)
        assertTrue(installer.installPage(installer.parsePage(page)).installed)

        // 服务器(异常地)带着新 cursor 又发了一遍同一 commit
        val stale = pageJson("cursor-6", "cursor-6", JSONArray().put(commit), hasMore = false, head = 6L)
        val second = installer.installPage(installer.parsePage(stale))
        assertFalse(second.installed)
        assertEquals(6L, second.version)
        assertEquals("cursor-6", store.state!!.cursor)
        assertEquals("新标题", JSONObject(store.state!!.serverMirrorJson).getJSONObject("tasks").getJSONObject("t1").getString("title"))
    }

    @Test
    fun `syncByCursor loops pages and returns applied totals`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)

        val s6 = mirrorWithTask("a")
        val s7 = mirrorWithTask("b")
        val pages = mapOf(
            "cursor-5" to pageJson(
                "cursor-5",
                "cursor-6",
                JSONArray().put(commitJson(6L, 5L, 6L, JSONArray().put(changeJson("task.put", "t1", 6L, canonicalTask("a"))), s6)),
                hasMore = true,
                head = 7L,
            ),
            "cursor-6" to pageJson(
                "cursor-6",
                "cursor-7",
                JSONArray().put(commitJson(7L, 6L, 7L, JSONArray().put(changeJson("task.put", "t1", 7L, canonicalTask("b"))), s7)),
                hasMore = false,
                head = 7L,
            ),
        )
        val http = FakeHttp(pages)
        val result = installer.syncByCursor(http, "https://sync.example")
        assertTrue(result.installed)
        assertEquals(7L, result.version)
        assertEquals(2, result.appliedCommits)
        assertEquals(listOf("cursor-5", "cursor-6"), http.requestedCursors)
        assertEquals("b", JSONObject(store.state!!.serverMirrorJson).getJSONObject("tasks").getJSONObject("t1").getString("title"))
    }

    @Test
    fun `a 410 response falls back with the server-provided reason`() {
        val store = FakeStore().apply { state = installedState() }
        val installer = installerWith(store)
        try {
            installer.syncByCursor(FakeHttp(emptyMap(), failCode = 410), "https://sync.example")
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("CURSOR_EXPIRED", error.reason)
        }
        assertEquals("cursor-5", store.state!!.cursor)
    }

    @Test
    fun `syncByCursor without a stored cursor is a fallback`() {
        val store = FakeStore().apply { state = installedState().copy(cursor = null) }
        val installer = installerWith(store)
        try {
            installer.syncByCursor(FakeHttp(emptyMap()), "https://sync.example")
            fail("expected DeltaFallbackException")
        } catch (error: DeltaFallbackException) {
            assertEquals("NO_CURSOR", error.reason)
        }
        assertNull(store.state!!.cursor)
    }
}
