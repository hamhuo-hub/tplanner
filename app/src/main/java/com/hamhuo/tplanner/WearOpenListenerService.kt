package com.hamhuo.tplanner

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

// 手表端（:wear 模块，com.example.tplanner）点击按钮后通过 Wearable MessageClient
// 发送 OPEN_APP_PATH 消息，这里收到后直接拉起 MainActivity。
// 注意：Android 10+ 对后台启动 Activity 有限制，并非所有机型/状态下都能保证拉起，
// 但这是 Wear OS 官方推荐的 WearableListenerService + BIND_LISTENER 标准用法。
// 三星手机的"休眠应用"省电策略可能会延迟/阻止系统唤起本服务来处理消息——
// 这里打日志记录电池优化豁免状态，方便定位是否是三星特有的省电策略拦截了消息。
class WearOpenListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        val gmsCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        Log.d(TAG, "onCreate: service bound, GooglePlayServicesAvailable code=$gmsCode (0=SUCCESS), ignoringBatteryOptimizations=$ignoringBatteryOptimizations")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: path=${messageEvent.path} sourceNode=${messageEvent.sourceNodeId}")
        if (messageEvent.path != OPEN_APP_PATH) return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
            Log.d(TAG, "startActivity: called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startActivity: failed", e)
        }
    }

    companion object {
        const val OPEN_APP_PATH = "/tplanner/open"
        private const val TAG = "TplannerWearListener"
    }
}
