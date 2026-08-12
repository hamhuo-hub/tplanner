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
import com.hamhuo.tplanner.persistence.SyncDatasets
import com.hamhuo.tplanner.persistence.SyncOutboxEntity
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import java.util.concurrent.TimeUnit

object SyncOutboxScheduler {
    private const val UNIQUE_WORK_NAME = "tplanner-sync-outbox"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            // Always append a successor. KEEP can strand a mutation that is committed after a
            // running worker's final empty check but before that worker transitions to finished.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class SyncOutboxWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val database = TPlannerDatabase.get(applicationContext)
        val syncDao = database.syncDao()
        if (syncDao.pendingCountNow() == 0) return Result.success()

        val journalStore = JournalStore(applicationContext, database)
        val eventStore = EventStore(applicationContext, database)
        val manager = LanSyncManager(applicationContext, journalStore, eventStore)
        val failures = mutableListOf<Pair<List<SyncOutboxEntity>, Throwable>>()

        val journalMutations = syncDao.outbox(SyncDatasets.JOURNALS)
        if (journalMutations.isNotEmpty()) {
            runCatching { manager.syncJournalsOrThrow(manager.getServerUrl()) }
                .onFailure { failures += journalMutations to it }
        }

        val eventMutations = syncDao.outbox(SyncDatasets.EVENTS)
        if (eventMutations.isNotEmpty()) {
            runCatching { manager.syncEventsOrThrow(manager.getServerUrl()) }
                .onFailure { failures += eventMutations to it }
        }

        if (failures.isNotEmpty()) {
            val nextAttemptAt = System.currentTimeMillis() + retryDelayMillis(runAttemptCount)
            failures.forEach { (mutations, error) ->
                val message = (error.message ?: error.javaClass.simpleName).take(1_000)
                mutations.forEach { mutation ->
                    syncDao.recordFailure(
                        dataset = mutation.dataset,
                        entityId = mutation.entityId,
                        mutationToken = mutation.mutationToken,
                        nextAttemptAt = nextAttemptAt,
                        error = message,
                    )
                }
            }
            return Result.retry()
        }

        // A mutation may have raced with the HTTP request. Token guards leave it in the outbox;
        // retry instead of returning success and stranding it until the next foreground launch.
        return if (syncDao.pendingCountNow() == 0) Result.success() else Result.retry()
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 8)
        return (10_000L shl exponent).coerceAtMost(TimeUnit.HOURS.toMillis(1))
    }
}
