package com.hamhuo.tplanner

import android.content.Context
import android.location.Location

// 手表打点定位的唯一真相源。
//
// fixTime / elapsedRealtimeNanos 来自 Location 本身，savedAt 只表示写入时刻；
// 新鲜度判断绝不能使用 savedAt，避免把陈旧缓存伪装成本次定位。
object WatchLocationStore {
    private const val PREFS = "tplanner_watch_location"

    fun save(ctx: Context, location: Location, requestId: String, fromCache: Boolean) {
        if (location.time <= 0L || !location.hasAccuracy()) return
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", location.latitude.toString())
            .putString("lng", location.longitude.toString())
            .putString("requestId", requestId)
            .putLong("fixTime", location.time)
            .putLong("elapsedRealtimeNanos", location.elapsedRealtimeNanos)
            .putFloat("accuracy", location.accuracy)
            .putString("provider", location.provider ?: "unknown")
            .putBoolean("fromCache", fromCache)
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    data class Fix(
        val lat: Double,
        val lng: Double,
        val requestId: String,
        val fixTime: Long,
        val elapsedRealtimeNanos: Long,
        val accuracy: Float,
        val provider: String,
        val fromCache: Boolean,
        val savedAt: Long,
    )

    fun get(ctx: Context): Fix? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lng = p.getString("lng", null)?.toDoubleOrNull() ?: return null
        val requestId = p.getString("requestId", null) ?: return null
        val fixTime = p.getLong("fixTime", 0L)
        if (fixTime <= 0L) return null // Reject records written by the old, lossy schema.
        return Fix(
            lat = lat,
            lng = lng,
            requestId = requestId,
            fixTime = fixTime,
            elapsedRealtimeNanos = p.getLong("elapsedRealtimeNanos", 0L),
            accuracy = p.getFloat("accuracy", Float.POSITIVE_INFINITY),
            provider = p.getString("provider", "unknown") ?: "unknown",
            fromCache = p.getBoolean("fromCache", false),
            savedAt = p.getLong("savedAt", 0L),
        )
    }
}
