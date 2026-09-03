package com.hamhuo.tplanner.syncv3

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步日志条目(诊断用,不参与任何正确性路径)。
 * 只保留最近 [com.hamhuo.tplanner.SyncLog.MAX_ENTRIES] 条,由写入侧裁剪。
 */
@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** info / warn / error */
    @ColumnInfo(name = "level") val level: String,
    /** 来源:pump / sync / delta / worker / snapshot … */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)
