package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Builds the same durable phone snapshot for live GMS and RFCOMM refresh requests. */
internal object WatchScheduleSnapshotProvider {
    fun queueCurrent(context: Context): String? {
        val appContext = context.applicationContext
        val events = runBlocking(Dispatchers.IO) {
            ScheduleItemStore(appContext, TPlannerDatabase.get(appContext)).getAll()
        }
        return WatchScheduleSync.push(appContext, events)
    }
}
