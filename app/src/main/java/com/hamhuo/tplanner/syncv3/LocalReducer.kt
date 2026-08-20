package com.hamhuo.tplanner.syncv3

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地乐观 reducer —— 与服务器 src/materializer/reducer.js、桌面 src/syncV3/localReducer.js
 * 逐语义一致。用途:Displayed State = reduce(Server Mirror, Pending Overlay) 的临时预览;
 * 权威永远来自中央快照(见 docs/sync-v3.md §8)。
 *
 * 铁律:纯函数、不读时钟(时间来自命令流的 brokerSequence)、删除是生命周期、
 * 重复同值 NOOP、内部业务拒绝返回 receipt 不抛异常。
 * 契约测试用同一份 sequence-01 fixture 验证与服务器/桌面实现产出相同状态。
 */
object LocalReducer {

    data class SyncState(
        val tasks: JSONObject,
        val customLists: JSONObject,
        val journals: JSONObject,
        val goals: JSONObject,
        val insights: JSONObject,
    )

    data class ReducerReceipt(val status: String, val errorCode: String? = null)

    data class Result(val state: SyncState, val receipt: ReducerReceipt)

    fun emptyState(): SyncState = SyncState(
        JSONObject(), JSONObject(), JSONObject(), JSONObject(), JSONObject(),
    )

    private fun copy(obj: JSONObject): JSONObject {
        val out = JSONObject()
        obj.keys().forEach { key -> out.put(key, obj.get(key)) }
        return out
    }

    private fun rejected(code: String) = ReducerReceipt("REJECTED", code)
    private fun noop(code: String? = null) = ReducerReceipt("NOOP", code)

    private fun findActive(entity: JSONObject?): Pair<JSONObject?, ReducerReceipt?> {
        if (entity == null) return null to rejected("ENTITY_NOT_FOUND")
        if (entity.optString("lifecycle") == "deleted") {
            return null to ReducerReceipt("ENTITY_DELETED", "ENTITY_DELETED")
        }
        return entity to null
    }

