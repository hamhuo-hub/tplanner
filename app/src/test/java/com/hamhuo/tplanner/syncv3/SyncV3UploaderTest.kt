package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SyncV3UploaderTest {

    private class FakeStore(initialState: SyncStateEntity) : SyncV3Store {
        var state = initialState
        val commands = mutableListOf<SyncCommandEntity>()
        val receipts = mutableListOf<SyncReceiptEntity>()

        override fun getSyncState(): SyncStateEntity? = state
        override fun upsertSyncState(newState: SyncStateEntity) { state = newState }
        override fun listCommands(filterState: String, limit: Int): List<SyncCommandEntity> =
            commands.filter { it.state == filterState }.sortedBy { it.clientSequence }.take(limit)
        override fun markUploaded(sequences: List<Long>) {
            commands.replaceAll { c ->
                if (c.clientSequence in sequences) c.copy(state = "uploaded") else c
            }
        }
        override fun deleteThroughSequence(through: Long) {
            commands.removeAll { it.clientSequence <= through }
        }
        override fun insertReceipts(newReceipts: List<SyncReceiptEntity>) {
            receipts.addAll(newReceipts)
        }
        override fun acceptedThrough(): Long? = receipts.maxOfOrNull { it.clientSequence }
    }

    private class FakeHttp : SyncHttpClient {
        val posts = mutableListOf<Triple<String, String, String>>()
        var postResponse: (String) -> SyncHttpResponse = {
            SyncHttpResponse.text(202, """{"batchId":"b1","brokerSequence":1,"state":"BROKER_PERSISTED"}""")
        }
        var receiptsResponse: () -> SyncHttpResponse = {
            SyncHttpResponse.text(
                200,
                """{"acceptedThrough":0,"results":[]}""",
            )
        }

        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse {
            posts.add(Triple(url, body, idempotencyKey))
            return postResponse(body)
        }

        override fun get(url: String, timeoutMs: Int): SyncHttpResponse = receiptsResponse()
    }

    private fun command(seq: Long, type: String = "task.create", aggregateId: String = "t$seq"): SyncCommandEntity =
        SyncCommandEntity(
            commandId = "c$seq",
            batchId = "",
            clientSequence = seq,
            commandType = type,
            aggregateId = aggregateId,
            argumentsJson = """{"title":"t$seq"}""",
            state = "pending",
            attemptCount = 0,
            nextAttemptAt = 0,
            lastErrorCode = null,
        )

    private fun state() = SyncStateEntity(
        singletonId = 1,
        deviceId = "phone-test-dev",
        nextClientSequence = 1,
        installedSnapshotVersion = 0,
        installedSnapshotHash = null,
        serverInstanceId = null,
    )

    @Test
    fun `pump posts one batch with Idempotency-Key and marks uploaded`() {
        val store = FakeStore(state())
        store.commands += command(1)
        store.commands += command(2)
        val http = FakeHttp()
        val uploader = SyncV3Uploader(store, http, "https://sync.example")

        val uploaded = uploader.pump()

        assertEquals(2, uploaded)
        assertEquals(1, http.posts.size)
        val (url, body, key) = http.posts[0]
        assertEquals("https://sync.example/tplanner/v3/command-batches", url)

        val wire = JSONObject(body)
        assertEquals(3, wire.getInt("protocolVersion"))
        assertEquals("phone-test-dev", wire.getString("deviceId"))
        assertEquals(1L, wire.getLong("firstClientSequence"))
        assertEquals(2L, wire.getLong("lastClientSequence"))
        val commands = wire.getJSONArray("commands")
        assertEquals(2, commands.length())
        assertEquals("task.create", commands.getJSONObject(0).getString("type"))
        assertEquals("t1", commands.getJSONObject(0).getString("aggregateId"))
        assertEquals("""{"title":"t1"}""", commands.getJSONObject(0).getJSONObject("arguments").toString())

        assertEquals("Idempotency-Key 必须等于 batchId", key, wire.getString("batchId"))
        assertTrue(key.split("-")[2].startsWith("7"))
        assertEquals(0, store.listCommands("pending", 10).size)
        assertEquals(2, store.listCommands("uploaded", 10).size)
    }

    @Test
    fun `broker rejection throws and keeps outbox intact`() {
        val store = FakeStore(state())
        store.commands += command(1)
        val http = FakeHttp()
        http.postResponse = { SyncHttpResponse.text(503, """{"error":"BROKER_UNAVAILABLE"}""") }
        val uploader = SyncV3Uploader(store, http, "https://sync.example")

        val e = assertThrows(SyncV3Uploader.SyncException::class.java) { uploader.pump() }
        assertEquals(503, e.status)
        assertEquals("BROKER_UNAVAILABLE", e.errorCode)
        assertEquals(1, store.listCommands("pending", 10).size)
    }

    @Test
    fun `collectReceipts removes confirmed commands and persists receipts`() {
        val store = FakeStore(state())
        store.commands += command(1).copy(state = "uploaded")
        store.commands += command(2).copy(state = "uploaded")
        val http = FakeHttp()
        http.receiptsResponse = {
            SyncHttpResponse.text(
                200,
                """{"acceptedThrough":2,"results":[
                    {"commandId":"c1","clientSequence":1,"status":"APPLIED","snapshotVersion":9},
                    {"commandId":"c2","clientSequence":2,"status":"ENTITY_DELETED","errorCode":"ENTITY_DELETED"}
                ]}""",
            )
        }
        val uploader = SyncV3Uploader(store, http, "https://sync.example")

        uploader.collectReceipts()

        assertEquals("回执确认后 outbox 清空", 0L, store.commands.size.toLong())
        assertEquals(2, store.receipts.size)
        assertEquals("APPLIED", store.receipts[0].status)
        assertEquals(9L, store.receipts[0].snapshotVersion)
        assertEquals("ENTITY_DELETED", store.receipts[1].status)
    }

    @Test
    fun `flush drains multiple batches until empty`() {
        val store = FakeStore(state())
        store.commands += command(1)
        store.commands += command(2)
        store.commands += command(3)
        val http = FakeHttp()
        val uploader = SyncV3Uploader(store, http, "https://sync.example")

        uploader.flush()

        assertEquals("3 条命令在一个批次内(≤100)发完", 1, http.posts.size)
        assertEquals("回执确认前已全部标记 uploaded", 3, store.listCommands("uploaded", 10).size)
    }
}
