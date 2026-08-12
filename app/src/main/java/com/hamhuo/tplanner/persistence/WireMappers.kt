package com.hamhuo.tplanner.persistence

import com.hamhuo.tplanner.CheckItem
import com.hamhuo.tplanner.ISO_MS
import com.hamhuo.tplanner.JournalEntry
import com.hamhuo.tplanner.MAX_ALARM_OFFSET_MINUTES
import com.hamhuo.tplanner.ScheduleItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class WireFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The only event JSON codec used by persistence, migration, and network synchronization. */
object EventWireMapper {
    val knownKeys: Set<String> = setOf(
        "id",
        "title",
        "type",
        "start",
        "end",
        "completed",
        "checklist",
        "colorId",
        "note",
        "deletedAt",
        "updatedAt",
        "alarmEnabled",
        "alarmOffsetMinutes",
        "lat",
        "lng",
        "listId",
    )

    fun decodeArrayStrict(json: String): List<ScheduleItem> {
        val array = try {
            JSONArray(json)
        } catch (error: Exception) {
            throw WireFormatException("Event payload is not a JSON array", error)
        }
        return (0 until array.length()).map { index ->
            val value = try {
                array.get(index)
            } catch (error: Exception) {
                throw WireFormatException("Cannot read event at array index $index", error)
            }
            if (value !is JSONObject) {
                throw WireFormatException("Event at array index $index is not an object")
            }
            try {
                decodeObject(value)
            } catch (error: Exception) {
                val id = value.optString("id").takeIf { it.isNotBlank() }
                val identity = id?.let { " (id=$it)" }.orEmpty()
                throw WireFormatException("Invalid event at array index $index$identity", error)
            }
        }
    }

    fun decodeObject(obj: JSONObject): ScheduleItem {
        val checklistArray = obj.optJSONArray("checklist") ?: JSONArray()
        val checklist = (0 until checklistArray.length()).map { index ->
            val item = checklistArray.getJSONObject(index)
            CheckItem(
                id = item.optString("id", ""),
                text = item.optString("text", ""),
                completed = item.optBoolean("completed", false),
            )
        }
        val extras = linkedMapOf<String, Any?>()
        obj.keys().forEach { key ->
            if (key !in knownKeys) extras[key] = obj.get(key)
        }
        return ScheduleItem(
            id = obj.getString("id"),
            title = obj.optString("title", ""),
            type = obj.optString("type", "event"),
            start = Instant.parse(obj.getString("start")),
            end = Instant.parse(obj.getString("end")),
            completed = obj.optBoolean("completed", false),
            checklist = checklist,
            colorId = obj.optInt("colorId", 0),
            note = obj.optString("note", ""),
            deletedAt = obj.optLong("deletedAt", 0L),
            updatedAt = obj.optLong("updatedAt", 0L),
            alarmEnabled = obj.optBoolean("alarmEnabled", false),
            alarmOffsetMinutes = obj.optInt("alarmOffsetMinutes", 0)
                .coerceIn(0, MAX_ALARM_OFFSET_MINUTES),
            lat = obj.optDouble("lat", 0.0),
            lng = obj.optDouble("lng", 0.0),
            listId = obj.optString("listId", ""),
            extras = extras,
        )
    }

    fun encodeArray(events: List<ScheduleItem>): String = JSONArray().apply {
        events.forEach { put(encodeObject(it)) }
    }.toString()

    fun encodeObject(event: ScheduleItem): JSONObject = JSONObject().apply {
        event.extras.forEach { (key, value) ->
            if (key !in knownKeys) put(key, value)
        }
        put("id", event.id)
        put("title", event.title)
        put("type", event.type)
        put("start", ISO_MS.format(event.start))
        put("end", ISO_MS.format(event.end))
        put("completed", event.completed)
        put("colorId", event.colorId)
        put("note", event.note)
        put("deletedAt", event.deletedAt)
        put("updatedAt", event.updatedAt)
        put("alarmEnabled", event.alarmEnabled)
        put(
            "alarmOffsetMinutes",
            event.alarmOffsetMinutes.coerceIn(0, MAX_ALARM_OFFSET_MINUTES),
        )
        if (event.lat != 0.0) put("lat", event.lat)
        if (event.lng != 0.0) put("lng", event.lng)
        if (event.listId.isNotEmpty()) put("listId", event.listId)
        put("checklist", JSONArray().apply {
            event.checklist.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("completed", item.completed)
                })
            }
        })
    }

    fun contentKey(event: ScheduleItem): String = encodeObject(event).toString()
}

