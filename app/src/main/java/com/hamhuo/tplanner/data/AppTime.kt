package com.hamhuo.tplanner

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * TPlanner's canonical business timezone.
 *
 * Events remain stored as UTC [java.time.Instant] values. This zone is only
 * used when an instant needs a calendar date or wall-clock representation.
 */
internal const val APP_TIME_ZONE_ID = "Asia/Shanghai"
internal val APP_ZONE: ZoneId = ZoneId.of(APP_TIME_ZONE_ID)

internal fun appToday(): LocalDate = LocalDate.now(APP_ZONE)

internal fun appLegacyTimeZone(): TimeZone = TimeZone.getTimeZone(APP_TIME_ZONE_ID)
