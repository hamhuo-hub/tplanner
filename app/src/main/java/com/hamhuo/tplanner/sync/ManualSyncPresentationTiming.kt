package com.hamhuo.tplanner

internal const val MANUAL_SYNC_ANIMATION_MILLIS = 3_000L

internal fun manualSyncResultDelayMillis(startedAtMillis: Long, finishedAtMillis: Long): Long {
    val elapsed = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L)
    return (MANUAL_SYNC_ANIMATION_MILLIS - elapsed).coerceAtLeast(0L)
}
