package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hamhuo.tplanner.BG
import com.hamhuo.tplanner.RED
import com.hamhuo.tplanner.TaskEvent
import com.hamhuo.tplanner.timeline.ConflictHighlight
import com.hamhuo.tplanner.timeline.DayPlacement
import com.hamhuo.tplanner.timeline.TimelineGeometry
import com.hamhuo.tplanner.timeline.TimelinePlacementMapper
import com.hamhuo.tplanner.timeline.TimelineState
import com.hamhuo.tplanner.timeline.timelineWallClockMinutes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
internal fun TimelineBody(
    days: List<LocalDate>,
    today: LocalDate,
    now: ZonedDateTime,
    events: List<TaskEvent>,
    placements: List<DayPlacement>,
    zone: ZoneId,
    state: TimelineState,
    hourHeightPx: Float,
    onEventClick: (TaskEvent) -> Unit,
    onEventMove: (TaskEvent, Instant, Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                state.updateViewport(
                    topPx = coordinates.positionInWindow().y,
                    heightPx = coordinates.size.height.toFloat(),
                )
            }
            .verticalScroll(state.scrollState),
    ) {
        val dayWidth =
            (maxWidth - TimelineGeometry.timeGutterWidth) / TimelineGeometry.visibleDayCount
        val dayWidthPx = with(density) { dayWidth.toPx() }
        val pixelsPerMinute = hourHeightPx / 60f
        val renderSpecs = remember(placements, dayWidth, zone, state.draggingEventId) {
            TimelinePlacementMapper.createRenderSpecs(
                placements = placements,
                dayWidth = dayWidth,
                zone = zone,
                draggingEventId = state.draggingEventId,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TimelineGeometry.hourHeight * 24)
                .background(BG),
        ) {
            TimelineGrid(days = days, today = today, now = now)
            state.highlight?.let { highlight ->
                TimelineConflictHighlight(
                    highlight = highlight,
                    days = days,
                    dayWidth = dayWidth,
                    zone = zone,
                )
            }
            TimelineEventLayer(
                renderSpecs = renderSpecs,
                highlightedEventIds = state.highlight?.eventIds.orEmpty(),
                visibleDays = days,
                zone = zone,
                dayWidthPx = dayWidthPx,
                pixelsPerMinute = pixelsPerMinute,
                scrollState = state.scrollState,
                viewportTopPx = state.viewportTopPx,
                viewportHeightPx = state.viewportHeightPx,
                onEventClick = onEventClick,
                onConflictClick = { source ->
                    state.showConflict(
                        source = source,
                        visibleEvents = events,
                        hourHeightPx = hourHeightPx,
                    )
                },
                onDraggingEventChange = state::setDraggingEvent,
                onEventMove = onEventMove,
                modifier = Modifier.zIndex(5f),
            )
        }
    }
}

@Composable
private fun TimelineConflictHighlight(
    highlight: ConflictHighlight,
    days: List<LocalDate>,
    dayWidth: androidx.compose.ui.unit.Dp,
    zone: ZoneId,
) {
    val dayIndex = days.indexOf(highlight.day)
    if (dayIndex < 0) return

    val startMinutes = timelineWallClockMinutes(highlight.start, highlight.day, zone)
    val endMinutes = timelineWallClockMinutes(highlight.end, highlight.day, zone)
    val top = TimelineGeometry.hourHeight * (startMinutes / 60f)
    val height = (
        TimelineGeometry.hourHeight * ((endMinutes - startMinutes) / 60f)
        ).coerceAtLeast(4.dp)
    val shape = RoundedCornerShape(8.dp)

    Box(
        Modifier
            .offset(
                x = TimelineGeometry.timeGutterWidth + dayWidth * dayIndex + 2.dp,
                y = top,
            )
            .width(dayWidth - 4.dp)
            .height(height)
            .background(RED.copy(alpha = 0.16f), shape)
            .border(2.dp, RED.copy(alpha = 0.8f), shape)
            .zIndex(4f),
    )
}
