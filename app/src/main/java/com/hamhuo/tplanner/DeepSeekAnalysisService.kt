package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// 手机直连 DeepSeek API（deepseek-chat），不走树莓派。
//
// 产品定位：帮用户把散乱的思路理清。核心不是"给答案/做分析"，而是【反过来
// 提问】——用户写下凌乱、片段、可能表述不准的想法，AI 只提问，帮他定位自己
// 卡在哪儿。
//
// 多轮会话：
//   processThought → refineAction（clarify 补全）或 followUpQuestions（多轮追问）
//   同一会话内各阶段共享上下文，后续调用会拼接之前的消息历史。
class DeepSeekAnalysisService(private val apiKey: String) {

    // 一次处理，分四种场景：
    //   record    —— 默认。只修错别字/语法，当笔记记下（不提问、不分析）。
    //   questions —— 文字里带问句/困惑就做场景判断，反过来提问帮他定位困惑。
    //   action    —— 文字明显是"要做/要记住的事"（待办/提醒），提议帮他建一个
    //                task/reminder。带明确时间→reminder，无时间的待办→task。
    // 无论哪种，最终有副作用的动作（建日程）都要用户在界面上确认后才执行。
    data class ProposedAction(val type: String, val title: String, val datetimeIso: String)
    // 缺关键参数时的一条澄清追问（含快捷选项）；agent 先问、答完再补全操作。
    data class Clarify(val q: String, val options: List<String>)
    data class ThoughtResult(
        val mode: String,
        val text: String,
        val questions: List<String>,
        val action: ProposedAction? = null,
        val clarify: Clarify? = null,   // action 且 !=null → 先追问，答完调 refineAction
    )

    // ── 会话记忆：同一轮"理一理"内多步共享上下文，累积 user/assistant 消息即可 ──
    private var sessionSystemPrompt: String = ""
    private val sessionMessages = mutableListOf<JSONObject>()  // user / assistant 交替

    private fun resetSession(systemPrompt: String) {
        sessionSystemPrompt = systemPrompt
        sessionMessages.clear()
    }

    private fun addUserTurn(content: String) {
        sessionMessages.add(JSONObject().apply {
            put("role", "user"); put("content", content)
        })
    }

    private fun addAssistantTurn(content: String) {
        sessionMessages.add(JSONObject().apply {
            put("role", "assistant"); put("content", content)
        })
    }

