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
        val error: Boolean = false,
    )

    data class FollowUpResult(
        val questions: List<String> = emptyList(),
        val action: ProposedAction? = null,
        val clarify: Clarify? = null,
        val error: Boolean = false,
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
    private var scheduleInProgress = false
    private var scheduleEvidence = ""

    private fun resetSession(systemPrompt: String) {
        sessionSystemPrompt = systemPrompt
        sessionMessages.clear()
        scheduleInProgress = false
        scheduleEvidence = ""
    }

    suspend fun processThought(
        text: String,
        timestamp: String,
        location: String,
    ): ThoughtResult = withContext(Dispatchers.IO) {
        resetSession(SYSTEM_AGENT)
        val forceScheduleTool = ScheduleIntentRouter.isExplicitRequest(text)
        scheduleInProgress = forceScheduleTool
        if (forceScheduleTool) scheduleEvidence = text
        val prompt = buildString {
            append("现在：${nowDescription()}\n地点：$location\n记录时间：$timestamp\n")
            append("用户写下的文字：\n\"\"\"\n$text\n\"\"\"\n\n")
            append(RESPONSE_RULES)
        }
        try {
            val turn = callDeepSeekWithHistory(prompt, forceScheduleTool)
            if (forceScheduleTool) toInitialScheduleResult(turn, text) else toThoughtResult(turn, text)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "processThought failed: ${e.message}")
            ThoughtResult("questions", text, emptyList(), error = true)
        }
    }

    /** Continue collecting schedule fields after a clarify question. */
    suspend fun refineAction(
        originalText: String,
        answer: String,
    ): ThoughtResult = withContext(Dispatchers.IO) {
        scheduleInProgress = true
        scheduleEvidence = listOf(scheduleEvidence, answer)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val prompt = buildString {
            append("现在：${nowDescription()}\n")
            append("最初文字：$originalText\n")
            append("用户对日程字段追问的回答：$answer\n\n")
            append("首轮 create_schedule 参数只是意图草稿，不代表用户确认。只合并最初文字和用户后续明确回答；")
            append("禁止猜值。无论字段是否完整都调用 create_schedule：已确认字段传入，未知字段省略，由应用继续追问。\n")
            append(RESPONSE_RULES)
        }
        try {
            toThoughtResult(callDeepSeekWithHistory(prompt, forceScheduleTool = true), originalText)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "refineAction failed: ${e.message}")
            ThoughtResult(
                mode = "questions",
                text = originalText,
                questions = emptyList(),
                error = true,
            )
        }
    }

    suspend fun followUp(
        originalText: String,
        qaHistory: String,
    ): FollowUpResult = withContext(Dispatchers.IO) {
        val wasScheduleInProgress = scheduleInProgress
        val userQaHistory = qaHistory.lineSequence()
            .filter { it.startsWith("用户答：") }
            .joinToString("\n") { it.removePrefix("用户答：") }
        val forceScheduleTool = wasScheduleInProgress ||
            ScheduleIntentRouter.isExplicitRequest(originalText) ||
            ScheduleIntentRouter.isExplicitRequest(userQaHistory)
        if (forceScheduleTool) scheduleInProgress = true
        if (forceScheduleTool && userQaHistory.isNotBlank()) {
            scheduleEvidence = listOf(scheduleEvidence, originalText, userQaHistory)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        val prompt = buildString {
            append("现在：${nowDescription()}\n\n")
            append("用户最初写下的文字：\n\"\"\"\n$originalText\n\"\"\"\n\n")
            if (qaHistory.isNotBlank()) append("此前 QA：\n$qaHistory\n\n")
            append("基于整段对话继续提出 1-3 个不重复的新问题。若对话里出现明确的日程意图，")
            append("立即调用 create_schedule：已确认字段传入，未知字段省略，由应用继续追问。\n")
            append(RESPONSE_RULES)
        }
        try {
            val turn = callDeepSeekWithHistory(prompt, forceScheduleTool)
            val result = if (forceScheduleTool && !wasScheduleInProgress) {
                toInitialScheduleResult(turn, originalText)
            } else {
                toThoughtResult(turn, originalText)
            }
            FollowUpResult(result.questions, result.action, result.clarify)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "followUp failed: ${e.message}")
            FollowUpResult(error = true)
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
        if (status == "accepted" || status == "declined") {
            scheduleInProgress = false
            scheduleEvidence = ""
        }
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
        if (tool.action != null || clarify != null) scheduleInProgress = true
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

    /**
     * A first-pass tool call only signals schedule intent. Always ask the full
     * field set before showing the confirmation card, because model arguments
     * may contain inferred values that the user never supplied.
     */
    private fun toInitialScheduleResult(turn: ModelTurn, fallbackText: String): ThoughtResult {
        val content = parseContent(turn.content, fallbackText)
        turn.toolCalls.forEachIndexed { index, call ->
            submitToolResult(
                toolCallId = call.id,
                status = if (index == 0 && call.name == CREATE_SCHEDULE_TOOL) "needs_input" else "failed",
                message = if (index == 0 && call.name == CREATE_SCHEDULE_TOOL) {
                    "首次工具参数仅用于识别日程意图，所有字段必须由用户确认后再创建"
                } else {
                    "一次只能处理一条 create_schedule 调用"
                },
            )
        }
        scheduleInProgress = true
        return ThoughtResult(
            mode = "questions",
            text = content.text,
            questions = content.questions.ifEmpty { FALLBACK_QUESTIONS },
            clarify = Clarify(
                q = INITIAL_SCHEDULE_QUESTION,
                options = listOf("其余使用默认值", "我来补充"),
            ),
        )
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

            val explicitType = ScheduleIntentRouter.explicitType(scheduleEvidence)
            if (type !in SCHEDULE_TYPES || explicitType != null && type != explicitType) {
                errors += "类型（提醒、状态或任务）"
            }
            if (title.isBlank()) errors += "标题"
            val start = runCatching { LocalDateTime.parse(startIso) }.getOrNull()
            val end = runCatching { LocalDateTime.parse(endIso) }.getOrNull()
            if (start == null || !ScheduleIntentRouter.hasExplicitClock(scheduleEvidence)) {
                errors += "有效的开始时间（包括具体钟点）"
            }
            if (end == null || start != null && !end.isAfter(start)) errors += "晚于开始时间的结束时间"
            if (colorId !in 0..7) errors += "颜色"
            if (!args.has("note")) errors += "备注（不需要可留空）"
            if (type == "task" && !args.has("checklist")) errors += "任务清单（不需要可为空）"
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

    private fun callDeepSeekWithHistory(
        currentUserMessage: String,
        forceScheduleTool: Boolean = false,
    ): ModelTurn {
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
                // DeepSeek V4 defaults to thinking mode. QA and tool routing are
                // latency-sensitive, so keep Flash explicitly in non-thinking mode.
                put("thinking", JSONObject().put("type", "disabled"))
                put("messages", messages)
                put("tools", buildTools())
                put("tool_choice", if (forceScheduleTool) "required" else "auto")
                put("response_format", JSONObject().put("type", "json_object"))
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
            val choice = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
            val message = choice.getJSONObject("message")
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
            android.util.Log.d(
                TAG,
                "finish=${choice.optString("finish_reason")} content=${content.isNotBlank()} toolCalls=${calls.size}",
            )

            sessionMessages.add(userMessage)
            // Keep the complete assistant message so its tool_calls remain paired
            // with subsequent tool results (and reasoning_content if re-enabled).
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
                    "识别到用户明确希望创建、安排、提醒或记录日程时立即调用。把已知参数传入，未知参数省略且禁止猜测；" +
                        "应用会校验缺失字段、继续询问，并在执行前向用户做最终确认。",
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
        private const val INITIAL_SCHEDULE_QUESTION =
            "我识别到你想创建日程。请一次确认：类型（提醒、状态或任务）、标题、开始时间（日期和钟点）、" +
                "结束时间、备注、颜色、是否开启系统闹铃（开启时还需提前分钟数）；任务还请提供清单。" +
                "不需要的项目可说“其余使用默认值”，但类型、标题和开始时间仍需明确。"

        private const val SYSTEM_AGENT =
            "你是 tPlanner 的 QA 与日程助手。第一步必须先判断用户是否明确希望创建、安排、记录或提醒一条日程。" +
                "日程意图优先于普通 QA；即使它是陈述句，也不能被通用追问吞掉。提醒我、记得、别忘、待办、任务、" +
                "加入日程、安排、设闹钟、明天要做某事等表达都属于强日程信号。" +
                "一旦存在明确日程意图，本轮必须调用 create_schedule：把用户已经给出的字段传入，未知字段直接省略，" +
                "禁止为了满足参数格式而猜值，也不要只返回普通 questions。应用会验证工具参数并一次列出全部缺失项。" +
                "日程可填写项包括类型、标题、开始、结束、备注、颜色、是否开启系统闹铃；开启闹铃时还要提前分钟数；" +
                "task 还包括清单。关闭闹铃时 alarm_offset_minutes 为 0。用户明确说“使用默认值”时，允许使用：" +
                "end_at=start_at+1小时、note=空字符串、color_id=0、checklist=空数组、alarm_enabled=false、" +
                "alarm_offset_minutes=0；type、title、start_at 仍必须来自用户文字或继续询问。每次最多创建一条日程。" +
                "只有非日程输入才进入普通 QA：反过来追问，帮助用户理清想法而不替用户下结论；每次至少产生一个新追问。" +
                "普通回复只输出有效 JSON，不要 markdown。"

        private const val RESPONSE_RULES =
            "先判断日程意图。只要有明确日程意图，必须立即调用 create_schedule；已知字段传入、未知字段省略，" +
                "不要在 content 中模拟工具调用，也不要只返回 questions。\n" +
                "只有非日程普通回复才输出以下 JSON（不要输出 action）：\n" +
                "{\"text\":\"保持原意、仅修正明显错字后的文字\",\"questions\":[\"1-3 个新追问\"]," +
                "\"clarify\":null}\n" +
                "普通回复 questions 不得为空。日程缺失字段由应用校验工具参数后统一追问。"
    }
}
