package com.hamhuo.tplanner

/** Compatibility entry point; repositories plan against their latest facts inside a transaction. */
internal fun createRecurringTaskInstances(source: ScheduleItem): List<ScheduleItem> =
    planRecurringTaskChange(emptyList(), null, source, source.updatedAt)
