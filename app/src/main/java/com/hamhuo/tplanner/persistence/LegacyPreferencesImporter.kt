package com.hamhuo.tplanner.persistence

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONObject

sealed interface LegacyImportResult {
    data object AlreadyImported : LegacyImportResult

    data class Imported(
        val events: Int,
        val journals: Int,
        val drafts: Int,
        val warnings: List<LegacyIssue>,
    ) : LegacyImportResult

    data class Blocked(val issues: List<LegacyIssue>) : LegacyImportResult
}
data class LegacyIssue(
    val source: String,
    val key: String?,
    val message: String,
    val fatal: Boolean,
)

data class LegacySnapshot(
    val eventsJson: String,
    val noteDrafts: Map<String, Any?>,
    val journals: Map<String, Any?>,
    val journalDrafts: Map<String, Any?>,
    val serverUrl: String?,
)

data class DecodedLegacy(
    val events: List<ScheduleItemEntity>,
    val journals: List<JournalEntity>,
    val drafts: List<EditDraftEntity>,
    val warnings: List<LegacyIssue>,
    val sourceDigest: String,
)

/**
 * One-shot SharedPreferences -> Room importer. Legacy preferences remain untouched for at least one
 * release so a blocked or interrupted migration is always recoverable.
 */
class LegacyPreferencesImporter(
    context: Context,
    private val db: TPlannerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    suspend fun importIfNeeded(): LegacyImportResult {
        if (db.migrationDao().marker(MARKER_ID) != null) {
            return LegacyImportResult.AlreadyImported
        }

        val decoded = decode(snapshotPreferences())
        if (decoded.warnings.any { it.fatal }) {
            return LegacyImportResult.Blocked(decoded.warnings)
        }

        return db.withTransaction {
            if (db.migrationDao().marker(MARKER_ID) != null) {
                return@withTransaction LegacyImportResult.AlreadyImported
            }
            if (!databaseIsEmpty()) {
                return@withTransaction LegacyImportResult.Blocked(
                    decoded.warnings + LegacyIssue(
                        source = "room",
                        key = null,
                        message = "Room contains data without a completed legacy migration marker",
                        fatal = true,
                    )
                )
            }

            if (decoded.events.isNotEmpty()) db.eventDao().upsertAll(decoded.events)
            if (decoded.journals.isNotEmpty()) db.journalDao().upsertAll(decoded.journals)
            if (decoded.drafts.isNotEmpty()) db.draftDao().upsertAll(decoded.drafts)
            db.migrationDao().insertMarker(
                MigrationMarkerEntity(
                    id = MARKER_ID,
                    completedAt = clock(),
                    sourceDigest = decoded.sourceDigest,
                    eventCount = decoded.events.size,
                    journalCount = decoded.journals.size,
                    draftCount = decoded.drafts.size,
                )
            )
            LegacyImportResult.Imported(
                events = decoded.events.size,
                journals = decoded.journals.size,
                drafts = decoded.drafts.size,
                warnings = decoded.warnings,
            )
        }
    }

    internal fun snapshotPreferences(): LegacySnapshot {
        val eventPrefs = appContext.getSharedPreferences(EVENT_PREFS, Context.MODE_PRIVATE)
        val journalPrefs = appContext.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
        val journalDraftPrefs = appContext.getSharedPreferences(
            JOURNAL_DRAFT_PREFS,
            Context.MODE_PRIVATE,
        )
        val syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)

        val noteDrafts = buildMap {
            eventPrefs.all.toSortedMap().forEach { (key, value) ->
                if (key.startsWith(NOTE_DRAFT_PREFIX)) {
                    put(key.removePrefix(NOTE_DRAFT_PREFIX), value)
                }
            }
        }
        val journalDrafts = buildMap {
            journalDraftPrefs.all.toSortedMap().forEach { (key, value) ->
                put(key, value)
            }
        }
        return LegacySnapshot(
            eventsJson = eventPrefs.getString(EVENTS_KEY, "[]") ?: "[]",
            noteDrafts = noteDrafts,
            journals = journalPrefs.all.toSortedMap(),
            journalDrafts = journalDrafts,
            serverUrl = syncPrefs.getString(SERVER_URL_KEY, null),
        )
    }

    internal fun decode(snapshot: LegacySnapshot): DecodedLegacy {
        val issues = mutableListOf<LegacyIssue>()
        val eventDomain = try {
            EventWireMapper.decodeArrayStrict(snapshot.eventsJson)
        } catch (error: Exception) {
            issues += LegacyIssue(
                source = EVENT_PREFS,
                key = EVENTS_KEY,
                message = error.message ?: "Cannot decode events",
                fatal = true,
            )
            emptyList()
        }
        eventDomain.groupingBy { it.id }.eachCount().filterValues { it > 1 }.forEach { (id, _) ->
            issues += LegacyIssue(
                source = EVENT_PREFS,
                key = id,
                message = "Duplicate event id",
                fatal = true,
            )
        }
        val events = eventDomain.mapIndexed { index, event ->
            PersistenceMapper.eventToEntity(event, index.toLong())
        }

        val journalDomain = linkedMapOf<String, com.hamhuo.tplanner.JournalEntry>()
        snapshot.journals.forEach { (date, value) ->
            try {
                journalDomain[date] = JournalWireMapper.decodeLegacyPreferenceValue(value)
            } catch (error: Exception) {
                issues += LegacyIssue(
                    source = JOURNAL_PREFS,
                    key = date,
                    message = error.message ?: "Cannot decode journal",
                    fatal = true,
                )
            }
        }
        val journals = journalDomain.map { (date, entry) ->
            PersistenceMapper.journalToEntity(date, entry)
        }

        val eventById = eventDomain.associateBy { it.id }
        val drafts = buildList {
            snapshot.noteDrafts.forEach { (eventId, value) ->
                val text = value as? String
                if (text == null) {
                    issues += LegacyIssue(
                        source = EVENT_PREFS,
                        key = "$NOTE_DRAFT_PREFIX$eventId",
                        message = "Legacy event draft is not a string",
                        fatal = true,
                    )
                    return@forEach
                }
                val base = eventById[eventId]
                add(
                    PersistenceMapper.draftToEntity(
                        VersionedDraft(
                            target = DraftTarget.event(eventId),
                            content = text,
                            baseHash = baseHash(base?.note.orEmpty()),
                            baseUpdatedAt = base?.updatedAt ?: 0L,
                            baseEntityExists = base != null,
                            draftUpdatedAt = 0L,
                        )
                    )
                )
            }
            snapshot.journalDrafts.forEach { (date, value) ->
                val text = value as? String
                if (text == null) {
                    issues += LegacyIssue(
                        source = JOURNAL_DRAFT_PREFS,
                        key = date,
                        message = "Legacy journal draft is not a string",
                        fatal = true,
                    )
                    return@forEach
                }
                val base = journalDomain[date]
                add(
                    PersistenceMapper.draftToEntity(
                        VersionedDraft(
                            target = DraftTarget.journal(date),
                            content = text,
                            baseHash = baseHash(base?.text.orEmpty()),
                            baseUpdatedAt = base?.updatedAt ?: 0L,
                            baseEntityExists = base != null,
                            draftUpdatedAt = 0L,
                        )
                    )
                )
            }
        }

        return DecodedLegacy(
            events = events,
            journals = journals,
            drafts = drafts,
            warnings = issues,
            sourceDigest = digest(snapshot),
        )
    }

    private suspend fun databaseIsEmpty(): Boolean =
        db.eventDao().count() == 0 &&
            db.journalDao().count() == 0 &&
            db.draftDao().count() == 0 &&
            db.pendingActionDao().count() == 0 &&
            db.migrationDao().count() == 0

    private fun digest(snapshot: LegacySnapshot): String = baseHash(
        buildString {
            appendPart("events", snapshot.eventsJson)
            snapshot.noteDrafts.toSortedMap().forEach { (key, value) ->
                appendPart("note:$key:${value?.javaClass?.name}", value?.toString().orEmpty())
            }
            snapshot.journals.toSortedMap().forEach { (key, value) ->
                appendPart("journal:$key:${value?.javaClass?.name}", value?.toString().orEmpty())
            }
            snapshot.journalDrafts.toSortedMap().forEach { (key, value) ->
                appendPart(
                    "journal-draft:$key:${value?.javaClass?.name}",
                    value?.toString().orEmpty(),
                )
            }
            appendPart("server-url", snapshot.serverUrl.orEmpty())
        }
    )

    private fun StringBuilder.appendPart(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value)
    }

    companion object {
        const val MARKER_ID = "shared_prefs_v1"
        private const val EVENT_PREFS = "tplanner_events"
        private const val EVENTS_KEY = "events"
        private const val NOTE_DRAFT_PREFIX = "note_draft:"
        private const val JOURNAL_PREFS = "tplanner_journals"
        private const val JOURNAL_DRAFT_PREFS = "tplanner_journal_drafts"
        private const val SYNC_PREFS = "tplanner_sync_config"
        private const val SERVER_URL_KEY = "serverUrl"
    }
}
