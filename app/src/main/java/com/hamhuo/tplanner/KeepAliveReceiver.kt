package com.hamhuo.tplanner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

// 前台服务的看门狗：每 15 分钟被 AlarmManager 唤醒，检查并重启被系统杀掉
// 的 BluetoothWakeService。STICKY 服务理论上会自动复活，但国产厂商（华米OV）
// 经常连前台服务都杀，单靠 STICKY 不够。AlarmManager 精确闹钟是系统级唤醒，
// 厂商白名单通常不拦截，是最可靠的保活最后一道防线。
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_KEEP_ALIVE && action != Intent.ACTION_BOOT_COMPLETED) return

        // BOOT_COMPLETED 交给 BootReceiver 单独处理避免重复启动
        if (action == Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "KeepAlive alarm fired; ensuring BluetoothWakeService is running")
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, BluetoothWakeService::class.java)
            )
        } catch (e: Exception) {
            Log.e(TAG, "KeepAlive: failed to start service", e)
        }

        // 重新调度下一次闹钟（Android 不会自动重复精确闹钟）
        schedule(context)
    }

    companion object {
        private const val TAG = "TplannerKeepAlive"
        private const val ACTION_KEEP_ALIVE = "com.hamhuo.tplanner.KEEP_ALIVE"
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 分钟

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

            try {
                // 使用 setExactAndAllowWhileIdle 保证在 Doze 模式下也能准时触发。
                // Android 12+ 上从后台启动 exact alarm 仍需用户手动在设置里放行，
                // 但 SCHEDULE_EXACT_ALARM 权限在 manifest 声明后可引导用户授权。
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    pending
                )
                Log.d(TAG, "KeepAlive alarm scheduled for +${ALARM_INTERVAL_MS / 60_000}min")
            } catch (e: SecurityException) {
                // SCHEDULE_EXACT_ALARM 未授权时回退到普通 set
                Log.w(TAG, "KeepAlive: exact alarm denied, falling back to inexact", e)
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    pending
                )
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_NO_CREATE or immutableFlag()
            )
            if (pending != null) {
                am.cancel(pending)
                pending.cancel()
            }
        }

        private fun immutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
