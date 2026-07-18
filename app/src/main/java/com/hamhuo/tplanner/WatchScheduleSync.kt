package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Phone → Watch: 发送 past 未完成任务 + later 即将事件的开始分钟数。
 * 手表侧全部绘制为蓝色半透明小点。
 */
object WatchScheduleSync {
    private const val TAG = "TplannerWatchSync"
    private const val PATH = "/tplanner/schedule"

    fun push(context: Context, events: List<TaskEvent>) {
        Thread {
            try {
                val zone = ZoneId.systemDefault()

                // past 未完成 + later 即将 = 所有未完成任务
                val minutes = events
                    .filter { e -> e.deletedAt == 0L && e.type == "task" && !e.completed }
                    .map { e ->
                        val local = e.start.atZone(zone)
                        local.hour * 60 + local.minute
                    }
                    .filter { it in 0..1439 }
                    .distinct()
                    .sorted()

                val payload = JSONObject().apply {
                    put("minutes", JSONArray(minutes))
                }

                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes, 3, java.util.concurrent.TimeUnit.SECONDS)
                if (nodes.isNullOrEmpty()) { Log.w(TAG, "push: no connected nodes"); return@Thread }
                val watchNode = nodes.firstOrNull { it.isNearby } ?: nodes.first()
                val request = PutDataRequest.create(PATH).setUrgent()
                request.data = payload.toString().toByteArray(Charsets.UTF_8)
                Tasks.await(Wearable.getDataClient(context).putDataItem(request))
                Log.d(TAG, "push: sent minutes=$minutes")
            } catch (e: Exception) {
                Log.e(TAG, "push: failed", e)
            }
        }.start()
    }
}
