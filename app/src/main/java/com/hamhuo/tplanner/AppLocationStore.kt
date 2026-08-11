package com.hamhuo.tplanner

import android.location.Location

/**
 * Process-memory bridge between [LocationCapture] and the phone UI.
 *
 * Precise coordinates are never written to SharedPreferences or Android backup. The resolved
 * business state is persisted separately by UntangleStateStore only after the active sheet and
 * request generation have been verified.
 */
object AppLocationStore {
    @Volatile
    private var current: Fix? = null

    fun save(location: Location, requestId: String, fromCache: Boolean) {
        if (location.time <= 0L || !location.hasAccuracy()) return
        current = Fix(
            lat = location.latitude,
            lng = location.longitude,
            requestId = requestId,
            fixTime = location.time,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            accuracy = location.accuracy,
            provider = location.provider ?: "unknown",
            fromCache = fromCache,
            savedAt = System.currentTimeMillis(),
        )
    }

    fun get(requestId: String): Fix? = current?.takeIf { it.requestId == requestId }

    fun clear(requestId: String) {
        if (current?.requestId == requestId) current = null
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
}
