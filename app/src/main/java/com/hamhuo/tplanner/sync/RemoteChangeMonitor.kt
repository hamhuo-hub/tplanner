package com.hamhuo.tplanner

import android.util.Log
import com.hamhuo.tplanner.syncv3.SyncV3NotificationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 过期通知判定:长轮询以旧 installed 发出后,Worker/其它链路可能已经抢先
 * 安装到 latestVersion —— 此时再跑一轮只会重复同一版本,直接丢弃通知。
 */
internal fun shouldHandleNotice(
    notice: SyncV3NotificationClient.NotificationResult,
    installedVersion: Long,
): Boolean = notice.hasNewVersion && installedVersion < notice.latestVersion

/** Foreground V3 version monitor. Notifications never contain or merge dataset fragments. */
internal class RemoteChangeMonitor(
    private val manager: SyncManager,
) {
    suspend fun run() {
        var retryDelayMillis = INITIAL_RETRY_MILLIS
        while (currentCoroutineContext().isActive) {
            try {
                val serverUrl = manager.getServerUrl()
                val notice = manager.awaitRemoteVersion(serverUrl)
                retryDelayMillis = INITIAL_RETRY_MILLIS
                if (shouldHandleNotice(notice, manager.installedSnapshotVersion())) {
                    val operationId = SyncCoordinator.requestSync(SyncReason.REMOTE_CHANGE) { report ->
                        // 非阻塞下行(PR F):拉一次 delta/snapshot 即完成,
                        // 绝不 long-poll 等 publication —— 与 Worker 同一条铁律。
                        manager.syncBackground(serverUrl)
                        report(SyncPhase.UPDATING)
                    }
                    // Joining an already-running transaction must also await that transaction.
                    // Otherwise the long-poll cursor is still old and the same version can wake a
                    // tight request loop while the real sync is in flight.
                    val terminal = SyncCoordinator.awaitCompletion(operationId)
                    if (terminal.phase == SyncPhase.ERROR) {
                        throw IllegalStateException(
                            terminal.errorCode ?: "ERROR008",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "V3 version listener failed; retrying with backoff", error)
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    companion object {
        private const val TAG = "SyncV3Monitor"
        private const val INITIAL_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
    }
}
