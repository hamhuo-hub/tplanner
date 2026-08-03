package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

internal const val WATCH_MARKS_PREFS = "tplanner_watch_marks"
internal const val WATCH_MARKS_KEY = "marks_json"

// 事件刻度数据：由手机端将当日事件写入（分钟数 0-1439 + 下一个事件）。
// 手表侧暂无同步通道时为空——表盘退化为纯时间显示，不画假数据。
object WatchEventMarks {
    data class Marks(val minutes: List<Int>, val nextMinute: Int?, val nextTitle: String?)

    val EMPTY = Marks(emptyList(), null, null)

    fun load(context: Context): Marks = try {
        val raw = context.getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            .getString(WATCH_MARKS_KEY, null)
        if (raw == null) EMPTY else {
            val obj = JSONObject(raw)
            val today = ZonedDateTime.now(APP_ZONE).toLocalDate().toString()
            val days = obj.optJSONArray("days")
            val todaySnapshot = if (days == null) {
                null
            } else {
                (0 until days.length())
                    .mapNotNull { index -> days.optJSONObject(index) }
                    .firstOrNull { day -> day.optString("date") == today }
            }
            if (todaySnapshot == null) {
                EMPTY
            } else {
                val arr = todaySnapshot.optJSONArray("minutes") ?: JSONArray()
                val minutes = (0 until arr.length()).map { arr.getInt(it) }.filter { it in 0..1439 }
                Marks(
                    minutes,
                    null,
                    null,
                )
            }
        }
    } catch (_: Exception) { EMPTY }
}
