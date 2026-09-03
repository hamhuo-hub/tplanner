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
        // 与 SyncV3CommandRepository 使用同一 deviceId,避免 identity rollover 重置状态。
        db.syncV3Dao().upsertSyncState(
            SyncStateEntity(
                deviceId = SyncV3DeviceIdentity.get(context),
                nextClientSequence = 2,
                installedSnapshotVersion = 1,
                installedSnapshotHash = null,
                serverInstanceId = "srv-test",
                serverMirrorJson = JSONObject()
                    .put("tasks", JSONObject())
                    .put("customLists", JSONObject())
                    .put("journals", JSONObject())
                    .put("goals", JSONObject())
                    .put("insights", JSONObject())
                    .toString(),
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

    private class BackgroundHttp(
        private val calls: MutableList<String>,
    ) : SyncHttpClient {
        override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse =
            SyncHttpResponse.text(400, "{}")

        override fun get(url: String, timeoutMs: Int): SyncHttpResponse {
            calls.add(url)
            return when {
                url.contains("/capabilities") -> SyncHttpResponse.text(
                    200,
                    JSONObject()
                        .put("softwareVersion", "8.0.0")
                        .put("protocolVersion", 3)
                        .put("schemaVersion", 3)
                        .put("serverInstanceId", "srv-test")
                        .put("downlinkModes", JSONArray().put("snapshot"))
                        .toString(),
                )
                url.contains("/receipts") -> SyncHttpResponse.text(
                    200,
                    JSONObject().put("acceptedThrough", 0).put("results", JSONArray()).toString(),
                )
                url.contains("/notifications") -> SyncHttpResponse.text(
                    200,
                    JSONObject().put("latestVersion", 1).put("stateHash", HASH_ZERO).toString(),
                )
                url.endsWith("/snapshots/latest") -> SyncHttpResponse.text(
                    200,
                    JSONObject()
                        .put("snapshotVersion", 1)
                        .put("parentVersion", 0)
                        .put("schemaVersion", 3)
                        .put("stateHash", HASH_ZERO)
                        .put("compressedHash", HASH_ZERO)
                        .put("encoding", "gzip")
                        .put("compressedBytes", 1)
                        .put("uncompressedBytes", 1)
                        .put("serverInstanceId", "srv-test")
                        .toString(),
                )
                else -> SyncHttpResponse.text(404, "{}")
            }
        }

        companion object {
            val HASH_ZERO = "sha256:${"0".repeat(64)}"
        }
    }

    private fun backgroundEngine(calls: MutableList<String>) = SyncV3Engine(
        context = ApplicationProvider.getApplicationContext(),
        database = db,
        http = BackgroundHttp(calls),
        networkGuard = {},
    )

    private fun uploadedCommand() = pendingCommand().copy(state = "uploaded")

    /** 越过一次性 cutover 屏障与 legacy import 屏障,让 runSync 直接进入上传/下行路径。 */
    private suspend fun markBootstrapped() {
        val dao = db.syncV3Dao()
        // LegacyPreferencesImporter 的完成标记(id 是其内部常量 "shared_prefs_v1")。
        db.migrationDao().insertMarker(
            com.hamhuo.tplanner.persistence.MigrationMarkerEntity(
                id = "shared_prefs_v1",
                completedAt = System.currentTimeMillis(),
                sourceDigest = "smoke",
                eventCount = 0,
                journalCount = 0,
                draftCount = 0,
            ),
        )
        dao.insertMigrationMarker(
            com.hamhuo.tplanner.persistence.MigrationMarkerEntity(
                id = SyncV3CommandRepository.BOOTSTRAP_MARKER,
                completedAt = System.currentTimeMillis(),
                sourceDigest = "smoke",
                eventCount = 0,
                journalCount = 0,
                draftCount = 0,
            ),
        )
    }

    @Test
    fun `syncBackgroundOnce never long-polls publication even with uploaded commands`() = runBlocking {
        db.syncV3Dao().upsertSyncState(
            db.syncV3Dao().getSyncState()!!.copy(installedSnapshotHash = BackgroundHttp.HASH_ZERO),
        )
        db.syncV3Dao().insertCommand(uploadedCommand())
        markBootstrapped()

        val calls = mutableListOf<String>()
        val result = backgroundEngine(calls).syncBackgroundOnce("https://sync.example")

        assertEquals(SyncV3Phase.UPLOADED, result.phase)
        assertTrue("background catch-up must pull receipts once", calls.any { it.contains("/receipts") })
        assertTrue("background catch-up must pull the snapshot once", calls.any { it.contains("/snapshots/latest") })
        assertTrue(
            "background catch-up must NEVER long-poll notifications",
            calls.none { it.contains("/notifications") },
        )
    }

    @Test
    fun `syncOnce may long-poll publication to reach convergence`() = runBlocking {
        db.syncV3Dao().upsertSyncState(
            db.syncV3Dao().getSyncState()!!.copy(installedSnapshotHash = BackgroundHttp.HASH_ZERO),
        )
        db.syncV3Dao().insertCommand(uploadedCommand())
        markBootstrapped()

        val calls = mutableListOf<String>()
        backgroundEngine(calls).syncOnce("https://sync.example")

        assertTrue(
            "explicit convergence sync is allowed to wait on the publication notification",
            calls.any { it.contains("/notifications") },
        )
    }
}
