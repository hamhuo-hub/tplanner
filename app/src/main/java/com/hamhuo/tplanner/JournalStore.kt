package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

class JournalStore(context: Context) {

    private val prefs = context.getSharedPreferences("tplanner_journals", Context.MODE_PRIVATE)

    fun getAll(): Map<String, String> =
        prefs.all.mapValues { it.value?.toString() ?: "" }

    fun saveAll(journals: Map<String, String>) {
        prefs.edit().apply {
            journals.forEach { (date, text) ->
                if (text.isNotBlank()) putString(date, text) else remove(date)
            }
        }.apply()
    }

    fun getToday(): String =
        prefs.getString(LocalDate.now().toString(), null) ?: ""

    fun fromJson(json: String): Map<String, String> {
        val obj = JSONObject(json)
        return buildMap { obj.keys().forEach { key -> put(key, obj.optString(key, "")) } }
    }

    fun toJson(journals: Map<String, String>): String {
        val obj = JSONObject()
        journals.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
