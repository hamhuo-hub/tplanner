package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv3.SyncV3Engine

/** Process-wide engine factory used by foreground sync, WorkManager, and the Watch gateway. */
object SyncV3Runtime {
    @Volatile
    private var instance: SyncV3Engine? = null

    fun engine(context: Context): SyncV3Engine {
        val appContext = context.applicationContext
        // JobScheduler state is not the durable source of truth. Reattach any projection payload
        // persisted before a reboot/process death every time a sync entry point is acquired.
        WatchScheduleSync.resumePending(appContext)
        return instance ?: synchronized(this) {
            instance ?: create(appContext).also { instance = it }
        }
    }

    private fun create(context: Context): SyncV3Engine = SyncV3Engine(
        context = context,
        onDisplayedInstalled = { displayedEvents, authoritativeEvents, snapshotVersion, brokerToSequence ->
            runCatching { TaskAlarmScheduler.reconcile(context, displayedEvents) }
            WatchScheduleSync.push(
                context,
                authoritativeEvents,
                snapshotVersion,
                brokerToSequence,
            ) != null
        },
    )
}
