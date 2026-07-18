package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/**
 * DeepSeek-backed schedule extractor.
 *
 * Single-turn: user text in → schedule Proposal out.  No QA, no clarifying
 * questions, no multi-turn conversation.  The create_schedule tool is always
 * required; missing fields are filled with defaults client-side.
 */
class DeepSeekAnalysisService(private val apiKey: String) {

    data class ProposedAction(
        val type: String,
        val title: String,
        val startIso: String,
        val endIso: String,
        val note: String,
        val colorId: Int,
        val checklist: List<String>,
        val alarmEnabled: Boolean,
        val alarmOffsetMinutes: Int,
    )

    suspend fun extractSchedule(
        text: String,
        timestamp: String = "",
        location: String = "",
    ): ProposedAction? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("现在：${nowDescription()}\n")
            if (location.isNotBlank()) append("地点：$location\n")
            if (timestamp.isNotBlank()) append("记录时间：$timestamp\n")
            append("用户写下的文字：\n\"\"\"\n$text\n\"\"\"\n\n")
            append("请立即调用 create_schedule。所有字段都必须填写——缺失字段用合理默认值。")
        }
        try {
            val action = callDeepSeek(prompt)
            // Fill defaults for any missing fields the model may have omitted
            if (action != null) fillDefaults(action, text) else null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractSchedule failed: ${e.message}")
            null
        }
    }

    // ── defaults ─────────────────────────────────────────────────────────────

    private fun fillDefaults(raw: ProposedAction, text: String): ProposedAction {
        val now = LocalDateTime.now()
        val start = runCatching { LocalDateTime.parse(raw.startIso) }
            .getOrDefault(now.plusHours(1).withMinute(0).withSecond(0))
        val end = runCatching { LocalDateTime.parse(raw.endIso) }
            .getOrDefault(start.plusHours(1))
        return ProposedAction(
            type = raw.type.takeIf { it in SCHEDULE_TYPES } ?: "event",
            title = raw.title.ifBlank { text.take(40).ifBlank { "未命名事项" } },
            startIso = start.toString(),
            endIso = if (end.isAfter(start)) end.toString() else start.plusHours(1).toString(),
            note = raw.note,
            colorId = raw.colorId.takeIf { it in 0..7 } ?: 0,
            checklist = raw.checklist,
            alarmEnabled = raw.alarmEnabled,
            alarmOffsetMinutes = if (raw.alarmEnabled) raw.alarmOffsetMinutes.coerceIn(0, MAX_ALARM_OFFSET_MINUTES) else 0,
        )
    }

    // ── API call ─────────────────────────────────────────────────────────────

    private fun callDeepSeek(userMessage: String): ProposedAction? {
        val conn = URL(DEEPSEEK_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("thinking", JSONObject().put("type", "disabled"))
            put("messages", messages)
            put("tools", buildTools())
            put("tool_choice", "required")
            put("max_tokens", 2048)
            put("temperature", 0.3)
        }.toString()

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    ?: "HTTP ${conn.responseCode}"
                throw Exception("DeepSeek API error: $error")
            }
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val choice = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls") ?: return null
            if (calls.length() == 0) return null

            val rawCall = calls.getJSONObject(0)
            val function = rawCall.getJSONObject("function")
            if (function.getString("name") != CREATE_SCHEDULE_TOOL) return null

            val args = JSONObject(function.optString("arguments", "{}"))
            return ProposedAction(
                type = args.optString("type", "event").trim(),
                title = args.optString("title", "").trim(),
                startIso = args.optString("start_at", "").trim(),
                endIso = args.optString("end_at", "").trim(),
                note = args.optString("note", ""),
                colorId = args.optInt("color_id", 0),
                checklist = parseChecklist(args),
                alarmEnabled = args.optBoolean("alarm_enabled", false),
                alarmOffsetMinutes = args.optInt("alarm_offset_minutes", 0),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseChecklist(args: JSONObject): List<String> {
        val items = mutableListOf<String>()
        args.optJSONArray("checklist")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let { items += it }
            }
        }
        return items
    }

    // ── tool definition ─────────────────────────────────────────────────────

    private fun buildTools(): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", CREATE_SCHEDULE_TOOL)
                put(
                    "description",
                    "从用户输入文字中提取日程信息。所有字段都必须填写，不明确的字段使用合理默认值。",
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("type", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(SCHEDULE_TYPES.toList()))
                            put("description", "event=定时提醒，status=状态或动态，task=可勾选任务。根据内容判断：有具体时间用event，待办用task，状态记录用status。默认event")
                        })
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "简短标题。如果用户没说具体标题，从文字中提炼核心事项作为标题")
                        })
                        put("start_at", JSONObject().apply {
                            put("type", "string")
                            put("description", "本地时间 ISO 8601。不明确时默认为今天最近的整点或半点（≥当前时间）")
                        })
                        put("end_at", JSONObject().apply {
                            put("type", "string")
                            put("description", "本地时间 ISO 8601，必须晚于 start_at。不明确时默认 start_at + 1 小时")
                        })
                        put("note", JSONObject().apply {
                            put("type", "string")
                            put("description", "备注；用户未指定时传空字符串")
                        })
                        put("color_id", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 7)
                            put("description", "0蓝、1金、2粉、3绿、4紫、5橙、6青、7灰。未指定时默认0")
                        })
                        put("checklist", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                            put("description", "task 类型的清单项；其他类型或无清单时传空数组")
                        })
                        put("alarm_enabled", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "是否为该日程创建系统闹铃。event 类型且时间在未来默认 true，其他默认 false")
                        })
                        put("alarm_offset_minutes", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", MAX_ALARM_OFFSET_MINUTES)
                            put("description", "闹铃提前分钟数。alarm_enabled=false 时必须为 0；alarm_enabled=true 时默认 0（开始时）")
                        })
                    })
                    put("required", JSONArray(listOf(
                        "type", "title", "start_at", "end_at", "note",
                        "color_id", "checklist", "alarm_enabled", "alarm_offset_minutes",
                    )))
                    put("additionalProperties", false)
                })
            })
        },
    )

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun nowDescription(): String {
        val now = LocalDateTime.now()
        val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]
        return "%04d-%02d-%02d %02d:%02d %s".format(
            now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, week,
        )
    }

    companion object {
        private const val TAG = "TplannerDS"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-v4-flash"
        private const val CREATE_SCHEDULE_TOOL = "create_schedule"
        private val SCHEDULE_TYPES = linkedSetOf("event", "status", "task")
        private const val MAX_ALARM_OFFSET_MINUTES = 1440 * 30 // 30 days

        private const val SYSTEM_PROMPT =
            "你是 tPlanner 的日程提取助手。你的唯一任务是：根据用户输入的文字，调用 create_schedule 工具。" +
            "必须始终调用 create_schedule。" +
            "对于每个字段：\n" +
            "- type: 有具体时间→event，待办事项→task，状态记录→status。默认为 event\n" +
            "- title: 从文字中提炼核心事项，最多 40 字。如果文字本身很短，直接用原文\n" +
            "- start_at: 提取用户提到的时间。不明确时用今天下一个整点或半点（≥当前时间）\n" +
            "- end_at: 提取用户提到的结束时间。不明确时默认 start_at + 1 小时\n" +
            "- note: 提取补充说明。没有则传空字符串\n" +
            "- color_id: 用户指定了颜色就填入，否则默认 0\n" +
            "- checklist: task 类型时提取清单项为数组，其他类型传空数组\n" +
            "- alarm_enabled: event 类型且开始时间在未来时默认为 true，其他为 false\n" +
            "- alarm_offset_minutes: 闹铃关闭时为 0；开启时用户指定了提前量就填入，否则默认 0" +
            "不要反问用户。不要输出内容（content 可以为空）。直接调用工具。"
    }
}
