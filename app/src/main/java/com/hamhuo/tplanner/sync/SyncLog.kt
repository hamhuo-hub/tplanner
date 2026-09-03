package com.hamhuo.tplanner

import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.syncv3.SyncLogEntity
import com.hamhuo.tplanner.syncv3.SyncV3Dao
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级同步日志记录器(纯诊断,不参与任何正确性路径)。
 *
 * 写入走单线程 executor + Room,绝不阻塞同步热路径;只保留最近 [MAX_ENTRIES]
 * 条。UI 通过 SyncV3Dao.observeRecentLogs 实时查看。
 */
object SyncLog {
    const val MAX_ENTRIES = 500

    const val LEVEL_INFO = "info"
    const val LEVEL_WARN = "warn"
    const val LEVEL_ERROR = "error"

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-sync-log").apply { isDaemon = true }
    }

    @Volatile
    private var dao: SyncV3Dao? = null
    private val initialized = AtomicBoolean(false)

    /** 由 SyncV3Engine 构造时调用;测试可注入内存数据库。 */
    fun init(database: TPlannerDatabase) {
        dao = database.syncV3Dao()
        initialized.set(true)
    }

    fun info(source: String, message: String, detail: String? = null) =
        record(LEVEL_INFO, source, message, detail, null)

    fun warn(source: String, message: String, detail: String? = null) =
        record(LEVEL_WARN, source, message, detail, null)

    fun error(source: String, message: String, detail: String? = null, errorCode: String? = null) =
        record(LEVEL_ERROR, source, message, detail, errorCode)

    private fun record(
        level: String,
        source: String,
        message: String,
        detail: String?,
        errorCode: String?,
    ) {
        if (!initialized.get()) return
        val target = dao ?: return
        writer.execute {
            runCatching {
                target.appendLog(
                    SyncLogEntity(
                        createdAt = System.currentTimeMillis(),
                        level = level,
                        source = source,
                        message = message,
                        detail = detail,
                        errorCode = errorCode,
                    ),
                    keep = MAX_ENTRIES,
                )
            }
        }
    }
}
