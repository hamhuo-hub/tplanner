package com.hamhuo.tplanner.timeline

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal const val TIMELINE_DATE_WINDOW_CENTER = 50_000
internal const val TIMELINE_DATE_WINDOW_COUNT =
    TIMELINE_DATE_WINDOW_CENTER * 2 + 1

internal fun timelineDateAtIndex(
    anchor: LocalDate,
    index: Int,
): LocalDate = anchor.plusDays(
    (index - TIMELINE_DATE_WINDOW_CENTER).toLong(),
)

internal fun timelineDateIndex(
    anchor: LocalDate,
    date: LocalDate,
): Int = (
    TIMELINE_DATE_WINDOW_CENTER.toLong() +
        ChronoUnit.DAYS.between(anchor, date)
    )
    .coerceIn(0L, TIMELINE_DATE_WINDOW_COUNT.toLong() - 1L)
    .toInt()

internal fun timelineDateFitsWindow(
    anchor: LocalDate,
    date: LocalDate,
): Boolean = abs(ChronoUnit.DAYS.between(anchor, date)) <=
    TIMELINE_DATE_WINDOW_CENTER
