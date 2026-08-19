package com.hamhuo.tplanner

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal const val MAX_TASK_RECURRENCE_COUNT = 50

/** Expands a new recurring task into independent facts without a series/group identity. */
internal fun createRecurringTaskInstances(source: ScheduleItem): List<ScheduleItem> {
    val independentSource = source.copy(extras = source.extras - "groupId")
    if (independentSource.type != "task") return listOf(independentSource)
    val recurrenceType = independentSource.extras["recurrenceType"]
        ?.toString()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    val recurrenceCount = when (val raw = independentSource.extras["recurrenceCount"]) {
        is Number -> raw.toInt()
        else -> raw?.toString()?.toIntOrNull() ?: 1
    }.coerceIn(1, MAX_TASK_RECURRENCE_COUNT)
    if (recurrenceType !in setOf("daily", "weekly", "monthly") || recurrenceCount <= 1) {
        return listOf(independentSource)
    }

    fun shift(value: Instant, index: Int): Instant {
        val local = value.atZone(APP_ZONE)
        return when (recurrenceType) {
            "daily" -> local.plusDays(index.toLong())
            "weekly" -> local.plusWeeks(index.toLong())
            else -> local.plusMonths(index.toLong())
        }.toInstant()
    }

    return List(recurrenceCount) { index ->
        if (index == 0) {
            independentSource
        } else {
            val occurrenceId = UUID.nameUUIDFromBytes(
                "${independentSource.id}:recurrence:$index".toByteArray(StandardCharsets.UTF_8),
            ).toString()
            independentSource.copy(
                id = occurrenceId,
                start = shift(independentSource.start, index),
                end = shift(independentSource.end, index),
                completed = false,
            )
        }
    }
}
