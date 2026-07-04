package com.hamhuo.tplanner

import org.json.JSONArray
import org.json.JSONObject

// ── 伯恩斯 10 种认知扭曲（扩展为 12 类，对齐 SocialCD-3K） ──────────────
enum class DistortionType(val label: String, val keywords: List<String>) {
    ALL_OR_NOTHING("全或无思维", listOf(
        "完全", "绝对", "永远", "从来", "总是", "从不", "一点都", "彻底",
        "要么", "非黑即白", "一直"
    )),
    OVER_GENERALIZATION("过度概括", listOf(
        "永远都", "每次都", "总是这样", "从来都", "一辈子",
        "所有人", "没人", "什么都"
    )),
    MENTAL_FILTER("心理过滤", listOf(
        "只看到", "只有", "光盯着", "满脑子都是", "就是忘不掉",
        "反复想", "挥之不去"
    )),
    DISQUALIFYING_POSITIVE("贬低正面", listOf(
        "那不算", "不代表什么", "只是运气", "任何人都会", "不值一提",
        "没什么大不了", "算不上"
    )),
    MIND_READING("读心术", listOf(
        "肯定觉得", "一定认为", "在笑我", "看不起", "觉得我",
        "肯定在", "他们都在", "别人都", "大家都"
    )),
    FORTUNE_TELLING("算命式预测", listOf(
        "肯定会", "一定会", "绝对会", "万一", "要是",
        "不可能", "做不到", "不会好的", "迟早", "最后肯定"
    )),
    MAGNIFICATION("夸大与缩小", listOf(
        "太可怕了", "完蛋了", "毁掉了", "天塌了", "世界末日",
        "灾难", "崩溃", "彻底完了", "无法挽回"
    )),
    EMOTIONAL_REASONING("情绪推理", listOf(
        "我感觉", "我就是觉得", "直觉告诉我", "心里清楚",
        "不用说也知道", "没什么理由就是"
    )),
    SHOULD_STATEMENTS("应该句式", listOf(
        "应该", "必须", "不得不", "本该", "一定得",
        "不该", "不许", "不能这样"
    )),
    LABELING("贴标签", listOf(
        "我就是", "我是个", "我这人", "我天生", "我这辈子",
        "loser", "废物", "差劲", "不行", "没用", "失败者"
    )),
    BLAMING_SELF("自责", listOf(
        "都怪我", "我的错", "是我不好", "我害的", "我不该",
        "怪我自己", "是我没做好", "我要负责"
    )),
    BLAMING_OTHERS("责备他人", listOf(
        "都怪他", "是他的错", "他们害的", "要不是他们",
        "全因为他们", "被坑了"
    ));

    companion object {
        fun fromLabel(label: String): DistortionType? =
            entries.firstOrNull { it.label == label }
    }
}

// ── 单事件 LLM 分析结果 ────────────────────────────────────────────────────
data class ThreeColumnResult(
    val autoThought: String,        // 自动思维
    val thoughtConfidence: Int,     // 相信度 0-100
    val distortions: List<String>,  // 思维钢印标签列表
    val rationalResponse: String,   // 理智反思
    val emotion: String = "",       // 主导情绪
    val intensity: Int = 0,         // 焦虑强度 0-100
)

// ── 结构化事件（存入 InsightStore） ─────────────────────────────────────────
data class StructuredEntry(
    val id: String,                 // UUID
    val timestamp: Long,            // epoch ms
    val text: String,               // 原始文本
    val location: String,           // 位置名（GPS 反查结果）
    val lat: Double,                // 纬度
    val lng: Double,                // 经度
    val intensity: Int,             // 焦虑强度 0-100
    val distortions: List<String>,  // 思维钢印标签
    val autoThought: String,        // 自动思维
    val thoughtConfidence: Int,     // 相信度
    val rationalResponse: String,   // 理智反思
    val emotion: String,            // 主导情绪
)

// ── 日终报告 ────────────────────────────────────────────────────────────────
data class DayReport(
    val date: String,               // yyyy-MM-dd
    val totalEvents: Int,
    val avgIntensity: Int,
    val distortionCounts: Map<String, Int>,  // 思维钢印 → 次数
    val topLocation: String,                  // 今日高发地点
    val topTimeSlot: String,                  // 今日高发时段
    val narrative: String,                    // LLM 生成的自然语言总结
)

// ── JSON 序列化辅助 ─────────────────────────────────────────────────────────
internal fun StructuredEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("timestamp", timestamp)
    put("text", text)
    put("location", location)
    put("lat", lat)
    put("lng", lng)
    put("intensity", intensity)
    put("distortions", JSONArray(distortions))
    put("autoThought", autoThought)
    put("thoughtConfidence", thoughtConfidence)
    put("rationalResponse", rationalResponse)
    put("emotion", emotion)
}

internal fun JSONObject.toStructuredEntry(): StructuredEntry? = try {
    val distortions = mutableListOf<String>()
    optJSONArray("distortions")?.let { arr ->
        for (i in 0 until arr.length()) distortions += arr.getString(i)
    }
    StructuredEntry(
        id = getString("id"),
        timestamp = getLong("timestamp"),
        text = optString("text", ""),
        location = optString("location", ""),
        lat = optDouble("lat", 0.0),
        lng = optDouble("lng", 0.0),
        intensity = optInt("intensity", 0),
        distortions = distortions,
        autoThought = optString("autoThought", ""),
        thoughtConfidence = optInt("thoughtConfidence", 0),
        rationalResponse = optString("rationalResponse", ""),
        emotion = optString("emotion", ""),
    )
} catch (_: Exception) { null }

internal fun DayReport.toJson(): JSONObject = JSONObject().apply {
    put("date", date)
    put("totalEvents", totalEvents)
    put("avgIntensity", avgIntensity)
    put("distortionCounts", JSONObject(distortionCounts))
    put("topLocation", topLocation)
    put("topTimeSlot", topTimeSlot)
    put("narrative", narrative)
}

internal fun JSONObject.toDayReport(): DayReport? = try {
    val counts = mutableMapOf<String, Int>()
    optJSONObject("distortionCounts")?.let { obj ->
        obj.keys().forEach { key -> counts[key] = obj.getInt(key) }
    }
    DayReport(
        date = getString("date"),
        totalEvents = getInt("totalEvents"),
        avgIntensity = getInt("avgIntensity"),
        distortionCounts = counts,
        topLocation = optString("topLocation", ""),
        topTimeSlot = optString("topTimeSlot", ""),
        narrative = optString("narrative", ""),
    )
} catch (_: Exception) { null }
