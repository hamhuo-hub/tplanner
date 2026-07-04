package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// 手机直连 DeepSeek API，不走树莓派。
// 两次调用：
//   1) restructureEntry —— 单事件自由文本 → 伯恩斯三栏
//   2) synthesizeDay —— 全天结构化事件 → 日终报告
class DeepSeekAnalysisService(private val apiKey: String) {

    // ── 第 1 次调用：单事件整理 ────────────────────────────────────────────

    suspend fun restructureEntry(
        text: String,
        timestamp: String,
        location: String
    ): ThreeColumnResult? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一位接受过伯恩斯CBT训练的心理咨询师。用户正在经历焦虑发作，写下了以下记录。")
            append("请将其整理为伯恩斯三栏格式，并识别思维钢印（认知扭曲）类型。\n\n")
            append("**用户记录**：\n$text\n\n")
            append("**背景**：时间 $timestamp，地点 $location\n\n")
            append("请以 JSON 格式返回（不要包含其他内容）：\n")
            append("{\n")
            append("  \"autoThought\": \"用户的核心自动思维（一句话）\",\n")
            append("  \"thoughtConfidence\": 85,\n")
            append("  \"distortions\": [\"读心术\", \"贴标签\"],\n")
            append("  \"rationalResponse\": \"温柔但有力的理智反思（2-3句话）\",\n")
            append("  \"emotion\": \"羞耻\",\n")
            append("  \"intensity\": 70\n")
            append("}\n\n")
            append("思维钢印类型必须从以下中选择：全或无思维、过度概括、心理过滤、贬低正面、")
            append("读心术、算命式预测、夸大与缩小、情绪推理、应该句式、贴标签、自责、责备他人\n\n")
            append("理智反思不要空洞安慰，要基于事实的温和反驳。语气是陪伴式而非说教式。")
        }

        val resp = callDeepSeek(prompt)
        parseThreeColumnResult(resp)
    }

    // ── 第 2 次调用：日终归档 ──────────────────────────────────────────────

    suspend fun synthesizeDay(
        entries: List<StructuredEntry>,
        date: String
    ): DayReport? = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext null

        val eventsText = buildString {
            entries.forEachIndexed { i, e ->
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(e.timestamp))
                append("${i + 1}. $time · ${e.location}\n")
                append("   强度: ${e.intensity}%\n")
                append("   思维钢印: ${e.distortions.joinToString("、")}\n")
                append("   核心思维: ${e.autoThought}\n\n")
            }
        }

        val prompt = buildString {
            append("你是一位心理咨询师。今天用户记录了以下焦虑事件。请综合全天数据，")
            append("做一份温柔的日终沙盘复盘。\n\n")
            append("**日期**：$date\n")
            append("**事件列表**：\n$eventsText\n")
            append("请以 JSON 格式返回（不要包含其他内容）：\n")
            append("{\n")
            append("  \"topLocation\": \"今日高发地点\",\n")
            append("  \"topTimeSlot\": \"今日高发时段（上午/午间/下午/晚间/深夜）\",\n")
            append("  \"narrative\": \"2-4句话的自然语言总结。语气是陪伴式而非诊断式。")
            append("如果发现了重复出现的思维钢印，温柔地指出来。")
            append("如果某个地点频繁出现，提一下。最后可以留一个好奇的小钩子。\"\n")
            append("}\n")
            append("语调参考：'你的情绪风暴今天高度集中在望京SOHO...'\n")
            append("避免直接说'你应该...'，而用'不妨'、'也许可以'等柔和建议。")
        }

        val resp = callDeepSeek(prompt)
        val parsed = parseDayReportJson(resp) ?: return@withContext null
        // 补充本地可计算的数据
        val avgIntensity = if (entries.isEmpty()) 0
            else entries.map { it.intensity }.average().toInt()
        val distortionCounts = mutableMapOf<String, Int>()
        entries.forEach { e -> e.distortions.forEach { d -> distortionCounts[d] = (distortionCounts[d] ?: 0) + 1 } }
        val topLoc = parsed.optString("topLocation", "")
        val topSlot = parsed.optString("topTimeSlot", "")
        val narrative = parsed.optString("narrative", "")

        DayReport(
            date = date,
            totalEvents = entries.size,
            avgIntensity = avgIntensity,
            distortionCounts = distortionCounts,
            topLocation = if (topLoc.isNotBlank()) topLoc else entries.groupBy { it.location }.maxByOrNull { it.value.size }?.key ?: "",
            topTimeSlot = if (topSlot.isNotBlank()) topSlot else "",
            narrative = narrative,
        )
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun callDeepSeek(userMessage: String): String {
        val conn = URL(DEEPSEEK_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "你是一位接受过CBT训练的心理咨询师。请总是返回有效的 JSON，不要包含 markdown 代码块标记。")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
                put("temperature", 0.3)
                put("max_tokens", 2048)
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP ${conn.responseCode}"
                throw Exception("DeepSeek API error: $err")
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseThreeColumnResult(resp: String): ThreeColumnResult? = try {
        val json = extractJson(resp)
        val distortions = mutableListOf<String>()
        json.optJSONArray("distortions")?.let { arr ->
            for (i in 0 until arr.length()) distortions += arr.getString(i)
        }
        ThreeColumnResult(
            autoThought = json.optString("autoThought", ""),
            thoughtConfidence = json.optInt("thoughtConfidence", 0),
            distortions = distortions,
            rationalResponse = json.optString("rationalResponse", ""),
            emotion = json.optString("emotion", ""),
            intensity = json.optInt("intensity", 0),
        )
    } catch (_: Exception) { null }

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

    private fun parseDayReportJson(resp: String): JSONObject? = try {
        extractJson(resp)
    } catch (_: Exception) { null }

    companion object {
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-chat"
    }
}
