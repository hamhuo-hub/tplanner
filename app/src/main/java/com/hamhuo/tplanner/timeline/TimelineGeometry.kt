package com.hamhuo.tplanner.timeline

import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object TimelineGeometry {
    const val visibleDayCount = 3
    const val snapMinutes = 10

    val timeGutterWidth = 43.dp
    val hourHeight = 72.dp
    val dayHeaderHeight = 50.dp
    val minEventHeight = 24.dp

    val eventHorizontalPadding = 3.dp
    val compactEventThreshold = 43.dp

    const val twoLaneWidthFraction = 0.72f
    const val multiLaneWidthFraction = 0.62f
}

internal fun timelineWallClockMinutes(
    instant: Instant,
    day: LocalDate,
    zone: ZoneId,
): Float {
    val local = instant.atZone(zone)
    return when {
        local.toLocalDate().isBefore(day) -> 0f
        local.toLocalDate().isAfter(day) -> 24f * 60f
        else -> local.hour * 60f +
            local.minute +
            local.second / 60f +
            local.nano / 60_000_000_000f
    }
}
