package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.EVENT_COLORS
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.RED
import com.hamhuo.tplanner.TaskEvent
import com.hamhuo.tplanner.timeline.calculateTimelineSnappedMove
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun TimelineEventCard(
    event: TaskEvent,
    conflictCount: Int,
    isHighlighted: Boolean,
    isShadow: Boolean,
    isCompact: Boolean,
    day: LocalDate,
    dayIndex: Int,
    visibleDays: List<LocalDate>,
    zone: ZoneId,
    dayWidthPx: Float,
    pixelsPerMinute: Float,
    scrollState: ScrollState,
    viewportTopPx: Float,
    viewportHeightPx: Float,
    draggable: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onConflictClick: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onDrop: (Instant, Instant) -> Unit,
) {
    val conflictDescription = stringResource(R.string.timeline_conflict_count, conflictCount)
    val startTime = remember(event.start, zone) {
        event.start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val endTime = remember(event.end, zone) {
        event.end.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val durationMinutes = Duration.between(event.start, event.end).toMinutes()

    DraggableTimelineEvent(
        event = event,
        isHighlighted = isHighlighted,
        isShadow = isShadow,
        isCompact = isCompact,
        day = day,
        dayIndex = dayIndex,
        visibleDays = visibleDays,
        zone = zone,
        dayWidthPx = dayWidthPx,
        pixelsPerMinute = pixelsPerMinute,
        scrollState = scrollState,
        viewportTopPx = viewportTopPx,
        viewportHeightPx = viewportHeightPx,
        draggable = draggable,
        modifier = modifier,
        onClick = onClick,
        onDraggingChange = onDraggingChange,
        onDrop = onDrop,
    ) {
        TimelineEventCardContent(
            event = event,
            conflictCount = conflictCount,
            conflictDescription = conflictDescription,
            isCompact = isCompact,
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            onConflictClick = onConflictClick,
        )
    }
}

@Composable
private fun DraggableTimelineEvent(
    event: TaskEvent,
    isHighlighted: Boolean,
    isShadow: Boolean,
    isCompact: Boolean,
    day: LocalDate,
    dayIndex: Int,
    visibleDays: List<LocalDate>,
    zone: ZoneId,
    dayWidthPx: Float,
    pixelsPerMinute: Float,
    scrollState: ScrollState,
    viewportTopPx: Float,
    viewportHeightPx: Float,
    draggable: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onDrop: (Instant, Instant) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var dragOffset by remember(event.id, day) { mutableStateOf(Offset.Zero) }
    var dragging by remember(event.id, day) { mutableStateOf(false) }
    var hasDragged by remember(event.id, day) { mutableStateOf(false) }
    var autoScrollOffsetPx by remember(event.id, day) { mutableStateOf(0f) }
    var pointerWindowY by remember(event.id, day) { mutableStateOf<Float?>(null) }
    var cardWindowTopPx by remember(event.id, day) { mutableStateOf(0f) }
    var suppressTrailingClick by remember(event.id, day) { mutableStateOf(false) }
    val effectiveDragOffset = Offset(
        x = dragOffset.x,
        y = dragOffset.y + autoScrollOffsetPx,
    )
    val move = remember(
        event.start,
        event.end,
        day,
        dayIndex,
        visibleDays,
        effectiveDragOffset,
        zone,
        dayWidthPx,
        pixelsPerMinute,
    ) {
        calculateTimelineSnappedMove(
            event = event,
            segmentDay = day,
            segmentDayIndex = dayIndex,
            visibleDays = visibleDays,
            dragOffset = effectiveDragOffset,
            dayWidthPx = dayWidthPx,
            pixelsPerMinute = pixelsPerMinute,
            zone = zone,
        )
    }
    val density = LocalDensity.current
    val visualX = with(density) { (move.visualDayDelta * dayWidthPx).toDp() }
    val visualY = with(density) { (move.visualMinuteDelta * pixelsPerMinute).toDp() }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val background = EVENT_COLORS.getOrElse(event.colorId) { EVENT_COLORS[0] }
    val shape = RoundedCornerShape(9.dp)

    fun resetDragState() {
        val notifyDragEnded = dragging
        dragOffset = Offset.Zero
        autoScrollOffsetPx = 0f
        pointerWindowY = null
        hasDragged = false
        dragging = false
        if (notifyDragEnded) currentOnDraggingChange(false)
    }

    LaunchedEffect(dragging, pointerWindowY, viewportTopPx, viewportHeightPx) {
        if (!dragging || viewportHeightPx <= 0f) return@LaunchedEffect
        val edgeSizePx = with(density) { 72.dp.toPx() }
        while (true) {
            val pointerY = pointerWindowY ?: break
            val viewportBottom = viewportTopPx + viewportHeightPx
            val scrollPerFrame = when {
                pointerY < viewportTopPx + edgeSizePx -> -18f
                pointerY > viewportBottom - edgeSizePx -> 18f
                else -> 0f
            }
            if (scrollPerFrame == 0f) break
            val consumed = scrollState.scrollBy(scrollPerFrame)
            if (consumed == 0f) break
            autoScrollOffsetPx += consumed
            hasDragged = true
            delay(16)
        }
    }

    LaunchedEffect(suppressTrailingClick, dragging) {
        if (suppressTrailingClick && !dragging) {
            delay(300)
            suppressTrailingClick = false
        }
    }

    Box(
        modifier = modifier
            .offset(x = if (dragging) visualX else 0.dp, y = if (dragging) visualY else 0.dp)
            .onGloballyPositioned { coordinates ->
                cardWindowTopPx = coordinates.positionInWindow().y
            }
            .alpha(if (isShadow) 0.34f else if (dragging) 0.78f else 1f)
            .background(background, shape)
            .border(
                width = if (isHighlighted || dragging) 2.dp else 1.dp,
                color = when {
                    isHighlighted -> RED
                    dragging -> GOLD
                    else -> Color.White.copy(alpha = 0.16f)
                },
                shape = shape,
            )
            .then(
                if (draggable) {
                    Modifier.pointerInput(
                        event,
                        day,
                        visibleDays,
                        dayWidthPx,
                        pixelsPerMinute,
                    ) {
                        try {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragOffset = Offset.Zero
                                    autoScrollOffsetPx = 0f
                                    pointerWindowY = cardWindowTopPx + it.y
                                    hasDragged = false
                                    suppressTrailingClick = true
                                    dragging = true
                                    currentOnDraggingChange(true)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragCancel = ::resetDragState,
                                onDragEnd = {
                                    val finalMove = calculateTimelineSnappedMove(
                                        event = event,
                                        segmentDay = day,
                                        segmentDayIndex = dayIndex,
                                        visibleDays = visibleDays,
                                        dragOffset = Offset(
                                            x = dragOffset.x,
                                            y = dragOffset.y + autoScrollOffsetPx,
                                        ),
                                        dayWidthPx = dayWidthPx,
                                        pixelsPerMinute = pixelsPerMinute,
                                        zone = zone,
                                    )
                                    if (
                                        hasDragged &&
                                        (
                                            finalMove.start != event.start ||
                                                finalMove.end != event.end
                                            )
                                    ) {
                                        currentOnDrop(finalMove.start, finalMove.end)
                                    }
                                    resetDragState()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (amount.getDistance() >= 0.5f) hasDragged = true
                                    dragOffset += amount
                                    pointerWindowY = cardWindowTopPx + change.position.y
                                },
                            )
                        } finally {
                            resetDragState()
                        }
                    }
                } else {
                    Modifier
                }
            )
            .clickable {
                if (suppressTrailingClick) {
                    suppressTrailingClick = false
                } else {
                    onClick()
                }
            }
            .padding(horizontal = 5.dp, vertical = if (isCompact) 3.dp else 5.dp),
    ) {
        content()
    }
}

@Composable
private fun TimelineEventCardContent(
    event: TaskEvent,
    conflictCount: Int,
    conflictDescription: String,
    isCompact: Boolean,
    startTime: String,
    endTime: String,
    durationMinutes: Long,
    onConflictClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (event.type == "task") {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.75f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (event.completed) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(8.dp),
                        )
                    }
                }
            }
            Text(
                text = event.title.ifBlank { stringResource(R.string.untitled_event) },
                color = Color.White,
                fontSize = if (isCompact) 9.sp else 11.sp,
                lineHeight = if (isCompact) 10.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (isCompact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.completed) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
                modifier = Modifier.weight(1f),
            )
            if (conflictCount > 0) {
                ConflictBadge(
                    count = conflictCount,
                    description = conflictDescription,
                    onClick = onConflictClick,
                )
            }
        }
        if (!isCompact) {
            Text(
                text = "$startTime–$endTime",
                color = Color.White.copy(alpha = 0.78f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
            )
            if (durationMinutes >= 60 && event.note.isNotBlank()) {
                Text(
                    text = event.note,
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
