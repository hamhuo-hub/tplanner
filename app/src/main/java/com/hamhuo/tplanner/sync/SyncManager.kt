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

    /**
     * PR A:交互同步 —— 到 BROKER_PERSISTED 即返回(用户确认点)。
     * 与 [syncAllOrThrow](完全收敛)分开,不再让"保存一条事项"等整个
     * receipt/delta/coverage 链。
     */
    suspend fun syncInteractive(serverUrl: String? = null) {
        engine.pumpToBroker(serverUrl ?: getServerUrl())
    }

    /**
     * PR F:后台收敛(RemoteChangeMonitor / 通知唤醒)—— 非阻塞下行,
     * 绝不 long-poll 等 publication;真失败仍抛异常供上层 backoff。
     */
    suspend fun syncBackground(serverUrl: String? = null) {
        engine.syncBackgroundOnce(serverUrl ?: getServerUrl())
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
