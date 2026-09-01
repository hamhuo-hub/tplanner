package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV3NotificationClientTest {

    private class FakeStore(var installed: Long) : SyncV3Store {
        override fun getSyncState(): SyncStateEntity? =
            SyncStateEntity(1, "phone-test", 1, installed, null, null)
        override fun upsertSyncState(s: SyncStateEntity) {}
        override fun listCommands(state: String, limit: Int): List<SyncCommandEntity> = emptyList()
        override fun markUploaded(sequences: List<Long>) {}
        override fun insertReceipts(r: List<SyncReceiptEntity>) {}
        override fun acceptedThrough(): Long? = null
    }

    private class FakeHttp(var response: SyncHttpResponse = SyncHttpResponse.text(200, """{"latestVersion":0}""")) :
        SyncHttpClient {
        val gets = mutableListOf<String>()
        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse =
            SyncHttpResponse.text(400, "{}")
        override fun get(url: String, timeoutMs: Int): SyncHttpResponse {
            gets.add(url)
            return response
        }
    }

    @Test
    fun `polls with afterVersion and detects a new version`() {
        val http = FakeHttp(SyncHttpResponse.text(200, """{"latestVersion":8,"stateHash":"sha256:x"}"""))
        val client = SyncV3NotificationClient(FakeStore(7), http, "https://sync.example", waitMs = 5000)

        val result = client.pollOnce()

        assertTrue(result.hasNewVersion)
        assertEquals(8L, result.latestVersion)
        val url = http.gets[0]
        assertTrue(url.contains("/tplanner/v3/notifications"))
        assertTrue(url.contains("afterVersion=7"))
        assertTrue(url.contains("wait=5000"))
    }

    @Test
    fun `no notification when server is on the same version`() {
        val http = FakeHttp(SyncHttpResponse.text(200, """{"latestVersion":7}"""))
        val client = SyncV3NotificationClient(FakeStore(7), http, "https://sync.example")

        val result = client.pollOnce()

        assertFalse(result.hasNewVersion)
    }

    @Test
    fun `non-2xx surfaces as a sync exception`() {
        val http = FakeHttp(SyncHttpResponse.text(503, """{"error":"BROKER_UNAVAILABLE"}"""))
        val client = SyncV3NotificationClient(FakeStore(7), http, "https://sync.example")

        val e = runCatching { client.pollOnce() }.exceptionOrNull() as? SyncV3Uploader.SyncException
        assertEquals(503, e?.status)
    }
}
