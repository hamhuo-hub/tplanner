package com.hamhuo.tplanner

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** System-owned retry entry point for a wake request committed before process death. */
class WakeOutboxJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val shouldRetry = try {
                PhoneWaker.flushFromJob(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "onStartJob: flush failed", e)
                true
            }
            jobFinished(params, shouldRetry)
        }, "tplanner-wake-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private companion object {
        const val TAG = "TplannerWakeJob"
    }
}
