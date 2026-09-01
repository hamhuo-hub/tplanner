package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.syncv3.SyncV3Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Builds the same durable phone snapshot for live GMS and RFCOMM refresh requests. */
internal object WatchScheduleSnapshotProvider {
    fun queueCurrent(context: Context): String? {
        val appContext = context.applicationContext
        val events = runBlocking(Dispatchers.IO) {
            ScheduleItemStore(appContext, TPlannerDatabase.get(appContext)).getAll()
        }
        val sourceSnapshotVersion = SyncV3Progress.installedSnapshotVersion(appContext)
        if (sourceSnapshotVersion <= 0L) return null
        return WatchScheduleSync.push(appContext, events, sourceSnapshotVersion)
    }
}
