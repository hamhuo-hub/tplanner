package com.hamhuo.tplanner

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import com.hamhuo.tplanner.syncv3.SyncV3Phase
import com.hamhuo.tplanner.syncv3.SyncV3RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV3SchedulerTest {
    @Test
    fun `safety-net scheduling keeps a running worker instead of replacing it`() {
        assertEquals("tplanner-sync-v3-pump", SyncV3Scheduler.UNIQUE_WORK_NAME)
        assertEquals(ExistingWorkPolicy.KEEP, SyncV3Scheduler.existingWorkPolicy)
    }

    @Test
    fun `UPLOADED counts as worker success and never eats the 10s retry backoff`() {
        assertTrue(resultFor(SyncV3Phase.UPLOADED) is ListenableWorker.Result.Success)
        assertTrue(resultFor(SyncV3Phase.SUCCESS) is ListenableWorker.Result.Success)
    }

    @Test
    fun `only real failures keep retrying`() {
        for (phase in listOf(SyncV3Phase.SAVED, SyncV3Phase.UPDATING, SyncV3Phase.ERROR)) {
            assertTrue(resultFor(phase) is ListenableWorker.Result.Retry)
        }
    }

    private fun resultFor(phase: SyncV3Phase): ListenableWorker.Result =
        SyncV3RunResult(
            installedSnapshotVersion = 0,
            pendingCommands = 0,
            uploadedCommands = 0,
            phase = phase,
        ).toWorkResult()
}
