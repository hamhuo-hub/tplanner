package com.hamhuo.tplanner.timeline

import androidx.compose.ui.geometry.Offset
import com.hamhuo.tplanner.TaskEvent
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

internal fun calculateTimelineSnappedMove(
    event: TaskEvent,
    segmentDay: LocalDate,
    segmentDayIndex: Int,
    visibleDays: List<LocalDate>,
    dragOffset: Offset,
    dayWidthPx: Float,
    pixelsPerMinute: Float,
    zone: ZoneId,
): TimelineSnappedMove {
    if (dayWidthPx <= 0f || pixelsPerMinute <= 0f) {
        return TimelineSnappedMove(event.start, event.end, 0, 0)
    }
    if (dragOffset == Offset.Zero) {
        return TimelineSnappedMove(event.start, event.end, 0, 0)
    }

    val requestedDayDelta = (dragOffset.x / dayWidthPx).roundToInt()
    val targetIndex = (segmentDayIndex + requestedDayDelta)
        .coerceIn(0, visibleDays.lastIndex)
    val targetDay = visibleDays[targetIndex]
    val original = event.start.atZone(zone)
    val originalMinute = original.hour * 60 + original.minute
    val rawMinuteDelta = (dragOffset.y / pixelsPerMinute).roundToInt()
    val snappedMinute = (
        (originalMinute + rawMinuteDelta).toFloat() / TimelineGeometry.snapMinutes
        ).roundToInt() * TimelineGeometry.snapMinutes
    val clampedMinute = snappedMinute.coerceIn(
        0,
        24 * 60 - TimelineGeometry.snapMinutes,
    )
    val newStart = targetDay
        .atTime(clampedMinute / 60, clampedMinute % 60)
        .atZone(zone)
        .toInstant()
    val durationMillis = Duration.between(event.start, event.end)
        .toMillis()
        .coerceAtLeast(60_000L)
    val newEnd = newStart.plusMillis(durationMillis)

    return TimelineSnappedMove(
        start = newStart,
        end = newEnd,
        visualDayDelta = targetIndex - segmentDayIndex,
        visualMinuteDelta = clampedMinute - originalMinute,
    )
}
