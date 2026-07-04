package com.hamhuo.tplanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

// 开机自启：像三星健康一样无感知常驻。BOOT_COMPLETED 启动 connectedDevice
// 类型的前台服务不在 Android 15 的"开机广播禁启清单"内（受限的是 dataSync/
// camera/mediaPlayback/phoneCall/mediaProjection/microphone 等类型）。
// 配合已申请的电池优化豁免（见 MainActivity.requestWakeSetup），服务开机后
// 即可持续监听手表的蓝牙唤醒信号。
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // MY_PACKAGE_REPLACED：应用更新后服务进程被杀，同样自动拉起（官方 FGS 启动豁免场景）
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.d("TplannerBoot", "$action received, starting BluetoothWakeService")
        try {
            // 检查蓝牙权限：极端情况下系统可能在 force-stop 后回收运行时权限
            // （虽然 BOOT_COMPLETED 有 FGS 豁免，但没有 BLUETOOTH_CONNECT 照样
            // 无法 accept 连接）。权限缺失时仍启动服务，让服务内的权限检查自行
            // 决定 stopSelf——至少前台通知会出现，用户能看到服务在运行。
            ContextCompat.startForegroundService(
                context, Intent(context, BluetoothWakeService::class.java)
            )
        } catch (e: Exception) {
            // Android 12+ ForegroundServiceStartNotAllowedException：
            // 某些 OEM ROM 上即使 BOOT_COMPLETED 豁免也不一定生效。
            // 这种情况下寄希望于用户下次打开 App 时手动拉起。
            Log.e("TplannerBoot", "failed to start service on $action", e)
        }
    }
}
