package com.hamhuo.tplanner

internal fun watchTaskFallsInWindow(task: WatchEventMarks.NextTask, start: Long, end: Long): Boolean =
    if (task.startEpochMs == task.endEpochMs) {
        task.startEpochMs >= start && task.startEpochMs < end
    } else {
        task.endEpochMs > start && task.startEpochMs < end
    }

/** List-only projection, applied after date/type filters and pending local deletions. */
internal fun collapseWatchTaskSeries(tasks: List<WatchEventMarks.NextTask>): List<WatchEventMarks.NextTask> {
    val representatives = tasks.asSequence()
        .filter { it.type == "task" && it.series != null }
        .groupBy { it.series!!.seriesId }
        .mapValues { (_, members) -> members.minWith(watchSeriesOccurrenceOrder).id }
    return tasks.filter { task ->
        val series = task.series
        task.type != "task" || series == null || representatives[series.seriesId] == task.id
    }
}

// The phone sends unfinished tasks. Keep overdue work visible instead of advancing by the clock.
private val watchSeriesOccurrenceOrder = compareBy<WatchEventMarks.NextTask>(
    { it.startEpochMs },
    { it.series?.occurrenceIndex ?: Int.MAX_VALUE },
    { it.endEpochMs },
    { it.id },
)
