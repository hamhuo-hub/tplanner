package com.hamhuo.tplanner

import androidx.compose.ui.geometry.Offset
import com.hamhuo.tplanner.timeline.calculateTimelineSnappedMove
import com.hamhuo.tplanner.timeline.timelineWallClockMinutes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimelineDragSnapTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val visibleDays = listOf(
        LocalDate.of(2026, 7, 25),
        LocalDate.of(2026, 7, 26),
        LocalDate.of(2026, 7, 27),
    )

    @Test
    fun wallClockPositionStaysAtNineOnSpringForwardDay() {
        val newYork = ZoneId.of("America/New_York")
        val springForwardDay = LocalDate.of(2026, 3, 8)
        val nineAm = Instant.parse("2026-03-08T13:00:00Z")

        assertEquals(
            9f * 60f,
            timelineWallClockMinutes(nineAm, springForwardDay, newYork),
        )
    }

    @Test
    fun zeroMovementDoesNotRoundAnExistingTime() {
        val event = event(
            start = Instant.parse("2026-07-26T01:05:37.250Z"),
            end = Instant.parse("2026-07-26T02:05:37.250Z"),
        )

        val move = calculateTimelineSnappedMove(
            event = event,
            segmentDay = visibleDays[1],
            segmentDayIndex = 1,
            visibleDays = visibleDays,
            dragOffset = Offset.Zero,
            dayWidthPx = 100f,
            pixelsPerMinute = 1f,
            zone = zone,
        )

        assertEquals(event.start, move.start)
        assertEquals(event.end, move.end)
        assertEquals(0, move.visualMinuteDelta)
    }

    @Test
    fun verticalDragSnapsStartAndPreservesDuration() {
        val event = event(
            start = Instant.parse("2026-07-26T01:07:00Z"), // 09:07 Shanghai
            end = Instant.parse("2026-07-26T02:37:00Z"),
        )

        val move = calculateTimelineSnappedMove(
            event = event,
            segmentDay = visibleDays[1],
            segmentDayIndex = 1,
            visibleDays = visibleDays,
            dragOffset = Offset(0f, 8f),
            dayWidthPx = 100f,
            pixelsPerMinute = 1f,
            zone = zone,
        )

        assertEquals(Instant.parse("2026-07-26T01:20:00Z"), move.start)
        assertEquals(Duration.ofMinutes(90), Duration.between(move.start, move.end))
        assertEquals(13, move.visualMinuteDelta)
    }

    @Test
    fun horizontalDragMovesToAdjacentVisibleDay() {
        val event = event(
            start = Instant.parse("2026-07-26T01:00:00Z"),
            end = Instant.parse("2026-07-26T02:00:00Z"),
        )

        val move = calculateTimelineSnappedMove(
            event = event,
            segmentDay = visibleDays[1],
            segmentDayIndex = 1,
            visibleDays = visibleDays,
            dragOffset = Offset(110f, 0f),
            dayWidthPx = 100f,
            pixelsPerMinute = 1f,
            zone = zone,
        )

        assertEquals(Instant.parse("2026-07-27T01:00:00Z"), move.start)
        assertEquals(Instant.parse("2026-07-27T02:00:00Z"), move.end)
        assertEquals(1, move.visualDayDelta)
    }

    @Test
    fun horizontalDragCannotLeaveVisibleDateGroup() {
        val event = event(
            start = Instant.parse("2026-07-25T01:00:00Z"),
            end = Instant.parse("2026-07-25T02:00:00Z"),
        )

        val move = calculateTimelineSnappedMove(
            event = event,
            segmentDay = visibleDays.first(),
            segmentDayIndex = 0,
            visibleDays = visibleDays,
            dragOffset = Offset(-500f, 0f),
            dayWidthPx = 100f,
            pixelsPerMinute = 1f,
            zone = zone,
        )

        assertEquals(event.start, move.start)
        assertEquals(0, move.visualDayDelta)
    }

    private fun event(start: Instant, end: Instant): TaskEvent = TaskEvent(
        id = "drag",
        title = "Drag me",
        type = "event",
        start = start,
        end = end,
        completed = false,
        checklist = emptyList(),
        colorId = 0,
        note = "",
        deletedAt = 0L,
    )
}
