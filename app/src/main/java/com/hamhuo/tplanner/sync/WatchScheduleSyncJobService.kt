package com.hamhuo.tplanner

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** System-owned retry entry point for the latest committed watch schedule snapshot. */
class WatchScheduleSyncJobService : JobService() {
    private val runLock = Any()
    private var activeThread: Thread? = null
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val thread = Thread({
            val complete = try {
                WatchScheduleSync.flushPending(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "onStartJob: flush failed", e)
                false
            }
            val shouldFinish = synchronized(runLock) {
                if (activeThread === Thread.currentThread() && activeParams === params) {
                    activeThread = null
                    activeParams = null
                    true
                } else {
                    false
                }
            }
            if (shouldFinish) jobFinished(params, !complete)
        }, "tplanner-schedule-job")
        val previous = synchronized(runLock) {
            val old = activeThread
            activeThread = thread
            activeParams = params
            old
        }
        previous?.let(WatchScheduleSync::cancelFlush)
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val thread = synchronized(runLock) {
            if (activeParams === params) {
                activeThread.also {
                    activeThread = null
                    activeParams = null
                }
            } else {
                null
            }
        }
        thread?.let(WatchScheduleSync::cancelFlush)
        return true
    }

    private companion object {
        const val TAG = "TplannerScheduleJob"
    }
}
