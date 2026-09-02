package com.hamhuo.tplanner.syncv3

import android.content.Context
import com.hamhuo.tplanner.CheckItem
import com.hamhuo.tplanner.JournalEntry
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.UserList
import com.hamhuo.tplanner.persistence.JournalEntity
import com.hamhuo.tplanner.persistence.PersistenceMapper
import com.hamhuo.tplanner.persistence.ScheduleItemEntity
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.UserListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class DisplayedStateProjection(
    val events: List<ScheduleItem>,
    val lists: List<UserList>,
    val journals: Map<String, JournalEntry>,
    val json: JSONObject,
)

data class RoomProjectionInstallResult(
    val installed: Boolean,
    val version: Long,
    val displayed: DisplayedStateProjection,
    val authoritative: DisplayedStateProjection,
)

data class RoomProjectionReconcileResult(
    val installedVersion: Long,
    val installedBrokerToSequence: Long,
    val removedCommands: Int,
    val displayed: DisplayedStateProjection,
    val authoritative: DisplayedStateProjection,
)

interface SyncV3ProjectionInstaller {
    fun installAtomically(state: JSONObject, manifest: SnapshotManifest): RoomProjectionInstallResult
    fun markWatchProjectionPublished(version: Long, brokerToSequence: Long)
}

/** Pure authoritative-mirror + pending-overlay projection shared by Room installation tests. */
object SyncV3ProjectionCodec {
    private val requiredTaskKeys = setOf(
        "title",
        "note",
        "completed",
        "itemType",
        "schedule",
        "recurrence",
        "alarm",
        "colorId",
        "location",
        "extras",
        "listId",
        "checklist",
        "lifecycle",
        "deletedAt",
    )
    private val canonicalTaskKeys = setOf(
        "title",
        "note",
        "completed",
        "itemType",
        "schedule",
        "recurrence",
        "alarm",
        "colorId",
        "location",
        "extras",
        "listId",
        "checklist",
        "lifecycle",
        "deletedAt",
    )

    fun validateAuthoritativeState(state: JSONObject) {
        listOf("tasks", "customLists", "journals", "goals", "insights").forEach { key ->
            if (state.optJSONObject(key) == null) {
                throw SyncV3SnapshotInstaller.SnapshotException(
                    "snapshot state is missing object '$key'",
                    "ERROR008",
                )
            }
        }
        state.getJSONObject("tasks").keys().forEach { id ->
            val task = state.getJSONObject("tasks").optJSONObject(id)
                ?: invalid("task '$id' is not an object")
            requiredTaskKeys.forEach { field ->
                if (!task.has(field)) invalid("task '$id' misses '$field'")
            }
            if (task.opt("alarm") !is JSONObject || task.opt("location") !is JSONObject ||
                task.opt("extras") !is JSONObject || task.opt("checklist") !is JSONArray
            ) {
                invalid("task '$id' has malformed durable fields")
            }
            val alarm = task.getJSONObject("alarm")
            if (!alarm.has("enabled") || !alarm.has("offsetMinutes")) {
                invalid("task '$id' has incomplete alarm")
            }
            val location = task.getJSONObject("location")
            if (!location.has("lat") || !location.has("lng")) {
                invalid("task '$id' has incomplete location")
            }
            if (!task.isNull("recurrence")) {
                val recurrence = task.optJSONObject("recurrence")
                    ?: invalid("task '$id' has malformed recurrence")
                if (recurrence.optString("frequency").isBlank() ||
                    !recurrence.has("count") || recurrence.optInt("count", 0) < 1
                ) {
                    invalid("task '$id' has incomplete recurrence")
                }
            }
            val checklist = task.getJSONArray("checklist")
            for (index in 0 until checklist.length()) {
                val item = checklist.optJSONObject(index)
                    ?: invalid("task '$id' checklist[$index] is not an object")
                if (item.optString("id").isBlank() || !item.has("title") ||
                    !item.has("completed") || item.has("text")
                ) {
                    invalid("task '$id' checklist[$index] violates the title contract")
                }
            }
        }
        state.getJSONObject("customLists").keys().forEach { id ->
            val list = state.getJSONObject("customLists").optJSONObject(id)
                ?: invalid("list '$id' is not an object")
            if (!list.has("title") || !list.has("lifecycle") || !list.has("deletedAt")) {
                invalid("list '$id' is incomplete")
            }
        }
        state.getJSONObject("journals").keys().forEach { id ->
            val journal = state.getJSONObject("journals").optJSONObject(id)
                ?: invalid("journal '$id' is not an object")
            if (!journal.has("text") || !journal.has("lifecycle") || !journal.has("deletedAt")) {
                invalid("journal '$id' is incomplete")
            }
        }
        val lists = state.getJSONObject("customLists")
        state.getJSONObject("tasks").keys().forEach { id ->
            val task = state.getJSONObject("tasks").getJSONObject(id)
            if (task.optString("lifecycle", "active") != "active" || task.isNull("listId")) {
                return@forEach
            }
            val listId = task.optString("listId")
            val list = lists.optJSONObject(listId)
            if (list == null || list.optString("lifecycle", "active") != "active") {
                invalid("task '$id' references missing/deleted custom list '$listId'")
            }
        }
    }

