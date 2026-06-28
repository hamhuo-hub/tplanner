package com.hamhuo.tplanner

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

// 手表端（com.example.tplanner，watch 分支）点击按钮后通过 Wearable MessageClient
// 发送 OPEN_APP_PATH 消息，这里收到后直接拉起 MainActivity。
// 注意：Android 10+ 对后台启动 Activity 有限制，并非所有机型/状态下都能保证拉起，
// 但这是 Wear OS 官方推荐的 WearableListenerService + BIND_LISTENER 标准用法。
class WearOpenListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != OPEN_APP_PATH) return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    companion object {
        const val OPEN_APP_PATH = "/tplanner/open"
    }
}
