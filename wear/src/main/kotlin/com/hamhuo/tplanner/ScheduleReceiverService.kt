package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/** Receives the phone's durable latest-task snapshot and commits it locally. */
class ScheduleReceiverService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.dataItem.uri.path != PATH) continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: continue
                    storeSchedule(String(bytes, Charsets.UTF_8))
                }

                DataEvent.TYPE_DELETED -> clearSchedule()
            }
        }
    }

    private fun storeSchedule(raw: String) {
        try {
            val payload = JSONObject(raw)
            val schemaVersion = payload.optInt("schemaVersion", -1)
            val version = payload.optLong("version", -1L)
            val hash = payload.optString("hash")
            if (schemaVersion != SCHEMA_VERSION || version < 0L || hash.isBlank()) {
                Log.w(
                    TAG,
                    "storeSchedule: ignored schema=$schemaVersion version=$version",
                )
                return
            }

            val inputDays = payload.optJSONArray("days") ?: run {
                Log.w(TAG, "storeSchedule: missing days version=$version")
                return
            }
            if (inputDays.length() !in 1..MAX_SNAPSHOT_DAYS) {
                Log.w(TAG, "storeSchedule: invalid day count=${inputDays.length()}")
                return
            }
            val seenDates = mutableSetOf<String>()
            val days = (0 until inputDays.length()).map { index ->
                val inputDay = inputDays.optJSONObject(index)
                    ?: throw IllegalArgumentException("days[$index] is not an object")
                val date = LocalDate.parse(inputDay.optString("date")).toString()
                if (!seenDates.add(date)) {
                    throw IllegalArgumentException("duplicate date=$date")
                }
                val inputMinutes = inputDay.optJSONArray("minutes") ?: JSONArray()
                val minutes = (0 until inputMinutes.length())
                    .mapNotNull { minuteIndex ->
                        inputMinutes.optInt(minuteIndex, -1).takeIf { it in 0..1439 }
                    }
                    .distinct()
                    .sorted()
                DaySnapshot(date, minutes)
            }.sortedBy { it.date }

            val expectedHash = scheduleHash(days)
            if (hash != expectedHash) {
                Log.w(TAG, "storeSchedule: hash mismatch version=$version")
                return
            }

            val prefs = getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(WATCH_MARKS_KEY, null)
                ?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            val existingVersion = existing?.optLong("version", -1L) ?: -1L
            val existingHash = existing?.optString("hash")
            if (existingVersion > version) {
                Log.w(TAG, "storeSchedule: ignored stale version=$version current=$existingVersion")
                return
            }
            if (existingVersion == version) {
                if (existingHash == hash) return
                Log.e(
                    TAG,
                    "storeSchedule: rejected divergent content for version=$version " +
                        "currentHash=${existingHash?.take(12)} incomingHash=${hash.take(12)}",
                )
                return
            }

            val normalizedPayload = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("version", version)
                put("generatedAtEpochMs", payload.optLong("generatedAtEpochMs", 0L))
                put("hash", hash)
                put("days", JSONArray().apply {
                    days.forEach { day ->
                        put(JSONObject().apply {
                            put("date", day.date)
                            put("minutes", JSONArray(day.minutes))
                        })
                    }
                })
            }
            val committed = prefs.edit()
                .putString(WATCH_MARKS_KEY, normalizedPayload.toString())
                .commit()
            if (committed) {
                Log.d(
                    TAG,
                    "storeSchedule: version=$version days=${days.first().date}..${days.last().date}",
                )
            } else {
                Log.e(TAG, "storeSchedule: SharedPreferences commit failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "storeSchedule: invalid payload", e)
        }
    }

    private fun clearSchedule() {
        val committed = getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(WATCH_MARKS_KEY)
            .commit()
        Log.d(TAG, "clearSchedule: committed=$committed")
    }

    private fun scheduleHash(days: List<DaySnapshot>): String {
        val canonical = buildString {
            append("schema=").append(SCHEMA_VERSION)
            days.forEach { day ->
                append('|').append(day.date).append('[')
                append(day.minutes.joinToString(","))
                append(']')
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val TAG = "TplannerScheduleRcv"
        const val PATH = "/tplanner/schedule"
        const val SCHEMA_VERSION = 3
        const val MAX_SNAPSHOT_DAYS = 31

        data class DaySnapshot(
            val date: String,
            val minutes: List<Int>,
        )
    }
}
