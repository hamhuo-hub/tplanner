package com.hamhuo.tplanner.syncv3

import com.hamhuo.tplanner.persistence.EventWireMapper
import com.hamhuo.tplanner.persistence.JournalWireMapper
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.json.JSONObject

internal data class SyncV3CutoverIntentResult(
    val commands: List<SyncCommandDraft>,
    val eventIds: Set<String>,
    val journalIds: Set<String>,
)

/** Converts the retired dataset outbox into field-level V3 intent exactly once. */
internal object SyncV3CutoverIntents {
    private const val TABLE = "v3_cutover_intents"
    private const val EVENTS = "EVENTS"
    private const val JOURNALS = "JOURNALS"

    fun read(
        db: TPlannerDatabase,
        authoritative: JSONObject?,
    ): SyncV3CutoverIntentResult {
        val sql = db.openHelper.writableDatabase
        if (!tableExists(sql)) return SyncV3CutoverIntentResult(emptyList(), emptySet(), emptySet())
        val remoteTasks = authoritative?.optJSONObject("tasks")
        val remoteJournals = authoritative?.optJSONObject("journals")
        val commands = mutableListOf<SyncCommandDraft>()
        val eventIds = linkedSetOf<String>()
        val journalIds = linkedSetOf<String>()
        sql.query(
            "SELECT dataset, entity_id, payload_json, is_tombstone, base_payload_json " +
                "FROM $TABLE ORDER BY created_at, dataset, entity_id"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dataset = cursor.getString(0)
                val entityId = cursor.getString(1)
                val payload = cursor.getString(2)
                val tombstone = cursor.getInt(3) != 0
                val base = if (cursor.isNull(4)) null else cursor.getString(4)
                when (dataset) {
                    EVENTS -> {
                        eventIds += entityId
                        val after = EventWireMapper.decodeObject(JSONObject(payload))
                        val before = base?.let { EventWireMapper.decodeObject(JSONObject(it)) }
                        val remote = remoteTasks?.optJSONObject(entityId)
                        when {
                            tombstone || after.deletedAt != 0L -> {
                                if (remote != null && remote.optString("lifecycle", "active") == "active") {
                                    commands += SyncCommandDraft(
                                        SyncCommandType.TASK_DELETE,
                                        entityId,
                                        JSONObject(),
                                    )
                                }
                            }
                            before != null -> {
                                if (remote == null) {
                                    throw IllegalStateException(
                                        "V3 cutover cannot safely apply an edit whose central task is missing: $entityId"
                                    )
                                }
                                commands += SyncV3CommandPlanner.taskChange(before, after)
                            }
                            remote == null -> commands += SyncV3CommandPlanner.fullTaskUpsert(after)
                            else -> throw IllegalStateException(
                                "V3 cutover has no base for an existing central task: $entityId"
                            )
                        }
                    }
                    JOURNALS -> {
                        journalIds += entityId
                        val after = JournalWireMapper.decodeWireValue(JSONObject(payload))
                        val before = base?.let { JournalWireMapper.decodeWireValue(JSONObject(it)) }
                        val remote = remoteJournals?.optJSONObject(entityId)
                        when {
                            tombstone || after.deletedAt != 0L -> {
                                if (remote != null && remote.optString("lifecycle", "active") == "active") {
                                    commands += SyncV3CommandPlanner.journalDelete(entityId)
                                }
                            }
                            before != null -> {
                                if (remote == null) {
                                    throw IllegalStateException(
                                        "V3 cutover cannot safely apply a journal edit whose central entry is missing: $entityId"
                                    )
                                }
                                if (before.text != after.text) {
                                    commands += SyncV3CommandPlanner.journalSetText(entityId, after.text)
                                }
                            }
                            remote == null -> commands += SyncV3CommandPlanner.journalSetText(
                                entityId,
                                after.text,
                                ifMissing = true,
                            )
                            else -> throw IllegalStateException(
                                "V3 cutover has no base for an existing central journal: $entityId"
                            )
                        }
                    }
                    else -> throw IllegalStateException("Unsupported cutover dataset: $dataset")
                }
            }
        }
        return SyncV3CutoverIntentResult(commands, eventIds, journalIds)
    }

    fun retire(db: TPlannerDatabase) {
        db.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS $TABLE")
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(TABLE),
        ).use { it.moveToFirst() }
}
