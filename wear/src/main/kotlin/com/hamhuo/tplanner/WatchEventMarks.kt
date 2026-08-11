package com.hamhuo.tplanner

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

internal const val WATCH_MARKS_PREFS = "tplanner_watch_marks"
internal const val WATCH_MARKS_KEY = "marks_json"

// 事件刻度数据：圆点来自有限日期窗口；事项弧带从全部已同步的未完成任务中选择。
// 手表侧暂无同步通道时为空——表盘退化为纯时间显示，不画假数据。
object WatchEventMarks {
    data class NextTask(
        val id: String,
        val title: String,
        val type: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
    )

    data class Marks(val minutes: List<Int>, val items: List<NextTask>) {
        val next: NextTask?
            get() = items.firstOrNull()
    }

    val EMPTY = Marks(emptyList(), emptyList())

    fun load(context: Context): Marks = try {
        val raw = context.getSharedPreferences(WATCH_MARKS_PREFS, Context.MODE_PRIVATE)
            .getString(WATCH_MARKS_KEY, null)
        val pending = WatchTaskOutbox.pendingTasks(context).map { task ->
            StoredTask(
                id = task.id,
                title = task.title,
                type = task.type,
                startEpochMs = task.startEpochMs,
                endEpochMs = task.endEpochMs,
            )
        }
        if (raw == null) {
            Marks(emptyList(), selectVisible(pending, Instant.now().toEpochMilli()))
        } else {
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
            val minutesArray = if (days == null) {
                // Rolling-upgrade bridge for a snapshot stored by the previous receiver.
                obj.optJSONArray("minutes")
            } else {
                todaySnapshot?.optJSONArray("minutes")
            }
            val minutes = if (minutesArray == null) {
                emptyList()
            } else {
                (0 until minutesArray.length())
                    .map { minutesArray.getInt(it) }
                    .filter { it in 0..1439 }
                    .distinct()
                    .sorted()
            }
            val tasks = obj.optJSONArray("tasks")?.let { input ->
                (0 until input.length()).mapNotNull { index ->
                    val item = input.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val title = item.optString("title").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val startEpochMs = item.optLong("startEpochMs", Long.MIN_VALUE)
                    val endEpochMs = item.optLong("endEpochMs", Long.MIN_VALUE)
                    if (startEpochMs == Long.MIN_VALUE || endEpochMs < startEpochMs) {
                        return@mapNotNull null
                    }
                    val type = item.optString("type", "task")
                        .takeIf { it in SUPPORTED_TASK_TYPES }
                        ?: "task"
                    StoredTask(id, title, type, startEpochMs, endEpochMs)
                }.sortedWith(taskOrder)
            }.orEmpty()
            val merged = (tasks + pending)
                .distinctBy(StoredTask::id)
                .sortedWith(taskOrder)
            Marks(minutes, selectVisible(merged, Instant.now().toEpochMilli()))
        }
    } catch (_: Exception) { EMPTY }

    private data class StoredTask(
        val id: String,
        val title: String,
        val type: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
    )

    private val taskOrder = compareBy<StoredTask>(
        { it.startEpochMs },
        { it.endEpochMs },
        { it.id },
    )

    private val recentPastOrder = compareByDescending<StoredTask> { it.endEpochMs }
        .thenBy { it.startEpochMs }
        .thenBy { it.id }

    private fun selectVisible(tasks: List<StoredTask>, nowEpochMs: Long): List<NextTask> {
        val current = tasks.asSequence()
            // Every interval is [start, end): a task stops being current exactly at end.
            .filter { it.startEpochMs <= nowEpochMs && nowEpochMs < it.endEpochMs }
            .sortedWith(taskOrder)
        val future = tasks.asSequence()
            .filter { it.startEpochMs > nowEpochMs }
            .sortedWith(taskOrder)
        val recentPast = tasks.asSequence()
            .filter { it.endEpochMs <= nowEpochMs }
            .sortedWith(recentPastOrder)

        return (current + future + recentPast)
            .distinctBy { it.id }
            .map { task ->
                NextTask(
                    id = task.id,
                    title = task.title,
                    type = task.type,
                    startEpochMs = task.startEpochMs,
                    endEpochMs = task.endEpochMs,
                )
            }
            .toList()
    }

    private val SUPPORTED_TASK_TYPES = setOf("event", "status", "task")
}
