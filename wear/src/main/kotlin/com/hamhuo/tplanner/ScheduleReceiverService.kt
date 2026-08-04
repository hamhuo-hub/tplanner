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
        ScheduleStore.store(this, raw)
    }

    private fun clearSchedule() {
        val committed = getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(WATCH_MARKS_KEY)
            .commit()
        Log.d(TAG, "clearSchedule: committed=$committed")
    }

    private companion object {
        const val TAG = "TplannerScheduleRcv"
        const val PATH = "/tplanner/schedule"
    }
}

// ── Shared schedule storage ────────────────────────────────────────────
// Reused by both the GMS Data Layer receiver and the Bluetooth RFCOMM bridge.
// All validation (schema version, hash, version monotonicity) is applied
// identically regardless of transport.

internal object ScheduleStore {
    private const val TAG = "TplannerScheduleStore"
    private const val SCHEMA_VERSION = 3
    private const val MAX_SNAPSHOT_DAYS = 31
    private const val SOURCE_LEGACY = "legacy"
    private const val SOURCE_PHONE = "phone"
    internal const val SOURCE_BLUETOOTH = "bluetooth"

    private data class DaySnapshot(
        val date: String,
        val minutes: List<Int>,
    )

    fun store(context: Context, raw: String, sourceOverride: String? = null) {
        try {
            val payload = JSONObject(raw)
            val prefs = context.getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(WATCH_MARKS_KEY, null)
                ?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            val existingVersion = existing?.optLong("version", -1L) ?: -1L
            val existingHash = existing?.optString("hash")
            val existingWasLegacy = existing != null && (
                existing.optString("source") == SOURCE_LEGACY ||
                    existing.optLong("generatedAtEpochMs", 0L) <= 0L
                )
            val schemaVersion = payload.optInt("schemaVersion", -1)
            val days: List<DaySnapshot>
            val version: Long
            val hash: String
            val isLegacy: Boolean
            if (schemaVersion == SCHEMA_VERSION) {
                isLegacy = false
                version = payload.optLong("version", -1L)
                hash = payload.optString("hash")
                if (version < 0L || hash.isBlank()) {
                    Log.w(TAG, "storeSchedule: invalid version/hash version=$version")
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
                days = (0 until inputDays.length()).map { index ->
                    val inputDay = inputDays.optJSONObject(index)
                        ?: throw IllegalArgumentException("days[$index] is not an object")
                    val date = LocalDate.parse(inputDay.optString("date")).toString()
                    if (!seenDates.add(date)) {
                        throw IllegalArgumentException("duplicate date=$date")
                    }
                    DaySnapshot(date, normalizedMinutes(inputDay.optJSONArray("minutes")))
                }.sortedBy { it.date }
                if (hash != scheduleHash(days)) {
                    Log.w(TAG, "storeSchedule: hash mismatch version=$version")
                    return
                }
            } else if (!payload.has("schemaVersion") && payload.has("minutes")) {
                isLegacy = true
                days = listOf(
                    DaySnapshot(
                        java.time.ZonedDateTime.now(APP_ZONE).toLocalDate().toString(),
                        normalizedMinutes(payload.optJSONArray("minutes")),
                    )
                )
                version = maxOf(System.currentTimeMillis(), existingVersion + 1L)
                hash = scheduleHash(days)
                Log.i(TAG, "storeSchedule: normalized legacy payload version=$version")
            } else {
                Log.w(TAG, "storeSchedule: ignored unsupported schema=$schemaVersion")
                return
            }

            if (isLegacy && existing != null && !existingWasLegacy) {
                Log.i(TAG, "storeSchedule: ignored legacy payload after versioned snapshot")
                return
            }
            if (!(existingWasLegacy && !isLegacy)) {
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
            }

            val source = sourceOverride ?: if (isLegacy) SOURCE_LEGACY else SOURCE_PHONE
            val normalizedPayload = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("version", version)
                put("generatedAtEpochMs", payload.optLong("generatedAtEpochMs", 0L))
                put("hash", hash)
                put("source", source)
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
                    "storeSchedule: source=$source version=$version days=${days.first().date}..${days.last().date}",
                )
            } else {
                Log.e(TAG, "storeSchedule: SharedPreferences commit failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "storeSchedule: invalid payload", e)
        }
    }

    private fun normalizedMinutes(input: JSONArray?): List<Int> {
        val minutes = input ?: JSONArray()
        return (0 until minutes.length())
            .mapNotNull { index -> minutes.optInt(index, -1).takeIf { it in 0..1439 } }
            .distinct()
            .sorted()
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
}
