package com.hamhuo.tplanner

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskViewTest {
    @Test
    fun filtersNeverBecomePersistedListMembership() {
        assertEquals("", TaskView.Inbox.listIdForNewItem())
        assertEquals("", TaskView.Today.listIdForNewItem())
        assertEquals("list-1", TaskView.CustomList("list-1", "Work").listIdForNewItem())
    }

    @Test
    fun eachViewFiltersTheSharedDatasetWithoutCrossListLeakage() {
        val date = LocalDate.of(2026, 8, 19)
        val items = listOf(
            item("unclassified-today", "2026-08-19T01:00:00Z"),
            item("list-a-today", "2026-08-19T02:00:00Z", listId = "list-a"),
            item("list-a-old", "2026-08-18T02:00:00Z", listId = "list-a"),
            item("list-b-old", "2026-08-18T03:00:00Z", listId = "list-b"),
            item("deleted-today", "2026-08-19T04:00:00Z", deletedAt = 1L),
        )

        assertEquals(
            setOf("unclassified-today", "list-a-today", "list-a-old", "list-b-old"),
            TaskView.Inbox.filter(items, date).map { it.id }.toSet(),
        )
        assertEquals(
            setOf("unclassified-today", "list-a-today"),
            TaskView.Today.filter(items, date).map { it.id }.toSet(),
        )
        assertEquals(
            setOf("list-a-today", "list-a-old"),
            TaskView.CustomList("list-a", "List A").filter(items, date).map { it.id }.toSet(),
        )
    }

    @Test
    fun missingCustomListFallsBackToInboxFilter() {
        assertEquals(TaskView.Inbox, TaskView.fromKey("missing", emptyList()))
    }

    private fun item(
        id: String,
        start: String,
        listId: String = "",
        deletedAt: Long = 0L,
    ): ScheduleItem {
        val startInstant = Instant.parse(start)
        return ScheduleItem(
            id = id,
            title = id,
            type = "task",
            start = startInstant,
            end = startInstant.plusSeconds(3_600),
            completed = false,
            checklist = emptyList(),
            colorId = 0,
            note = "",
            deletedAt = deletedAt,
            listId = listId,
        )
    }
}
