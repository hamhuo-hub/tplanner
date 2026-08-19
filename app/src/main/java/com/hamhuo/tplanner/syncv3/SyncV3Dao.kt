package com.hamhuo.tplanner.syncv3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncV3Dao {

    // ── 命令 outbox ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCommand(command: SyncCommandEntity)

    @Query(
        "SELECT * FROM sync_commands WHERE state = :state " +
            "ORDER BY client_sequence ASC LIMIT :limit"
    )
    fun listCommands(state: String, limit: Int): List<SyncCommandEntity>

    @Query("UPDATE sync_commands SET state = 'uploaded' WHERE client_sequence IN (:sequences)")
    fun markUploaded(sequences: List<Long>)

    @Query("DELETE FROM sync_commands WHERE client_sequence <= :through")
    fun deleteThroughSequence(through: Long)

    @Query("SELECT COUNT(*) FROM sync_commands WHERE state = 'pending'")
    fun pendingCount(): Int

    // ── 设备状态 ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_state WHERE singleton_id = 1")
    fun getSyncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSyncState(state: SyncStateEntity)

    /** 原子分配 clientSequence:读-改-写在一个事务里。 */
    @Transaction
    fun allocateClientSequence(count: Int): List<Long> {
        val current = getSyncState() ?: return emptyList()
        val start = current.nextClientSequence
        upsertSyncState(current.copy(nextClientSequence = start + count))
        return (start until start + count).toList()
    }

    // ── 回执 ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReceipts(receipts: List<SyncReceiptEntity>)

    @Query("SELECT * FROM sync_receipts WHERE client_sequence > :after ORDER BY client_sequence ASC LIMIT 200")
    fun receiptsAfter(after: Long): List<SyncReceiptEntity>

    @Query("SELECT MAX(client_sequence) FROM sync_receipts")
    fun acceptedThrough(): Long?
}
