package com.hamhuo.tplanner.syncv3

import org.json.JSONObject

/**
 * 版本通知长轮询(见 docs/sync-v3.md §12/§14):通知只含 version + hash,
 * 不含数据片段;收到新版本由上层触发快照安装。与桌面 notificationClient.js 同语义。
 */
class SyncV3NotificationClient(
    private val store: SyncV3Store,
    private val http: SyncHttpClient,
    private val serverUrl: String,
    private val waitMs: Int = 25_000,
) {

    data class NotificationResult(val hasNewVersion: Boolean, val latestVersion: Long)

    /** 单次长轮询:有新版本返回 true(不自动安装,由调用方决定时机)。 */
    fun pollOnce(): NotificationResult {
        val meta = store.getSyncState() ?: return NotificationResult(false, 0)
        val after = meta.installedSnapshotVersion
        val response = http.get("$serverUrl/tplanner/v3/notifications?afterVersion=$after&wait=$waitMs")
        if (!response.isOk) {
            throw SyncV3Uploader.SyncException("notifications request failed: ${response.code}", response.code, null)
        }
        val body: JSONObject = response.json()
        val latest = body.optLong("latestVersion", 0)
        return NotificationResult(latest > after, latest)
    }

    /** 轮询一次并在有新版本时立即安装。返回是否有新版本。 */
    fun pollAndSync(installer: SyncV3SnapshotInstaller): NotificationResult {
        val result = pollOnce()
        if (result.hasNewVersion) {
            installer.syncToLatest()
        }
        return result
    }
}
