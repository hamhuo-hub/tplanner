package com.hamhuo.tplanner

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncV3SchedulerTest {
    @Test
    fun `repeated writes address one replaceable pump and never append successors`() {
        assertEquals("tplanner-sync-v3-pump", SyncV3Scheduler.UNIQUE_WORK_NAME)
        assertEquals(ExistingWorkPolicy.REPLACE, SyncV3Scheduler.existingWorkPolicy)
    }
}
