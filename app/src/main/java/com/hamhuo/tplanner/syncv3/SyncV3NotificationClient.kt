package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.io.IOException

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
        val url = "$serverUrl/tplanner/v3/notifications?afterVersion=$after&wait=$waitMs"
        // 客户端 read timeout 必须显著大于服务端 long-poll wait(留余量,避免边界竞态);
        // 长轮询偶尔碰上死连接(GET 幂等)时立即重试一次,绝不把一整轮 poll 浪费掉。
        val response = getWithOneRetry(url, waitMs + POLL_TIMEOUT_MARGIN_MS)
        if (!response.isOk) {
            throw SyncV3Uploader.SyncException("notifications request failed: ${response.code}", response.code, null)
        }
        val body: JSONObject = response.json()
        val latest = body.optLong("latestVersion", 0)
        return NotificationResult(latest > after, latest)
    }

    private fun getWithOneRetry(url: String, timeoutMs: Int): SyncHttpResponse {
        try {
            return http.get(url, timeoutMs)
        } catch (error: IOException) {
            // 假超时 / EOF / Socket closed:连接已死,换新连接再试一次。
            return http.get(url, timeoutMs)
        }
    }

    /** 轮询一次并在有新版本时立即安装。返回是否有新版本。 */
    fun pollAndSync(installer: SyncV3SnapshotInstaller): NotificationResult {
        val result = pollOnce()
        if (result.hasNewVersion) {
            installer.syncToLatest()
        }
        return result
    }

    private companion object {
        const val POLL_TIMEOUT_MARGIN_MS = 5_000
    }
}
