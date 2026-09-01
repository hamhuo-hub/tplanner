package com.hamhuo.tplanner.syncv3

import com.hamhuo.tplanner.CheckItem
import com.hamhuo.tplanner.ScheduleItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncV3ProductionContractTest {
    @Test
    fun `phone commands round trip every durable task field through fresh phone projection`() {
        val source = task(
            checklist = listOf(
                CheckItem("check_a", "准备材料", false),
                CheckItem("check_b", "发送确认", true),
            ),
        )
        val drafts = buildList {
            add(SyncV3CommandPlanner.listCreate("work_list", "工作"))
            add(SyncV3CommandPlanner.listRename("work_list", "工作"))
            addAll(SyncV3CommandPlanner.fullTaskUpsert(source))
        }

        val serverShape = reduce(drafts)
        SyncV3ProjectionCodec.validateAuthoritativeState(serverShape)
        val freshPhone = SyncV3ProjectionCodec.project(serverShape).events.single()

        assertEquals(source.id, freshPhone.id)
        assertEquals(source.title, freshPhone.title)
        assertEquals(source.type, freshPhone.type)
        assertEquals(source.start, freshPhone.start)
        assertEquals(source.end, freshPhone.end)
        assertEquals(source.completed, freshPhone.completed)
        assertEquals(source.checklist, freshPhone.checklist)
        assertEquals(source.colorId, freshPhone.colorId)
        assertEquals(source.note, freshPhone.note)
        assertEquals(source.alarmEnabled, freshPhone.alarmEnabled)
        assertEquals(source.alarmOffsetMinutes, freshPhone.alarmOffsetMinutes)
        assertEquals(source.lat, freshPhone.lat, 0.0)
        assertEquals(source.lng, freshPhone.lng, 0.0)
        assertEquals(source.listId, freshPhone.listId)
        assertEquals("weekly", freshPhone.extras["recurrenceType"])
        assertEquals(10, freshPhone.extras["recurrenceCount"])
        assertEquals("Asia/Shanghai", freshPhone.extras["timezone"])
        assertEquals(4, freshPhone.extras["priority"])
        assertEquals("工作", SyncV3ProjectionCodec.project(serverShape).lists.single().name)

        val wireItems = serverShape.getJSONObject("tasks")
            .getJSONObject(source.id)
            .getJSONArray("checklist")
        for (index in 0 until wireItems.length()) {
            assertTrue(wireItems.getJSONObject(index).has("title"))
            assertFalse(wireItems.getJSONObject(index).has("text"))
        }
    }

    @Test
    fun `list create and assignment arrive as entity plus resolvable reference`() {
        val state = reduce(
            listOf(SyncV3CommandPlanner.listCreate("work_list", "工作")) +
                SyncV3CommandPlanner.fullTaskUpsert(task()),
        )
        val projection = SyncV3ProjectionCodec.project(state)

        assertEquals("工作", projection.lists.single { it.id == "work_list" }.name)
        assertEquals("work_list", projection.events.single().listId)
    }

    @Test
    fun `future recurrence fields survive phone projection and unrelated edits`() {
        val state = reduce(
            listOf(SyncV3CommandPlanner.listCreate("work_list", "工作")) +
                SyncV3CommandPlanner.fullTaskUpsert(task()),
        )
        state.getJSONObject("tasks").getJSONObject("task_1")
            .getJSONObject("recurrence")
            .put("futureRule", JSONObject().put("calendar", "lunar"))
        state.getJSONObject("tasks").getJSONObject("task_1")
            .getJSONObject("schedule")
            .put("futureZone", "floating")
        state.getJSONObject("tasks").getJSONObject("task_1")
            .getJSONObject("alarm")
            .put("futureSound", JSONObject().put("name", "chime"))
        state.getJSONObject("tasks").getJSONObject("task_1")
            .getJSONObject("location")
            .put("futureAccuracy", 3)

        val phone = SyncV3ProjectionCodec.project(state).events.single()
        val preserved = phone.extras[SYNC_V3_RECURRENCE_WIRE_EXTRA] as JSONObject
        assertEquals("lunar", preserved.getJSONObject("futureRule").getString("calendar"))

        val titleOnly = SyncV3CommandPlanner.taskChange(phone, phone.copy(title = "新标题"))
        assertFalse(titleOnly.any { it.type == SyncCommandType.TASK_SET_RECURRENCE })

        val bootstrapRecurrence = SyncV3CommandPlanner.fullTaskUpsert(phone)
            .single { it.type == SyncCommandType.TASK_SET_RECURRENCE }
            .arguments.getJSONObject("recurrence")
        assertEquals(
            "lunar",
            bootstrapRecurrence.getJSONObject("futureRule").getString("calendar"),
        )
        val bootstrap = SyncV3CommandPlanner.fullTaskUpsert(phone)
        assertEquals(
            "floating",
            bootstrap.single { it.type == SyncCommandType.TASK_SET_SCHEDULE }
                .arguments.getJSONObject("schedule").getString("futureZone"),
        )
        assertEquals(
            "chime",
            bootstrap.single { it.type == SyncCommandType.TASK_SET_ALARM }
                .arguments.getJSONObject("futureSound").getString("name"),
        )
        assertEquals(
            3,
            bootstrap.single { it.type == SyncCommandType.TASK_SET_LOCATION }
                .arguments.getInt("futureAccuracy"),
        )

        val cleared = phone.copy(
            extras = phone.extras - setOf("recurrenceType", "recurrenceCount"),
        )
        val clearCommand = SyncV3CommandPlanner.taskChange(phone, cleared)
            .single { it.type == SyncCommandType.TASK_SET_RECURRENCE }
        assertTrue(clearCommand.arguments.isNull("recurrence"))
    }

    @Test
    fun `null central schedule remains null through a phone bootstrap`() {
        val state = reduce(
            listOf(SyncV3CommandPlanner.listCreate("work_list", "工作")) +
                SyncV3CommandPlanner.fullTaskUpsert(task()),
        )
        state.getJSONObject("tasks").getJSONObject("task_1").put("schedule", JSONObject.NULL)

        val phone = SyncV3ProjectionCodec.project(state).events.single()
        assertEquals(Instant.EPOCH, phone.start)
        assertEquals(Instant.EPOCH, phone.end)
        val schedule = SyncV3CommandPlanner.fullTaskUpsert(phone)
            .single { it.type == SyncCommandType.TASK_SET_SCHEDULE }
            .arguments
        assertTrue(schedule.isNull("schedule"))
    }

    @Test
    fun `checklist CRUD and reorder use canonical title`() {
        val before = task(
            checklist = listOf(
                CheckItem("check_a", "A", false),
                CheckItem("check_b", "B", false),
            ),
        )
        val after = before.copy(
            checklist = listOf(
                CheckItem("check_c", "C", true),
                CheckItem("check_b", "B renamed", true),
            ),
        )
        val state = reduce(
            listOf(SyncV3CommandPlanner.listCreate("work_list", "工作")) +
                SyncV3CommandPlanner.fullTaskUpsert(before) +
                SyncV3CommandPlanner.taskChange(before, after),
        )
        val items = state.getJSONObject("tasks").getJSONObject(before.id).getJSONArray("checklist")

        assertEquals(listOf("check_c", "check_b"), (0 until items.length()).map {
            items.getJSONObject(it).getString("id")
        })
        assertEquals("C", items.getJSONObject(0).getString("title"))
        assertEquals("B renamed", items.getJSONObject(1).getString("title"))
        assertTrue(items.getJSONObject(0).getBoolean("completed"))
        assertTrue(items.getJSONObject(1).getBoolean("completed"))
        assertFalse(items.getJSONObject(0).has("text"))
    }

    @Test
    fun `offline delete remains deleted while stale central snapshot is replayed`() {
        val central = reduce(
            listOf(SyncV3CommandPlanner.listCreate("work_list", "工作")) +
                SyncV3CommandPlanner.fullTaskUpsert(task()),
        )
        val pendingDelete = commandEntity(
            sequence = 101,
            draft = SyncV3CommandPlanner.taskChange(
                task(),
                task().copy(deletedAt = 123L),
            ).single(),
        )

        repeat(2) {
            val displayed = SyncV3ProjectionCodec.replay(JSONObject(central.toString()), listOf(pendingDelete))
            val projected = SyncV3ProjectionCodec.project(displayed).events.single()
            assertTrue("stale remote task must not resurrect", projected.deletedAt != 0L)
        }
    }

    @Test
    fun `bootstrap over 200 commands owns three distinct retry-stable batch ids`() {
        var next = 0
        val chunks = SyncV3BatchPartitioner.partition(
            commands = (1..251).toList(),
            batchId = { "batch-${++next}" },
        )

        assertEquals(listOf(100, 100, 51), chunks.map { it.commands.size })
        assertEquals(3, chunks.map { it.batchId }.distinct().size)
        assertEquals(listOf("batch-1", "batch-2", "batch-3"), chunks.map { it.batchId })
    }

    private fun reduce(drafts: List<SyncCommandDraft>): JSONObject {
        var state = LocalReducer.emptyState()
        drafts.forEachIndexed { index, draft ->
            val result = LocalReducer.apply(
                state,
                SyncCommand(
                    commandId = "command-$index",
                    clientSequence = index + 1L,
                    type = draft.type,
                    aggregateId = draft.aggregateId,
                    arguments = JSONObject(draft.arguments.toString()),
                ),
                brokerSequence = index + 1L,
            )
            assertFalse(
                "${draft.type.wire} unexpectedly rejected: ${result.receipt.errorCode}",
                result.receipt.status == "REJECTED" || result.receipt.status == "ENTITY_DELETED",
            )
            state = result.state
        }
        return LocalReducer.toJson(state)
    }

    private fun commandEntity(sequence: Long, draft: SyncCommandDraft) = SyncCommandEntity(
        commandId = "0198f2a1-0000-7000-8000-${sequence.toString().padStart(12, '0')}",
        batchId = "0198f2a1-0000-7000-8000-000000000001",
        clientSequence = sequence,
        commandType = draft.type.wire,
        aggregateId = draft.aggregateId,
        argumentsJson = draft.arguments.toString(),
        state = "pending",
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastErrorCode = null,
    )

    private fun task(checklist: List<CheckItem> = listOf(CheckItem("check_a", "准备材料", false))) =
        ScheduleItem(
            id = "task_1",
            title = "周会",
            type = "task",
            start = Instant.parse("2026-09-01T01:00:00.000Z"),
            end = Instant.parse("2026-09-01T02:00:00.000Z"),
            completed = true,
            checklist = checklist,
            colorId = 5,
            note = "逐字段保持",
            deletedAt = 0L,
            updatedAt = 999L,
            alarmEnabled = true,
            alarmOffsetMinutes = 12_345,
            lat = 31.2304,
            lng = 121.4737,
            listId = "work_list",
            extras = mapOf(
                "timezone" to "Asia/Shanghai",
                "priority" to 4,
                "recurrenceType" to "weekly",
                "recurrenceCount" to 10,
            ),
        )
}
