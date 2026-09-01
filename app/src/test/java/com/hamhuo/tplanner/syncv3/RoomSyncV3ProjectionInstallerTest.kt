package com.hamhuo.tplanner.syncv3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hamhuo.tplanner.CheckItem
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.persistence.PersistenceMapper
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncV3ProjectionInstallerTest {
    private lateinit var db: TPlannerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TPlannerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `atomic install stores mirror and replays every pending command into phone tables`() {
        val dao = db.syncV3Dao()
        dao.upsertSyncState(syncState(version = 1, hash = hash('1')))
        dao.insertCommand(titleCommand(sequence = 1, title = "离线编辑", state = "uploaded"))
        dao.insertCommand(titleCommand(sequence = 10_001, title = "超过旧截断边界", state = "pending"))
        dao.insertReceipts(listOf(receipt(sequence = 1, status = "APPLIED", snapshotVersion = 2)))

        val remote = canonicalState("中央旧标题")
        val result = RoomSyncV3ProjectionInstaller(db).installAtomically(remote, manifest(2, hash('2')))

        assertEquals("超过旧截断边界", dao.eventRows().single().title)
        assertEquals("中央旧标题", JSONObject(dao.getSyncState()!!.serverMirrorJson!!)
            .getJSONObject("tasks").getJSONObject("task_1").getString("title"))
        assertEquals(2L, result.version)
        assertEquals(2L, dao.getSyncState()!!.installedSnapshotVersion)
        assertEquals("被快照证明的命令已原子清理", 1, dao.commandCount())
    }

    @Test
    fun `late terminal receipt removes rejected overlay and rebuilds displayed rows`() {
        val dao = db.syncV3Dao()
        val remote = canonicalState("中央权威标题")
        dao.upsertSyncState(
            syncState(version = 2, hash = hash('2')).copy(serverMirrorJson = remote.toString()),
        )
        dao.upsertEventRows(listOf(PersistenceMapper.eventToEntity(task("离线错误标题"), 0)))
        dao.insertCommand(titleCommand(sequence = 1, title = "离线错误标题", state = "uploaded"))
        dao.insertReceipts(listOf(receipt(sequence = 1, status = "REJECTED", snapshotVersion = null)))

        val result = RoomSyncV3ProjectionInstaller(db).reconcileInstalledState()

        assertEquals(1, result!!.removedCommands)
        assertEquals(0, dao.commandCount())
        assertEquals("中央权威标题", dao.eventRows().single().title)
        assertEquals("中央权威标题", result.authoritative.events.single().title)
    }

    @Test
    fun `failure after staging projection rolls back rows mirror and installed pointer`() {
        val dao = db.syncV3Dao()
        val original = task("事务前")
        dao.upsertEventRows(listOf(PersistenceMapper.eventToEntity(original, 0)))
        dao.upsertSyncState(syncState(version = 1, hash = hash('1')))
        val installer = RoomSyncV3ProjectionInstaller(db) { error("simulated process death") }

        runCatching {
            installer.installAtomically(canonicalState("不应提交"), manifest(2, hash('2')))
        }

        assertEquals("事务前", dao.eventRows().single().title)
        assertEquals(1L, dao.getSyncState()!!.installedSnapshotVersion)
        assertNull(dao.getSyncState()!!.serverMirrorJson)
    }

    private fun titleCommand(sequence: Long, title: String, state: String) = SyncCommandEntity(
        commandId = "0198f2a1-0000-7000-8000-${sequence.toString().padStart(12, '0')}",
        batchId = "0198f2a1-0000-7000-8000-${sequence.toString().padStart(12, '0')}",
        clientSequence = sequence,
        commandType = SyncCommandType.TASK_SET_TITLE.wire,
        aggregateId = "task_1",
        argumentsJson = JSONObject().put("title", title).toString(),
        state = state,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastErrorCode = null,
    )

    private fun receipt(sequence: Long, status: String, snapshotVersion: Long?) = SyncReceiptEntity(
        commandId = "0198f2a1-0000-7000-8000-${sequence.toString().padStart(12, '0')}",
        clientSequence = sequence,
        status = status,
        snapshotVersion = snapshotVersion,
        errorCode = if (status == "REJECTED") "COMMAND_REJECTED" else null,
        brokerSequence = sequence,
    )

    private fun syncState(version: Long, hash: String) = SyncStateEntity(
        deviceId = "android-test",
        nextClientSequence = 20_000,
        installedSnapshotVersion = version,
        installedSnapshotHash = hash,
        serverInstanceId = "server-test",
        installedBrokerToSequence = version,
    )

    private fun manifest(version: Long, stateHash: String) = SnapshotManifest(
        snapshotVersion = version,
        parentVersion = version - 1,
        schemaVersion = 3,
        stateHash = stateHash,
        compressedHash = hash('c'),
        encoding = "gzip",
        compressedBytes = 1,
        uncompressedBytes = 1,
        serverInstanceId = "server-test",
        brokerToSequence = version,
    )

    private fun hash(char: Char) = "sha256:${char.toString().repeat(64)}"

    private fun canonicalState(title: String): JSONObject = JSONObject()
        .put("tasks", JSONObject().put("task_1", canonicalTask(title)))
        .put("customLists", JSONObject())
        .put("journals", JSONObject())
        .put("goals", JSONObject())
        .put("insights", JSONObject())

    private fun canonicalTask(title: String): JSONObject = JSONObject()
        .put("title", title)
        .put("note", "")
        .put("completed", false)
        .put("itemType", "task")
        .put("schedule", JSONObject()
            .put("startAt", "2026-09-01T01:00:00.000Z")
            .put("endAt", "2026-09-01T02:00:00.000Z"))
        .put("recurrence", JSONObject.NULL)
        .put("alarm", JSONObject().put("enabled", false).put("offsetMinutes", 0))
        .put("colorId", 0)
        .put("location", JSONObject().put("lat", JSONObject.NULL).put("lng", JSONObject.NULL))
        .put("extras", JSONObject())
        .put("listId", JSONObject.NULL)
        .put("checklist", org.json.JSONArray())
        .put("lifecycle", "active")
        .put("deletedAt", JSONObject.NULL)

    private fun task(title: String) = ScheduleItem(
        id = "task_1",
        title = title,
        type = "task",
        start = Instant.parse("2026-09-01T01:00:00.000Z"),
        end = Instant.parse("2026-09-01T02:00:00.000Z"),
        completed = false,
        checklist = emptyList<CheckItem>(),
        colorId = 0,
        note = "",
        deletedAt = 0,
    )
}
