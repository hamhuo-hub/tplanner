package com.hamhuo.tplanner.syncv3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hamhuo.tplanner.persistence.MigrationMarkerEntity
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncV3IdentityRestoreRoomTest {
    private lateinit var context: Context
    private lateinit var db: TPlannerDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TPlannerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `new no-backup identity atomically rekeys restored offline commands`() {
        val dao = db.syncV3Dao()
        val delete = command(
            id = "0198f2a1-0000-7000-8000-000000000041",
            batch = "0198f2a1-0000-7000-8000-000000000040",
            sequence = 41L,
            type = SyncCommandType.TASK_DELETE.wire,
            arguments = "{}",
            state = "pending",
        )
        val edit = command(
            id = "0198f2a1-0000-7000-8000-000000000042",
            batch = delete.batchId,
            sequence = 42L,
            type = SyncCommandType.TASK_SET_NOTE.wire,
            arguments = "{\"note\":\"offline\"}",
            state = "uploaded",
        )
        dao.upsertSyncState(
            SyncStateEntity(
                deviceId = "restored-device-that-must-not-be-reused",
                nextClientSequence = 43L,
                installedSnapshotVersion = 88L,
                installedSnapshotHash = "sha256:old",
                serverInstanceId = "old-server",
                serverMirrorJson = "{\"old\":true}",
                installedBrokerToSequence = 900L,
            )
        )
        dao.insertCommand(delete)
        dao.insertCommand(edit)
        dao.insertReceipts(
            listOf(
                SyncReceiptEntity(
                    commandId = edit.commandId,
                    clientSequence = edit.clientSequence,
                    status = "APPLIED",
                    snapshotVersion = 89L,
                    errorCode = null,
                    brokerSequence = 901L,
                )
            )
        )
        dao.insertMigrationMarker(marker(SyncV3CommandRepository.BOOTSTRAP_MARKER, "old"))

        val needsBootstrap = SyncV3CommandRepository(context, db).needsBootstrap()

        assertTrue(needsBootstrap)
        val state = dao.getSyncState()!!
        assertNotEquals("restored-device-that-must-not-be-reused", state.deviceId)
        assertEquals(3L, state.nextClientSequence)
        assertEquals(0L, state.installedSnapshotVersion)
        assertEquals("old-server", state.serverInstanceId)
        assertNull(state.serverMirrorJson)
        assertEquals(0L, state.installedBrokerToSequence)
        assertEquals(0, dao.receipts(listOf(edit.commandId)).size)
        assertEquals(
            edit.commandId,
            SyncV3ArchivedReceipts.decode(
                dao.migrationMarker(SyncV3ArchivedReceipts.markerId(edit.commandId))!!.sourceDigest,
            ).commandId,
        )

        val retained = dao.listAllCommands()
        assertEquals(2, retained.size)
        assertEquals(listOf(1L, 2L), retained.map(SyncCommandEntity::clientSequence))
        assertEquals(
            listOf(SyncCommandType.TASK_DELETE.wire, SyncCommandType.TASK_SET_NOTE.wire),
            retained.map(SyncCommandEntity::commandType),
        )
        assertEquals(listOf("{}", "{\"note\":\"offline\"}"), retained.map { it.argumentsJson })
        assertTrue(retained.all { it.state == SyncV3CommandRepository.COMMAND_PENDING })
        assertTrue(retained.none { it.commandId in setOf(delete.commandId, edit.commandId) })
        assertTrue(retained.none { it.batchId == delete.batchId })
        assertEquals(
            retained[0].commandId,
            dao.migrationMarker(SyncV3CommandAliases.markerId(delete.commandId))?.sourceDigest,
        )
        assertEquals(
            retained[1].commandId,
            dao.migrationMarker(SyncV3CommandAliases.markerId(edit.commandId))?.sourceDigest,
        )

        assertTrue(SyncV3CommandRepository(context, db).prepareForServerInstance("new-server"))
        assertNull(dao.getSyncState()!!.serverInstanceId)
        assertNull(dao.migrationMarker(SyncV3ArchivedReceipts.markerId(edit.commandId)))
        assertEquals(2, dao.commandCount())
    }

    private fun command(
        id: String,
        batch: String,
        sequence: Long,
        type: String,
        arguments: String,
        state: String,
    ) = SyncCommandEntity(
        commandId = id,
        batchId = batch,
        clientSequence = sequence,
        commandType = type,
        aggregateId = "task-1",
        argumentsJson = arguments,
        state = state,
        attemptCount = 2,
        nextAttemptAt = 123L,
        lastErrorCode = "ERROR002",
    )

    private fun marker(id: String, digest: String) = MigrationMarkerEntity(
        id = id,
        completedAt = 1L,
        sourceDigest = digest,
        eventCount = 0,
        journalCount = 0,
        draftCount = 0,
    )
}
