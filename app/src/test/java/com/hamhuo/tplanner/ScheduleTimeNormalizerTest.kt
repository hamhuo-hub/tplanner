package com.hamhuo.tplanner

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimeNormalizerTest {
    @Test
    fun defaultTimeAlwaysUsesTheNextHalfHourBoundary() {
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 10, 30),
            nextScheduleHalfHour(LocalDateTime.of(2026, 8, 19, 10, 0, 0)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 11, 0),
            nextScheduleHalfHour(LocalDateTime.of(2026, 8, 19, 10, 47, 12)),
        )
    }

    @Test
    fun offsetTimestampsAreConvertedToShanghaiWallTime() {
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 10, 0),
            parseScheduleLocalDateTime("2026-08-19T02:00:00Z"),
        )
    }

    @Test
    fun temporalContextPinsRelativeDatesToOneClockSnapshot() {
        val context = scheduleTemporalContext(
            LocalDateTime.of(2026, 8, 19, 23, 59, 58),
            timestamp = "2026-08-19T23:58:00+08:00",
        )

        assertTrue(context.contains("今天=2026-08-19"))
        assertTrue(context.contains("明天=2026-08-20"))
        assertTrue(context.contains("后天=2026-08-21"))
        assertTrue(context.contains("原始记录时间"))
    }
}