    fun replay(authoritative: JSONObject, commands: List<SyncCommandEntity>): JSONObject {
        validateAuthoritativeState(authoritative)
        var state = LocalReducer.fromJson(authoritative)
        commands.sortedBy(SyncCommandEntity::clientSequence).forEach { command ->
            state = LocalReducer.apply(state, command).state
        }
        return LocalReducer.toJson(state)
    }

    fun project(
        displayed: JSONObject,
        existingEvents: Map<String, ScheduleItem> = emptyMap(),
    ): DisplayedStateProjection {
        val tasks = displayed.getJSONObject("tasks")
        val events = buildList {
            tasks.keys().forEach { id ->
                val task = tasks.getJSONObject(id)
                add(taskToPhone(id, task, existingEvents[id]))
            }
        }
        val lists = buildList {
            val source = displayed.getJSONObject("customLists")
            source.keys().forEach { id ->
                val list = source.getJSONObject(id)
                if (list.optString("lifecycle", "active") != "deleted") {
                    add(UserList(id = id, name = list.optString("title", "")))
                }
            }
        }
        val journals = linkedMapOf<String, JournalEntry>().apply {
            val source = displayed.getJSONObject("journals")
            source.keys().forEach { date ->
                val journal = source.getJSONObject(date)
                put(
                    date,
                    JournalEntry(
                        text = journal.optString("text", ""),
                        updatedAt = 0L,
                        deletedAt = if (journal.optString("lifecycle", "active") == "deleted") {
                            nonZeroDeletedAt(journal)
                        } else {
                            0L
                        },
                    )
                )
            }
        }
        return DisplayedStateProjection(events, lists, journals, displayed)
    }

