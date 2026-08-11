package com.hamhuo.tplanner

/** The complete, phone-compatible task payload produced by the watch creation flow. */
data class WatchTaskDraft(
    val id: String,
    val title: String,
    val type: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val alarmEnabled: Boolean,
    val alarmOffsetMinutes: Int,
    val colorId: Int,
    val updatedAtEpochMs: Long,
)
