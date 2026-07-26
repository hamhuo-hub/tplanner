package com.hamhuo.tplanner.timeline

import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.LocalDate

internal data class DayPlacement(
    val day: LocalDate,
    val dayIndex: Int,
    val dayStart: Instant,
    val dayEnd: Instant,
    val placement: TimelinePlacement,
)

internal data class ConflictHighlight(
    val day: LocalDate,
    val eventIds: Set<String>,
    val start: Instant,
    val end: Instant,
)

internal data class TimelineSnappedMove(
    val start: Instant,
    val end: Instant,
    val visualDayDelta: Int,
    val visualMinuteDelta: Int,
)

internal data class TimelineEventRenderSpec(
    val placement: DayPlacement,
    val top: Dp,
    val height: Dp,
    val x: Dp,
    val width: Dp,
    val zIndex: Float,
    val draggable: Boolean,
)
