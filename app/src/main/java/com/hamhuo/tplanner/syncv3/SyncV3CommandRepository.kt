package com.hamhuo.tplanner.syncv3

import android.content.Context
import androidx.room.withTransaction
import com.hamhuo.tplanner.JournalEntry
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.persistence.MigrationMarkerEntity
import com.hamhuo.tplanner.persistence.PersistenceMapper
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class V3BootstrapResult(
    val performed: Boolean,
    val commandCount: Int,
)

data class ExternalSyncCommand(
    val commandId: String,
    val type: SyncCommandType,
    val aggregateId: String?,
    val arguments: JSONObject,
)

internal data class SyncV3PersistChunk<T>(
    val batchId: String,
    val commands: List<T>,
)

/** Pure persistence partitioning: every distinct POST payload owns one durable batch id. */
internal object SyncV3BatchPartitioner {
    const val MAX_BATCH_COMMANDS = 100

    fun <T> partition(
        commands: List<T>,
        preferredFirstBatchId: String? = null,
        batchId: () -> String,
    ): List<SyncV3PersistChunk<T>> = commands.chunked(MAX_BATCH_COMMANDS).mapIndexed { index, chunk ->
        SyncV3PersistChunk(
            batchId = preferredFirstBatchId?.takeIf { index == 0 } ?: batchId(),
            commands = chunk,
        )
    }
}

/**
 * The only Android uplink writer. Domain facts and their commands are committed in the same Room
 * transaction by the calling repositories; bootstrap itself owns one transaction.
 */
