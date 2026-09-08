package com.hamhuo.tplanner

import org.json.JSONObject
import java.security.MessageDigest

/** Optional list metadata. Every materialized occurrence remains in the watch schedule. */
data class WatchTaskSeriesMetadata(val seriesId: String, val occurrenceIndex: Int) {
    init {
        require(seriesId.isNotBlank() && seriesId.toByteArray(Charsets.UTF_8).size <= 256)
        require(occurrenceIndex >= 0)
    }
}

/** Additive schema-3 fields; the established tasksHash bytes are deliberately unchanged. */
object WatchTaskSeriesCodec {
    const val HASH_FIELD = "taskSeriesHash"

    fun read(task: JSONObject): WatchTaskSeriesMetadata? {
        if (!task.has("seriesId") && !task.has("occurrenceIndex")) return null
        val id = task.opt("seriesId") as? String
            ?: throw IllegalArgumentException("seriesId must be a string")
        val index = when (val raw = task.opt("occurrenceIndex")) {
            is Byte -> raw.toInt()
            is Short -> raw.toInt()
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            else -> null
        } ?: throw IllegalArgumentException("occurrenceIndex must be an integer")
        return WatchTaskSeriesMetadata(id, index)
    }

    fun write(task: JSONObject, series: WatchTaskSeriesMetadata?) {
        task.remove("seriesId")
        task.remove("occurrenceIndex")
        if (series != null) {
            task.put("seriesId", series.seriesId)
            task.put("occurrenceIndex", series.occurrenceIndex)
        }
    }

    /** Include the task identity so moving a binding between two tasks changes the digest. */
    fun hash(tasks: List<Pair<String, WatchTaskSeriesMetadata?>>): String {
        val canonical = buildString {
            append("task-series=1")
            tasks.sortedBy { it.first }.forEach { (id, series) ->
                append('|')
                field(id)
                field(series?.seriesId.orEmpty())
                append(series?.occurrenceIndex ?: -1)
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.field(value: String) {
        append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(':')
    }
}
