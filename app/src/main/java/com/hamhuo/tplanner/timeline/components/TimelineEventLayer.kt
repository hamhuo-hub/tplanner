package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.timeline.DayPlacement
import com.hamhuo.tplanner.timeline.TimelineEventRenderSpec
import com.hamhuo.tplanner.timeline.TimelineGeometry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun TimelineEventLayer(
    renderSpecs: List<TimelineEventRenderSpec>,
    highlightedEventIds: Set<String>,
    visibleDays: List<LocalDate>,
    zone: ZoneId,
    dayWidthPx: Float,
    pixelsPerMinute: Float,
    scrollState: ScrollState,
    viewportTopPx: Float,
    viewportHeightPx: Float,
    onEventClick: (ScheduleItem) -> Unit,
    onConflictClick: (DayPlacement) -> Unit,
    onDraggingEventChange: (String?) -> Unit,
    onEventMove: (ScheduleItem, Instant, Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        renderSpecs.forEach { spec ->
            val placement = spec.placement
            val event = placement.placement.event
            TimelineEventCard(
                event = event,
                conflictCount = placement.placement.conflictIds.size,
                isHighlighted = event.id in highlightedEventIds,
                isShadow = placement.placement.isShadow,
                isCompact = spec.height < TimelineGeometry.compactEventThreshold,
                day = placement.day,
                dayIndex = placement.dayIndex,
                visibleDays = visibleDays,
                zone = zone,
                dayWidthPx = dayWidthPx,
                pixelsPerMinute = pixelsPerMinute,
                scrollState = scrollState,
                viewportTopPx = viewportTopPx,
                viewportHeightPx = viewportHeightPx,
                draggable = spec.draggable,
                modifier = Modifier
                    .offset(x = spec.x, y = spec.top)
                    .width(spec.width)
                    .height(spec.height)
                    .zIndex(spec.zIndex),
                onClick = { onEventClick(event) },
                onConflictClick = { onConflictClick(placement) },
                onDraggingChange = { dragging ->
                    onDraggingEventChange(if (dragging) event.id else null)
                },
                onDrop = { newStart, newEnd ->
                    onEventMove(event, newStart, newEnd)
                },
            )
        }
    }
}
