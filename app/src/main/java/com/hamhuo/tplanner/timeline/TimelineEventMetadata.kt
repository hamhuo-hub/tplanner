package com.hamhuo.tplanner.timeline

import com.hamhuo.tplanner.TaskEvent
import java.util.Locale

internal fun TaskEvent.hasTimelineRecurrenceMarker(): Boolean {
    if (type != "task") return false
    val recurrenceType = extras["recurrenceType"]
        ?.toString()
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return when (recurrenceType) {
        "daily", "weekly", "monthly" -> true
        "none" -> false
        "", "null" ->
            (extras["recurrenceCount"] as? Number)?.toInt()?.let { it > 1 } == true
        else -> false
    }
}
