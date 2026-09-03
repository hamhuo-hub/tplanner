package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * PR B:前台立即排空(交互热路径)。
 *
 * 本地 Room 事务提交后立刻把 pending 命令泵到 BROKER_PERSISTED —— 不等
 * WorkManager、不 debounce、不等 receipt/delta。WorkManager 仍是 durable
 * safety net(进程死亡 / 断网兜底),但不再是实时发送器。
 *
 * 并发由 SyncV3Engine 的 processMutex 串行化;重复触发是空转 no-op。
 */
object SyncV3ForegroundPump {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun request(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val engine = SyncV3Runtime.engine(appContext)
                val serverUrl = SettingsRepository(appContext).serverUrl.first()
                engine.pumpToBroker(serverUrl)
            }
        }
    }
}