    private fun taskToPhone(id: String, task: JSONObject, existing: ScheduleItem?): ScheduleItem {
        val scheduleWire = task.get("schedule")
        val schedule = scheduleWire as? JSONObject
        val start = schedule?.optString("startAt")
            ?.takeIf(String::isNotEmpty)
            ?.let(::parseInstant)
            ?: Instant.EPOCH
        val end = schedule?.optString("endAt")
            ?.takeIf(String::isNotEmpty)
            ?.let(::parseInstant)
            ?: start

        val checklist = task.optJSONArray("checklist")?.let { source ->
            (0 until source.length()).map { index ->
                val item = source.getJSONObject(index)
                CheckItem(
                    id = item.optString("id", ""),
                    text = item.optString("title", item.optString("text", "")),
                    completed = item.optBoolean("completed", false),
                )
            }
        } ?: existing?.checklist.orEmpty()

        val extras = linkedMapOf<String, Any?>()
        task.optJSONObject("extras")?.let { source ->
            source.keys().forEach { key -> extras[key] = source.get(key).nullIfJsonNull() }
        }
        extras[SYNC_V3_SCHEDULE_WIRE_EXTRA] = when (scheduleWire) {
            is JSONObject -> JSONObject(scheduleWire.toString())
            else -> JSONObject.NULL
        }
        extras[SYNC_V3_ALARM_WIRE_EXTRA] = JSONObject(task.getJSONObject("alarm").toString())
        extras[SYNC_V3_LOCATION_WIRE_EXTRA] = JSONObject(task.getJSONObject("location").toString())
        // Preserve forward-compatible task fields even before this phone release understands them.
        task.keys().forEach { key ->
            if (key !in canonicalTaskKeys && key !in extras) extras[key] = task.get(key).nullIfJsonNull()
        }
        task.optJSONObject("recurrence")?.let { recurrence ->
            // Keep the complete object in Room so a future server field is not erased by this
            // release. Supported editor fields remain available through the historical UI keys.
            extras[SYNC_V3_RECURRENCE_WIRE_EXTRA] = JSONObject(recurrence.toString())
            val frequency = recurrence.optString("frequency").lowercase()
            if (frequency in setOf("daily", "weekly", "monthly")) {
                extras["recurrenceType"] = frequency
                if (recurrence.has("count")) {
                    extras["recurrenceCount"] = recurrence.optInt("count", 1)
                }
            }
        }

        val alarm = task.optJSONObject("alarm")
        val location = task.optJSONObject("location")
        val listId = if (task.has("listId") && !task.isNull("listId")) {
            task.optString("listId", "")
        } else {
            ""
        }
        return ScheduleItem(
            id = id,
            title = task.optString("title", existing?.title.orEmpty()),
            type = task.optString("itemType", existing?.type ?: "task"),
            start = start,
            end = end,
            completed = task.optBoolean("completed", existing?.completed ?: false),
            checklist = checklist,
            colorId = if (task.has("colorId")) task.optInt("colorId", 0) else existing?.colorId ?: 0,
            note = task.optString("note", existing?.note.orEmpty()),
            deletedAt = if (task.optString("lifecycle", "active") == "deleted") {
                nonZeroDeletedAt(task)
            } else {
                0L
            },
            updatedAt = existing?.updatedAt ?: 0L,
            alarmEnabled = alarm?.optBoolean("enabled", false) ?: existing?.alarmEnabled ?: false,
            alarmOffsetMinutes = alarm?.optInt("offsetMinutes", 0)
                ?: existing?.alarmOffsetMinutes
                ?: 0,
            lat = location?.coordinate("lat") ?: 0.0,
            lng = location?.coordinate("lng") ?: 0.0,
            listId = listId,
            extras = extras,
        )
    }

