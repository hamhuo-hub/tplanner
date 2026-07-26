package com.hamhuo.tplanner

import com.hamhuo.tplanner.timeline.TimelineLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineLayoutEngineTest {
    private val dayStart: Instant = Instant.parse("2026-07-26T00:00:00Z")
    private val dayEnd: Instant = Instant.parse("2026-07-27T00:00:00Z")

    @Test
    fun sameTypeOverlapGetsLanesAndDistinctConflictIds() {
        val first = event("a", "event", "09:00", "10:00")
        val second = event("b", "event", "09:30", "10:30")

        val placements = TimelineLayoutEngine.layoutDay(listOf(first, second), dayStart, dayEnd)
            .associateBy { it.event.id }

        assertEquals(2, placements.getValue("a").laneCount)
        assertEquals(2, placements.getValue("b").laneCount)
        assertEquals(setOf("b"), placements.getValue("a").conflictIds)
        assertEquals(setOf("a"), placements.getValue("b").conflictIds)
        assertTrue(placements.getValue("a").laneIndex != placements.getValue("b").laneIndex)
    }

    @Test
    fun touchingEndpointsDoNotConflictOrShareACluster() {
        val first = event("a", "task", "09:00", "10:00")
        val second = event("b", "task", "10:00", "11:00")

        val placements = TimelineLayoutEngine.layoutDay(listOf(first, second), dayStart, dayEnd)

        assertTrue(placements.all { it.laneCount == 1 })
        assertTrue(placements.all { it.conflictIds.isEmpty() })
    }

    @Test
    fun mixedTypesStackVisuallyButDoNotShowConflictBadges() {
        val reminder = event("event", "event", "09:00", "10:00")
        val task = event("task", "task", "09:15", "10:15")

        val placements = TimelineLayoutEngine.layoutDay(listOf(reminder, task), dayStart, dayEnd)

        assertTrue(placements.all { it.laneCount == 2 })
        assertTrue(placements.all { it.conflictIds.isEmpty() })
    }

    @Test
    fun statusIsExcludedAndCompletedTaskIsABackgroundShadow() {
        val status = event("status", "status", "09:00", "12:00")
        val completed = event("done", "task", "09:00", "10:00", completed = true)
        val active = event("active", "task", "09:15", "10:15")

        val placements = TimelineLayoutEngine.layoutDay(
            listOf(status, completed, active),
            dayStart,
            dayEnd,
        )

        assertFalse(placements.any { it.event.id == "status" })
        val shadow = placements.single { it.event.id == "done" }
        assertTrue(shadow.isShadow)
        assertEquals(2, shadow.laneCount)
        assertTrue(shadow.conflictIds.isEmpty())
        val activePlacement = placements.single { it.event.id == "active" }
        assertEquals(2, activePlacement.laneCount)
        assertTrue(activePlacement.conflictIds.isEmpty())
        assertTrue(shadow.laneIndex != activePlacement.laneIndex)
    }

    @Test
    fun crossDayEventIsClippedWithoutMutatingOriginalEvent() {
        val event = TaskEvent(
            id = "cross-day",
            title = "Cross day",
            type = "event",
            start = Instant.parse("2026-07-25T23:30:00Z"),
            end = Instant.parse("2026-07-26T01:30:00Z"),
            completed = false,
            checklist = emptyList(),
            colorId = 0,
            note = "",
            deletedAt = 0L,
        )

        val placement = TimelineLayoutEngine.layoutDay(listOf(event), dayStart, dayEnd).single()

        assertEquals(dayStart, placement.visibleStart)
        assertEquals(Instant.parse("2026-07-26T01:30:00Z"), placement.visibleEnd)
        assertEquals(Instant.parse("2026-07-25T23:30:00Z"), placement.event.start)
    }

    @Test
    fun chainOverlapCountsOnlyDirectPeers() {
        val first = event("a", "event", "09:00", "10:00")
        val middle = event("b", "event", "09:30", "10:30")
        val last = event("c", "event", "10:00", "11:00")

        val placements = TimelineLayoutEngine.layoutDay(listOf(first, middle, last), dayStart, dayEnd)
            .associateBy { it.event.id }

        assertEquals(setOf("b"), placements.getValue("a").conflictIds)
        assertEquals(setOf("a", "c"), placements.getValue("b").conflictIds)
        assertEquals(setOf("b"), placements.getValue("c").conflictIds)
        assertEquals(2, placements.getValue("b").laneCount)
    }

    private fun event(
        id: String,
        type: String,
        startTime: String,
        endTime: String,
        completed: Boolean = false,
    ): TaskEvent = TaskEvent(
        id = id,
        title = id,
        type = type,
        start = Instant.parse("2026-07-26T${startTime}:00Z"),
        end = Instant.parse("2026-07-26T${endTime}:00Z"),
        completed = completed,
        checklist = emptyList(),
        colorId = 0,
        note = "",
        deletedAt = 0L,
    )
}
