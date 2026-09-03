package com.hamhuo.tplanner.syncv3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.hamhuo.tplanner.persistence.JournalEntity
import com.hamhuo.tplanner.persistence.MigrationMarkerEntity
import com.hamhuo.tplanner.persistence.ScheduleItemEntity
import com.hamhuo.tplanner.persistence.UserListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncV3Dao {

    // ── 命令 outbox ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCommand(command: SyncCommandEntity)

    @Query("SELECT * FROM sync_commands WHERE command_id = :commandId")
    fun command(commandId: String): SyncCommandEntity?

    @Query(
        "SELECT * FROM sync_commands WHERE state = :state " +
            "ORDER BY client_sequence ASC LIMIT :limit"
    )
    fun listCommands(state: String, limit: Int): List<SyncCommandEntity>

    @Query("UPDATE sync_commands SET state = 'uploaded' WHERE client_sequence IN (:sequences)")
    fun markUploaded(sequences: List<Long>)

    /**
     * Remove only commands whose terminal receipt is represented by the installed snapshot.
     * A receipt alone is not enough: deleting earlier would let an older snapshot roll back the
     * optimistic phone state. Null snapshot versions are terminal no-op/rejection receipts and
     * become safe once any current authoritative snapshot is installed.
     */
    @Query(
        "DELETE FROM sync_commands WHERE command_id IN (" +
            "SELECT command_id FROM sync_receipts " +
            "WHERE status != 'SEQUENCE_GAP' AND (" +
            "(snapshot_version IS NOT NULL AND snapshot_version <= :installedVersion) OR " +
            "(snapshot_version IS NULL AND broker_sequence IS NOT NULL " +
            "AND broker_sequence <= :installedBrokerToSequence)))"
    )
    fun deletePublishedCommands(installedVersion: Long, installedBrokerToSequence: Long): Int

    @Query("SELECT COUNT(*) FROM sync_commands WHERE state = 'pending'")
    fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_commands")
    fun commandCount(): Int

    /**
     * The projection overlay is a correctness path, not a UI page. It must include every
     * unacknowledged command even after a long offline period; an arbitrary LIMIT would allow a
     * snapshot install to silently discard the tail of the user's local intent.
     */
    @Query("SELECT * FROM sync_commands ORDER BY client_sequence ASC")
    fun listAllCommands(): List<SyncCommandEntity>

    @Query("SELECT COUNT(*) FROM sync_commands WHERE state = 'uploaded'")
    fun uploadedCount(): Int

    @Query(
        "UPDATE sync_commands SET attempt_count = attempt_count + 1, " +
            "next_attempt_at = :nextAttemptAt, last_error_code = :errorCode " +
            "WHERE state = 'pending'"
    )
    fun recordPendingFailure(nextAttemptAt: Long, errorCode: String)

    @Query("DELETE FROM sync_commands")
    fun deleteAllCommands()

    @Query("DELETE FROM sync_receipts")
    fun deleteAllReceipts()

    @Query(
        "UPDATE sync_commands SET state = 'pending', attempt_count = 0, " +
            "next_attempt_at = 0, last_error_code = NULL"
    )
    fun resetAllCommandsPending()

    // ── 设备状态 ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_state WHERE singleton_id = 1")
    fun getSyncState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE singleton_id = 1")
    fun observeSyncState(): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSyncState(state: SyncStateEntity)

    @Query(
        "UPDATE sync_state SET sync_phase = :phase, sync_error_code = :errorCode, " +
            "sync_updated_at = :updatedAt WHERE singleton_id = 1"
    )
    fun updateSyncStatus(phase: String, errorCode: String?, updatedAt: Long)

    @Query(
        "UPDATE sync_state SET watch_projection_snapshot_version = " +
            "MAX(watch_projection_snapshot_version, :version), " +
            "watch_projection_broker_to_sequence = " +
            "MAX(watch_projection_broker_to_sequence, :brokerToSequence) " +
            "WHERE singleton_id = 1"
    )
    fun markWatchProjection(version: Long, brokerToSequence: Long)

    /** 原子分配 clientSequence:读-改-写在一个事务里。 */
    @Transaction
    fun allocateClientSequence(count: Int): List<Long> {
        val current = getSyncState() ?: return emptyList()
        val start = current.nextClientSequence
        upsertSyncState(current.copy(nextClientSequence = start + count))
        return (start until start + count).toList()
    }

    // ── V3 bootstrap and atomic displayed-state projection ────────────────────────────

    @Query("SELECT * FROM events ORDER BY sort_index")
    fun eventRows(): List<ScheduleItemEntity>

    @Query("SELECT * FROM journals ORDER BY date")
    fun journalRows(): List<JournalEntity>

    @Query("SELECT * FROM user_lists ORDER BY sort_order")
    fun userListRows(): List<UserListEntity>

    @Query("DELETE FROM events")
    fun clearEvents()

    @Query("DELETE FROM journals")
    fun clearJournals()

    @Query("DELETE FROM user_lists")
    fun clearUserLists()

    @Upsert
    fun upsertEventRows(rows: List<ScheduleItemEntity>)

    @Upsert
    fun upsertJournalRows(rows: List<JournalEntity>)

    @Upsert
    fun upsertUserListRows(rows: List<UserListEntity>)

    @Query("SELECT * FROM migration_markers WHERE id = :id")
    fun migrationMarker(id: String): MigrationMarkerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMigrationMarker(marker: MigrationMarkerEntity)

    @Query("DELETE FROM migration_markers WHERE id = :id")
    fun deleteMigrationMarker(id: String)

    @Query("DELETE FROM migration_markers WHERE id LIKE :prefix || '%'")
    fun deleteMigrationMarkersByPrefix(prefix: String)

    // ── 回执 ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReceipts(receipts: List<SyncReceiptEntity>)

    @Query("SELECT * FROM sync_receipts WHERE client_sequence > :after ORDER BY client_sequence ASC LIMIT 200")
    fun receiptsAfter(after: Long): List<SyncReceiptEntity>

    @Query("SELECT * FROM sync_receipts WHERE command_id IN (:commandIds)")
    fun receipts(commandIds: List<String>): List<SyncReceiptEntity>

    @Query("SELECT * FROM sync_receipts ORDER BY client_sequence ASC")
    fun listAllReceipts(): List<SyncReceiptEntity>

    @Query("SELECT MAX(client_sequence) FROM sync_receipts WHERE status != 'SEQUENCE_GAP'")
    fun acceptedThrough(): Long?

    @Query("SELECT MAX(snapshot_version) FROM sync_receipts")
    fun maxReceiptSnapshotVersion(): Long?

    @Query(
        "SELECT * FROM sync_receipts WHERE status IN " +
            "('REJECTED', 'SEQUENCE_GAP', 'ENTITY_DELETED', 'SCHEMA_UNSUPPORTED', " +
            "'ID_ALREADY_EXISTS') " +
            "AND client_sequence > :after AND (" +
            "(snapshot_version IS NOT NULL AND snapshot_version <= :installedVersion) OR " +
            "(snapshot_version IS NULL AND broker_sequence IS NOT NULL " +
            "AND broker_sequence <= :installedBrokerToSequence)) " +
            "ORDER BY client_sequence DESC LIMIT 1"
    )
    fun latestFailedReceiptAfter(
        after: Long,
        installedVersion: Long,
        installedBrokerToSequence: Long,
    ): SyncReceiptEntity?

    // ── 同步日志(诊断,不参与正确性路径)──────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertLog(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_logs ORDER BY id DESC LIMIT :limit")
    fun recentLogs(limit: Int): List<SyncLogEntity>

    @Query("SELECT * FROM sync_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_logs WHERE id NOT IN (SELECT id FROM sync_logs ORDER BY id DESC LIMIT :keep)")
    fun trimLogs(keep: Int): Int

    @Query("DELETE FROM sync_logs")
    fun clearLogs()

    @Query("SELECT COUNT(*) FROM sync_logs")
    fun logCount(): Int

    @Transaction
    fun appendLog(entry: SyncLogEntity, keep: Int) {
        insertLog(entry)
        trimLogs(keep)
    }
}
