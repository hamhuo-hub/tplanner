package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.UUID

internal data class RemoteChangeNotice(
    val revision: Long,
    val datasets: Set<String>,
) {
    companion object {
        fun fromJson(raw: String): RemoteChangeNotice {
            val json = JSONObject(raw)
            val datasetsJson = json.optJSONArray("datasets")
            val datasets = buildSet {
                if (datasetsJson != null) {
                    for (index in 0 until datasetsJson.length()) {
                        datasetsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            return RemoteChangeNotice(
                revision = json.optLong("revision", 0L),
                datasets = datasets,
            )
        }
    }
}

internal object SyncClientIdentity {
    private const val PREFERENCES = "sync_client_identity"
    private const val CLIENT_ID = "client_id"

    @Synchronized
    fun get(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(CLIENT_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        return "android-${UUID.randomUUID()}".also { generated ->
            preferences.edit().putString(CLIENT_ID, generated).apply()
        }
    }
}

/**
 * Foreground long-poll client. Notifications carry dataset names only; this class then asks the
 * regular merge engine to actively pull the authoritative snapshot. Local writes remain owned by
 * the durable Room outbox, so losing the foreground process cannot lose an upload.
 */
internal class RemoteChangeMonitor(
    private val manager: LanSyncManager,
) {
    suspend fun run() {
        var revision = 0L
        var retryDelayMillis = INITIAL_RETRY_MILLIS

        while (currentCoroutineContext().isActive) {
            try {
                val serverUrl = manager.getServerUrl()
                val notice = manager.awaitRemoteChanges(serverUrl, revision)
                revision = notice.revision.takeIf { it > 0L } ?: revision
                retryDelayMillis = INITIAL_RETRY_MILLIS

                if (notice.datasets.isNotEmpty()) {
                    // A remote notification is a request, not an animation trigger. It joins any
                    // active startup/manual transaction and all screens observe that same id.
                    SyncCoordinator.requestSync(SyncReason.REMOTE_CHANGE) { report ->
                        // Always install one coherent projection. Dataset-specific pulls can lose
                        // a second notification when it joins this single-flight transaction.
                        manager.syncAllOrThrow(serverUrl)
                        report(SyncPhase.UPDATING)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Remote change listener failed; retrying", error)
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    companion object {
        private const val TAG = "RemoteChangeMonitor"
        private const val INITIAL_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
    }
}
