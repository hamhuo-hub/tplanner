package com.hamhuo.tplanner

/**
 * Returns only entities that must be uploaded: durable outbox entries plus local content that has
 * drifted from (or never existed in) the last acknowledged server shadow.
 */
internal fun <T> uploadEntityIds(
    local: Iterable<T>,
    baseKeys: Map<String, String>?,
    capturedIds: Set<String>,
    idOf: (T) -> String,
    contentKeyOf: (T) -> String,
): Set<String> = buildSet {
    addAll(capturedIds)
    local.forEach { entity ->
        val id = idOf(entity)
        if (baseKeys?.get(id) != contentKeyOf(entity)) add(id)
    }
}