class SyncV3CommandRepository(
    context: Context,
    private val db: TPlannerDatabase,
) {
    private val deviceId = SyncV3DeviceIdentity.get(context.applicationContext)
    private val dao = db.syncV3Dao()

    fun enqueueTaskChange(before: ScheduleItem?, after: ScheduleItem) {
        enqueue(SyncV3CommandPlanner.taskChange(before, after))
    }

    fun enqueueJournal(date: String, entry: JournalEntry) {
        enqueue(
            listOf(
                if (entry.deletedAt != 0L) SyncV3CommandPlanner.journalDelete(date)
                else SyncV3CommandPlanner.journalSetText(date, entry.text),
            )
        )
    }

    fun enqueueListCreate(id: String, title: String) {
        enqueue(listOf(SyncV3CommandPlanner.listCreate(id, title)))
    }

    fun enqueueListRename(id: String, title: String) {
        enqueue(listOf(SyncV3CommandPlanner.listRename(id, title)))
    }

    fun enqueueListDelete(id: String) {
        enqueue(listOf(SyncV3CommandPlanner.listDelete(id)))
    }

    /** Entry point for the phone-side Watch bridge; duplicate commandId is a durable NOOP. */
    suspend fun enqueueExternal(
        commandId: String,
        type: SyncCommandType,
        aggregateId: String?,
        arguments: JSONObject,
    ): Boolean = enqueueExternalBatch(
        envelopeId = commandId,
        commands = listOf(ExternalSyncCommand(commandId, type, aggregateId, arguments)),
    )

    /** Atomically persists a complete Watch envelope or recognizes its complete retry. */
    suspend fun enqueueExternalBatch(
        envelopeId: String,
        commands: List<ExternalSyncCommand>,
    ): Boolean = db.withTransaction {
        require(envelopeId.isNotBlank()) { "Watch envelope id is required" }
        require(commands.isNotEmpty()) { "Watch envelope must contain semantic commands" }
        require(commands.map(ExternalSyncCommand::commandId).distinct().size == commands.size) {
            "Watch envelope contains duplicate command ids"
        }
        ensureIdentity()
        // A final receipt outlives its outbox row. Checking both tables is essential: a watch
        // may retry an old envelope after the phone has already collected the central receipt.
        // Re-enqueuing that commandId with a new clientSequence would otherwise create a permanent
        // sequence gap even though server-side command idempotency correctly returns the old row.
        val knownIds = commands.mapNotNull { command ->
            command.commandId.takeIf(::isKnownCommandIdentity)
        }.toSet()
        if (knownIds.size == commands.size) return@withTransaction false
        check(knownIds.isEmpty()) { "Incomplete Watch envelope was found in the V3 ledger" }
        val sequences = dao.allocateClientSequence(commands.size)
        SyncV3BatchPartitioner.partition(
            commands = commands.zip(sequences),
            preferredFirstBatchId = envelopeId.takeIf(UUID_V7::matches),
            batchId = SyncV3Uploader::uuidV7Default,
        ).forEach { chunk ->
            chunk.commands.forEach { (command, sequence) ->
                dao.insertCommand(
                    SyncCommandEntity(
                        commandId = command.commandId,
                        batchId = chunk.batchId,
                        clientSequence = sequence,
                        commandType = command.type.wire,
                        aggregateId = command.aggregateId,
                        argumentsJson = command.arguments.toString(),
                        state = COMMAND_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L,
                        lastErrorCode = null,
                    )
                )
            }
        }
        dao.updateSyncStatus(PHASE_SAVED, null, System.currentTimeMillis())
        true
    }

    /**
     * One-time cutover barrier. It is illegal to install a remote snapshot before this returns.
     * The marker, semantic repair commands, and retirement of V1 outbox/shadows commit together.
     */
    fun needsBootstrap(): Boolean = db.runInTransaction<Boolean> {
        ensureIdentity()
        dao.migrationMarker(BOOTSTRAP_MARKER) == null
    }

    /**
     * Capabilities is checked before any upload. A different serverInstanceId denotes a separate
     * authority universe even when the user reused the same URL. Retain the user's semantic
     * outbox, but discard receipts, mirrors and sequence/projection watermarks that only prove
     * facts about the previous universe; bootstrap will atomically re-sequence the retained
     * commands behind its central-aware repair commands.
     */
    fun prepareForServerInstance(serverInstanceId: String): Boolean =
        db.runInTransaction<Boolean> {
            require(serverInstanceId.isNotBlank()) { "serverInstanceId is required" }
            ensureIdentity()
            val current = requireNotNull(dao.getSyncState())
            val previous = current.serverInstanceId
            if (previous == null || previous == serverInstanceId) {
                return@runInTransaction false
            }

            dao.resetAllCommandsPending()
            dao.deleteAllReceipts()
            // Archived receipts prove publication only in their original authority universe.
            // Command aliases remain valid because retained outbox rows keep their re-keyed ids.
            dao.deleteMigrationMarkersByPrefix(SyncV3ArchivedReceipts.MARKER_PREFIX)
            dao.deleteMigrationMarker(BOOTSTRAP_MARKER)
            dao.upsertSyncState(
                current.copy(
                    nextClientSequence = 1L,
                    installedSnapshotVersion = 0L,
                    installedSnapshotHash = null,
                    serverInstanceId = null,
                    serverMirrorJson = null,
                    watchProjectionSnapshotVersion = 0L,
                    syncPhase = PHASE_SAVED,
                    syncErrorCode = null,
                    syncUpdatedAt = System.currentTimeMillis(),
                    installedBrokerToSequence = 0L,
                    watchProjectionBrokerToSequence = 0L,
                )
            )
            true
        }

    fun bootstrapIfNeeded(authoritative: JSONObject? = null): V3BootstrapResult =
        db.runInTransaction<V3BootstrapResult> {
        ensureIdentity()
        if (dao.migrationMarker(BOOTSTRAP_MARKER) != null) {
            return@runInTransaction V3BootstrapResult(false, 0)
        }

        val lists = dao.userListRows()
        val events = dao.eventRows().map(PersistenceMapper::eventToDomain)
        val journals = dao.journalRows().associate { row ->
            row.date to PersistenceMapper.journalToDomain(row)
        }
        val stagedV3Commands = dao.listAllCommands()
        check(stagedV3Commands.none { it.state == "uploaded" }) {
            "Bootstrap marker is missing after transport acceptance"
        }
        val cutover = SyncV3CutoverIntents.read(db, authoritative)
        val remoteLists = authoritative?.optJSONObject("customLists")
        val remoteTasks = authoritative?.optJSONObject("tasks")
        val remoteJournals = authoritative?.optJSONObject("journals")
        val activeListIds = buildSet {
            remoteLists?.keys()?.forEach { id ->
                if (remoteLists.optJSONObject(id)?.optString("lifecycle", "active") == "active") add(id)
            }
            lists.forEach { list ->
                if (remoteLists?.optJSONObject(list.id) == null) add(list.id)
            }
        }
        val commands = buildList {
            lists.forEach { list ->
                // list.create is first-writer-wins. Never rename an existing central list from a
                // potentially stale phone bootstrap.
                if (remoteLists?.optJSONObject(list.id) == null) {
                    add(SyncV3CommandPlanner.listCreate(list.id, list.name))
                }
            }
            events.filter { it.id !in cutover.eventIds }.forEach { event ->
                addAll(
                    SyncV3CommandPlanner.bootstrapTaskRepair(
                        event = event,
                        authoritative = remoteTasks?.optJSONObject(event.id),
                        canAssignLocalList = event.listId.isNotEmpty() && event.listId in activeListIds,
                    )
                )
            }
            journals.filterKeys { it !in cutover.journalIds }.forEach { (date, entry) ->
                if (entry.deletedAt == 0L && remoteJournals?.optJSONObject(date) == null) {
                    add(SyncV3CommandPlanner.journalSetText(date, entry.text, ifMissing = true))
                }
            }
            addAll(cutover.commands)
        }

        // UI and Watch writes can happen before the first network run. They are durable already,
        // but repair/create commands must precede them in the device sequence. Re-sequence the
        // existing intent after cutover commands without changing its command identity.
        dao.deleteAllCommands()
        val currentState = requireNotNull(dao.getSyncState())
        dao.upsertSyncState(currentState.copy(nextClientSequence = 1L))
        enqueue(commands)
        reenqueueStaged(stagedV3Commands)
        dao.insertMigrationMarker(
            MigrationMarkerEntity(
                id = BOOTSTRAP_MARKER,
                completedAt = System.currentTimeMillis(),
                sourceDigest = "v3-semantic-bootstrap:${cutover.commands.size}:${stagedV3Commands.size}",
                eventCount = events.size,
                journalCount = journals.size,
                draftCount = 0,
            )
        )
        SyncV3CutoverIntents.retire(db)
        V3BootstrapResult(true, commands.size + stagedV3Commands.size)
    }

    private fun reenqueueStaged(commands: List<SyncCommandEntity>) {
        if (commands.isEmpty()) return
        val groups = mutableListOf<MutableList<SyncCommandEntity>>()
        commands.sortedBy(SyncCommandEntity::clientSequence).forEach { command ->
            val current = groups.lastOrNull()
            if (current == null || current.last().batchId != command.batchId ||
                current.size >= SyncV3BatchPartitioner.MAX_BATCH_COMMANDS
            ) {
                groups += mutableListOf(command)
            } else {
                current += command
            }
        }
        groups.forEach { group ->
            val sequences = dao.allocateClientSequence(group.size)
            val batchId = group.first().batchId.takeIf(UUID_V7::matches)
                ?: SyncV3Uploader.uuidV7Default()
            group.zip(sequences).forEach { (command, sequence) ->
                dao.insertCommand(
                    command.copy(
                        batchId = batchId,
                        clientSequence = sequence,
                        state = COMMAND_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L,
                        lastErrorCode = null,
                    )
                )
            }
        }
        dao.updateSyncStatus(PHASE_SAVED, null, System.currentTimeMillis())
    }

    private fun enqueue(commands: List<SyncCommandDraft>) {
        if (commands.isEmpty()) return
        ensureIdentity()
        val sequences = dao.allocateClientSequence(commands.size)
        check(sequences.size == commands.size) { "Sync V3 state is not initialized" }
        SyncV3BatchPartitioner.partition(
            commands.zip(sequences),
            batchId = SyncV3Uploader::uuidV7Default,
        ).forEach { chunk ->
            chunk.commands.forEach { (draft, sequence) ->
                dao.insertCommand(
                    SyncCommandEntity(
                        commandId = SyncV3Uploader.uuidV7Default(),
                        batchId = chunk.batchId,
                        clientSequence = sequence,
                        commandType = draft.type.wire,
                        aggregateId = draft.aggregateId,
                        argumentsJson = draft.arguments.toString(),
                        state = COMMAND_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L,
                        lastErrorCode = null,
                    )
                )
            }
        }
        dao.updateSyncStatus(PHASE_SAVED, null, System.currentTimeMillis())
    }

    private fun isKnownCommandIdentity(commandId: String): Boolean {
        fun hasLedgerEntry(id: String): Boolean =
            dao.command(id) != null ||
                dao.receipts(listOf(id)).isNotEmpty() ||
                dao.migrationMarker(SyncV3ArchivedReceipts.markerId(id)) != null

        if (hasLedgerEntry(commandId)) return true
        val resolved = SyncV3CommandAliases.resolve(commandId) { current ->
            dao.migrationMarker(SyncV3CommandAliases.markerId(current))?.sourceDigest
        }
        return resolved != commandId && hasLedgerEntry(resolved)
    }

    private fun ensureIdentity() {
        val current = dao.getSyncState()
        if (current == null) {
            dao.upsertSyncState(
                SyncStateEntity(
                    deviceId = deviceId,
                    nextClientSequence = 1L,
                    installedSnapshotVersion = 0L,
                    installedSnapshotHash = null,
                    serverInstanceId = null,
                )
            )
            return
        }
        if (current.deviceId == deviceId) return

        // A restored database must never continue another installation's device sequence, but
        // the outbox is user data too: it may be the only durable record of an offline delete or
        // edit. Re-key every semantic command into the new device namespace, retain an alias for
        // Watch retries, and force a verified central-aware bootstrap before anything uploads.
        val rollover = SyncV3IdentityRollover.rewrite(
            commands = dao.listAllCommands(),
            commandId = SyncV3Uploader::uuidV7Default,
            batchId = SyncV3Uploader::uuidV7Default,
        )
        val archivedReceipts = dao.listAllReceipts()
        dao.deleteAllCommands()
        dao.deleteAllReceipts()
        dao.deleteMigrationMarker(BOOTSTRAP_MARKER)
        dao.upsertSyncState(
            SyncStateEntity(
                deviceId = deviceId,
                nextClientSequence = 1L,
                installedSnapshotVersion = 0L,
                installedSnapshotHash = null,
                // Keep this one identifier until capabilities is checked. It lets
                // prepareForServerInstance distinguish a same-authority restore from a URL now
                // pointing at a different server, while every snapshot/receipt watermark resets.
                serverInstanceId = current.serverInstanceId,
            )
        )
        rollover.forEach { retained ->
            dao.insertCommand(retained.command)
            val aliasId = SyncV3CommandAliases.markerId(retained.previousCommandId)
            if (dao.migrationMarker(aliasId) == null) {
                dao.insertMigrationMarker(
                    MigrationMarkerEntity(
                        id = aliasId,
                        completedAt = System.currentTimeMillis(),
                        sourceDigest = retained.command.commandId,
                        eventCount = 0,
                        journalCount = 0,
                        draftCount = 0,
                    )
                )
            }
        }
        archivedReceipts.forEach { receipt ->
            val archiveId = SyncV3ArchivedReceipts.markerId(receipt.commandId)
            if (dao.migrationMarker(archiveId) == null) {
                dao.insertMigrationMarker(
                    MigrationMarkerEntity(
                        id = archiveId,
                        completedAt = System.currentTimeMillis(),
                        sourceDigest = SyncV3ArchivedReceipts.encode(receipt),
                        eventCount = 0,
                        journalCount = 0,
                        draftCount = 0,
                    )
                )
            }
        }
        if (rollover.isNotEmpty()) {
            dao.upsertSyncState(
                requireNotNull(dao.getSyncState()).copy(
                    nextClientSequence = rollover.size + 1L,
                    syncPhase = PHASE_SAVED,
                    syncErrorCode = null,
                    syncUpdatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        const val BOOTSTRAP_MARKER = "sync-v3-semantic-bootstrap-v1"
        const val COMMAND_PENDING = "pending"
        const val PHASE_SAVED = "saved"
        private val UUID_V7 = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
    }
}

/** Stored below noBackupFilesDir, so Android restore cannot clone a device identity. */
object SyncV3DeviceIdentity {
    private const val FILE_NAME = "sync-v3-device-id"

    @Synchronized
    fun get(context: Context): String {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        runCatching { file.readText().trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
            ?.let { return it }
        val generated = "android-${UUID.randomUUID()}"
        val temporary = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        temporary.writeText(generated)
        check(temporary.renameTo(file) || file.exists()) { "Unable to persist Sync V3 device id" }
        return file.readText().trim().ifEmpty { generated }
    }
}
