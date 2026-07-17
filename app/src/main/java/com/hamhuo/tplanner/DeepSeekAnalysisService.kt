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
 * DeepSeek-backed QA agent.
 *
 * Schedule creation uses the provider's native Tool Calls envelope, while the
 * tool definition, validation, execution, confirmation and result contract all
 * remain owned by tPlanner. The model can only propose a create_schedule call;
 * Android decides whether and when to execute it.
 */
class DeepSeekAnalysisService(private val apiKey: String) {

    data class ProposedAction(
        val toolCallId: String,
        val type: String,
        val title: String,
        val startIso: String,
        val endIso: String,
        val note: String,
        val colorId: Int,
        val checklist: List<String>,
        val alarmEnabled: Boolean = false,
        val alarmOffsetMinutes: Int = 0,
    )

    data class Clarify(val q: String, val options: List<String>)

    data class ThoughtResult(
        val mode: String,
        val text: String,
        val questions: List<String>,
        val action: ProposedAction? = null,
        val clarify: Clarify? = null,
    )

    data class FollowUpResult(
        val questions: List<String> = emptyList(),
        val action: ProposedAction? = null,
        val clarify: Clarify? = null,
    )

    private data class ToolInvocation(
        val id: String,
        val name: String,
        val arguments: String,
    )

    private data class ModelTurn(
        val content: String,
        val toolCalls: List<ToolInvocation>,
    )

    private data class ParsedContent(
        val text: String,
        val questions: List<String>,
        val clarify: Clarify?,
    )

    private data class ParsedTool(
        val action: ProposedAction? = null,
        val clarify: Clarify? = null,
    )

    private var sessionSystemPrompt: String = ""
    private val sessionMessages = mutableListOf<JSONObject>()

    private fun resetSession(systemPrompt: String) {
        sessionSystemPrompt = systemPrompt
        sessionMessages.clear()
    }

