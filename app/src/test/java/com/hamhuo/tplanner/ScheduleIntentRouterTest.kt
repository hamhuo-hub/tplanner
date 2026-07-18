package com.hamhuo.tplanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleIntentRouterTest {
    @Test
    fun recognizesExplicitScheduleRequests() {
        listOf(
            "提醒我明天交电费",
            "帮我安排明天下午三点开会",
            "新建一个任务：买菜",
            "明天要买菜",
            "remind me to call mom",
        ).forEach { text ->
            assertTrue(text, ScheduleIntentRouter.isExplicitRequest(text))
        }
    }

    @Test
    fun leavesReflectiveThoughtsInQa() {
        listOf(
            "我担心明天会议讲不好",
            "为什么我总是拖延任务",
            "今天心情不错",
            "",
        ).forEach { text ->
            assertFalse(text, ScheduleIntentRouter.isExplicitRequest(text))
        }
    }

    @Test
    fun validatesUserSuppliedClockAndLatestType() {
        assertFalse(ScheduleIntentRouter.hasExplicitClock("提醒我明天交电费"))
        assertTrue(ScheduleIntentRouter.hasExplicitClock("提醒我明天上午九点交电费"))
        assertTrue(ScheduleIntentRouter.hasExplicitClock("明天 09:30 开会"))
        assertEquals("event", ScheduleIntentRouter.explicitType("提醒我明天交电费"))
        assertEquals("task", ScheduleIntentRouter.explicitType("提醒我明天交电费\n改成任务"))
    }
}
