package com.hamhuo.tplanner

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

internal fun nextScheduleHalfHour(now: LocalDateTime): LocalDateTime {
    val base = now.withSecond(0).withNano(0)
    return if (base.minute < 30) {
        base.withMinute(30)
    } else {
        base.plusHours(1).withMinute(0)
    }
}

internal fun parseScheduleLocalDateTime(value: String): LocalDateTime? {
    val candidate = value.trim()
    if (candidate.isBlank()) return null
    return runCatching { LocalDateTime.parse(candidate) }.getOrNull()
        ?: runCatching {
            OffsetDateTime.parse(candidate).atZoneSameInstant(APP_ZONE).toLocalDateTime()
        }.getOrNull()
        ?: runCatching {
            ZonedDateTime.parse(candidate).withZoneSameInstant(APP_ZONE).toLocalDateTime()
        }.getOrNull()
        ?: runCatching {
            Instant.parse(candidate).atZone(APP_ZONE).toLocalDateTime()
        }.getOrNull()
}

internal fun scheduleTemporalContext(now: LocalDateTime, timestamp: String): String {
    val weekday = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]
    val zonedNow = now.atZone(APP_ZONE)
    return buildString {
        append("当前基准时间（唯一基准）：$zonedNow，$weekday\n")
        append("相对日期：今天=${now.toLocalDate()}，明天=${now.toLocalDate().plusDays(1)}，后天=${now.toLocalDate().plusDays(2)}\n")
        if (timestamp.isNotBlank()) {
            append("原始记录时间：$timestamp（仅在文字明确相对记录时刻时作为锚点）\n")
        }
    }
}

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
        val requestId: String,
    )

    suspend fun extractSchedule(
        text: String,
        timestamp: String = "",
        location: String = "",
        requestId: String = "",
    ): ProposedAction? = withContext(Dispatchers.IO) {
        val logRequestId = requestIdForLog(requestId)
        val startedAt = SystemClock.elapsedRealtime()
        val referenceNow = LocalDateTime.now(APP_ZONE).withNano(0)
        Log.i(
            TAG,
            "request=$logRequestId phase=extract_start inputChars=${text.length} " +
                "timestampProvided=${timestamp.isNotBlank()} locationProvided=${location.isNotBlank()}",
        )
        val prompt = buildString {
            append(scheduleTemporalContext(referenceNow, timestamp))
            if (location.isNotBlank()) append("地点：$location\n")
            append("用户写下的文字：\n\"\"\"\n$text\n\"\"\"\n\n")
            append("严格按上述时间基准解析今天、明天、周几、稍后、今晚等表达，然后立即调用 create_schedule。")
        }
        try {
            val action = callDeepSeek(prompt, logRequestId)
            // Fill defaults for any missing fields the model may have omitted
            if (action == null) {
                Log.w(
                    TAG,
                    "request=$logRequestId phase=extract_result result=no_action " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                null
            } else {
                val normalized = fillDefaults(action, text, referenceNow)
                Log.i(
                    TAG,
                    "request=$logRequestId phase=extract_result result=proposal type=${normalized.type} " +
                        "titlePresent=${normalized.title.isNotBlank()} noteChars=${normalized.note.length} " +
                        "checklistCount=${normalized.checklist.size} alarmEnabled=${normalized.alarmEnabled} " +
                        "alarmOffsetMinutes=${normalized.alarmOffsetMinutes} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                normalized
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "request=$logRequestId phase=extract_failed errorType=${e.javaClass.simpleName} " +
                    "at=${exceptionLocationForLog(e)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            null
        }
    }

    // ── defaults ─────────────────────────────────────────────────────────────

    private fun fillDefaults(
        raw: ProposedAction,
        text: String,
        referenceNow: LocalDateTime,
    ): ProposedAction {
        val parsedStart = parseScheduleLocalDateTime(raw.startIso)
        val start = parsedStart ?: nextScheduleHalfHour(referenceNow)
        val parsedEnd = parseScheduleLocalDateTime(raw.endIso)
        val end = parsedEnd ?: start.plusHours(1)
        val normalized = ProposedAction(
            type = raw.type.takeIf { it in SCHEDULE_TYPES } ?: "event",
            title = raw.title.ifBlank { text.take(40).ifBlank { "未命名事项" } },
            startIso = start.toString(),
            endIso = if (end.isAfter(start)) end.toString() else start.plusHours(1).toString(),
            note = raw.note,
            colorId = raw.colorId.takeIf { it in 0..7 } ?: 0,
            checklist = raw.checklist,
            alarmEnabled = raw.alarmEnabled,
            alarmOffsetMinutes = if (raw.alarmEnabled) raw.alarmOffsetMinutes.coerceIn(0, MAX_ALARM_OFFSET_MINUTES) else 0,
            requestId = raw.requestId,
        )
        val normalizedFields = buildList {
            if (normalized.type != raw.type) add("type")
            if (raw.title.isBlank()) add("title")
            if (parsedStart == null) add("start_at")
            if (parsedEnd == null || !end.isAfter(start)) add("end_at")
            if (normalized.colorId != raw.colorId) add("color_id")
            if (normalized.alarmOffsetMinutes != raw.alarmOffsetMinutes) add("alarm_offset_minutes")
        }
        Log.d(
            TAG,
            "request=${raw.requestId} phase=defaults normalizedFields=" +
                normalizedFields.ifEmpty { listOf("none") }.joinToString(","),
        )
        return normalized
    }

    // ── API call ─────────────────────────────────────────────────────────────

    private fun callDeepSeek(userMessage: String, requestId: String): ProposedAction? {
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
            put("temperature", 0.1)
        }.toString()

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "request=$requestId phase=http_request model=$MODEL promptChars=${userMessage.length} " +
                "bodyChars=${body.length} tool=$CREATE_SCHEDULE_TOOL toolChoice=required " +
                "connectTimeoutMs=${conn.connectTimeout} readTimeoutMs=${conn.readTimeout}",
        )
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val responseCode = conn.responseCode
            Log.i(
                TAG,
                "request=$requestId phase=http_response status=$responseCode " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    ?: "HTTP $responseCode"
                Log.w(
                    TAG,
                    "request=$requestId phase=http_error status=$responseCode " +
                        "bodyChars=${error.length}",
                )
                throw Exception("DeepSeek API error: HTTP $responseCode")
            }
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val choices = JSONObject(response).optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(
                    TAG,
                    "request=$requestId phase=response_parse result=missing_choices responseChars=${response.length}",
                )
                return null
            }
            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls")
            val finishReason = choice.optString("finish_reason")
                .takeIf { it in KNOWN_FINISH_REASONS }
                ?: "other"
            Log.d(
                TAG,
                "request=$requestId phase=response_parse finishReason=$finishReason " +
                    "responseChars=${response.length} contentChars=${message.optString("content", "").length} " +
                    "reasoningChars=${message.optString("reasoning_content", "").length} " +
                    "toolCallCount=${calls?.length() ?: 0}",
            )
            if (calls == null || calls.length() == 0) {
                Log.w(TAG, "request=$requestId phase=tool_call result=missing")
                return null
            }

            val rawCall = calls.getJSONObject(0)
            val function = rawCall.getJSONObject("function")
            val toolName = function.optString("name")
            val rawArguments = function.optString("arguments", "{}")
            if (toolName != CREATE_SCHEDULE_TOOL) {
                Log.w(
                    TAG,
                    "request=$requestId phase=tool_call result=unexpected_tool",
                )
                return null
            }

            val args = JSONObject(rawArguments)
            val knownArgumentCount = TOOL_ARGUMENT_KEYS.count(args::has)
            Log.i(
                TAG,
                "request=$requestId phase=tool_call result=received tool=$toolName " +
                    "argumentChars=${rawArguments.length} argumentKeyCount=${args.length()} " +
                    "knownArgumentCount=$knownArgumentCount",
            )
            val proposal = ProposedAction(
                type = args.optString("type", "event").trim(),
                title = args.optString("title", "").trim(),
                startIso = args.optString("start_at", "").trim(),
                endIso = args.optString("end_at", "").trim(),
                note = args.optString("note", ""),
                colorId = args.optInt("color_id", 0),
                checklist = parseChecklist(args),
                alarmEnabled = args.optBoolean("alarm_enabled", false),
                alarmOffsetMinutes = args.optInt("alarm_offset_minutes", 0),
                requestId = requestId,
            )
            Log.d(
                TAG,
                "request=$requestId phase=tool_parse typeKnown=${proposal.type in SCHEDULE_TYPES} " +
                    "titlePresent=${proposal.title.isNotBlank()} startPresent=${proposal.startIso.isNotBlank()} " +
                    "endPresent=${proposal.endIso.isNotBlank()} noteChars=${proposal.note.length} " +
                    "checklistCount=${proposal.checklist.size} alarmEnabled=${proposal.alarmEnabled} " +
                    "alarmOffsetMinutes=${proposal.alarmOffsetMinutes}",
            )
            return proposal
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
                            put("description", "北京时间（Asia/Shanghai）的 YYYY-MM-DDTHH:mm:ss。必须依据用户消息中的当前基准时间解析相对日期；只给时分且今天已过时顺延到明天。完全不明确时用严格晚于当前时间的下一个整点或半点")
                        })
                        put("end_at", JSONObject().apply {
                            put("type", "string")
                            put("description", "北京时间（Asia/Shanghai）ISO 8601，必须晚于 start_at。不明确时默认 start_at + 1 小时")
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

    private fun requestIdForLog(requestId: String): String {
        val safe = requestId
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_REQUEST_ID_CHARS)
        return safe.ifBlank { "service-${REQUEST_SEQUENCE.incrementAndGet()}" }
    }

    private fun exceptionLocationForLog(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.hamhuo.tplanner") }
            ?: error.stackTrace.firstOrNull()
            ?: return "unknown"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    companion object {
        private const val TAG = "TplannerLLM"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-v4-flash"
        private const val CREATE_SCHEDULE_TOOL = "create_schedule"
        private const val MAX_REQUEST_ID_CHARS = 48
        private val SCHEDULE_TYPES = linkedSetOf("event", "status", "task")
        private val TOOL_ARGUMENT_KEYS = setOf(
            "type",
            "title",
            "start_at",
            "end_at",
            "note",
            "color_id",
            "checklist",
            "alarm_enabled",
            "alarm_offset_minutes",
        )
        private val KNOWN_FINISH_REASONS = setOf(
            "stop",
            "length",
            "tool_calls",
            "content_filter",
            "insufficient_system_resource",
        )
        private val REQUEST_SEQUENCE = AtomicInteger(0)
        private const val MAX_ALARM_OFFSET_MINUTES = 1440 * 30 // 30 days

        private const val SYSTEM_PROMPT =
            "你是 tPlanner 的日程提取助手。你的唯一任务是：根据用户输入的文字，调用 create_schedule 工具。" +
            "必须始终调用 create_schedule。" +
            "对于每个字段：\n" +
            "- type: 有具体时间→event，待办事项→task，状态记录→status。默认为 event\n" +
            "- title: 从文字中提炼核心事项，最多 40 字。如果文字本身很短，直接用原文\n" +
            "- 时间基准: 只能使用用户消息中的“当前基准时间”，绝不能使用模型训练时间或自行猜测今天日期\n" +
            "- 相对时间: 今天/明天/后天/本周/下周/周几/稍后/今晚都从当前基准时间计算；“原始记录时间”仅在文字明确相对记录时刻时使用\n" +
            "- 只给时分: 优先安排在今天；若该时分已过且用户未明确描述过去，则顺延到明天。用户明确说昨天、刚才、之前时才允许过去时间\n" +
            "- 周几: 没说本周或下周时选择严格晚于当前基准时间的最近一次该周几；不得落到已过去日期\n" +
            "- start_at: 输出北京时间 YYYY-MM-DDTHH:mm:ss。完全不明确时使用严格晚于当前基准时间的下一个整点或半点\n" +
            "- end_at: 提取用户提到的结束时间。不明确时默认 start_at + 1 小时\n" +
            "- note: 提取补充说明。没有则传空字符串\n" +
            "- color_id: 用户指定了颜色就填入，否则默认 0\n" +
            "- checklist: task 类型时提取清单项为数组，其他类型传空数组\n" +
            "- alarm_enabled: event 类型且开始时间在未来时默认为 true，其他为 false\n" +
            "- alarm_offset_minutes: 闹铃关闭时为 0；开启时用户指定了提前量就填入，否则默认 0\n" +
            "不要反问用户。不要输出内容（content 可以为空）。直接调用工具。"
    }
}
