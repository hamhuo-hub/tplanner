package com.hamhuo.tplanner

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecurringTaskFactoryTest {
    @Test
    fun dailyRepeatCreatesIndependentTaskInstances() {
        val source = ScheduleItem(
            id = "source-task",
            title = "Read",
            type = "task",
            start = Instant.parse("2026-08-19T01:00:00Z"),
            end = Instant.parse("2026-08-19T02:00:00Z"),
            completed = false,
            checklist = emptyList(),
            colorId = 0,
            note = "",
            deletedAt = 0L,
            extras = mapOf(
                "recurrenceType" to "daily",
                "recurrenceCount" to 3,
                "groupId" to "legacy-series",
            ),
        )

        val instances = createRecurringTaskInstances(source)

        assertEquals(3, instances.size)
        assertEquals(Instant.parse("2026-08-20T01:00:00Z"), instances[1].start)
        assertEquals(Instant.parse("2026-08-21T01:00:00Z"), instances[2].start)
        assertNotEquals(instances[0].id, instances[1].id)
        assertNotEquals(instances[1].id, instances[2].id)
        assertFalse(instances.any { "groupId" in it.extras })
    }
}
