package com.hamhuo.tplanner.syncv3

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V3 同步命令 outbox(见 docs/sync-v3.md §15):
 * 本地持久化后才更新 UI;只有中央回执/快照确认后才删除(不变量 #10/#12)。
 */
@Entity(
    tableName = "sync_commands",
    indices = [Index(value = ["client_sequence"]), Index(value = ["state"])],
)
data class SyncCommandEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "client_sequence") val clientSequence: Long,
    @ColumnInfo(name = "command_type") val commandType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String?,
    @ColumnInfo(name = "arguments_json") val argumentsJson: String,
    /** pending(未上传) / uploaded(BROKER_PERSISTED,等回执) */
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
)

/** 设备级同步元数据:deviceId 每次安装新生成、永不随备份恢复。 */
@Entity(tableName = "sync_state", primaryKeys = ["singleton_id"])
data class SyncStateEntity(
    @ColumnInfo(name = "singleton_id") val singletonId: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "next_client_sequence") val nextClientSequence: Long,
    @ColumnInfo(name = "installed_snapshot_version") val installedSnapshotVersion: Long,
    @ColumnInfo(name = "installed_snapshot_hash") val installedSnapshotHash: String?,
    @ColumnInfo(name = "server_instance_id") val serverInstanceId: String?,
    /** The canonical server state before local pending commands are replayed. */
    @ColumnInfo(name = "server_mirror_json") val serverMirrorJson: String? = null,
    /** Last central snapshot used to build and durably queue the watch projection. */
    @ColumnInfo(name = "watch_projection_snapshot_version", defaultValue = "0")
    val watchProjectionSnapshotVersion: Long = 0L,
    @ColumnInfo(name = "sync_phase", defaultValue = "'idle'") val syncPhase: String = "idle",
    @ColumnInfo(name = "sync_error_code") val syncErrorCode: String? = null,
    @ColumnInfo(name = "sync_updated_at", defaultValue = "0") val syncUpdatedAt: Long = 0L,
    /** Highest central broker sequence covered by [installedSnapshotVersion]. */
    @ColumnInfo(name = "installed_broker_to_sequence", defaultValue = "0")
    val installedBrokerToSequence: Long = 0L,
    /** Broker coverage of the projection durably queued for the Watch. */
    @ColumnInfo(name = "watch_projection_broker_to_sequence", defaultValue = "0")
    val watchProjectionBrokerToSequence: Long = 0L,
    /** Opaque delta-v1 journal cursor; installed atomically with the mirror it proves (§9.3). */
    @ColumnInfo(name = "cursor") val cursor: String? = null,
)

/** 中央回执:由服务器返回值落库,用于重启后对账与 outbox 清理。 */
@Entity(
    tableName = "sync_receipts",
    indices = [Index(value = ["client_sequence"])],
)
data class SyncReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "client_sequence") val clientSequence: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "snapshot_version") val snapshotVersion: Long?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "broker_sequence") val brokerSequence: Long? = null,
)