    // ── 第一阶段：分析用户想法 → record / questions / action ──────────
    suspend fun processThought(
        text: String,
        timestamp: String,
        location: String,
    ): ThoughtResult = withContext(Dispatchers.IO) {
        // 新会话开始：清空历史，换上对应的 system prompt
        resetSession(SYSTEM_THOUGHT)

        // 相对时间（明天/下周一/早上…）需要一个"现在"作参照才能解析成绝对时间
        val nowDt = java.time.LocalDateTime.now()
        val zhWeek = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[nowDt.dayOfWeek.value - 1]
        val nowStr = "%04d-%02d-%02d %02d:%02d %s".format(
            nowDt.year, nowDt.monthValue, nowDt.dayOfMonth, nowDt.hour, nowDt.minute, zhWeek)

        val prompt = buildString {
            append("现在：$nowStr\n地点：$location\n")
            append("用户写下的文字（原样，可能有错别字/语病）：\n\"\"\"\n")
            append(text)
            append("\n\"\"\"\n\n")
            append("返回 JSON（不要 markdown 代码块）：\n")
            append("{\n")
            append("  \"mode\": \"record 或 questions 或 action\",\n")
            append("  \"text\": \"<修正错别字和语法后的文字，保持原意原语气，绝不改写、扩写、发挥；没错就原样返回>\",\n")
            append("  \"questions\": [],\n")
            append("  \"action\": {\"type\": \"task 或 reminder\", \"title\": \"<简短标题，动宾短语>\", \"datetime\": \"<本地时间 ISO；已知日期就填日期部分、钟点未定填 T00:00:00；纯 task 无时间填空>\"},\n")
            append("  \"clarify\": {\"q\": \"<追问，如：明天几点提醒你？>\", \"options\": [\"上午9点\", \"中午12点\", \"下午3点\", \"晚上8点\"]}\n")
            append("}\n\n")
            append("mode 判定：\n")
            append("- action：文字明显是【一件要做/要记住的事】——待办、提醒、\"记得/别忘了/")
            append("提醒我/明天要\"+一件具体的事。带明确时间点→reminder，没时间的待办→task。\n")
            append("- questions：文字里带【问句/困惑】——有问号，或\"要不要 / 该不该 / 怎么办 / ")
            append("为什么 / 是不是 / 能不能 / 值不值\"这类疑问，或明确求助。检测到问句就进 questions ")
            append("做场景判断（不必等他明说\"帮我理理\"）。\n")
            append("- record：陈述性的想法——musing、观察、感受、片段、一般笔记，且不含问句。\n")
            append("record/questions 模式：action 与 clarify 都置为 null。\n")
            append("【clarify 追问规则】reminder（定时提醒）必须有明确钟点。如果用户只给了日期")
            append("（\"明天\"\"下周一\"\"后天\"）或根本没提时间，【绝对不要自己猜钟点】，而是在 clarify 里")
            append("问\"几点\"并给 4 个快捷选项（上午9点/中午12点/下午3点/晚上8点）；datetime 先只填")
            append("日期部分。只有用户明确说了 早上(→08:00)/中午(→12:00)/下午(→14:00)/晚上(→20:00)/")
            append("具体几点，才直接用、clarify 置 null。纯 task（无时间意味的待办）clarify 置 null。\n")
            append("其余相对时间按\"现在\"解析成绝对 ISO。\n")
            append("questions 模式：先判断这个问句值不值得反问。若只是简单事实问、随口一问、")
            append("信息已足够、没什么可深挖的——questions 返回【空数组】，退回记录，不打扰。\n")
            append("值得的话给 1-3 个问题：只提问不给答案；扎在具体接缝上（含混的词、")
            append("跳过的步骤、没说出口的前提、被当成一回事的两件事）；真开放不诱导；优先他一时")
            append("答不上来的；保持临时不制造\"想通了\"的终局感；卡点关乎他自己时转成他能自己")
            append("去试去验证的问题，别剖析他是什么样的人。")
        }
        try {
            val json = extractJson(callDeepSeekWithHistory(prompt))
            val rawMode = json.optString("mode", "record")
            val cleaned = json.optString("text", text).ifBlank { text }

            val qs = if (rawMode == "questions") {
                val out = mutableListOf<String>()
                json.optJSONArray("questions")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
                }
                out.take(3)
            } else emptyList()

            val action = if (rawMode == "action") {
                json.optJSONObject("action")?.let { a ->
                    val title = a.optString("title", "").trim()
                    if (title.isBlank()) null else ProposedAction(
                        type = if (a.optString("type", "task") == "reminder") "reminder" else "task",
                        title = title,
                        datetimeIso = a.optString("datetime", "").trim(),
                    )
                }
            } else null

            val clarify = if (rawMode == "action") {
                json.optJSONObject("clarify")?.let { c ->
                    val q = c.optString("q", "").trim()
                    val opts = mutableListOf<String>()
                    c.optJSONArray("options")?.let { arr ->
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { opts += it }
                    }
                    if (q.isBlank()) null else Clarify(q, opts)
                }
            } else null

            // 归一化：mode 与产物一致，缺产物则回退 record
            val mode = when {
                rawMode == "action" && action != null -> "action"
                rawMode == "questions" && qs.isNotEmpty() -> "questions"
                else -> "record"
            }
            android.util.Log.d("TplannerDS", "processThought mode=$mode q=${qs.size} action=${action?.type} clarify=${clarify != null}")
            ThoughtResult(mode, cleaned, qs, action, if (mode == "action") clarify else null)
        } catch (e: Exception) {
            // 网络/超时/限流：退化为"原样记录"，绝不打断记录本身
            android.util.Log.e("TplannerDS", "processThought failed: ${e.message}")
            ThoughtResult("record", text, emptyList())
        }
    }

    // ── 第二阶段 A：补全澄清追问后的日程参数 ─────────────────────────
    // 在 processThought 后调用，拼接了之前的会话历史，模型能引用前面的分析结果。
    suspend fun refineAction(
        text: String,
        action: ProposedAction,
        answer: String,
    ): ProposedAction = withContext(Dispatchers.IO) {
        val nowDt = java.time.LocalDateTime.now()
        val zhWeek = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[nowDt.dayOfWeek.value - 1]
        val nowStr = "%04d-%02d-%02d %02d:%02d %s".format(
            nowDt.year, nowDt.monthValue, nowDt.dayOfMonth, nowDt.hour, nowDt.minute, zhWeek)
        val prompt = buildString {
            append("现在：$nowStr\n原文：$text\n")
            append("已确定的部分操作：type=${action.type}, title=${action.title}, datetime=${action.datetimeIso.ifBlank { "(未定)" }}\n")
            append("用户对澄清追问的回答：$answer\n\n")
            append("把回答里的钟点并进已知日期，算成绝对本地时间。返回 JSON（不要 markdown）：\n")
            append("{\"type\": \"${action.type}\", \"title\": \"${action.title}\", \"datetime\": \"<本地 ISO，如 2026-07-07T15:00:00>\"}")
        }
        try {
            val j = extractJson(callDeepSeekWithHistory(prompt))
            ProposedAction(
                type = if (j.optString("type", action.type) == "reminder") "reminder" else "task",
                title = j.optString("title", action.title).ifBlank { action.title },
                datetimeIso = j.optString("datetime", action.datetimeIso).trim(),
            )
        } catch (e: Exception) {
            android.util.Log.e("TplannerDS", "refineAction failed: ${e.message}")
            action
        }
    }

    // ── 第二阶段 B：多轮追问（用户答完当前问题 → 往更深层再问） ─────
    // 在 processThought（questions 模式）后调用，拼接了会话历史，模型知道前面问了什么。
    suspend fun followUpQuestions(
        originalText: String,
        qaHistory: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("用户最初写下的文字：\n\"\"\"\n$originalText\n\"\"\"\n\n")
            if (qaHistory.isNotBlank()) {
                append("此前的对话：\n$qaHistory\n\n")
            }
            append("你已经问过了上面这些问题并得到了用户的回答。现在请基于整段对话往更深处问 1-3 个新问题——")
            append("扎在具体接缝上（含混的词、跳过的步骤、没说出口的前提、被当成一回事的两件事）；")
            append("真开放不诱导；优先他一时答不上来的；保持临时不制造「想通了」的终局感。")
            append("如果确实没有更多值得问的了，返回空数组。\n\n")
            append("返回 JSON（不要 markdown）：\n")
            append("{\"questions\": []}  或  {\"questions\": [\"问题1\", \"问题2\", \"问题3\"]}")
        }
        try {
            val json = extractJson(callDeepSeekWithHistory(prompt))
            val out = mutableListOf<String>()
            json.optJSONArray("questions")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
            }
            android.util.Log.d("TplannerDS", "followUpQuestions count=${out.size}")
            out.take(3)
        } catch (e: Exception) {
            android.util.Log.e("TplannerDS", "followUpQuestions failed: ${e.message}")
            emptyList()
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    // 多轮感知的 API 调用：把 sessionMessages（历史 user/assistant 交替）
    // 拼到当前 user message 前面一起发送。收到响应后把本轮 user+assistant
    // 追加到 sessionMessages，供后续调用使用。
    private fun callDeepSeekWithHistory(currentUserMessage: String): String {
        val conn = URL(DEEPSEEK_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        try {
            // 构建完整消息数组：[system] + [历史 user/assistant ...] + [当前 user]
            val messages = org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", sessionSystemPrompt) })
                sessionMessages.forEach { put(it) }
                put(JSONObject().apply { put("role", "user"); put("content", currentUserMessage) })
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.7)
            }.toString()

            android.util.Log.d("TplannerDS", "callDeepSeek historyTurns=${sessionMessages.size / 2}")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP ${conn.responseCode}"
                throw Exception("DeepSeek API error: $err")
            }

            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val choice = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            val msg = choice.getJSONObject("message")
            val content = msg.getString("content").trim()

            // 把本轮对话追加到会话历史
            addUserTurn(currentUserMessage)
            addAssistantTurn(content)

            return content
        } finally {
            conn.disconnect()
        }
    }

    // 有时 LLM 返回的 JSON 被包在 ```json ... ``` 里
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

    companion object {
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        // deepseek-chat：快速响应，不做推理/thinking
        private const val MODEL = "deepseek-chat"

        private const val SYSTEM_THOUGHT =
            "你在帮用户处理随手写下的文字，分四种：默认 record（只修错别字/语法，当笔记" +
            "记下，不提问不分析）；questions（文字里带问句/困惑就进来做场景判断，反过来提问" +
            "帮他定位困惑——但若没什么值得反问的就返回空问题、退回 record；不给答案不剖析他" +
            "这个人）；action（文字明显是要做/要记住的事时，提议帮他建 task/reminder）。" +
            "action 判断从严；questions 只要是问句就先进来判断。有副作用的动作最终都要用户" +
            "确认才执行。永远只返回有效 JSON，不要 markdown 代码块。"

        private const val SYSTEM_REFINE =
            "你根据用户对澄清追问的回答，补全一个日程操作（把答的钟点并进已知日期算成绝对时间）。" +
            "永远只返回有效 JSON，不要 markdown 代码块。"

        private const val SYSTEM_FOLLOWUP =
            "你在帮用户理清思路。你已经问过几轮问题、得到了用户的一些回答。" +
            "现在基于整段对话，往更深处再追问 1-3 个真正值得他坐在那儿想的问题。" +
            "扎在具体接缝上（含混的词、跳过的步骤、没说出口的前提、被当成一回事的两件事）；" +
            "真开放不诱导；优先他一时答不上来的；保持临时感——这些问题不是终点。" +
            "如果确实没有更多值得问的，返回空数组。" +
            "永远只返回有效 JSON，不要 markdown 代码块。"
    }
}