    private fun JSONObject.coordinate(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (error: Exception) {
        throw SyncV3SnapshotInstaller.SnapshotException(
            "invalid schedule instant '$value'",
            "ERROR008",
        ).apply { initCause(error) }
    }

    private fun nonZeroDeletedAt(entity: JSONObject): Long =
        entity.optLong("deletedAt", 1L).coerceAtLeast(1L)

    private fun Any?.nullIfJsonNull(): Any? = if (this === JSONObject.NULL) null else this

    private fun invalid(message: String): Nothing =
        throw SyncV3SnapshotInstaller.SnapshotException(message, "ERROR006")
}

/** Replaces mirror, pending overlay, phone tables, and installed pointer in one SQLite commit. */
class RoomSyncV3ProjectionInstaller(
    private val db: TPlannerDatabase,
    /** Test seam used to prove SQLite rollback after projection rows are staged. */
    private val beforeCommit: () -> Unit = {},
) : SyncV3ProjectionInstaller {
    private val dao = db.syncV3Dao()

    override fun installAtomically(
        state: JSONObject,
        manifest: SnapshotManifest,
    ): RoomProjectionInstallResult = db.runInTransaction<RoomProjectionInstallResult> {
        val meta = dao.getSyncState()
            ?: throw SyncV3SnapshotInstaller.SnapshotException(
                "sync state not initialized",
                "ERROR007",
            )
        if (meta.installedSnapshotVersion > manifest.snapshotVersion) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "snapshot version regressed below installed version",
                "ERROR008",
            )
        }
        if (meta.installedSnapshotVersion == manifest.snapshotVersion) {
            if (meta.installedSnapshotHash != manifest.stateHash) {
                throw SyncV3SnapshotInstaller.SnapshotException(
                    "same snapshot version has conflicting stateHash",
                    "ERROR008",
                )
            }
            if (meta.serverInstanceId != null && manifest.serverInstanceId != null &&
                meta.serverInstanceId != manifest.serverInstanceId
            ) {
                throw SyncV3SnapshotInstaller.SnapshotException(
                    "same snapshot version changed serverInstanceId",
                    "ERROR008",
                )
            }
            if (meta.cursor == null && manifest.cursor != null) {
                // 同版本但本地没有 cursor(例如 pre-V4 构建安装过同版本快照):
                // 只收养 delta 起点,不动任何状态。
                dao.upsertSyncState(meta.copy(cursor = manifest.cursor))
            }
            val existing = dao.eventRows().map(PersistenceMapper::eventToDomain)
            val mirror = meta.serverMirrorJson?.let(::JSONObject)
                ?: throw SyncV3SnapshotInstaller.SnapshotException(
                    "installed snapshot mirror is missing",
                    "ERROR007",
                )
            val removed = dao.deletePublishedCommands(
                manifest.snapshotVersion,
                manifest.brokerToSequence,
            )
            val displayed = SyncV3ProjectionCodec.replay(mirror, dao.listAllCommands())
            val existingById = existing.associateBy(ScheduleItem::id)
            val projected = SyncV3ProjectionCodec.project(displayed, existingById)
            if (removed > 0) replacePhoneRows(projected, dao.eventRows())
            return@runInTransaction RoomProjectionInstallResult(
                installed = false,
                version = meta.installedSnapshotVersion,
                displayed = projected,
                authoritative = SyncV3ProjectionCodec.project(mirror, existingById),
            )
        }
        if (meta.serverInstanceId != null && manifest.serverInstanceId != null &&
            meta.serverInstanceId != manifest.serverInstanceId
        ) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "server instance changed; client must re-bootstrap",
                "ERROR008",
            )
        }
        return@runInTransaction commitInstall(
            meta = meta,
            mirror = state,
            version = manifest.snapshotVersion,
            stateHash = manifest.stateHash,
            brokerToSequence = manifest.brokerToSequence,
            serverInstanceId = manifest.serverInstanceId ?: meta.serverInstanceId,
            cursor = manifest.cursor ?: meta.cursor,
        )
    }

    /**
     * delta-v1 原子安装(§9.3):同一 Room transaction 内替换 server mirror、重放
     * surviving pending overlay、重建 phone 行、推进 installed 指针与 cursor。
     * 版本必须严格新于当前安装(版本链连续性由 SyncV4DeltaInstaller 校验)。
     */
    fun installMirrorAtomically(
        mirror: JSONObject,
        version: Long,
        stateHash: String,
        brokerToSequence: Long,
        serverInstanceId: String?,
        cursor: String?,
    ): RoomProjectionInstallResult = db.runInTransaction<RoomProjectionInstallResult> {
        val meta = dao.getSyncState()
            ?: throw SyncV3SnapshotInstaller.SnapshotException(
                "sync state not initialized",
                "ERROR007",
            )
        if (meta.installedSnapshotVersion >= version) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "delta install version must be strictly newer than the installed version",
                "ERROR008",
            )
        }
        if (meta.serverInstanceId != null && serverInstanceId != null &&
            meta.serverInstanceId != serverInstanceId
        ) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "server instance changed; client must re-bootstrap",
                "ERROR008",
            )
        }
        commitInstall(
            meta = meta,
            mirror = mirror,
            version = version,
            stateHash = stateHash,
            brokerToSequence = brokerToSequence,
            serverInstanceId = serverInstanceId ?: meta.serverInstanceId,
            cursor = cursor ?: meta.cursor,
        )
    }

    /** 调用方必须已处于 Room transaction 内:镜像 + overlay + phone 行 + 指针一锤子提交。 */
    private fun commitInstall(
        meta: SyncStateEntity,
        mirror: JSONObject,
        version: Long,
        stateHash: String,
        brokerToSequence: Long,
        serverInstanceId: String?,
        cursor: String?,
    ): RoomProjectionInstallResult {
        // Receipt rows and the proving snapshot cross the safety barrier in this same SQLite
        // transaction. A crash can therefore expose neither a deleted overlay with an old mirror
        // nor a new mirror with a stale rejected overlay.
        dao.deletePublishedCommands(version, brokerToSequence)
        val existingRows = dao.eventRows()
        val existing = existingRows.map(PersistenceMapper::eventToDomain).associateBy(ScheduleItem::id)
        val displayedJson = SyncV3ProjectionCodec.replay(mirror, dao.listAllCommands())
        val projection = SyncV3ProjectionCodec.project(displayedJson, existing)
        replacePhoneRows(projection, existingRows)
        beforeCommit()
        dao.upsertSyncState(
            meta.copy(
                installedSnapshotVersion = version,
                installedSnapshotHash = stateHash,
                serverInstanceId = serverInstanceId,
                serverMirrorJson = mirror.toString(),
                installedBrokerToSequence = brokerToSequence,
                cursor = cursor,
                syncPhase = "updating",
                syncErrorCode = null,
                syncUpdatedAt = System.currentTimeMillis(),
            ),
        )
        return RoomProjectionInstallResult(
            installed = true,
            version = version,
            displayed = projection,
            authoritative = SyncV3ProjectionCodec.project(mirror, existing),
        )
    }

    /**
     * Reconcile receipts that arrived after the same snapshot was installed. This also supplies
     * an authoritative (overlay-free) Watch projection on every run, so a process death between
     * the Room commit and Data Layer send is retried without needing a newer server version.
     */
    fun reconcileInstalledState(): RoomProjectionReconcileResult? =
        db.runInTransaction<RoomProjectionReconcileResult?> {
            val meta = dao.getSyncState() ?: return@runInTransaction null
            if (meta.installedSnapshotVersion < 1L) return@runInTransaction null
            val mirror = meta.serverMirrorJson?.let(::JSONObject)
                ?: throw SyncV3SnapshotInstaller.SnapshotException(
                    "installed snapshot mirror is missing",
                    "ERROR007",
                )
            val existingRows = dao.eventRows()
            val existing = existingRows.map(PersistenceMapper::eventToDomain)
                .associateBy(ScheduleItem::id)
            val removed = dao.deletePublishedCommands(
                meta.installedSnapshotVersion,
                meta.installedBrokerToSequence,
            )
            val displayed = SyncV3ProjectionCodec.project(
                SyncV3ProjectionCodec.replay(mirror, dao.listAllCommands()),
                existing,
            )
            if (removed > 0) replacePhoneRows(displayed, existingRows)
            RoomProjectionReconcileResult(
                installedVersion = meta.installedSnapshotVersion,
                installedBrokerToSequence = meta.installedBrokerToSequence,
                removedCommands = removed,
                displayed = displayed,
                authoritative = SyncV3ProjectionCodec.project(mirror, existing),
            )
        }

    private fun replacePhoneRows(
        projection: DisplayedStateProjection,
        existingRows: List<ScheduleItemEntity>,
    ) {
        val sortById = existingRows.associate { it.id to it.sortIndex }
        val eventRows = projection.events.mapIndexed { index, event ->
            PersistenceMapper.eventToEntity(event, sortById[event.id] ?: index.toLong())
        }
        val journalRows = projection.journals.map { (date, entry) ->
            PersistenceMapper.journalToEntity(date, entry)
        }
        val listRows = projection.lists.mapIndexed { index, list ->
            UserListEntity(id = list.id, name = list.name, sortOrder = index)
        }

        dao.clearEvents()
        dao.clearJournals()
        dao.clearUserLists()
        if (eventRows.isNotEmpty()) dao.upsertEventRows(eventRows)
        if (journalRows.isNotEmpty()) dao.upsertJournalRows(journalRows)
        if (listRows.isNotEmpty()) dao.upsertUserListRows(listRows)
    }

    override fun markWatchProjectionPublished(version: Long, brokerToSequence: Long) {
        db.runInTransaction { dao.markWatchProjection(version, brokerToSequence) }
    }
}

