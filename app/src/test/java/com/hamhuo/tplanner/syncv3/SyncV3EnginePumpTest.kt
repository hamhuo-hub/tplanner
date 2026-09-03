package com.hamhuo.tplanner.syncv3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncV3EnginePumpTest {
    private lateinit var db: TPlannerDatabase
    private val calls = mutableListOf<String>()

    private class FakeHttp(
        private val calls: MutableList<String>,
        private val failBatches: Boolean = false,
    ) : SyncHttpClient {
        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse {
            calls.add(url)
            if (url.contains("/command-batches")) {
                if (failBatches) return SyncHttpResponse.text(503, "{}")
                val batchId = JSONObject(body).optString("batchId")
                return SyncHttpResponse.text(
                    202,
                    JSONObject()
                        .put("batchId", batchId)
                        .put("state", "BROKER_PERSISTED")
                        .put("brokerSequence", 7)
                        .toString(),
                )
            }
            return SyncHttpResponse.text(400, "{}")
        }

        override fun get(url: String, timeoutMs: Int): SyncHttpResponse {
            calls.add(url)
            if (url.contains("/capabilities")) {
                return SyncHttpResponse.text(
                    200,
                    JSONObject()
                        .put("softwareVersion", "8.0.0")
                        .put("protocolVersion", 3)
                        .put("schemaVersion", 3)
                        .put("serverInstanceId", "srv-test")
                        .put("downlinkModes", JSONArray().put("snapshot"))
                        .toString(),
                )
            }
            return SyncHttpResponse.text(404, "{}")
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TPlannerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.syncV3Dao().upsertSyncState(
            SyncStateEntity(
                deviceId = "android-test",
                nextClientSequence = 2,
                installedSnapshotVersion = 1,
                installedSnapshotHash = null,
                serverInstanceId = "srv-test",
                installedBrokerToSequence = 1,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun engine(failBatches: Boolean = false) = SyncV3Engine(
        context = ApplicationProvider.getApplicationContext(),
        database = db,
        http = FakeHttp(calls, failBatches),
        networkGuard = {},
    )

    private fun pendingCommand() = SyncCommandEntity(
        commandId = "0198f2a1-0000-7000-8000-000000000001",
        batchId = "0198f2a1-0000-7000-8000-000000000001",
        clientSequence = 1,
        commandType = "task.create",
        aggregateId = "t1",
        argumentsJson = """{"title":"a"}""",
        state = "pending",
        attemptCount = 0,
        nextAttemptAt = 0,
        lastErrorCode = null,
    )

    @Test
    fun `pumpToBroker uploads pending commands and stops at BROKER_PERSISTED`() = runBlocking {
        db.syncV3Dao().insertCommand(pendingCommand())
        val result = engine().pumpToBroker("https://sync.example")

        assertEquals(SyncV3Phase.UPLOADED, result.phase)
        assertEquals(0, result.pendingCommands)
        assertEquals(1, result.uploadedCommands)
        assertEquals("uploaded", db.syncV3Dao().listCommands("uploaded", 10).single().state)
        assertTrue("interactive pump must send the batch", calls.any { it.contains("/command-batches") })
        assertTrue(
            "interactive pump must not wait for receipts",
            calls.none { it.contains("/receipts") },
        )

        // 同步日志异步落库:poll 到出现 pump 行。
        val deadline = System.currentTimeMillis() + 5_000
        while (db.syncV3Dao().logCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val logs = db.syncV3Dao().recentLogs(20)
        assertTrue(
            "pump must record a BROKER_PERSISTED log entry",
            logs.any { it.source == "pump" && it.message == "BROKER_PERSISTED" },
        )
    }

    @Test
    fun `a transport failure keeps the command pending and surfaces ERROR003`() = runBlocking {
        db.syncV3Dao().insertCommand(pendingCommand())
        try {
            engine(failBatches = true).pumpToBroker("https://sync.example")
            fail("expected SyncV3RunException")
        } catch (error: SyncV3RunException) {
            assertEquals("ERROR003", error.errorCode)
        }
        assertEquals("pending", db.syncV3Dao().listCommands("pending", 10).single().state)
    }
}
