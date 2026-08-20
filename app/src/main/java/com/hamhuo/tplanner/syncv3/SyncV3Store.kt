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
    fun deleteThroughSequence(through: Long)
    fun insertReceipts(receipts: List<SyncReceiptEntity>)
    fun acceptedThrough(): Long?
}

class RoomSyncV3Store(private val dao: SyncV3Dao) : SyncV3Store {
    override fun getSyncState(): SyncStateEntity? = dao.getSyncState()
    override fun upsertSyncState(state: SyncStateEntity) = dao.upsertSyncState(state)
    override fun listCommands(state: String, limit: Int): List<SyncCommandEntity> = dao.listCommands(state, limit)
    override fun markUploaded(sequences: List<Long>) {
        if (sequences.isNotEmpty()) dao.markUploaded(sequences)
    }
    override fun deleteThroughSequence(through: Long) = dao.deleteThroughSequence(through)
    override fun insertReceipts(receipts: List<SyncReceiptEntity>) {
        if (receipts.isNotEmpty()) dao.insertReceipts(receipts)
    }
    override fun acceptedThrough(): Long? = dao.acceptedThrough()
}
