package com.hamhuo.tplanner

import java.time.LocalDate

internal data class JournalDayRolloverPlan(
    val previousDate: LocalDate,
    val nextDate: LocalDate,
    val draftContent: String?,
)

/**
 * Plans the UI's daily note transition without discarding an active edit session.
 *
 * A persisted draft is committed to [previousDate] before the UI advances. A note without a
 * draft is already durable, so it can move directly to [nextDate].
 */
internal fun planJournalDayRollover(
    displayedDate: LocalDate,
    today: LocalDate,
    isEditing: Boolean,
    hasDraft: Boolean,
    content: String,
): JournalDayRolloverPlan? {
    if (isEditing || displayedDate == today) return null
    return JournalDayRolloverPlan(
        previousDate = displayedDate,
        nextDate = today,
        draftContent = content.takeIf { hasDraft },
    )
}
