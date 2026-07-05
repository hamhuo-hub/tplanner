package com.hamhuo.tplanner

import org.json.JSONArray
import org.json.JSONObject

// ── Burns 10 cognitive distortions (extended to 12 types, aligned with SocialCD-3K) ──
enum class DistortionType(val label: String, val keywords: List<String>) {
    ALL_OR_NOTHING("All-or-Nothing", listOf(
        "完全", "绝对", "永远", "从来", "总是", "从不", "一点都", "彻底",
        "要么", "非黑即白", "一直"
    )),
    OVER_GENERALIZATION("Overgeneralization", listOf(
        "永远都", "每次都", "总是这样", "从来都", "一辈子",
        "所有人", "没人", "什么都"
    )),
    MENTAL_FILTER("Mental Filter", listOf(
        "只看到", "只有", "光盯着", "满脑子都是", "就是忘不掉",
        "反复想", "挥之不去"
    )),
    DISQUALIFYING_POSITIVE("Disqualifying Positive", listOf(
        "那不算", "不代表什么", "只是运气", "任何人都会", "不值一提",
        "没什么大不了", "算不上"
    )),
    MIND_READING("Mind Reading", listOf(
        "肯定觉得", "一定认为", "在笑我", "看不起", "觉得我",
        "肯定在", "他们都在", "别人都", "大家都"
    )),
    FORTUNE_TELLING("Fortune Telling", listOf(
        "肯定会", "一定会", "绝对会", "万一", "要是",
        "不可能", "做不到", "不会好的", "迟早", "最后肯定"
    )),
    MAGNIFICATION("Magnification", listOf(
        "太可怕了", "完蛋了", "毁掉了", "天塌了", "世界末日",
        "灾难", "崩溃", "彻底完了", "无法挽回"
    )),
    EMOTIONAL_REASONING("Emotional Reasoning", listOf(
        "我感觉", "我就是觉得", "直觉告诉我", "心里清楚",
        "不用说也知道", "没什么理由就是"
    )),
    SHOULD_STATEMENTS("Should Statements", listOf(
        "应该", "必须", "不得不", "本该", "一定得",
        "不该", "不许", "不能这样"
    )),
    LABELING("Labeling", listOf(
        "我就是", "我是个", "我这人", "我天生", "我这辈子",
        "loser", "废物", "差劲", "不行", "没用", "失败者"
    )),
    BLAMING_SELF("Blaming Self", listOf(
        "都怪我", "我的错", "是我不好", "我害的", "我不该",
        "怪我自己", "是我没做好", "我要负责"
    )),
    BLAMING_OTHERS("Blaming Others", listOf(
        "都怪他", "是他的错", "他们害的", "要不是他们",
        "全因为他们", "被坑了"
    ));

    companion object {
        fun fromLabel(label: String): DistortionType? =
            entries.firstOrNull { it.label == label }
    }
}

// ── Single-event LLM analysis result ────────────────────────────────────
data class ThreeColumnResult(
    val autoThought: String,        // Automatic thought
    val thoughtConfidence: Int,     // Belief level 0-100
    val distortions: List<String>,  // Distortion label list
    val rationalResponse: String,   // Rational response
    val emotion: String = "",       // Dominant emotion
    val intensity: Int = 0,         // Anxiety intensity 0-100
)

// ── Structured event (stored in InsightStore) ────────────────────────────
// updatedAt/deletedAt: participate in multi-device sync LWW merging and tombstone soft-delete propagation,
// semantics consistent with JournalEntry (deletedAt == 0L means alive).
data class StructuredEntry(
    val id: String,                 // UUID
    val timestamp: Long,            // epoch ms
    val text: String,               // Raw text
    val location: String,           // Location name (reverse geocoded from GPS)
    val lat: Double,                // Latitude
    val lng: Double,                // Longitude
    val intensity: Int,             // Anxiety intensity 0-100
    val distortions: List<String>,  // Distortion labels
    val autoThought: String,        // Automatic thought
    val thoughtConfidence: Int,     // Belief level
    val rationalResponse: String,   // Rational response
    val emotion: String,            // Dominant emotion
    val updatedAt: Long = 0L,       // Last modification time (LWW merge basis)
    val deletedAt: Long = 0L,       // tombstone; 0 = alive
)

// ── End-of-day report ───────────────────────────────────────────────────
data class DayReport(
    val date: String,               // yyyy-MM-dd
    val totalEvents: Int,
    val avgIntensity: Int,
    val distortionCounts: Map<String, Int>,  // Distortion → count
    val topLocation: String,                  // Top location today
    val topTimeSlot: String,                  // Top time slot today
    val narrative: String,                    // LLM-generated natural language summary
    val updatedAt: Long = 0L,                 // Last modification time (LWW merge basis)
    val deletedAt: Long = 0L,                 // tombstone; 0 = alive
)

// ── JSON serialization helpers ───────────────────────────────────────────
// Wire format is consistent with server/desktop spec: deletedAt serialized as JSON null when alive
// (Kotlin internally uses 0L for alive, same as JournalEntry handling).
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
    put("updatedAt", updatedAt)
    put("deletedAt", if (deletedAt == 0L) JSONObject.NULL else deletedAt)
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
        updatedAt = optLong("updatedAt", 0L),
        deletedAt = if (isNull("deletedAt")) 0L else optLong("deletedAt", 0L),
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
    put("updatedAt", updatedAt)
    put("deletedAt", if (deletedAt == 0L) JSONObject.NULL else deletedAt)
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
        updatedAt = optLong("updatedAt", 0L),
        deletedAt = if (isNull("deletedAt")) 0L else optLong("deletedAt", 0L),
    )
} catch (_: Exception) { null }
