package com.hamhuo.tplanner

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** System-owned retry entry point for the latest committed watch schedule snapshot. */
class WatchScheduleSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val complete = try {
                WatchScheduleSync.flushPending(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "onStartJob: flush failed", e)
                false
            }
            jobFinished(params, !complete)
        }, "tplanner-schedule-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private companion object {
        const val TAG = "TplannerScheduleJob"
    }
}
