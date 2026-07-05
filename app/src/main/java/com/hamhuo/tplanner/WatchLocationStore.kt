package com.hamhuo.tplanner

import android.content.Context

// 手表打点定位的唯一真相源。
//
// 背景：BluetoothWakeService 收到手表信号后异步取定位（GPS 冷启可达数秒），
// 与此同时它立刻拉起 MainActivity 弹焦虑面板。此前 MainActivity 靠正则从随笔
// 的 "[WATCH] HH:mm @ lat,lng" 行里抠坐标——但那行的坐标要等定位回调才补上，
// LaunchedEffect 早跑一步就抠不到，导致地名恒为空（只显示坐标/Unknown）。
//
// 改为：定位一旦拿到就写这里（含保存时刻 savedAt），MainActivity 轮询等待
// 一条足够新的定位再做逆地理编码，彻底消除竞态。savedAt 记的是"写入时刻"而非
// Location.time（后者可能是 getLastKnownLocation 的陈旧时间），用于判定新鲜度。
object WatchLocationStore {
    private const val PREFS = "tplanner_watch_location"

    fun save(ctx: Context, lat: Double, lng: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", lat.toString())
            .putString("lng", lng.toString())
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    data class Fix(val lat: Double, val lng: Double, val savedAt: Long)

    fun get(ctx: Context): Fix? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lng = p.getString("lng", null)?.toDoubleOrNull() ?: return null
        return Fix(lat, lng, p.getLong("savedAt", 0L))
    }
}
