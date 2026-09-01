package com.hamhuo.tplanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hamhuo.tplanner.persistence.SettingsRepository
import com.hamhuo.tplanner.syncv3.SyncV3Engine
import com.hamhuo.tplanner.syncv3.SyncV3Phase
import com.hamhuo.tplanner.syncv3.SyncV3RunException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * One replaceable queue pump. Repeated local operations never append successor WorkRequests;
 * cancelling/replacing a running request is safe because the Room command outbox is authoritative.
 */
object SyncV3Scheduler {
    internal const val UNIQUE_WORK_NAME = "tplanner-sync-v3-pump"
    internal val existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

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

class SyncV3PumpWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val serverUrl = SettingsRepository(applicationContext).serverUrl.first()
        return try {
            val result = SyncV3Runtime.engine(applicationContext).syncOnce(serverUrl)
            if (result.phase == SyncV3Phase.SUCCESS) Result.success() else Result.retry()
        } catch (error: SyncV3RunException) {
            when (error.errorCode) {
                "ERROR008", "ERROR009", "ERROR010" -> Result.failure()
                else -> Result.retry()
            }
        }
    }
}