    suspend fun processThought(
        text: String,
        timestamp: String,
        location: String,
    ): ThoughtResult = withContext(Dispatchers.IO) {
        resetSession(SYSTEM_AGENT)
        val prompt = buildString {
            append("现在：${nowDescription()}\n地点：$location\n记录时间：$timestamp\n")
            append("用户写下的文字：\n\"\"\"\n$text\n\"\"\"\n\n")
            append(RESPONSE_RULES)
        }
        try {
            toThoughtResult(callDeepSeekWithHistory(prompt), text)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "processThought failed: ${e.message}")
            ThoughtResult("questions", text, FALLBACK_QUESTIONS)
        }
    }

    /** Continue collecting schedule fields after a clarify question. */
    suspend fun refineAction(
        originalText: String,
        answer: String,
    ): ThoughtResult = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("现在：${nowDescription()}\n")
            append("最初文字：$originalText\n")
            append("用户对日程字段追问的回答：$answer\n\n")
            append("合并这次回答与会话中已经确认的字段。仍有未询问或未确认的可填写项时，")
            append("继续通过 clarify 一次列出全部缺失项；禁止猜值。全部字段明确后才调用 create_schedule。\n")
            append(RESPONSE_RULES)
        }
        try {
            toThoughtResult(callDeepSeekWithHistory(prompt), originalText)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "refineAction failed: ${e.message}")
            ThoughtResult(
                mode = "questions",
                text = originalText,
                questions = FALLBACK_QUESTIONS,
                clarify = Clarify("刚才没能处理这些日程信息，请再补充一次。", emptyList()),
            )
        }
    }

    suspend fun followUp(
        originalText: String,
        qaHistory: String,
    ): FollowUpResult = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("现在：${nowDescription()}\n\n")
            append("用户最初写下的文字：\n\"\"\"\n$originalText\n\"\"\"\n\n")
            if (qaHistory.isNotBlank()) append("此前 QA：\n$qaHistory\n\n")
            append("基于整段对话继续提出 1-3 个不重复的新问题。若对话里出现明确的日程意图，")
            append("按规则收集全部字段；字段齐全时调用 create_schedule。\n")
            append(RESPONSE_RULES)
        }
        try {
            val result = toThoughtResult(callDeepSeekWithHistory(prompt), originalText)
            FollowUpResult(result.questions, result.action, result.clarify)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "followUp failed: ${e.message}")
            FollowUpResult(FALLBACK_QUESTIONS)
        }
    }

    /**
     * Append the application-owned result for a model tool call. It will be
     * included, with the matching tool_call_id, on the next QA request.
     */
    @Synchronized
    fun submitToolResult(
        toolCallId: String,
        status: String,
        scheduleId: String? = null,
        alarmStatus: String? = null,
        message: String,
    ) {
        val content = JSONObject().apply {
            put("status", status)
            scheduleId?.let { put("schedule_id", it) }
            alarmStatus?.let { put("alarm_status", it) }
            put("message", message)
        }.toString()
        sessionMessages.add(JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        })
    }

    private fun toThoughtResult(turn: ModelTurn, fallbackText: String): ThoughtResult {
        val content = parseContent(turn.content, fallbackText)
        val tool = parseScheduleTool(turn.toolCalls.firstOrNull())
        // The current UI confirms one schedule at a time. Always answer every
        // extra tool_call_id so the next Chat Completions request remains valid.
        turn.toolCalls.drop(1).forEach { extra ->
            submitToolResult(
                toolCallId = extra.id,
                status = "failed",
                message = "一次只能创建一条日程，请逐条确认",
            )
        }
        val questions = content.questions.ifEmpty { FALLBACK_QUESTIONS }
        val clarify = tool.clarify ?: content.clarify
        val mode = when {
            tool.action != null -> "action"
            clarify != null -> "questions"
            else -> "questions"
        }
        android.util.Log.d(
            TAG,
            "turn mode=$mode q=${questions.size} tool=${tool.action != null} clarify=${clarify != null}",
        )
        return ThoughtResult(mode, content.text, questions, tool.action, clarify)
    }

    private fun parseContent(raw: String, fallbackText: String): ParsedContent {
        if (raw.isBlank()) return ParsedContent(fallbackText, FALLBACK_QUESTIONS, null)
        return try {
            val json = extractJson(raw)
            val questions = mutableListOf<String>()
            json.optJSONArray("questions")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let { questions += it }
                }
            }
            val clarify = json.optJSONObject("clarify")?.let { obj ->
                val q = obj.optString("q").trim()
                val options = mutableListOf<String>()
                obj.optJSONArray("options")?.let { array ->
                    for (i in 0 until array.length()) {
                        array.optString(i).trim().takeIf { it.isNotBlank() }?.let { options += it }
                    }
                }
                q.takeIf { it.isNotBlank() }?.let { Clarify(it, options.take(6)) }
            }
            ParsedContent(
                text = json.optString("text", fallbackText).ifBlank { fallbackText },
                questions = questions.take(3),
                clarify = clarify,
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "assistant content was not valid JSON: ${e.message}")
            ParsedContent(fallbackText, FALLBACK_QUESTIONS, null)
        }
    }

    private fun parseScheduleTool(call: ToolInvocation?): ParsedTool {
        if (call == null) return ParsedTool()
        if (call.name != CREATE_SCHEDULE_TOOL) {
            submitToolResult(call.id, "failed", message = "未知工具：${call.name}")
            return ParsedTool(
                clarify = Clarify("这个操作暂时不支持。你希望我继续帮你整理哪一部分？", emptyList()),
            )
        }

        return try {
            val args = JSONObject(call.arguments)
            val errors = mutableListOf<String>()
            val type = args.optString("type").trim()
            val title = args.optString("title").trim()
            val startIso = args.optString("start_at").trim()
            val endIso = args.optString("end_at").trim()
            val note = args.optString("note", "")
            val colorId = args.optInt("color_id", -1)
            val alarmEnabled = args.optBoolean("alarm_enabled", false)
            val alarmOffsetMinutes = args.optInt("alarm_offset_minutes", -1)
            val checklist = mutableListOf<String>()
            args.optJSONArray("checklist")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let { checklist += it }
                }
            }

            if (type !in SCHEDULE_TYPES) errors += "类型（提醒、状态或任务）"
            if (title.isBlank()) errors += "标题"
            val start = runCatching { LocalDateTime.parse(startIso) }.getOrNull()
            val end = runCatching { LocalDateTime.parse(endIso) }.getOrNull()
            if (start == null) errors += "有效的开始时间"
            if (end == null || start != null && !end.isAfter(start)) errors += "晚于开始时间的结束时间"
            if (colorId !in 0..7) errors += "颜色"
            if (!args.has("note")) errors += "备注（不需要可留空）"
            if (!args.has("checklist")) errors += "清单（不需要可为空）"
            if (!args.has("alarm_enabled")) errors += "是否开启系统闹铃"
            if (!args.has("alarm_offset_minutes") || alarmOffsetMinutes !in 0..MAX_ALARM_OFFSET_MINUTES) {
                errors += "闹铃提前分钟（0 到 $MAX_ALARM_OFFSET_MINUTES）"
            }
            if (!alarmEnabled && alarmOffsetMinutes > 0) {
                errors += "关闭闹铃时提前分钟应为 0"
            }

            if (errors.isNotEmpty()) {
                submitToolResult(call.id, "failed", message = "参数不完整：${errors.joinToString("、")}")
                ParsedTool(
                    clarify = Clarify(
                        "创建前还需要确认：${errors.distinct().joinToString("、")}。请补充；不需要的可说“使用默认值”。",
                        listOf("使用默认值", "我来补充"),
                    ),
                )
            } else {
                ParsedTool(
                    action = ProposedAction(
                        toolCallId = call.id,
                        type = type,
                        title = title,
                        startIso = startIso,
                        endIso = endIso,
                        note = note,
                        colorId = colorId,
                        checklist = if (type == "task") checklist else emptyList(),
                        alarmEnabled = alarmEnabled,
                        alarmOffsetMinutes = if (alarmEnabled) alarmOffsetMinutes else 0,
                    ),
                )
            }
        } catch (e: Exception) {
            submitToolResult(call.id, "failed", message = "工具参数不是有效 JSON")
            ParsedTool(
                clarify = Clarify(
                    "日程参数格式不完整，请重新说明类型、标题、起止时间、备注、颜色、清单和系统闹铃。",
                    emptyList(),
                ),
            )
        }
    }

    private fun callDeepSeekWithHistory(currentUserMessage: String): ModelTurn {
        val conn = URL(DEEPSEEK_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", currentUserMessage)
        }
        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", sessionSystemPrompt)
                })
                sessionMessages.forEach { put(it) }
                put(userMessage)
            }
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("tools", buildTools())
                put("tool_choice", "auto")
                put("max_tokens", 3072)
                put("temperature", 0.5)
            }.toString()

            android.util.Log.d(TAG, "callDeepSeek historyMessages=${sessionMessages.size}")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    ?: "HTTP ${conn.responseCode}"
                throw Exception("DeepSeek API error: $error")
            }

            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val message = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val content = if (message.isNull("content")) "" else message.optString("content", "").trim()
            val calls = mutableListOf<ToolInvocation>()
            message.optJSONArray("tool_calls")?.let { array ->
                for (i in 0 until array.length()) {
                    val rawCall = array.getJSONObject(i)
                    val function = rawCall.getJSONObject("function")
                    calls += ToolInvocation(
                        id = rawCall.getString("id"),
                        name = function.getString("name"),
                        arguments = function.optString("arguments", "{}"),
                    )
                }
            }

            sessionMessages.add(userMessage)
            // Keep the complete assistant message. Thinking models require
            // reasoning_content to be passed back alongside tool_calls.
            sessionMessages.add(JSONObject(message.toString()))
            return ModelTurn(content, calls)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildTools(): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", CREATE_SCHEDULE_TOOL)
                put(
                    "description",
                    "创建 tPlanner 日程。只有类型、标题、开始、结束、备注、颜色、任务清单以及系统闹铃都已向用户询问，" +
                        "或用户明确接受默认值后才可调用。应用仍会在执行前向用户做最终确认。",
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("type", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(SCHEDULE_TYPES.toList()))
                            put("description", "event=定时提醒，status=状态或动态，task=可勾选任务")
                        })
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "简短明确的标题")
                        })
                        put("start_at", JSONObject().apply {
                            put("type", "string")
                            put("description", "本地时间 ISO 8601，例如 2026-07-18T09:00:00")
                        })
                        put("end_at", JSONObject().apply {
                            put("type", "string")
                            put("description", "晚于 start_at 的本地时间 ISO 8601")
                        })
                        put("note", JSONObject().apply {
                            put("type", "string")
                            put("description", "备注；用户不需要时传空字符串")
                        })
                        put("color_id", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 7)
                            put("description", "0蓝、1金、2粉、3绿、4紫、5橙、6青、7灰")
                        })
                        put("checklist", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                            put("description", "task 的清单文本；其他类型或无清单时传空数组")
                        })
                        put("alarm_enabled", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "是否为这条日程创建 Android 系统闹铃")
                        })
                        put("alarm_offset_minutes", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", MAX_ALARM_OFFSET_MINUTES)
                            put("description", "闹铃比 start_at 提前的分钟数；开始时为 0，alarm_enabled=false 时必须为 0")
                        })
                    })
                    put(
                        "required",
                        JSONArray(
                            listOf(
                                "type", "title", "start_at", "end_at", "note", "color_id", "checklist",
                                "alarm_enabled", "alarm_offset_minutes",
                            ),
                        ),
                    )
                    put("additionalProperties", false)
                })
            })
        },
    )

    private fun extractJson(raw: String): JSONObject {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("```") -> {
                val start = trimmed.indexOf('{')
                val end = trimmed.lastIndexOf('}')
                if (start >= 0 && end > start) JSONObject(trimmed.substring(start, end + 1))
                else throw Exception("No JSON found in response")
            }
            else -> throw Exception("Unexpected response format")
        }
    }

    private fun nowDescription(): String {
        val now = LocalDateTime.now()
        val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]
        return "%04d-%02d-%02d %02d:%02d %s".format(
            now.year,
            now.monthValue,
            now.dayOfMonth,
            now.hour,
            now.minute,
            week,
        )
    }

    companion object {
        private const val TAG = "TplannerDS"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-v4-flash"
        private const val CREATE_SCHEDULE_TOOL = "create_schedule"
        private val SCHEDULE_TYPES = linkedSetOf("event", "status", "task")
        private val FALLBACK_QUESTIONS = listOf("这段话里，你最想先理清或继续展开的是哪一部分？")

        private const val SYSTEM_AGENT =
            "你是 tPlanner 的 QA 助手。你的核心行为是反过来追问，帮助用户理清想法，而不是替用户下结论。" +
                "每次响应都必须产生至少一个与当前上下文相关的新追问。你可以使用 create_schedule 工具，" +
                "但工具、参数校验、确认与执行均由应用负责。识别到日程意图时，必须询问所有可填写字段：" +
                "类型、标题、开始、结束、备注、颜色、是否开启系统闹铃；开启闹铃时还要询问提前多少分钟；" +
                "task 还要询问清单。关闭闹铃时 alarm_offset_minutes 必须为 0。没有提供的值绝不猜测，除非用户" +
                "明确接受默认值；接受默认值时系统闹铃默认关闭（false/0）。字段不全时不要调用工具，而是在 clarify 中一次列出全部缺失字段。字段齐全" +
                "后调用 create_schedule。每次最多调用一次工具、创建一条日程；多条日程必须逐条确认。" +
                "普通回复只输出有效 JSON，不要 markdown。"

        private const val RESPONSE_RULES =
            "普通回复必须是以下 JSON（不要输出 action，创建日程只能使用 create_schedule 工具）：\n" +
                "{\"text\":\"保持原意、仅修正明显错字后的文字\",\"questions\":[\"1-3 个新追问\"]," +
                "\"clarify\":null}\n" +
                "每次普通回复 questions 都不得为空。若有日程意图但字段不全，clarify 改为" +
                "{\"q\":\"一次列出全部缺失字段的问题\",\"options\":[\"合适的快捷答案\",\"使用默认值\"]}。" +
                "日程字段齐全后不要再输出日程 JSON，直接调用 create_schedule。"
    }
}
