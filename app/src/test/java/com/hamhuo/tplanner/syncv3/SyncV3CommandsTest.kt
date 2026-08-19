package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

/** 纯 JVM 测试:批次序列化与回执解析不依赖 Android(契约与协议 fixtures 对齐)。 */
class SyncV3CommandsTest {

    private fun uuidV7(): String {
        val ts = System.currentTimeMillis() and 0xFFFFFFFFFFFFL
        val randA = UUID.randomUUID().mostSignificantBits and 0xFFFL
        val msb = (ts shl 16) or (0x7L shl 12) or randA
        val lsbRaw = UUID.randomUUID().leastSignificantBits
        val lsb = (lsbRaw and 0x3FFFFFFFFFFFFFFFL) or (0x8L shl 62) // variant 10

        fun h8(v: Long) = "%08x".format(v and 0xFFFFFFFFL)
        fun h4(v: Long) = "%04x".format(v and 0xFFFFL)
        return "${h8(msb shr 32)}-${h4(msb shr 16)}-${h4(msb)}" +
            "-${h4(lsb shr 48)}-${h4(lsb shr 32)}-${h8(lsb)}"
    }

    @Test
    fun `batch wire format matches protocol v3`() {
        val batch = SyncCommandBatch(
            batchId = uuidV7(),
            deviceId = "phone-test",
            commands = listOf(
                SyncCommand(
                    commandId = uuidV7(),
                    clientSequence = 1,
                    type = SyncCommandType.TASK_SET_COMPLETED,
                    aggregateId = "task-123",
                    arguments = JSONObject().put("completed", true),
                ),
                SyncCommand(
                    commandId = uuidV7(),
                    clientSequence = 2,
                    type = SyncCommandType.TASK_SET_TITLE,
                    aggregateId = "task-456",
                    arguments = JSONObject().put("title", "开会"),
                ),
            ),
        )

        val wire = batch.toWire()
        assertEquals(3, wire.getInt("protocolVersion"))
        assertEquals("phone-test", wire.getString("deviceId"))
        assertEquals(1L, wire.getLong("firstClientSequence"))
        assertEquals(2L, wire.getLong("lastClientSequence"))

        val commands = wire.getJSONArray("commands")
        assertEquals(2, commands.length())
        val first = commands.getJSONObject(0)
        assertEquals("task.setCompleted", first.getString("type"))
        assertTrue(first.getJSONObject("arguments").getBoolean("completed"))
        // UUIDv7 特征:第 3 组以 7 开头
        assertTrue(first.getString("commandId").split("-")[2].startsWith("7"))
    }

    @Test
    fun `receipt parsing round-trips status and optional fields`() {
        val applied = SyncReceipt.fromWire(
            JSONObject()
                .put("commandId", "c1")
                .put("clientSequence", 1L)
                .put("status", "APPLIED")
                .put("snapshotVersion", 813L),
        )
        assertEquals("APPLIED", applied.status)
        assertEquals(813L, applied.snapshotVersion)
        assertEquals(null, applied.errorCode)

        val deleted = SyncReceipt.fromWire(
            JSONObject()
                .put("commandId", "c2")
                .put("clientSequence", 2L)
                .put("status", "ENTITY_DELETED")
                .put("errorCode", "ENTITY_DELETED"),
        )
        assertEquals("ENTITY_DELETED", deleted.status)
        assertEquals(null, deleted.snapshotVersion)
        assertEquals("ENTITY_DELETED", deleted.errorCode)
    }

    @Test
    fun `all command types carry the wire names from the protocol`() {
        assertEquals("task.create", SyncCommandType.TASK_CREATE.wire)
        assertEquals("checklist.reorderItem", SyncCommandType.CHECKLIST_REORDER_ITEM.wire)
        assertEquals("journal.setText", SyncCommandType.JOURNAL_SET_TEXT.wire)
        assertEquals("insight.upsert", SyncCommandType.INSIGHT_UPSERT.wire)
    }
}
