package com.hamhuo.tplanner

// 本地关键词规则引擎：纯本地运算，零延迟，零网络。
// 基于 SocialCD-3K 数据集 + Burns 12 类认知扭曲的中文特征词。
// 用于 AnxietyInputSheet 中的实时芯片高亮提示，不作为最终分类结果。
object CognitiveDistortionDetector {

    data class DistortionCandidate(
        val type: DistortionType,
        val matchCount: Int,          // 匹配到的关键词数
        val matchedKeywords: List<String>,
    )

    fun detect(text: String): List<DistortionCandidate> {
        if (text.isBlank()) return emptyList()
        val results = DistortionType.entries.map { type ->
            val matched = type.keywords.filter { keyword ->
                text.contains(keyword)
            }
            DistortionCandidate(
                type = type,
                matchCount = matched.size,
                matchedKeywords = matched,
            )
        }
        // 按匹配数降序，至少匹配一个关键词才返回
        return results.filter { it.matchCount > 0 }
            .sortedByDescending { it.matchCount }
    }
}
