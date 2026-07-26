package com.hamhuo.tplanner

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EventUpdatesTest {
    @Test
    fun `replacing an event preserves its position`() {
        val original = listOf(event("first"), event("edited"), event("last"))
        val updated = original[1].copy(title = "Updated title", note = "Updated note")

        val result = upsertEventPreservingOrder(original, updated)

        assertEquals(listOf("first", "edited", "last"), result.map { it.id })
        assertEquals("Updated title", result[1].title)
        assertEquals("Updated note", result[1].note)
    }

    @Test
    fun `a new event is appended`() {
        val original = listOf(event("first"), event("second"))

        val result = upsertEventPreservingOrder(original, event("new"))

        assertEquals(listOf("first", "second", "new"), result.map { it.id })
    }

    private fun event(id: String) = TaskEvent(
        id = id,
        title = id,
        type = "task",
        start = Instant.parse("2026-07-26T00:00:00Z"),
        end = Instant.parse("2026-07-26T01:00:00Z"),
        completed = false,
        checklist = emptyList(),
        colorId = 0,
        note = "",
        deletedAt = 0L,
    )
}
