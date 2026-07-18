package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 事件刻度数据：由手机端将当日事件写入（分钟数 0-1439 + 下一个事件）。
// 手表侧暂无同步通道时为空——表盘退化为纯时间显示，不画假数据。
object WatchEventMarks {
    data class Marks(val minutes: List<Int>, val nextMinute: Int?, val nextTitle: String?)

    val EMPTY = Marks(emptyList(), null, null)

    fun load(context: Context): Marks = try {
        val raw = context.getSharedPreferences("tplanner_watch_marks", Context.MODE_PRIVATE)
            .getString("marks_json", null)
        if (raw == null) EMPTY else {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("minutes") ?: JSONArray()
            val minutes = (0 until arr.length()).map { arr.getInt(it) }.filter { it in 0..1439 }
            val next = obj.optJSONObject("next")
            Marks(
                minutes,
                next?.optInt("minute", -1)?.takeIf { it in 0..1439 },
                next?.optString("title")?.takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) { EMPTY }
}
