package com.hamhuo.tplanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hamhuo.tplanner.persistence.SettingsRepository
import com.hamhuo.tplanner.syncv3.SyncV3Engine
import com.hamhuo.tplanner.syncv3.SyncV3Phase
import com.hamhuo.tplanner.syncv3.SyncV3RunException
import com.hamhuo.tplanner.syncv3.SyncV3RunResult
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Durable safety-net scheduling。Room outbox 是权威队列:正在运行的 worker
 * 本来就能看到新写入的 command,KEEP 保证新保存不打断正在进行的 catch-up,
 * 也不会制造 "cancel A → 重建 B" 的链条。前台热路径由 SyncV3ForegroundPump 负责。
 */
object SyncV3Scheduler {
    internal const val UNIQUE_WORK_NAME = "tplanner-sync-v3-pump"
    internal val existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncV3PumpWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            existingWorkPolicy,
            request,
        )
    }
}

/**
 * PR A+F:Worker 是 durable safety net,不是热路径的延续。
 *
 * 它跑 [SyncV3Engine.syncBackgroundOnce] —— 补传 pending、收一次回执、
 * 拉一次 delta/snapshot 后立即返回,**绝不 long-poll 等 publication**;
 * 剩余收敛由 RemoteChangeMonitor / 下一次通知或触发继续。UPLOADED 与
 * SUCCESS 一样算成功,只有真失败才吃 WorkManager 的 10s backoff。
 */
internal fun SyncV3RunResult.toWorkResult(): ListenableWorker.Result = when (phase) {
    SyncV3Phase.SUCCESS, SyncV3Phase.UPLOADED -> ListenableWorker.Result.success()
    else -> ListenableWorker.Result.retry()
}

class SyncV3PumpWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val serverUrl = SettingsRepository(applicationContext).serverUrl.first()
        return try {
            val result = SyncV3Runtime.engine(applicationContext).syncBackgroundOnce(serverUrl)
            result.toWorkResult()
        } catch (error: SyncV3RunException) {
            when (error.errorCode) {
                "ERROR008", "ERROR009", "ERROR010" -> Result.failure()
                else -> Result.retry()
            }
        }
    }
}
