package com.hamhuo.tplanner

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PR C:交互热路径的同步反馈事件(与 SyncCoordinator 的收敛事务无关)。
 *
 *   Sending        本地 Room 事务已提交,命令已入 outbox(顶部 Gold「正在同步」)
 *   CloudAccepted  BROKER_PERSISTED(202)已收到 —— 用户确认点(顶部 Teal「已同步」)
 *   FailedLocally  热路径失败,修改仍安全保存在本机(顶部 Red)
 *
 * 收敛(receipt / delta / pending 清理)继续在后台完成,不参与用户反馈。
 */
sealed class SyncFeedbackEvent {
    object Sending : SyncFeedbackEvent()
    data class CloudAccepted(val serverHost: String) : SyncFeedbackEvent()
    data class FailedLocally(val errorCode: String?) : SyncFeedbackEvent()
}

object SyncFeedbackBus {
    private val eventsFlow = MutableSharedFlow<SyncFeedbackEvent>(extraBufferCapacity = 16)

    val events = eventsFlow.asSharedFlow()

    fun publish(event: SyncFeedbackEvent) {
        eventsFlow.tryEmit(event)
    }
}
