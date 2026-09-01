package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.SettingsRepository
import com.hamhuo.tplanner.syncv3.SyncV3Engine
import com.hamhuo.tplanner.syncv3.SyncV3NotificationClient
import com.hamhuo.tplanner.syncv3.SyncV3Phase
import com.hamhuo.tplanner.syncv3.SyncV3RunException
import kotlinx.coroutines.flow.first

/** Semantic commands up, one immutable authoritative projection down. */
class SyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)
    private val engine: SyncV3Engine = SyncV3Runtime.engine(appContext)

    suspend fun getServerUrl(): String = settings.serverUrl.first()

    suspend fun saveServerUrl(url: String) {
        settings.setServerUrl(normalizeServerUrl(url))
    }

    suspend fun syncAllOrThrow(serverUrl: String? = null) {
        val result = engine.syncOnce(serverUrl ?: getServerUrl())
        if (result.phase != SyncV3Phase.SUCCESS) {
            throw SyncV3RunException(
                "ERROR004",
                IllegalStateException(
                    "central snapshot pending: local=${result.installedSnapshotVersion} " +
                        "pending=${result.pendingCommands} uploaded=${result.uploadedCommands}"
                ),
            )
        }
    }

    internal suspend fun awaitRemoteVersion(
        serverUrl: String,
    ): SyncV3NotificationClient.NotificationResult = engine.awaitNewSnapshot(serverUrl)

    companion object {
        const val DEFAULT_SERVER_URL = SettingsRepository.DEFAULT_SERVER_URL
        fun normalizeServerUrl(url: String): String = SyncV3Engine.normalizeServerUrl(url)
    }
}
