package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * 接收手机推送的日程数据（/tplanner/schedule），写入 SharedPreferences。
 * 表盘每分钟通过 [WatchEventMarks.load] 读取，无需其他改动。
 */
class ScheduleReceiverService : WearableListenerService() {
    private companion object {
        const val TAG = "TplannerScheduleRcv"
        const val PATH = "/tplanner/schedule"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue
            try {
                val bytes = event.dataItem.data ?: continue
                val raw = String(bytes, Charsets.UTF_8)
                val prefs = getSharedPreferences("tplanner_watch_marks", Context.MODE_PRIVATE)
                prefs.edit().putString("marks_json", raw).apply()
                Log.d(TAG, "received schedule data")
            } catch (e: Exception) {
                Log.e(TAG, "onDataChanged: failed", e)
            }
        }
    }
}
