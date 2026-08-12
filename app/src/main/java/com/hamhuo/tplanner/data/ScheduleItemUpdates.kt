package com.hamhuo.tplanner

internal fun upsertEventPreservingOrder(
    events: List<ScheduleItem>,
    updated: ScheduleItem,
): List<ScheduleItem> {
    var replaced = false
    val nextEvents = events.map { event ->
        if (event.id == updated.id) {
            replaced = true
            updated
        } else {
            event
        }
    }
    return if (replaced) nextEvents else nextEvents + updated
}
