package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime

/** Persists the times at which the Tide watch-face button invoked the phone. */
object WakeInvocationMarks {
    private const val PREFS = "tplanner_wake_invocations"
    private const val KEY = "wake_invocations_json"

    fun load(context: Context, date: LocalDate): List<Int> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return emptyList()
            val obj = JSONObject(raw)
            if (obj.optString("date") != date.toString()) return emptyList()

            val minutes = obj.optJSONArray("minutes") ?: JSONArray()
            (0 until minutes.length())
                .map { minutes.getInt(it) }
                .filter { it in 0..1439 }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun record(context: Context, at: ZonedDateTime): List<Int> {
        val date = at.toLocalDate()
        val minute = at.hour * 60 + at.minute
        val updated = (load(context, date) + minute).distinct().sorted()
        val payload = JSONObject()
            .put("date", date.toString())
            .put("minutes", JSONArray(updated))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, payload.toString())
            .apply()
        return updated
    }
}
