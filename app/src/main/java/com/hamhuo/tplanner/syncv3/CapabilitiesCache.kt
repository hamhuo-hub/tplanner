package com.hamhuo.tplanner.syncv3

import org.json.JSONObject

/**
 * PR E:capabilities 进程内缓存(见 docs/sync-v3.md §9.5)。
 *
 * 每次同步都 GET /capabilities 会白付一个 RTT;缓存后只在
 * serverUrl 变化或 TTL 到期时刷新。ERROR008 语义不受影响:
 * serverInstanceId 校验仍然每次都拿缓存值对照本地 meta。
 */
class CapabilitiesCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: JSONObject? = null
    private var cachedForUrl: String? = null
    private var cachedAt: Long = 0L

    fun get(serverUrl: String): JSONObject? {
        val body = cached ?: return null
        if (cachedForUrl != serverUrl) return null
        if (clock() - cachedAt >= ttlMs) return null
        return body
    }

    fun put(serverUrl: String, body: JSONObject) {
        cached = body
        cachedForUrl = serverUrl
        cachedAt = clock()
    }

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1_000L
    }
}
