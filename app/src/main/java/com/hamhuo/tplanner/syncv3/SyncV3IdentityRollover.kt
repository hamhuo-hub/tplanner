package com.hamhuo.tplanner.syncv3

import org.json.JSONObject

/** One command retained from a restored database, plus its new transport identity. */
internal data class SyncV3RekeyedCommand(
    val previousCommandId: String,
    val command: SyncCommandEntity,
)

/**
 * Pure planner for moving a backed-up semantic outbox into a new device sequence namespace.
 *
 * Domain intent is immutable here: command type, aggregate and arguments are copied byte-for-byte.
 * Only transport identity and retry bookkeeping are replaced. Every persisted batch receives a
 * fresh JetStream idempotency key; otherwise a restore inside the broker duplicate window could
 * acknowledge an old batch without ever publishing the new device sequence.
 */
internal object SyncV3IdentityRollover {
    fun rewrite(
        commands: List<SyncCommandEntity>,
        commandId: () -> String,
        batchId: () -> String,
    ): List<SyncV3RekeyedCommand> {
        if (commands.isEmpty()) return emptyList()

        val ordered = commands.sortedBy(SyncCommandEntity::clientSequence)
        val usedCommandIds = ordered.mapTo(mutableSetOf(), SyncCommandEntity::commandId)
        val usedBatchIds = ordered.mapTo(mutableSetOf(), SyncCommandEntity::batchId)
        val result = mutableListOf<SyncV3RekeyedCommand>()
        var nextSequence = 1L

        contiguousBatches(ordered).forEach { oldBatch ->
            val freshBatchId = nextUnique("batchId", usedBatchIds, batchId)
            oldBatch.forEach { old ->
                val freshCommandId = nextUnique("commandId", usedCommandIds, commandId)
                result += SyncV3RekeyedCommand(
                    previousCommandId = old.commandId,
                    command = old.copy(
                        commandId = freshCommandId,
                        batchId = freshBatchId,
                        clientSequence = nextSequence++,
                        state = SyncV3CommandRepository.COMMAND_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L,
                        lastErrorCode = null,
                    ),
                )
            }
        }
        return result
    }

    private fun contiguousBatches(
        commands: List<SyncCommandEntity>,
    ): List<List<SyncCommandEntity>> {
        val groups = mutableListOf<MutableList<SyncCommandEntity>>()
        commands.forEach { command ->
            val current = groups.lastOrNull()
            if (current == null || current.last().batchId != command.batchId ||
                current.size >= SyncV3BatchPartitioner.MAX_BATCH_COMMANDS
            ) {
                groups += mutableListOf(command)
            } else {
                current += command
            }
        }
        return groups
    }

    private fun nextUnique(
        label: String,
        used: MutableSet<String>,
        generate: () -> String,
    ): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = generate()
            if (candidate.isNotBlank() && used.add(candidate)) return candidate
        }
        error("Unable to generate a fresh Sync V3 $label")
    }

    private const val MAX_ID_ATTEMPTS = 32
}

/** Durable old-id lookup used by a Watch retry after the phone database was restored. */
internal object SyncV3CommandAliases {
    const val MARKER_PREFIX = "sync-v3-command-alias:"

    fun markerId(commandId: String): String = "$MARKER_PREFIX$commandId"

    fun resolve(
        commandId: String,
        lookup: (String) -> String?,
    ): String {
        var current = commandId
        val visited = mutableSetOf<String>()
        repeat(MAX_ALIAS_DEPTH) {
            if (!visited.add(current)) error("Sync V3 command alias cycle at $current")
            val next = lookup(current)?.takeIf(String::isNotBlank) ?: return current
            current = next
        }
        error("Sync V3 command alias chain is too deep")
    }

    private const val MAX_ALIAS_DEPTH = 64
}

/**
 * Moves old-device receipts out of the active receipt cursor without discarding their evidence.
 * They are needed only for a late Watch retry; the new phone uploader must never use their old
 * clientSequence as its `afterClientSequence` cursor.
 */
internal object SyncV3ArchivedReceipts {
    const val MARKER_PREFIX = "sync-v3-archived-receipt:"

    fun markerId(commandId: String): String = "$MARKER_PREFIX$commandId"

    fun encode(receipt: SyncReceiptEntity): String = JSONObject().apply {
        put("commandId", receipt.commandId)
        put("clientSequence", receipt.clientSequence)
        put("status", receipt.status)
        put("snapshotVersion", receipt.snapshotVersion ?: JSONObject.NULL)
        put("errorCode", receipt.errorCode ?: JSONObject.NULL)
        put("brokerSequence", receipt.brokerSequence ?: JSONObject.NULL)
    }.toString()

    fun decode(value: String): SyncReceiptEntity = JSONObject(value).let { json ->
        SyncReceiptEntity(
            commandId = json.getString("commandId"),
            clientSequence = json.getLong("clientSequence"),
            status = json.getString("status"),
            snapshotVersion = json.nullableLong("snapshotVersion"),
            errorCode = json.optString("errorCode").takeIf { it.isNotEmpty() && it != "null" },
            brokerSequence = json.nullableLong("brokerSequence"),
        )
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)
}