data class SyncV3ProgressSnapshot(
    val installedSnapshotVersion: Long,
    val watchProjectionSnapshotVersion: Long,
    val watchProjectionBrokerToSequence: Long,
    val phase: String,
    val errorCode: String?,
)

/** Stable query point used by the Watch bridge for SNAPSHOT_PUBLISHED receipts. */
object SyncV3Progress {
    fun installedSnapshotVersion(context: Context): Long = state(context)?.installedSnapshotVersion ?: 0L

    fun watchProjectionSnapshotVersion(context: Context): Long =
        state(context)?.watchProjectionSnapshotVersion ?: 0L

    fun watchProjectionBrokerToSequence(context: Context): Long =
        state(context)?.watchProjectionBrokerToSequence ?: 0L

    fun receipts(
        context: Context,
        commandIds: Collection<String>,
    ): Map<String, SyncReceiptEntity> {
        if (commandIds.isEmpty()) return emptyMap()
        val dao = TPlannerDatabase.get(context.applicationContext).syncV3Dao()
        val requested = commandIds.distinct()
        val archived = requested.mapNotNull { commandId ->
            dao.migrationMarker(SyncV3ArchivedReceipts.markerId(commandId))
                ?.sourceDigest
                ?.let(SyncV3ArchivedReceipts::decode)
                ?.copy(commandId = commandId)
                ?.let { commandId to it }
        }.toMap()
        val resolved = requested.associateWith { commandId ->
            SyncV3CommandAliases.resolve(commandId) { current ->
                dao.migrationMarker(SyncV3CommandAliases.markerId(current))?.sourceDigest
            }
        }
        val receiptsByResolvedId = dao.receipts(resolved.values.distinct())
            .associateBy(SyncReceiptEntity::commandId)
        return resolved.mapNotNull { (requestedId, resolvedId) ->
            archived[requestedId]
                ?: dao.migrationMarker(SyncV3ArchivedReceipts.markerId(resolvedId))
                    ?.sourceDigest
                    ?.let(SyncV3ArchivedReceipts::decode)
                    ?.copy(commandId = requestedId)
                ?: receiptsByResolvedId[resolvedId]?.copy(commandId = requestedId)
        }.associateBy(SyncReceiptEntity::commandId)
    }

    fun observe(context: Context): Flow<SyncV3ProgressSnapshot?> =
        TPlannerDatabase.get(context.applicationContext).syncV3Dao().observeSyncState().map { state ->
            state?.let {
                SyncV3ProgressSnapshot(
                    installedSnapshotVersion = it.installedSnapshotVersion,
                    watchProjectionSnapshotVersion = it.watchProjectionSnapshotVersion,
                    watchProjectionBrokerToSequence = it.watchProjectionBrokerToSequence,
                    phase = it.syncPhase,
                    errorCode = it.syncErrorCode,
                )
            }
        }

    private fun state(context: Context): SyncStateEntity? =
        TPlannerDatabase.get(context.applicationContext).syncV3Dao().getSyncState()
}
