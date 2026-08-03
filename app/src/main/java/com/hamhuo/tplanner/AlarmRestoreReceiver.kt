package com.hamhuo.tplanner

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.hamhuo.tplanner.persistence.LegacyImportResult
import com.hamhuo.tplanner.persistence.LegacyPreferencesImporter
import com.hamhuo.tplanner.persistence.TPlannerDatabase

/** Restores alarms cleared by reboot, package replacement or permission changes. */
class AlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = TPlannerDatabase.get(context)
                val migration = LegacyPreferencesImporter(context, database).importIfNeeded()
                if (migration !is LegacyImportResult.Blocked) {
                    TaskAlarmScheduler.reconcile(context, EventStore(context, database).getAll())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