/** Journal codec preserving the existing JavaScript stable-stringify tie-break contract. */
object JournalWireMapper {
    fun decodeMapStrict(json: String): LinkedHashMap<String, JournalEntry> {
        val obj = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw WireFormatException("Journal payload is not a JSON object", error)
        }
        return linkedMapOf<String, JournalEntry>().apply {
            obj.keys().forEach { date ->
                try {
                    put(date, decodeWireValue(obj.get(date)))
                } catch (error: Exception) {
                    throw WireFormatException("Invalid journal entry for key '$date'", error)
                }
            }
        }
    }

    fun decodeWireValue(value: Any?): JournalEntry = when (value) {
        is JSONObject -> JournalEntry(
            text = value.optString("text", ""),
            updatedAt = value.optLong("updatedAt", 0L),
            deletedAt = value.optLong("deletedAt", 0L),
        )
        is String -> JournalEntry(text = value)
        else -> throw WireFormatException("Journal entry must be an object or legacy string")
    }

    /** SharedPreferences stores both object JSON strings and older plain text in the same slot. */
    fun decodeLegacyPreferenceValue(raw: Any?): JournalEntry {
        if (raw !is String) {
            throw WireFormatException("Legacy journal preference is not a string")
        }
        return try {
            decodeWireValue(JSONObject(raw))
        } catch (_: Exception) {
            JournalEntry(text = raw)
        }
    }

    fun encodeMap(entries: Map<String, JournalEntry>): String = JSONObject().apply {
        entries.forEach { (date, entry) -> put(date, encodeObject(entry)) }
    }.toString()

    fun encodeObject(entry: JournalEntry): JSONObject = JSONObject().apply {
        put("text", entry.text)
        put("updatedAt", entry.updatedAt)
        put("deletedAt", if (entry.deletedAt == 0L) JSONObject.NULL else entry.deletedAt)
    }

    fun contentKey(entry: JournalEntry): String = tieKey(entry)

    fun tieKey(entry: JournalEntry): String {
        val deleted = if (entry.deletedAt == 0L) "null" else entry.deletedAt.toString()
        return "{\"deletedAt\":$deleted,\"payload\":{\"deletedAt\":$deleted," +
            "\"text\":${jsonQuote(entry.text)},\"updatedAt\":${entry.updatedAt}}}"
    }

    internal fun jsonQuote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

object PersistenceMapper {
    fun eventToEntity(event: ScheduleItem, sortIndex: Long): EventEntity {
        val checklistJson = JSONArray().apply {
            event.checklist.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("completed", item.completed)
                })
            }
        }.toString()
        val extrasJson = JSONObject().apply {
            event.extras.forEach { (key, value) ->
                if (key !in EventWireMapper.knownKeys) put(key, value)
            }
        }.toString()
        return EventEntity(
            id = event.id,
            title = event.title,
            type = event.type,
            startEpochMs = event.start.toEpochMilli(),
            endEpochMs = event.end.toEpochMilli(),
            completed = event.completed,
            checklistJson = checklistJson,
            colorId = event.colorId,
            note = event.note,
            deletedAt = event.deletedAt,
            updatedAt = event.updatedAt,
            alarmEnabled = event.alarmEnabled,
            alarmOffsetMinutes = event.alarmOffsetMinutes.coerceIn(0, MAX_ALARM_OFFSET_MINUTES),
            lat = event.lat,
            lng = event.lng,
            listId = event.listId,
            extrasJson = extrasJson,
            sortIndex = sortIndex,
        )
    }

    fun eventToDomain(row: EventEntity): ScheduleItem {
        val checklistArray = JSONArray(row.checklistJson)
        val checklist = (0 until checklistArray.length()).map { index ->
            val item = checklistArray.getJSONObject(index)
            CheckItem(
                id = item.optString("id", ""),
                text = item.optString("text", ""),
                completed = item.optBoolean("completed", false),
            )
        }
        val extrasObject = JSONObject(row.extrasJson)
        val extras = linkedMapOf<String, Any?>().apply {
            extrasObject.keys().forEach { key -> put(key, extrasObject.get(key)) }
        }
        return ScheduleItem(
            id = row.id,
            title = row.title,
            type = row.type,
            start = Instant.ofEpochMilli(row.startEpochMs),
            end = Instant.ofEpochMilli(row.endEpochMs),
            completed = row.completed,
            checklist = checklist,
            colorId = row.colorId,
            note = row.note,
            deletedAt = row.deletedAt,
            updatedAt = row.updatedAt,
            alarmEnabled = row.alarmEnabled,
            alarmOffsetMinutes = row.alarmOffsetMinutes,
            lat = row.lat,
            lng = row.lng,
            listId = row.listId,
            extras = extras,
        )
    }

    fun journalToEntity(date: String, entry: JournalEntry): JournalEntity = JournalEntity(
        date = date,
        text = entry.text,
        updatedAt = entry.updatedAt,
        deletedAt = entry.deletedAt,
    )

    fun journalToDomain(row: JournalEntity): JournalEntry = JournalEntry(
        text = row.text,
        updatedAt = row.updatedAt,
        deletedAt = row.deletedAt,
    )

    fun draftToEntity(draft: VersionedDraft): EditDraftEntity = EditDraftEntity(
        storageKey = draft.target.storageKey,
        entityKind = draft.target.kind.name,
        entityId = draft.target.entityId,
        content = draft.content,
        baseHash = draft.baseHash,
        baseUpdatedAt = draft.baseUpdatedAt,
        baseEntityExists = draft.baseEntityExists,
        baseDeletedAt = draft.baseDeletedAt,
        initialContentHash = draft.initialContentHash,
        draftUpdatedAt = draft.draftUpdatedAt,
        state = draft.state.name,
        targetHash = draft.targetHash,
        formatVersion = draft.version,
    )

    fun draftToDomain(row: EditDraftEntity): VersionedDraft = VersionedDraft(
        target = DraftTarget(DraftEntityKind.valueOf(row.entityKind), row.entityId),
        content = row.content,
        baseHash = row.baseHash,
        baseUpdatedAt = row.baseUpdatedAt,
        baseEntityExists = row.baseEntityExists,
        baseDeletedAt = row.baseDeletedAt,
        initialContentHash = row.initialContentHash,
        draftUpdatedAt = row.draftUpdatedAt,
        state = DraftState.valueOf(row.state),
        targetHash = row.targetHash,
        version = row.formatVersion,
    )
}
