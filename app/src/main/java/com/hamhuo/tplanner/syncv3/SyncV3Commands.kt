package com.hamhuo.tplanner.syncv3

import org.json.JSONArray
import org.json.JSONObject

/**
 * V3 命令模型与批次序列化(纯 Kotlin,与 docs/sync-v3.md §5 协议一致)。
 * 服务器与桌面端用同一份 JSON 契约;这里只构造,不裁决。
 */
enum class SyncCommandType(val wire: String) {
    TASK_CREATE("task.create"),
    TASK_SET_TITLE("task.setTitle"),
    TASK_SET_NOTE("task.setNote"),
    TASK_SET_SCHEDULE("task.setSchedule"),
    TASK_SET_COMPLETED("task.setCompleted"),
    TASK_DELETE("task.delete"),
    TASK_RESTORE("task.restore"),
    TASK_CHANGE_TYPE("task.changeType"),
    TASK_SET_RECURRENCE("task.setRecurrence"),
    TASK_ASSIGN_LIST("task.assignList"),
    TASK_MOVE_IN_TIMELINE("task.moveInTimeline"),
    CHECKLIST_CREATE_ITEM("checklist.createItem"),
    CHECKLIST_SET_TITLE("checklist.setTitle"),
    CHECKLIST_SET_COMPLETED("checklist.setCompleted"),
    CHECKLIST_DELETE_ITEM("checklist.deleteItem"),
    CHECKLIST_REORDER_ITEM("checklist.reorderItem"),
    LIST_CREATE("list.create"),
    LIST_RENAME("list.rename"),
    LIST_SET_COLOR("list.setColor"),
    LIST_DELETE("list.delete"),
    JOURNAL_SET_TEXT("journal.setText"),
    JOURNAL_DELETE("journal.delete"),
    GOAL_CREATE("goal.create"),
    GOAL_PATCH("goal.patch"),
    GOAL_DELETE("goal.delete"),
    INSIGHT_UPSERT("insight.upsert"),
    INSIGHT_DELETE("insight.delete"),
}

data class SyncCommand(
    val commandId: String,
    val clientSequence: Long,
    val type: SyncCommandType,
    val aggregateId: String?,
    val arguments: JSONObject,
) {
    fun toWire(): JSONObject = JSONObject().apply {
        put("commandId", commandId)
        put("clientSequence", clientSequence)
        put("type", type.wire)
        aggregateId?.let { put("aggregateId", it) }
        put("arguments", arguments)
    }
}

data class SyncCommandBatch(
    val batchId: String,
    val deviceId: String,
    val commands: List<SyncCommand>,
) {
    val firstClientSequence: Long get() = commands.first().clientSequence
    val lastClientSequence: Long get() = commands.last().clientSequence

    fun toWire(): JSONObject = JSONObject().apply {
        put("protocolVersion", 3)
        put("batchId", batchId)
        put("deviceId", deviceId)
        put("firstClientSequence", firstClientSequence)
        put("lastClientSequence", lastClientSequence)
        put("commands", JSONArray().apply {
            commands.forEach { put(it.toWire()) }
        })
    }
}

/** 回执解析:GET /tplanner/v3/receipts 的 results[] 单项。 */
data class SyncReceipt(
    val commandId: String,
    val clientSequence: Long,
    val status: String,
    val snapshotVersion: Long?,
    val errorCode: String?,
) {
    companion object {
        fun fromWire(json: JSONObject): SyncReceipt = SyncReceipt(
            commandId = json.getString("commandId"),
            clientSequence = json.getLong("clientSequence"),
            status = json.getString("status"),
            snapshotVersion = if (json.has("snapshotVersion") && !json.isNull("snapshotVersion")) {
                json.getLong("snapshotVersion")
            } else null,
            errorCode = json.optString("errorCode").takeIf { it.isNotEmpty() },
        )
    }
}