    private fun updateTask(state: SyncState, id: String, updater: (JSONObject) -> JSONObject?): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(id))
        if (receipt != null) return Result(state, receipt)
        val next = updater(entity!!)
        if (next === entity) return Result(state, noop())
        val tasks = copy(state.tasks)
        tasks.put(id, next)
        return Result(state.copy(tasks = tasks), ReducerReceipt("APPLIED"))
    }

    private fun setTaskField(state: SyncState, id: String, patch: JSONObject): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(id))
        if (receipt != null) return Result(state, receipt)
        val next = copy(entity!!)
        patch.keys().forEach { key -> next.put(key, patch.get(key)) }
        val tasks = copy(state.tasks)
        tasks.put(id, next)
        return Result(state.copy(tasks = tasks), ReducerReceipt("APPLIED"))
    }

    private fun updateInMap(state: SyncState, mapKey: String, id: String, updater: (JSONObject) -> JSONObject?): Result {
        val map = state.mapForKey(mapKey)
        val (entity, receipt) = findActive(map.optJSONObject(id))
        if (receipt != null) return Result(state, receipt)
        val next = updater(entity!!)
        if (next === entity) return Result(state, noop())
        return Result(state.withMap(mapKey) { copy(it).apply { put(id, next) } }, ReducerReceipt("APPLIED"))
    }

    private fun deleteFromMap(state: SyncState, mapKey: String, id: String, seq: Long): Result {
        val map = state.mapForKey(mapKey)
        val (entity, receipt) = findActive(map.optJSONObject(id))
        if (receipt != null) return Result(state, receipt)
        val next = copy(entity!!)
        next.put("lifecycle", "deleted")
        next.put("deletedAt", seq)
        return Result(state.withMap(mapKey) { copy(it).apply { put(id, next) } }, ReducerReceipt("APPLIED"))
    }

    private fun SyncState.mapForKey(key: String): JSONObject = when (key) {
        "tasks" -> tasks
        "customLists" -> customLists
        "journals" -> journals
        "goals" -> goals
        "insights" -> insights
        else -> throw IllegalArgumentException("unknown map key $key")
    }

    private fun SyncState.withMap(key: String, transform: (JSONObject) -> JSONObject): SyncState = when (key) {
        "tasks" -> copy(tasks = transform(tasks))
        "customLists" -> copy(customLists = transform(customLists))
        "journals" -> copy(journals = transform(journals))
        "goals" -> copy(goals = transform(goals))
        "insights" -> copy(insights = transform(insights))
        else -> throw IllegalArgumentException("unknown map key $key")
    }

    private fun jsonEquals(a: Any?, b: Any?): Boolean =
        (a == null && b == null) || (a != null && b != null && a.toString() == b.toString())

    fun apply(state: SyncState, command: SyncCommand, brokerSequence: Long): Result {
        return when (command.type) {
            SyncCommandType.TASK_CREATE -> taskCreate(state, command)
            SyncCommandType.TASK_SET_TITLE -> taskSetTitle(state, command)
            SyncCommandType.TASK_SET_NOTE -> taskSetNote(state, command)
            SyncCommandType.TASK_SET_COMPLETED -> taskSetCompleted(state, command)
            SyncCommandType.TASK_SET_SCHEDULE -> taskSetSchedule(state, command)
            SyncCommandType.TASK_SET_RECURRENCE -> taskSetRecurrence(state, command)
            SyncCommandType.TASK_CHANGE_TYPE -> taskChangeType(state, command)
            SyncCommandType.TASK_ASSIGN_LIST -> taskAssignList(state, command)
            SyncCommandType.TASK_MOVE_IN_TIMELINE -> taskMoveInTimeline(state, command)
            SyncCommandType.TASK_DELETE -> taskDelete(state, command, brokerSequence)
            SyncCommandType.TASK_RESTORE -> taskRestore(state, command)

            SyncCommandType.CHECKLIST_CREATE_ITEM -> checklistCreateItem(state, command)
            SyncCommandType.CHECKLIST_SET_TITLE -> checklistSetTitle(state, command)
            SyncCommandType.CHECKLIST_SET_COMPLETED -> checklistSetCompleted(state, command)
            SyncCommandType.CHECKLIST_DELETE_ITEM -> checklistDeleteItem(state, command)
            SyncCommandType.CHECKLIST_REORDER_ITEM -> checklistReorderItem(state, command)

            SyncCommandType.LIST_CREATE -> listCreate(state, command)
            SyncCommandType.LIST_RENAME -> listRename(state, command)
            SyncCommandType.LIST_SET_COLOR -> listSetColor(state, command)
            SyncCommandType.LIST_DELETE -> listDelete(state, command, brokerSequence)

            SyncCommandType.JOURNAL_SET_TEXT -> journalSetText(state, command)
            SyncCommandType.JOURNAL_DELETE -> journalDelete(state, command, brokerSequence)

            SyncCommandType.GOAL_CREATE -> goalCreate(state, command)
            SyncCommandType.GOAL_PATCH -> goalPatch(state, command)
            SyncCommandType.GOAL_DELETE -> goalDelete(state, command, brokerSequence)

            SyncCommandType.INSIGHT_UPSERT -> insightUpsert(state, command)
            SyncCommandType.INSIGHT_DELETE -> insightDelete(state, command, brokerSequence)
        }
    }

    // ── task ─────────────────────────────────────────────────────────────

    private fun taskCreate(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("MISSING_AGGREGATE_ID"))
        if (state.tasks.has(id)) return Result(state, ReducerReceipt("ID_ALREADY_EXISTS", "ID_ALREADY_EXISTS"))
        val args = cmd.arguments
        val task = JSONObject().apply {
            put("title", args.optString("title", ""))
            put("note", "")
            put("completed", false)
            put("itemType", args.optString("itemType", "task"))
            put("lifecycle", "active")
            put("deletedAt", JSONObject.NULL)
        }
        return Result(state.copy(tasks = copy(state.tasks).apply { put(id, task) }), ReducerReceipt("APPLIED"))
    }

    private fun taskSetTitle(state: SyncState, cmd: SyncCommand): Result {
        val title = cmd.arguments.optString("title", "")
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (t.optString("title") == title) t else copy(t).apply { put("title", title) }
        }
    }

    private fun taskSetNote(state: SyncState, cmd: SyncCommand): Result {
        val note = cmd.arguments.optString("note", "")
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (t.optString("note") == note) t else copy(t).apply { put("note", note) }
        }
    }

    private fun taskSetCompleted(state: SyncState, cmd: SyncCommand): Result {
        val completed = cmd.arguments.optBoolean("completed", false)
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (t.optBoolean("completed", false) == completed) t else copy(t).apply { put("completed", completed) }
        }
    }

    private fun taskSetSchedule(state: SyncState, cmd: SyncCommand): Result {
        val schedule = cmd.arguments.optJSONObject("schedule")
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (jsonEquals(t.opt("schedule"), schedule)) t else copy(t).apply { put("schedule", schedule ?: JSONObject.NULL) }
        }
    }

    private fun taskSetRecurrence(state: SyncState, cmd: SyncCommand): Result {
        val recurrence = cmd.arguments.opt("recurrence")
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (jsonEquals(t.opt("recurrence"), recurrence)) t else copy(t).apply { put("recurrence", recurrence ?: JSONObject.NULL) }
        }
    }

    private fun taskChangeType(state: SyncState, cmd: SyncCommand): Result {
        val itemType = cmd.arguments.optString("itemType", "")
        if (itemType.isEmpty()) return Result(state, rejected("MISSING_ITEM_TYPE"))
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (t.optString("itemType") == itemType) t else copy(t).apply { put("itemType", itemType) }
        }
    }

    private fun taskAssignList(state: SyncState, cmd: SyncCommand): Result {
        val listId: String? = if (cmd.arguments.isNull("listId")) null else cmd.arguments.optString("listId", null)
        if (listId != null) {
            val list = state.customLists.optJSONObject(listId)
            if (list == null || list.optString("lifecycle") == "deleted") {
                return Result(state, rejected("LIST_NOT_FOUND"))
            }
        }
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            if (t.opt("listId") == null && listId == null) t
            else if (t.optString("listId", null) == listId) t
            else copy(t).apply { put("listId", listId ?: JSONObject.NULL) }
        }
    }

    private fun taskMoveInTimeline(state: SyncState, cmd: SyncCommand): Result {
        val offsetMinutes = cmd.arguments.optDouble("offsetMinutes", Double.NaN)
        if (offsetMinutes.isNaN()) return Result(state, rejected("MISSING_OFFSET"))
        return updateTask(state, cmd.aggregateId ?: "") { t ->
            val schedule = t.optJSONObject("schedule") ?: return@updateTask t
            val startAt = schedule.optString("startAt", "")
            if (startAt.isEmpty()) return@updateTask t
            fun shift(iso: String): String =
                java.time.Instant.parse(iso).plusMillis((offsetMinutes * 60_000).toLong()).toString()
            val next = copy(schedule)
            next.put("startAt", shift(startAt))
            if (!schedule.isNull("endAt") && schedule.optString("endAt", "").isNotEmpty()) {
                next.put("endAt", shift(schedule.optString("endAt")))
            }
            copy(t).apply { put("schedule", next) }
        }
    }

    private fun taskDelete(state: SyncState, cmd: SyncCommand, seq: Long): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("ENTITY_NOT_FOUND"))
        val t = state.tasks.optJSONObject(id) ?: return Result(state, rejected("ENTITY_NOT_FOUND"))
        if (t.optString("lifecycle") == "deleted") return Result(state, noop("NOOP_ALREADY_DELETED"))
        val next = copy(t).apply {
            put("lifecycle", "deleted")
            put("deletedAt", seq)
        }
        return Result(state.copy(tasks = copy(state.tasks).apply { put(id, next) }), ReducerReceipt("APPLIED"))
    }

    private fun taskRestore(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("ENTITY_NOT_FOUND"))
        val t = state.tasks.optJSONObject(id) ?: return Result(state, rejected("ENTITY_NOT_FOUND"))
        if (t.optString("lifecycle") == "active") return Result(state, noop())
        val next = copy(t).apply {
            put("lifecycle", "active")
            put("deletedAt", JSONObject.NULL)
        }
        return Result(state.copy(tasks = copy(state.tasks).apply { put(id, next) }), ReducerReceipt("APPLIED"))
    }

    // ── checklist ─────────────────────────────────────────────────────────

    private fun checklistItems(t: JSONObject): JSONArray = t.optJSONArray("checklist") ?: JSONArray()

    private fun checklistSet(state: SyncState, cmd: SyncCommand, items: JSONArray): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(cmd.aggregateId))
        if (receipt != null) return Result(state, receipt)
        val next = copy(entity!!)
        next.put("checklist", items)
        val tasks = copy(state.tasks)
        tasks.put(cmd.aggregateId, next)
        return Result(state.copy(tasks = tasks), ReducerReceipt("APPLIED"))
    }

    private fun checklistItemIndex(items: JSONArray, itemId: String): Int {
        for (i in 0 until items.length()) {
            if (items.getJSONObject(i).optString("id") == itemId) return i
        }
        return -1
    }

    private fun checklistCreateItem(state: SyncState, cmd: SyncCommand): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(cmd.aggregateId))
        if (receipt != null) return Result(state, receipt)
        val itemId = cmd.arguments.optString("checklistItemId", "")
        if (itemId.isEmpty()) return Result(state, rejected("MISSING_CHECKLIST_ITEM_ID"))
        val items = checklistItems(entity!!)
        if (checklistItemIndex(items, itemId) >= 0) return Result(state, noop())
        val item = JSONObject().apply {
            put("id", itemId)
            put("title", cmd.arguments.optString("title", ""))
            put("completed", false)
        }
        items.put(item)
        return checklistSet(state, cmd, items)
    }

    private fun checklistSetTitle(state: SyncState, cmd: SyncCommand): Result {
        val title = cmd.arguments.optString("title", "")
        return checklistUpdateItem(state, cmd) { item, _ ->
            if (item.optString("title") == title) item else copy(item).apply { put("title", title) }
        }
    }

    private fun checklistSetCompleted(state: SyncState, cmd: SyncCommand): Result {
        val completed = cmd.arguments.optBoolean("completed", false)
        return checklistUpdateItem(state, cmd) { item, _ ->
            if (item.optBoolean("completed", false) == completed) item else copy(item).apply { put("completed", completed) }
        }
    }

    private fun checklistDeleteItem(state: SyncState, cmd: SyncCommand): Result {
        return checklistUpdateItem(state, cmd) { _, items ->
            val next = JSONArray()
            val target = cmd.arguments.optString("checklistItemId", "")
            for (i in 0 until items.length()) {
                if (items.getJSONObject(i).optString("id") != target) next.put(items.getJSONObject(i))
            }
            next
        }
    }

    private fun checklistReorderItem(state: SyncState, cmd: SyncCommand): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(cmd.aggregateId))
        if (receipt != null) return Result(state, receipt)
        val items = checklistItems(entity!!)
        val itemId = cmd.arguments.optString("checklistItemId", "")
        val beforeId: String? = if (cmd.arguments.isNull("beforeItemId")) null else cmd.arguments.optString("beforeItemId", null)
        val from = checklistItemIndex(items, itemId)
        if (from < 0) return Result(state, noop())
        val moved = items.getJSONObject(from)
        items.remove(from)
        val to = if (beforeId == null) items.length() else checklistItemIndex(items, beforeId)
        if (to < 0) {
            items.put(from, moved) // 恢复原位,避免破坏状态
            return Result(state, noop())
        }
        items.put(to, moved)
        return checklistSet(state, cmd, items)
    }

    private fun checklistUpdateItem(
        state: SyncState,
        cmd: SyncCommand,
        updater: (JSONObject, JSONArray) -> Any?,
    ): Result {
        val (entity, receipt) = findActive(state.tasks.optJSONObject(cmd.aggregateId))
        if (receipt != null) return Result(state, receipt)
        val items = checklistItems(entity!!)
        val idx = checklistItemIndex(items, cmd.arguments.optString("checklistItemId", ""))
        if (idx < 0) return Result(state, noop())
        val updated = updater(items.getJSONObject(idx), items)
        val item: JSONObject? = items.getJSONObject(idx)
        if (updated === item) return Result(state, noop())
        val nextItems = if (updated is JSONArray) updated else JSONArray().apply {
            for (i in 0 until items.length()) {
                put(if (i == idx) updated as JSONObject else items.getJSONObject(i))
            }
        }
        return checklistSet(state, cmd, nextItems)
    }

    // ── list ──────────────────────────────────────────────────────────────

    private fun listCreate(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("MISSING_AGGREGATE_ID"))
        if (state.customLists.has(id)) return Result(state, ReducerReceipt("ID_ALREADY_EXISTS", "ID_ALREADY_EXISTS"))
        val list = JSONObject().apply {
            put("title", cmd.arguments.optString("title", ""))
            put("color", if (cmd.arguments.isNull("color")) JSONObject.NULL else cmd.arguments.optString("color"))
            put("lifecycle", "active")
            put("deletedAt", JSONObject.NULL)
        }
        return Result(state.copy(customLists = copy(state.customLists).apply { put(id, list) }), ReducerReceipt("APPLIED"))
    }

    private fun listRename(state: SyncState, cmd: SyncCommand): Result {
        val title = cmd.arguments.optString("title", "")
        return updateInMap(state, "customLists", cmd.aggregateId ?: "") { l ->
            if (l.optString("title") == title) l else copy(l).apply { put("title", title) }
        }
    }

    private fun listSetColor(state: SyncState, cmd: SyncCommand): Result {
        val color: String? = if (cmd.arguments.isNull("color")) null else cmd.arguments.optString("color")
        return updateInMap(state, "customLists", cmd.aggregateId ?: "") { l ->
            if (l.opt("color") == null && color == null) l
            else if (l.optString("color", null) == color) l
            else copy(l).apply { put("color", color ?: JSONObject.NULL) }
        }
    }

    private fun listDelete(state: SyncState, cmd: SyncCommand, seq: Long): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("ENTITY_NOT_FOUND"))
        val before = deleteFromMap(state, "customLists", id, seq)
        if (before.receipt.status != "APPLIED") return before
        val tasks = copy(before.state.tasks)
        before.state.tasks.keys().forEach { taskId ->
            val t = tasks.getJSONObject(taskId)
            if (t.optString("listId", null) == id) {
                val next = copy(t)
                next.remove("listId")
                tasks.put(taskId, next)
            }
        }
        return Result(before.state.copy(tasks = tasks), before.receipt)
    }

    // ── journal ───────────────────────────────────────────────────────────

    private fun journalSetText(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("MISSING_AGGREGATE_ID"))
        val text = cmd.arguments.optString("text", "")
        val existing = state.journals.optJSONObject(id)
        if (existing == null) {
            val journal = JSONObject().apply {
                put("text", text)
                put("lifecycle", "active")
                put("deletedAt", JSONObject.NULL)
            }
            return Result(state.copy(journals = copy(state.journals).apply { put(id, journal) }), ReducerReceipt("APPLIED"))
        }
        return updateInMap(state, "journals", id) { j ->
            if (j.optString("text") == text) j else copy(j).apply { put("text", text) }
        }
    }

    private fun journalDelete(state: SyncState, cmd: SyncCommand, seq: Long): Result =
        deleteFromMap(state, "journals", cmd.aggregateId ?: "", seq)

    // ── goal ──────────────────────────────────────────────────────────────

    private fun goalCreate(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("MISSING_AGGREGATE_ID"))
        if (state.goals.has(id)) return Result(state, ReducerReceipt("ID_ALREADY_EXISTS", "ID_ALREADY_EXISTS"))
        val goal = JSONObject().apply {
            put("title", cmd.arguments.optString("title", ""))
            put("lifecycle", "active")
            put("deletedAt", JSONObject.NULL)
        }
        return Result(state.copy(goals = copy(state.goals).apply { put(id, goal) }), ReducerReceipt("APPLIED"))
    }

    private fun goalPatch(state: SyncState, cmd: SyncCommand): Result {
        val patch = cmd.arguments.optJSONObject("patch") ?: return Result(state, rejected("INVALID_PATCH"))
        return updateInMap(state, "goals", cmd.aggregateId ?: "") { g ->
            val next = copy(g)
            patch.keys().forEach { key ->
                if (key != "lifecycle" && key != "deletedAt") next.put(key, patch.get(key))
            }
            if (next.toString() == g.toString()) g else next
        }
    }

    private fun goalDelete(state: SyncState, cmd: SyncCommand, seq: Long): Result =
        deleteFromMap(state, "goals", cmd.aggregateId ?: "", seq)

    // ── insight ───────────────────────────────────────────────────────────

    private fun insightUpsert(state: SyncState, cmd: SyncCommand): Result {
        val id = cmd.aggregateId ?: return Result(state, rejected("MISSING_AGGREGATE_ID"))
        val existing = state.insights.optJSONObject(id)
        if (existing?.optString("lifecycle") == "deleted") {
            return Result(state, ReducerReceipt("ENTITY_DELETED", "ENTITY_DELETED"))
        }
        val payload = cmd.arguments.optJSONObject("payload") ?: return Result(state, rejected("INVALID_PAYLOAD"))
        val entity = JSONObject()
        payload.keys().forEach { key ->
            if (key != "lifecycle" && key != "deletedAt") entity.put(key, payload.get(key))
        }
        entity.put("lifecycle", "active")
        entity.put("deletedAt", JSONObject.NULL)
        if (existing != null && existing.toString() == entity.toString()) return Result(state, noop())
        return Result(state.copy(insights = copy(state.insights).apply { put(id, entity) }), ReducerReceipt("APPLIED"))
    }

    private fun insightDelete(state: SyncState, cmd: SyncCommand, seq: Long): Result =
        deleteFromMap(state, "insights", cmd.aggregateId ?: "", seq)
}
