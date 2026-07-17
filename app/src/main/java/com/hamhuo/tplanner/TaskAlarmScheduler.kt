package com.hamhuo.tplanner

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Keeps app-owned system alarms aligned with the persisted TaskEvent list.
 * PendingIntent identity is based on the event UUID, so edits replace the old
 * alarm and completion/deletion can cancel it without depending on clock time.
 */
internal object TaskAlarmScheduler {
    private const val PREFS = "tplanner_task_alarms"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_DELIVERED_PREFIX = "delivered_"

    const val EXTRA_EVENT_ID = "alarm_event_id"
    const val EXTRA_SIGNATURE = "alarm_signature"

    @Synchronized
    fun reconcile(context: Context, events: List<TaskEvent>) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val eventsById = events.associateBy { it.id }
        val previouslyScheduled = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toSet()
        val newlyScheduled = mutableSetOf<String>()
        val editor = prefs.edit()

        // Remove delivery markers for events that no longer exist after a sync.
        prefs.all.keys
            .filter { it.startsWith(KEY_DELIVERED_PREFIX) }
            .forEach { key ->
                val id = key.removePrefix(KEY_DELIVERED_PREFIX)
                if (id !in eventsById) editor.remove(key)
            }

        events.forEach { event ->
            val signature = event.alarmSignature()
            val deliveredKey = KEY_DELIVERED_PREFIX + event.id
            val deliveredCurrent = prefs.getString(deliveredKey, null) == signature
            val active = event.isAlarmActive()

            when {
                !active -> {
                    editor.remove(deliveredKey)
                    notificationManager?.cancel(notificationId(event.id))
                }
                deliveredCurrent -> {
                    // The current alarm already fired. Do not re-arm it merely
                    // because another task was edited or a sync completed.
                }
                event.start.toEpochMilli() <= now -> {
                    editor.remove(deliveredKey)
                    notificationManager?.cancel(notificationId(event.id))
                }
                else -> {
                    editor.remove(deliveredKey)
                    notificationManager?.cancel(notificationId(event.id))
                    if (schedule(appContext, alarmManager, event, signature, now)) {
                        newlyScheduled += event.id
                    }
                }
            }
        }

        (previouslyScheduled - newlyScheduled).forEach { id ->
            cancelAlarm(appContext, alarmManager, id)
            if (id !in eventsById) notificationManager?.cancel(notificationId(id))
        }
        editor.putStringSet(KEY_SCHEDULED_IDS, newlyScheduled).apply()
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun requestExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms(context)) return false
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Persist before posting to prevent duplicate alarms after a crash.
    fun markDelivered(context: Context, eventId: String, signature: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduled = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
        scheduled.remove(eventId)
        prefs.edit()
            .putString(KEY_DELIVERED_PREFIX + eventId, signature)
            .putStringSet(KEY_SCHEDULED_IDS, scheduled)
            .commit()
    }

    fun contentPendingIntent(context: Context, eventId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_EVENT
            data = Uri.Builder().scheme("tplanner").authority("event").appendPath(eventId).build()
            putExtra(EXTRA_EVENT_ID, eventId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notificationId(eventId: String): Int = eventId.hashCode()

    private fun schedule(
        context: Context,
        manager: AlarmManager,
        event: TaskEvent,
        signature: String,
        now: Long,
    ): Boolean {
        val nominalTrigger = event.start.toEpochMilli() - event.alarmOffsetMinutes * 60_000L
        // If the selected lead time has already passed but the event has not,
        // notify as soon as possible instead of silently dropping the alarm.
        val triggerAt = maxOf(nominalTrigger, now + 1_000L)
        val fireIntent = alarmPendingIntent(context, event.id, signature)
        return try {
            if (canScheduleExactAlarms(context)) {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, contentPendingIntent(context, event.id)),
                    fireIntent,
                )
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fireIntent)
            }
            true
        } catch (_: SecurityException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fireIntent)
            }.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun alarmPendingIntent(context: Context, eventId: String, signature: String): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            data = Uri.Builder().scheme("tplanner").authority("alarm").appendPath(eventId).build()
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_SIGNATURE, signature)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAlarm(context: Context, manager: AlarmManager, eventId: String) {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            data = Uri.Builder().scheme("tplanner").authority("alarm").appendPath(eventId).build()
        }
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pending ->
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private const val ACTION_FIRE_ALARM = "com.hamhuo.tplanner.action.FIRE_TASK_ALARM"
    private const val ACTION_SHOW_EVENT = "com.hamhuo.tplanner.action.SHOW_EVENT"
}

internal fun TaskEvent.isAlarmActive(): Boolean =
    alarmEnabled &&
        deletedAt == 0L &&
        !(type == "task" && completed) &&
        alarmOffsetMinutes in 0..MAX_ALARM_OFFSET_MINUTES

internal fun TaskEvent.alarmSignature(): String =
    "${start.toEpochMilli()}:$alarmOffsetMinutes"
