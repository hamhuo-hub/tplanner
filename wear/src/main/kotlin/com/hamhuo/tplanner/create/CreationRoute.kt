package com.hamhuo.tplanner

import android.app.Activity
import android.content.Intent

const val DATE_EPOCH_DAY_UNSET = Long.MIN_VALUE
const val REQUEST_CREATION_NEXT = 9101

private const val EXTRA_DRAFT_ID = "task_creation_id"
private const val EXTRA_DRAFT_UPDATED_AT = "task_creation_updated_at"
private const val EXTRA_DRAFT_TITLE = "task_creation_title"
private const val EXTRA_DRAFT_TYPE = "task_creation_type"
private const val EXTRA_DRAFT_HOUR = "task_creation_hour"
private const val EXTRA_DRAFT_MINUTE = "task_creation_minute"
private const val EXTRA_DRAFT_DATE_EPOCH_DAY = "task_creation_date_epoch_day"

data class CreationRoute(
    val id: String,
    val updatedAtEpochMs: Long,
    val title: String,
    val type: String? = null,
    val hour: Int = -1,
    val minute: Int = -1,
    val dateEpochDay: Long = DATE_EPOCH_DAY_UNSET,
)

fun Intent.putCreationRoute(route: CreationRoute): Intent = apply {
    putExtra(EXTRA_DRAFT_ID, route.id)
    putExtra(EXTRA_DRAFT_UPDATED_AT, route.updatedAtEpochMs)
    putExtra(EXTRA_DRAFT_TITLE, route.title)
    route.type?.let { putExtra(EXTRA_DRAFT_TYPE, it) }
    if (route.hour >= 0) putExtra(EXTRA_DRAFT_HOUR, route.hour)
    if (route.minute >= 0) putExtra(EXTRA_DRAFT_MINUTE, route.minute)
    if (route.dateEpochDay != DATE_EPOCH_DAY_UNSET) {
        putExtra(EXTRA_DRAFT_DATE_EPOCH_DAY, route.dateEpochDay)
    }
}

fun Intent.creationRouteOrNull(): CreationRoute? {
    val id = getStringExtra(EXTRA_DRAFT_ID)?.takeIf { it.isNotBlank() } ?: return null
    val updatedAt = getLongExtra(EXTRA_DRAFT_UPDATED_AT, -1L).takeIf { it > 0L } ?: return null
    val title = getStringExtra(EXTRA_DRAFT_TITLE)?.takeIf { it.isNotBlank() } ?: return null
    return CreationRoute(
        id = id,
        updatedAtEpochMs = updatedAt,
        title = title,
        type = getStringExtra(EXTRA_DRAFT_TYPE),
        hour = getIntExtra(EXTRA_DRAFT_HOUR, -1),
        minute = getIntExtra(EXTRA_DRAFT_MINUTE, -1),
        dateEpochDay = getLongExtra(EXTRA_DRAFT_DATE_EPOCH_DAY, DATE_EPOCH_DAY_UNSET),
    )
}

fun Activity.propagateCreationResult(requestCode: Int, resultCode: Int) {
    if (requestCode != REQUEST_CREATION_NEXT || resultCode != Activity.RESULT_OK) return
    setResult(Activity.RESULT_OK)
    finish()
}
