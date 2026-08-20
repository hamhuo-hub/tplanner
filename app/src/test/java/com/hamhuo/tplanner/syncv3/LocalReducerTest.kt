package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 契约测试:与服务器 / 桌面 reducer 共用同一份 sequence-01 fixture,
 * 重放后状态与回执必须逐键一致 —— 三端 canonical 状态一致性的直接证明。
 * fixture 副本位于 src/test/resources/syncv3/,与仓库根 sync-v3/ 由 CI 校验同源。
 */
class LocalReducerTest {

    private fun resource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("syncv3/$name")
            ?: throw IllegalStateException("missing fixture $name")
        return stream.readBytes().toString(StandardCharsets.UTF_8)
    }

    private fun commandFrom(json: JSONObject): SyncCommand {
        val typeWire = json.getString("type")
        val type = SyncCommandType.entries.first { it.wire == typeWire }
        return SyncCommand(
            commandId = json.getString("commandId"),
            clientSequence = json.getLong("clientSequence"),
            type = type,
            aggregateId = if (json.isNull("aggregateId")) null else json.optString("aggregateId"),
            arguments = json.optJSONObject("arguments") ?: JSONObject(),
        )
    }

    @Test
    fun `replays sequence-01 to the exact expected state and receipts`() {
        val input = JSONObject(resource("sequence-01.input.json"))
        val expectedState = JSONObject(resource("sequence-01.expected-state.json"))
        val expectedReceipts = JSONObject(resource("sequence-01.expected-receipts.json"))

        var state = LocalReducer.emptyState()
        val commands = input.getJSONArray("commands")
        val receipts = mutableListOf<JSONObject>()
        for (i in 0 until commands.length()) {
            val entry = commands.getJSONObject(i)
            val brokerSequence = entry.getLong("brokerSequence")
            val result = LocalReducer.apply(state, commandFrom(entry.getJSONObject("command")), brokerSequence)
            state = result.state
            receipts.add(JSONObject().apply {
                put("brokerSequence", brokerSequence)
                put("status", result.receipt.status)
            })
        }

        assertEquals("tasks 状态不一致", expectedState.getJSONObject("tasks").toString(), state.tasks.toString())
        assertEquals("customLists 状态不一致", expectedState.getJSONObject("customLists").toString(), state.customLists.toString())
        assertEquals("journals 状态不一致", expectedState.getJSONObject("journals").toString(), state.journals.toString())
        assertEquals("goals 状态不一致", expectedState.getJSONObject("goals").toString(), state.goals.toString())
        assertEquals("insights 状态不一致", expectedState.getJSONObject("insights").toString(), state.insights.toString())

        val expectedArray = expectedReceipts.getJSONArray("receipts")
        assertEquals("回执条数不一致", expectedArray.length(), receipts.size)
        for (i in 0 until expectedArray.length()) {
            assertEquals("第 $i 条回执", expectedArray.getJSONObject(i).toString(), receipts[i].toString())
        }
    }

    @Test
    fun `pending overlay keeps a local edit visible over the mirror`() {
        var state = LocalReducer.emptyState()
        val create = SyncCommand(
            commandId = "c1",
            clientSequence = 1,
            type = SyncCommandType.TASK_CREATE,
            aggregateId = "t-local",
            arguments = JSONObject().put("title", "离线创建"),
        )
        state = LocalReducer.apply(state, create, 1).state
        assertEquals("离线创建", state.tasks.getJSONObject("t-local").getString("title"))
        assertEquals("active", state.tasks.getJSONObject("t-local").getString("lifecycle"))
    }

    @Test
    fun `delete then stale edit stays rejected, only restore revives`() {
        var state = LocalReducer.emptyState()
        fun cmd(id: String, type: SyncCommandType, args: JSONObject = JSONObject(), seq: Long = 1): SyncCommand =
            SyncCommand("c-$seq", seq, type, id, args)

        state = LocalReducer.apply(state, cmd("t1", SyncCommandType.TASK_CREATE, JSONObject().put("title", "x"), 1), 1).state
        state = LocalReducer.apply(state, cmd("t1", SyncCommandType.TASK_DELETE, seq = 2), 2).state
        assertEquals("deleted", state.tasks.getJSONObject("t1").getString("lifecycle"))
        assertEquals(2L, state.tasks.getJSONObject("t1").getLong("deletedAt"))

        val stale = LocalReducer.apply(state, cmd("t1", SyncCommandType.TASK_SET_NOTE, JSONObject().put("note", "n"), 3), 3)
        assertEquals("ENTITY_DELETED", stale.receipt.status)
        assertEquals(state.tasks.toString(), stale.state.tasks.toString())

        val restored = LocalReducer.apply(state, cmd("t1", SyncCommandType.TASK_RESTORE, seq = 4), 4)
        assertEquals("APPLIED", restored.receipt.status)
        assertEquals("active", restored.state.tasks.getJSONObject("t1").getString("lifecycle"))
        assertTrue("deletedAt 应为 null", restored.state.tasks.getJSONObject("t1").isNull("deletedAt"))
    }

    @Test
    fun `same value writes are NOOP`() {
        var state = LocalReducer.emptyState()
        val create = SyncCommand("c1", 1, SyncCommandType.TASK_CREATE, "t1", JSONObject().put("title", "x"))
        state = LocalReducer.apply(state, create, 1).state
        val same = LocalReducer.apply(
            state,
            SyncCommand("c2", 2, SyncCommandType.TASK_SET_TITLE, "t1", JSONObject().put("title", "x")),
            2,
        )
        assertEquals("NOOP", same.receipt.status)
        assertEquals(state.tasks.toString(), same.state.tasks.toString())
    }
}
