package com.hamhuo.tplanner.timeline

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.ScheduleItem
import java.time.LocalDate
import java.time.ZoneId

internal object TimelinePlacementMapper {

    internal fun createDayPlacements(
        events: List<ScheduleItem>,
        days: List<LocalDate>,
        zone: ZoneId,
    ): List<DayPlacement> {
        return days.flatMapIndexed { index, day ->
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()

            TimelineLayoutEngine.layoutDay(events, dayStart, dayEnd).map { placement ->
                DayPlacement(
                    day = day,
                    dayIndex = index,
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    placement = placement,
                )
            }
        }
    }

    internal fun createRenderSpecs(
        placements: List<DayPlacement>,
        dayWidth: Dp,
        zone: ZoneId,
        draggingEventId: String?,
    ): List<TimelineEventRenderSpec> {
        return placements.map { item ->
            createRenderSpec(
                item = item,
                dayWidth = dayWidth,
                zone = zone,
                draggingEventId = draggingEventId,
            )
        }
    }

    private fun createRenderSpec(
        item: DayPlacement,
        dayWidth: Dp,
        zone: ZoneId,
        draggingEventId: String?,
    ): TimelineEventRenderSpec {
        val placement = item.placement
        val event = placement.event
        val startMinutes = timelineWallClockMinutes(placement.visibleStart, item.day, zone)
        val endMinutes = timelineWallClockMinutes(placement.visibleEnd, item.day, zone)
        val top = TimelineGeometry.hourHeight * (startMinutes / 60f)
        val height = (
            TimelineGeometry.hourHeight * ((endMinutes - startMinutes) / 60f)
            ).coerceAtLeast(TimelineGeometry.minEventHeight)

        val isStacked = placement.laneCount > 1
        val widthFraction = when {
            !isStacked -> 1f
            placement.laneCount == 2 -> TimelineGeometry.twoLaneWidthFraction
            else -> TimelineGeometry.multiLaneWidthFraction
        }
        val horizontalPadding = TimelineGeometry.eventHorizontalPadding
        val width = (dayWidth - horizontalPadding * 2) * widthFraction
        val availableOffset = dayWidth - horizontalPadding * 2 - width
        val laneOffset = if (placement.laneCount <= 1) {
            0.dp
        } else {
            availableOffset * (
                placement.laneIndex.toFloat() /
                    (placement.laneCount - 1).coerceAtLeast(1)
                )
        }
        val x = TimelineGeometry.timeGutterWidth +
            dayWidth * item.dayIndex +
            horizontalPadding +
            laneOffset
        val zIndex = when {
            draggingEventId == event.id -> 100f
            placement.isShadow -> 5f
            else -> 10f + startMinutes + placement.laneIndex * 0.01f
        }

        return TimelineEventRenderSpec(
            placement = item,
            top = top,
            height = height,
            x = x,
            width = width,
            zIndex = zIndex,
            draggable = !placement.isShadow && placement.visibleStart == event.start,
        )
    }
}
