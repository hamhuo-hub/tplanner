package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilitiesCacheTest {
    @Test
    fun `same server url is served from cache within the ttl`() {
        var now = 1_000L
        val cache = CapabilitiesCache(ttlMs = 60_000L, clock = { now })
        val body = JSONObject().put("softwareVersion", "8.0.0")

        assertNull(cache.get("https://sync.example"))
        cache.put("https://sync.example", body)
        assertEquals(body, cache.get("https://sync.example"))

        now += 30_000L
        assertEquals(body, cache.get("https://sync.example"))

        now += 30_001L
        assertNull("expired entry must miss", cache.get("https://sync.example"))
    }

    @Test
    fun `a different server url always misses the cache`() {
        val cache = CapabilitiesCache(ttlMs = 60_000L, clock = { 1_000L })
        cache.put("https://a.example", JSONObject().put("softwareVersion", "8.0.0"))
        assertNull(cache.get("https://b.example"))
    }
}
