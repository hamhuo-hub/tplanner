package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV3IdentityRolloverTest {
    @Test
    fun `restore preserves offline semantic intent while replacing all transport identity`() {
        val restored = listOf(
            command(
                id = "old-delete",
                batch = "old-batch-a",
                sequence = 41L,
                type = "task.delete",
                aggregateId = "offline-deleted-task",
                arguments = "{}",
                state = "pending",
                attemptCount = 3,
                nextAttemptAt = 99L,
                error = "ERROR002",
            ),
            command(
                id = "old-edit",
                batch = "old-batch-a",
                sequence = 42L,
                type = "task.setNote",
                aggregateId = "offline-edited-task",
                arguments = "{\"note\":\"must survive restore\"}",
                state = "uploaded",
                attemptCount = 1,
                nextAttemptAt = 50L,
                error = "stale",
            ),
        )
        val commandIds = ArrayDeque(listOf("new-delete", "new-edit"))
        val batchIds = ArrayDeque(listOf("new-batch"))

        val rewritten = SyncV3IdentityRollover.rewrite(
            restored,
            commandId = commandIds::removeFirst,
            batchId = batchIds::removeFirst,
        )

        assertEquals(listOf("old-delete", "old-edit"), rewritten.map { it.previousCommandId })
        assertEquals(listOf("new-delete", "new-edit"), rewritten.map { it.command.commandId })
        assertEquals(listOf(1L, 2L), rewritten.map { it.command.clientSequence })
        assertEquals(setOf("new-batch"), rewritten.map { it.command.batchId }.toSet())
        rewritten.zip(restored).forEach { (replacement, old) ->
            val current = replacement.command
            assertNotEquals(old.commandId, current.commandId)
            assertNotEquals(old.batchId, current.batchId)
            assertEquals(old.commandType, current.commandType)
            assertEquals(old.aggregateId, current.aggregateId)
            assertEquals(old.argumentsJson, current.argumentsJson)
            assertEquals(SyncV3CommandRepository.COMMAND_PENDING, current.state)
            assertEquals(0, current.attemptCount)
            assertEquals(0L, current.nextAttemptAt)
            assertNull(current.lastErrorCode)
        }
    }

    @Test
    fun `restore retains batch boundaries and splits oversized legacy batches`() {
        val restored = (1L..102L).map { sequence ->
            command(
                id = "old-$sequence",
                batch = if (sequence <= 101L) "large" else "second",
                sequence = sequence,
            )
        }
        var commandCounter = 0
        var batchCounter = 0

        val rewritten = SyncV3IdentityRollover.rewrite(
            restored,
            commandId = { "new-command-${++commandCounter}" },
            batchId = { "new-batch-${++batchCounter}" },
        ).map(SyncV3RekeyedCommand::command)

        assertEquals((1L..102L).toList(), rewritten.map(SyncCommandEntity::clientSequence))
        assertEquals(3, rewritten.map(SyncCommandEntity::batchId).distinct().size)
        assertEquals(100, rewritten.takeWhile { it.batchId == "new-batch-1" }.size)
        assertEquals("new-batch-2", rewritten[100].batchId)
        assertEquals("new-batch-3", rewritten[101].batchId)
        assertTrue(rewritten.none { it.commandId.startsWith("old-") })
    }

    @Test
    fun `watch command alias follows multiple device restores`() {
        val aliases = mapOf(
            "watch-original" to "phone-restore-one",
            "phone-restore-one" to "phone-restore-two",
        )

        assertEquals(
            "phone-restore-two",
            SyncV3CommandAliases.resolve("watch-original", aliases::get),
        )
        assertEquals("unrelated", SyncV3CommandAliases.resolve("unrelated", aliases::get))
    }

    @Test
    fun `old receipt can leave active sequence cursor without losing watch evidence`() {
        val receipt = SyncReceiptEntity(
            commandId = "watch-command",
            clientSequence = 91L,
            status = "APPLIED",
            snapshotVersion = 12L,
            errorCode = null,
            brokerSequence = 300L,
        )

        assertEquals(receipt, SyncV3ArchivedReceipts.decode(SyncV3ArchivedReceipts.encode(receipt)))
    }

    private fun command(
        id: String,
        batch: String,
        sequence: Long,
        type: String = "task.setTitle",
        aggregateId: String = "task-$sequence",
        arguments: String = "{\"title\":\"$sequence\"}",
        state: String = "pending",
        attemptCount: Int = 0,
        nextAttemptAt: Long = 0L,
        error: String? = null,
    ) = SyncCommandEntity(
        commandId = id,
        batchId = batch,
        clientSequence = sequence,
        commandType = type,
        aggregateId = aggregateId,
        argumentsJson = arguments,
        state = state,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastErrorCode = error,
    )
}
