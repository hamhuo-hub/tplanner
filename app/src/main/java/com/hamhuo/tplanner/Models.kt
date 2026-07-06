package com.hamhuo.tplanner

import org.json.JSONArray
import org.json.JSONObject

// ── 一条"想法"记录（存入 InsightStore）──────────────────────────────────────
// 产品定位从"焦虑分析"泛化为"理清散乱的思路"：用户写下凌乱、片段、可能表述
// 不准的想法，AI 不给答案、不做诊断，而是【反过来提问】，帮用户定位自己卡在
// 哪儿。questions 就是 AI 抛回的澄清问题。
//
// updatedAt/deletedAt：参与多设备同步的 LWW 合并与 tombstone 软删除传播，
// 语义与 JournalEntry 一致（deletedAt == 0L 表示存活）。
data class StructuredEntry(
    val id: String,                 // UUID
    val timestamp: Long,            // epoch ms
    val text: String,               // 用户写下的原始片段
    val location: String,           // 位置名（GPS 反查结果）
    val lat: Double,                // 纬度
    val lng: Double,                // 经度
    val questions: List<String>,    // AI 抛回的澄清问题
    val updatedAt: Long = 0L,       // 最后修改时间（LWW 合并依据）
    val deletedAt: Long = 0L,       // tombstone；0 = 存活
)

// ── 日终归纳 ─────────────────────────────────────────────────────────────────
// 从"焦虑日终复盘"泛化为描述性的一天回顾：今天想了些什么、有没有反复出现的
// 线索、多在哪儿/什么时段。描述，不评判、不诊断。
data class DayReport(
    val date: String,               // yyyy-MM-dd
    val totalEvents: Int,
    val topLocation: String,        // 今日高发地点
    val topTimeSlot: String,        // 今日高发时段
    val narrative: String,          // LLM 生成的描述性总结
    val updatedAt: Long = 0L,       // 最后修改时间（LWW 合并依据）
    val deletedAt: Long = 0L,       // tombstone；0 = 存活
)

// ── JSON 序列化辅助 ─────────────────────────────────────────────────────────
// 线格式与服务器/桌面端规范形一致：存活时 deletedAt 序列化为 JSON null
//（Kotlin 内部用 0L 表示存活，与 JournalEntry 的处理相同）。
internal fun StructuredEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("timestamp", timestamp)
    put("text", text)
    put("location", location)
    put("lat", lat)
    put("lng", lng)
    put("questions", JSONArray(questions))
    put("updatedAt", updatedAt)
    put("deletedAt", if (deletedAt == 0L) JSONObject.NULL else deletedAt)
}

internal fun JSONObject.toStructuredEntry(): StructuredEntry? = try {
    val questions = mutableListOf<String>()
    optJSONArray("questions")?.let { arr ->
        for (i in 0 until arr.length()) questions += arr.getString(i)
    }
    StructuredEntry(
        id = getString("id"),
        timestamp = getLong("timestamp"),
        text = optString("text", ""),
        location = optString("location", ""),
        lat = optDouble("lat", 0.0),
        lng = optDouble("lng", 0.0),
        questions = questions,
        updatedAt = optLong("updatedAt", 0L),
        deletedAt = if (isNull("deletedAt")) 0L else optLong("deletedAt", 0L),
    )
} catch (_: Exception) { null }

internal fun DayReport.toJson(): JSONObject = JSONObject().apply {
    put("date", date)
    put("totalEvents", totalEvents)
    put("topLocation", topLocation)
    put("topTimeSlot", topTimeSlot)
    put("narrative", narrative)
    put("updatedAt", updatedAt)
    put("deletedAt", if (deletedAt == 0L) JSONObject.NULL else deletedAt)
}

internal fun JSONObject.toDayReport(): DayReport? = try {
    DayReport(
        date = getString("date"),
        totalEvents = optInt("totalEvents", 0),
        topLocation = optString("topLocation", ""),
        topTimeSlot = optString("topTimeSlot", ""),
        narrative = optString("narrative", ""),
        updatedAt = optLong("updatedAt", 0L),
        deletedAt = if (isNull("deletedAt")) 0L else optLong("deletedAt", 0L),
    )
} catch (_: Exception) { null }
