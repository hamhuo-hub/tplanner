package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.EventWireMapper
import com.hamhuo.tplanner.persistence.JournalWireMapper
import com.hamhuo.tplanner.persistence.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.URL

/** Full-dataset three-way synchronization backed by Room shadows and a durable local outbox. */
class LanSyncManager(
    context: Context,
    private val store: JournalStore,
    private val eventStore: EventStore? = null,
) {
    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)

    sealed class SyncResult {
        data class Success(val todayText: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    suspend fun getServerUrl(): String = settings.serverUrl.first()

    suspend fun saveServerUrl(url: String) {
        val normalized = normalizeServerUrl(url).ifBlank { DEFAULT_SERVER_URL }
        settings.setServerUrl(normalized)
    }

    suspend fun fetchEvents(serverUrl: String): List<ScheduleItem> = try {
        syncEventsOrThrow(serverUrl)
    } catch (_: Exception) {
        eventStore?.getAll() ?: emptyList()
    }

    suspend fun syncEventsOrThrow(serverUrl: String): List<ScheduleItem> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val store = eventStore ?: return@withContext emptyList()
            val base = normalizeServerUrl(serverUrl)
            val captured = store.capturedMutations()
            val local = store.getAll()
            val remote = store.fromJson(httpGet("$base/tplanner/events"))
            val merged = mergeEventsWithBase(local, remote, store.baseKeys())
            httpPut("$base/tplanner/events", store.toJson(merged))
            store.applySync(merged, captured)
            store.getAll()
        }
    }

    suspend fun syncJournals(serverUrl: String): SyncResult = try {
        SyncResult.Success(syncJournalsOrThrow(serverUrl))
    } catch (error: Exception) {
        SyncResult.Error(error.message ?: appContext.getString(R.string.unknown_error))
    }

    suspend fun syncJournalsOrThrow(serverUrl: String): String = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val base = normalizeServerUrl(serverUrl)
            val captured = store.capturedMutations()
            val local = store.getAll()
            val remote = store.fromJson(httpGet("$base/tplanner/journals"))
            val merged = mergeJournalsWithBase(local, remote, store.baseKeys())
            httpPut("$base/tplanner/journals", store.toJson(merged))
            store.applySync(merged, captured)
            store.getToday()
        }
    }

    suspend fun syncAllOrThrow(serverUrl: String? = null) {
        val resolvedUrl = serverUrl ?: getServerUrl()
        syncJournalsOrThrow(resolvedUrl)
        syncEventsOrThrow(resolvedUrl)
    }

    private fun mergeEventsWithBase(
        local: List<ScheduleItem>,
        remote: List<ScheduleItem>,
        baseKeys: Map<String, String>?,
    ): List<ScheduleItem> {
        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val allIds = LinkedHashSet(localMap.keys).apply { addAll(remoteMap.keys) }
        return allIds.map { id ->
            val localEvent = localMap[id]
            val remoteEvent = remoteMap[id]
            when {
                localEvent == null -> remoteEvent!!
                remoteEvent == null -> localEvent
                else -> pickEventWithBase(localEvent, remoteEvent, baseKeys?.get(id))
            }
        }
    }

    private fun pickEventWithBase(
        local: ScheduleItem,
        remote: ScheduleItem,
        baseKey: String?,
    ): ScheduleItem {
        val localKey = EventWireMapper.contentKey(local)
        val remoteKey = EventWireMapper.contentKey(remote)
        if (localKey == remoteKey) return local
        if (baseKey != null) {
            if (localKey == baseKey) return remote
            if (remoteKey == baseKey) return local
        }
        if (local.updatedAt != remote.updatedAt) {
            return if (local.updatedAt > remote.updatedAt) local else remote
        }
        // Existing protocol treats the server's already-deterministic winner as authoritative.
        return remote
    }

    private fun mergeJournalsWithBase(
        local: Map<String, JournalEntry>,
        remote: Map<String, JournalEntry>,
        baseKeys: Map<String, String>?,
    ): Map<String, JournalEntry> {
        val allDates = LinkedHashSet(local.keys).apply { addAll(remote.keys) }
        return linkedMapOf<String, JournalEntry>().apply {
            allDates.forEach { date ->
                val localEntry = local[date]
                val remoteEntry = remote[date]
                put(
                    date,
                    when {
                        localEntry == null -> remoteEntry!!
                        remoteEntry == null -> localEntry
                        else -> pickJournalWithBase(localEntry, remoteEntry, baseKeys?.get(date))
                    },
                )
            }
        }
    }

    private fun pickJournalWithBase(
        local: JournalEntry,
        remote: JournalEntry,
        baseKey: String?,
    ): JournalEntry {
        val localKey = JournalWireMapper.contentKey(local)
        val remoteKey = JournalWireMapper.contentKey(remote)
        if (localKey == remoteKey) return local
        if (baseKey != null) {
            if (localKey == baseKey) return remote
            if (remoteKey == baseKey) return local
        }
        if (local.updatedAt != remote.updatedAt) {
            return if (local.updatedAt > remote.updatedAt) local else remote
        }
        return if (localKey >= remoteKey) local else remote
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPut(url: String, body: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = SettingsRepository.DEFAULT_SERVER_URL
        private const val NETWORK_TIMEOUT_MILLIS = 10_000
        private val syncMutex = Mutex()

        fun normalizeServerUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return ""
            val hasScheme = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            return (if (hasScheme) trimmed else "https://$trimmed").trimEnd('/')
        }
    }
}
