package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * PR B/F:前台立即排空(交互热路径)。
 *
 * 本地 Room 事务提交后立刻把 pending 命令泵到 BROKER_PERSISTED —— 不等
 * WorkManager、不 debounce、不等 receipt/delta。
 *
 * **顺序铁律**:WorkManager 的 durable safety net 在泵结束后(finally)才
 * enqueue。两者绝不一起起跑抢同一把 processMutex —— 否则完整 catch-up
 * (下行 + 对账)可能先抢到锁,把「已同步」的确认推后好几秒。进程中途死亡
 * 也不丢数据:Room outbox 是权威状态,且应用启动时必然再次 enqueue。
 */
object SyncV3ForegroundPump {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun request(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                runCatching {
                    val engine = SyncV3Runtime.engine(appContext)
                    val serverUrl = SettingsRepository(appContext).serverUrl.first()
                    engine.pumpToBroker(serverUrl)
                }
            } finally {
                // 前台热路径(无论成败)结束后,才轮到后台收敛。
                runCatching { SyncV3Scheduler.enqueue(appContext) }
            }
        }
    }
}
