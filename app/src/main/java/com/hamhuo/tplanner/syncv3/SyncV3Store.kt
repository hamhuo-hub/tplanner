package com.hamhuo.tplanner.syncv3

/**
 * 同步存储接口:上传器/安装器只依赖此接口,便于 JVM 测试用内存实现替代 Room。
 * Room 实现见 [RoomSyncV3Store]。
 */
interface SyncV3Store {
    fun getSyncState(): SyncStateEntity?
    fun upsertSyncState(state: SyncStateEntity)
    fun listCommands(state: String, limit: Int): List<SyncCommandEntity>
    fun markUploaded(sequences: List<Long>)
    fun insertReceipts(receipts: List<SyncReceiptEntity>)
    fun acceptedThrough(): Long?
    fun listAllCommands(): List<SyncCommandEntity> {
        fun readEvery(state: String): List<SyncCommandEntity> {
            val result = mutableListOf<SyncCommandEntity>()
            while (true) {
                // In-memory/JVM stores historically expose only the state+limit primitive. Grow
                // the requested prefix until it is no longer full, so this default never truncates.
                val requested = (result.size + COMMAND_PAGE_SIZE).coerceAtMost(Int.MAX_VALUE)
                val prefix = listCommands(state, requested)
                result.clear()
                result.addAll(prefix)
                if (prefix.size < requested || requested == Int.MAX_VALUE) return result
            }
        }
        return (readEvery("uploaded") + readEvery("pending"))
            .sortedBy(SyncCommandEntity::clientSequence)
    }
    fun pendingCount(): Int = listCommands("pending", Int.MAX_VALUE).size
    fun uploadedCount(): Int = listCommands("uploaded", Int.MAX_VALUE).size
    fun updateSyncStatus(phase: String, errorCode: String?, updatedAt: Long) = Unit
    fun recordPendingFailure(nextAttemptAt: Long, errorCode: String) = Unit

    private companion object {
        const val COMMAND_PAGE_SIZE = 1_000
    }
}

class RoomSyncV3Store(private val dao: SyncV3Dao) : SyncV3Store {
    override fun getSyncState(): SyncStateEntity? = dao.getSyncState()
    override fun upsertSyncState(state: SyncStateEntity) = dao.upsertSyncState(state)
    override fun listCommands(state: String, limit: Int): List<SyncCommandEntity> = dao.listCommands(state, limit)
    override fun markUploaded(sequences: List<Long>) {
        if (sequences.isNotEmpty()) dao.markUploaded(sequences)
    }
    override fun insertReceipts(receipts: List<SyncReceiptEntity>) {
        if (receipts.isNotEmpty()) dao.insertReceipts(receipts)
    }
    override fun acceptedThrough(): Long? = dao.acceptedThrough()
    override fun listAllCommands(): List<SyncCommandEntity> = dao.listAllCommands()
    override fun pendingCount(): Int = dao.pendingCount()
    override fun uploadedCount(): Int = dao.uploadedCount()
    override fun updateSyncStatus(phase: String, errorCode: String?, updatedAt: Long) =
        dao.updateSyncStatus(phase, errorCode, updatedAt)
    override fun recordPendingFailure(nextAttemptAt: Long, errorCode: String) =
        dao.recordPendingFailure(nextAttemptAt, errorCode)
}
